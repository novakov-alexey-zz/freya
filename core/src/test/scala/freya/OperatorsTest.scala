package freya

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, TimeUnit}

import cats.Parallel
import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, IO, Sync, Timer}
import cats.implicits._
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import freya.Configuration.{ConfigMapConfig, CrdConfig}
import freya.ExitCodes.ConsumerExitCode
import freya.K8sNamespace.{AllNamespaces, Namespace}
import freya.Retry.{Infinite, Times}
import freya.generators.arbitrary
import freya.internal.kubeapi.CrdApi.StatusUpdate
import freya.internal.kubeapi.MetadataApi
import freya.json.jackson._
import freya.models.{CustomResource, NewStatus, Resource, ResourcesList}
import freya.resource.ConfigMapParser
import freya.watcher.AbstractWatcher.CloseableWatcher
import freya.watcher.FeedbackConsumer.FeedbackChannel
import freya.watcher._
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.Watcher.Action
import io.fabric8.kubernetes.client.dsl.Watchable
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}
import org.scalacheck.Gen
import org.scalactic.anyvals.PosInt
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.scalacheck.{Checkers, ScalaCheckPropertyChecks}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.jdk.CollectionConverters._

class OperatorsTest
    extends AnyPropSpec
    with Matchers
    with Eventually
    with Checkers
    with ScalaCheckPropertyChecks
    with BeforeAndAfter {
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
  implicit val patienceCfg: PatienceConfig = PatienceConfig(scaled(Span(5, Seconds)), scaled(Span(50, Millis)))

  val crdCfg: CrdConfig = CrdConfig(Namespace("test"), prefix, checkOpenshiftOnStartup = false)
  val configMapcfg = ConfigMapConfig(AllNamespaces, prefix, checkOpenshiftOnStartup = false)
  val server = new KubernetesServer(false, false)
  val cmParser = ConfigMapParser[IO]().unsafeRunSync()

  val mapper = new ObjectMapper
  mapper.registerModule(DefaultScalaModule)

  before {
    server.before()
  }

  after {
    server.after()
  }

  def client[F[_]: Sync]: F[KubernetesClient] =
    Sync[F].pure(server.getClient)

  def makeWatchable[T, U]: (Watchable[Watch, Watcher[U]], mutable.Set[Watcher[U]]) = {
    val singleWatcher = concurrentHashSet[Watcher[U]]

    val watchable = new Watchable[Watch, Watcher[U]] {
      override def watch(watcher: Watcher[U]): Watch = {
        singleWatcher += watcher

        () => singleWatcher -= watcher
      }

      override def watch(resourceVersion: String, watcher: Watcher[U]): Watch =
        watch(watcher)
    }

    (watchable, singleWatcher)
  }

  implicit def cmWatch[F[_]: ConcurrentEffect: Timer: Parallel, T](
    implicit watchable: Watchable[Watch, Watcher[ConfigMap]]
  ): ConfigMapWatchMaker[F, T] =
    (context: ConfigMapWatcherContext[F, T]) =>
      new ConfigMapWatcher(context) {
        override def watch: F[(CloseableWatcher, F[ConsumerExitCode])] =
          registerWatcher(watchable)
      }

  implicit def crdWatch[F[_]: ConcurrentEffect: Timer: Parallel, T, U](
    implicit watchable: Watchable[Watch, Watcher[AnyCustomResource]]
  ): CrdWatchMaker[F, T, U] =
    (context: CrdWatcherContext[F, T, U]) =>
      new CustomResourceWatcher(context) {
        override def watch: F[(CloseableWatcher, F[ConsumerExitCode])] =
          registerWatcher(watchable)
      }

  implicit def crdDeployer[F[_]: Sync]: CrdDeployer[F] = new CrdDeployer[F] {
    override def deployCrd[T: JsonReader](
      client: KubernetesClient,
      cfg: CrdConfig,
      isOpenShift: Option[Boolean]
    ): F[CustomResourceDefinition] =
      Sync[F].pure(new CustomResourceDefinition())
  }

  def configMapOperator[F[_]: ConcurrentEffect: Timer: ContextShift: Parallel](
    controller: CmController[F, Kerb]
  ): (Operator[F, Kerb, Unit], mutable.Set[Watcher[ConfigMap]]) = {
    import freya.yaml.jackson._
    val (fakeWatchable, singleWatcher) = makeWatchable[Kerb, ConfigMap]
    implicit val watchable: Watchable[Watch, Watcher[ConfigMap]] = fakeWatchable

    Operator.ofConfigMap[F, Kerb](configMapcfg, client[F], controller) -> singleWatcher
  }

  private def concurrentHashSet[T]: mutable.Set[T] =
    java.util.Collections.newSetFromMap(new ConcurrentHashMap[T, java.lang.Boolean]).asScala

  def crdOperator[F[_]: ConcurrentEffect: Timer: ContextShift: Parallel, T: JsonReader](
    controller: Controller[F, T, Status],
    cfg: CrdConfig = crdCfg
  ): (Operator[F, T, Status], mutable.Set[Watcher[AnyCustomResource]], mutable.Set[StatusUpdate[Status]]) = {
    val (fakeWatchable, singleWatcher) = makeWatchable[T, AnyCustomResource]
    implicit val watchable: Watchable[Watch, Watcher[AnyCustomResource]] = fakeWatchable

    val status = mutable.Set.empty[StatusUpdate[Status]]
    implicit val feedbackConsumer: FeedbackConsumerMaker[F, Status] = testFeedbackConsumer[F](status)

    val operator = Operator.ofCrd[F, T, Status](cfg, client[F], controller)
    (operator, singleWatcher, status)
  }

  private def testFeedbackConsumer[F[_]: ConcurrentEffect: Timer: ContextShift](
    status: mutable.Set[StatusUpdate[Status]]
  ): FeedbackConsumerMaker[F, Status] = {
    (client: KubernetesClient, crd: CustomResourceDefinition, channel: FeedbackChannel[F, Status]) =>
      new FeedbackConsumer(client, crd, channel) {
        override def consume: F[ConsumerExitCode] =
          for {
            cr <- channel.take
            _ <- cr match {
              case Right(s) =>
                status += s
                consume
              case Left(()) => ExitCodes.FeedbackExitCode.pure[F]
            }
          } yield ExitCodes.FeedbackExitCode
      }
  }

  property("Crd Operator handles different events") {
    //given
    val controller = new CrdTestController[IO]
    val (operator, singleWatcher, status) = crdOperator[IO, Kerb](controller)
    val allEvents = new ListBuffer[(Watcher.Action, Kerb, Metadata)]()
    //when
    val cancelable = startOperator(operator.run)

    //then
    controller.initialized should ===(true)

    forAll(WatcherAction.gen, AnyCustomResource.gen[Kerb](crdCfg.getKind[Kerb])) {
      case (action, (anyCr, spec, _)) =>
        //when
        singleWatcher.foreach(_.eventReceived(action, anyCr))

        val meta = MetadataApi.translate(anyCr.getMetadata)
        allEvents += ((action, spec, meta))
        //then
        eventually {
          controller.events.asScala.toList should ===(allEvents)
        }
        eventually {
          if (action != Watcher.Action.DELETED) checkStatus(status, spec.failInTest, meta)
        }
    }

    cancelable.unsafeRunSync()
  }

  property("Crd Operator dispatches different namespace events concurrently") {
    val parallelNamespaces = 10
    val delay = 1.second

    val actualDuration = concurrentControllerTest(delay, parallelNamespaces, concurrent = true)
    val overhead = 700.millis
    val expectedDuration = delay + overhead
    println(s"Expected Max Duration: $expectedDuration")

    actualDuration should be <= expectedDuration
  }

  property("Crd Operator dispatches different namespace events via single thread") {
    val parallelNamespaces = 10
    val delay = 100.millis

    val actualDuration = concurrentControllerTest(delay, parallelNamespaces, concurrent = false)
    val expectedDuration = delay * parallelNamespaces.toLong
    println(s"Expected Min Duration: $expectedDuration")

    actualDuration should be >= expectedDuration
  }

  private def concurrentControllerTest(delay: FiniteDuration, parallelNamespaces: Int, concurrent: Boolean) = {
    //given
    val controller = new CrdTestController[IO](delay)
    val (operator, singleWatcher, _) = crdOperator[IO, Kerb](controller, crdCfg.copy(concurrentController = concurrent))
    val allEvents = new ConcurrentLinkedQueue[(Watcher.Action, Kerb, Metadata)]()
    //when
    val cancelable = startOperator(operator.run)
    val start = System.currentTimeMillis()
    //then
    forAll(
      WatcherAction.gen,
      AnyCustomResource.gen[Kerb](crdCfg.getKind[Kerb]),
      workers(PosInt.ensuringValid(parallelNamespaces)),
      minSuccessful(PosInt.ensuringValid(parallelNamespaces))
    ) {
      case (action, (anyCr, spec, _)) =>
        //when
        singleWatcher.foreach(_.eventReceived(action, anyCr))

        val meta = MetadataApi.translate(anyCr.getMetadata)
        allEvents.add((action, spec, meta))
    }
    //then
    eventually {
      controller.events.asScala.toSet should ===(allEvents.asScala.toSet)
    }
    val finish = System.currentTimeMillis()

    val duration = FiniteDuration(finish - start, TimeUnit.MILLISECONDS)
    println(s"Actual Duration: $duration")
    cancelable.unsafeRunSync()
    duration
  }

  property("Crd Operator dispatches same namespace events in original sequence") {
    //given
    val parallelNamespaces = 10
    val controllerEvents = TrieMap.empty[String, ConcurrentLinkedQueue[(Action, Kerb, Metadata)]]
    val controller = new CrdTestController[IO]() {
      override def save(action: Watcher.Action, spec: Kerb, meta: Metadata): IO[Unit] =
        IO {
          controllerEvents
            .getOrElseUpdate(meta.namespace, new ConcurrentLinkedQueue[(Action, Kerb, Metadata)])
            .add((action, spec, meta))
        }.void
    }
    val (operator, singleWatcher, _) = crdOperator[IO, Kerb](controller, crdCfg)

    def actionAndResourceGen(namespace: String) =
      for {
        a <- WatcherAction.gen
        r <- AnyCustomResource.gen[Kerb](crdCfg.getKind[Kerb], ObjectMetaTest.constNamespaceGen(namespace))
      } yield (a, r)

    //when
    val cancelable = startOperator(operator.run)
    //then
    (0 until parallelNamespaces).toList.map { namespace =>
      IO {
        val ns = namespace.toString
        val list = Gen.nonEmptyListOf(actionAndResourceGen(ns)).sample.toList.flatten
        //when
        val currentEvents = list.zipWithIndex.map {
          case ((action, (anyCr, spec, _)), i) =>
            // mutate current spec to have an index test event ordering later
            val specWithIndex = spec.copy(index = i)
            anyCr.setSpec(StringProperty(mapper.writeValueAsString(specWithIndex)))

            singleWatcher.foreach(_.eventReceived(action, anyCr))

            val meta = MetadataApi.translate(anyCr.getMetadata)
            (action, specWithIndex, meta)
        }

        //then
        eventually(timeout(60.seconds), interval(100.millis)) {
          val namespaceEvents = controllerEvents.get(ns).map(_.asScala.toList).getOrElse(Nil)
          namespaceEvents should contain allElementsOf currentEvents
        }
      }
    }.parSequence.unsafeRunSync()

    controllerEvents.foreach {
      case (namespace, queue) =>
        queue.asScala.toList.foldLeft(0) {
          case (acc, (_, kerb, _)) =>
            withClue(s"[namespace: $namespace, ${queue.asScala.toList.map(_._2.index)}] ") {
              acc should ===(kerb.index)
              acc + 1
            }
        }
    }

    cancelable.unsafeRunSync()
  }

  private def checkStatus[T](status: mutable.Set[StatusUpdate[Status]], statusFlag: Boolean, meta: Metadata) = {
    val cr = StatusUpdate(meta, Status(statusFlag))
    status should contain(cr)
  }

  property("Crd Operator gets event from reconciler process") {
    //given
    val controller = new CrdTestController[IO]
    implicit val (fakeWatchable, _) = makeWatchable[Kerb, AnyCustomResource]

    val testResources = new mutable.ArrayBuffer[Resource[Kerb, Status]]()
    implicit val helper: CrdHelperMaker[IO, Kerb, Status] = (context: CrdHelperContext) =>
      new CrdHelper[IO, Kerb, Status](context) {
        override def currentResources: Either[Throwable, ResourcesList[Kerb, Status]] =
          Right(testResources.toList)
      }

    val statusSet = mutable.Set.empty[StatusUpdate[Status]]
    implicit val feedbackConsumer: FeedbackConsumerMaker[IO, Status] = testFeedbackConsumer[IO](statusSet)

    val operator = Operator.ofCrd[IO, Kerb, Status](crdCfg, client[IO], controller).withReconciler(1.millis)
    //when
    val cancelable = startOperator(operator.run)

    forAll(AnyCustomResource.gen[Kerb](crdCfg.getKind)) {
      case (anyCr, spec, status) =>
        //val kerb = transformToString(anyCr)

        val meta = MetadataApi.translate(anyCr.getMetadata)
        testResources += Right(CustomResource(meta, spec, status.some))
        //then
        eventually {
          controller.reconciledEvents should contain((spec, meta))
        }
        eventually {
          checkStatus(statusSet, spec.failInTest, meta)
        }
    }

    cancelable.unsafeRunSync()
  }

//  private def transformToString(anyCr: AnyCustomResource): Kerb = {
//    val kerb = anyCr.getSpec.asInstanceOf[Kerb]
//    anyCr.setSpec(mapper.writeValueAsString(kerb))
//    anyCr.setStatus(mapper.writeValueAsString(anyCr.getStatus))
//    kerb
//  }

  property("ConfigMap Operator gets event from reconciler process") {
    import freya.yaml.jackson._
    //given
    val controller = new ConfigMapTestController[IO]
    implicit val (fakeWatchable, _) = makeWatchable[Kerb, ConfigMap]

    val testResources = new mutable.ArrayBuffer[Resource[Kerb, Unit]]()
    implicit val helper: ConfigMapHelperMaker[IO, Kerb] = (context: ConfigMapHelperContext) =>
      new ConfigMapHelper[IO, Kerb](context) {
        override def currentResources: Either[Throwable, ResourcesList[Kerb, Unit]] =
          Right(testResources.toList)
      }

    val operator = Operator.ofConfigMap[IO, Kerb](configMapcfg, client[IO], controller).withReconciler(1.millis)
    //when
    val cancelable = startOperator(operator.run)

    forAll(CM.gen[Kerb]) { cm =>
      val (meta, spec) = getSpecAndMeta(cm)
      testResources += Right(CustomResource(meta, spec, None))
      //then
      eventually {
        controller.reconciledEvents should contain((spec, meta))
      }
    }

    cancelable.unsafeRunSync()
  }

  property("Crd Operator handles different events on restarts") {
    //given
    val controller = new CrdTestController[IO]
    val (operator, singleWatcher, _) = crdOperator[IO, Kerb](controller)
    val maxRestarts = PosInt(20)
    val allEvents = new ListBuffer[(Watcher.Action, Kerb, Metadata)]()
    //when
    val cancelable = startOperator(operator.withRestart(Times(maxRestarts, 0.seconds)))
    var oldWatcher = getWatcherOrFail(singleWatcher)

    //then
    controller.initialized should ===(true)

    forAll(
      WatcherAction.gen,
      AnyCustomResource.gen[Kerb](crdCfg.getKind),
      arbitrary[Boolean],
      minSuccessful(maxRestarts)
    ) {
      case (action, (anyCr, spec, _), close) =>
//      val kerb = transformToString(anyCr)

        //when
        if (close)
          closeCurrentWatcher[AnyCustomResource](singleWatcher, oldWatcher)

        oldWatcher = getWatcherOrFail(singleWatcher)
        singleWatcher.foreach(_.eventReceived(action, anyCr))

        val meta = MetadataApi.translate(anyCr.getMetadata)
        allEvents += ((action, spec, meta))
        //then
        eventually {
          controller.events.asScala.toList should ===(allEvents)
        }
    }

    cancelable.unsafeRunSync()
  }

  property("ConfigMap Operator handles different events on restarts") {
    //given
    val controller = new ConfigMapTestController[IO]
    val (operator, singleWatcher) = configMapOperator[IO](controller)
    val maxRestarts = PosInt(20)
    val allEvents = new ListBuffer[(Watcher.Action, Kerb, Metadata)]()

    //when
    val cancelable = startOperator(operator.withRestart(Infinite(0.seconds, 1.seconds)))
    var currentWatcher = getWatcherOrFail(singleWatcher)

    //then
    controller.initialized should ===(true)

    forAll(WatcherAction.gen, CM.gen[Kerb], arbitrary[Boolean], minSuccessful(maxRestarts)) { (action, cm, close) =>
      //when
      if (close)
        closeCurrentWatcher[ConfigMap](singleWatcher, currentWatcher)

      currentWatcher = getWatcherOrFail(singleWatcher)
      singleWatcher.foreach(_.eventReceived(action, cm))

      val (meta, spec) = getSpecAndMeta(cm)
      allEvents += ((action, spec, meta))

      //then
      eventually {
        controller.events.asScala.toList should ===(allEvents)
      }
    }

    cancelable.unsafeRunSync()
  }

  private def getSpecAndMeta(cm: ConfigMap) = {
    val meta = toMetadata(cm)
    val spec = parseCM(cmParser, cm)
    (meta, spec)
  }

  private def getWatcherOrFail[T](set: mutable.Set[Watcher[T]]): Watcher[T] =
    set.headOption.getOrElse(fail("there must be at least one watcher"))

  property("Operators restarts n times in case of failure") {
    //given
    val controller = new ConfigMapTestController[IO]
    val (operator, singleWatcher) = configMapOperator[IO](controller)
    val maxRestarts = PosInt(3)
    val allEvents = new ListBuffer[(Watcher.Action, Kerb, Metadata)]()

    //when
    val exitCode = operator.withRestart(Times(maxRestarts, 0.seconds)).unsafeToFuture()
    var currentWatcher = getWatcherOrFail(singleWatcher)

    forAll(WatcherAction.gen, CM.gen[Kerb], minSuccessful(maxRestarts)) { (action, cm) =>
      //when
      closeCurrentWatcher(singleWatcher, currentWatcher)
      currentWatcher = getWatcherOrFail(singleWatcher)
      singleWatcher.foreach(_.eventReceived(action, cm))

      //then
      val (meta, spec) = getSpecAndMeta(cm)
      allEvents += ((action, spec, meta))

      eventually {
        controller.events.asScala.toList should ===(allEvents)
      }
    }

    singleWatcher.foreach(_.onClose(new KubernetesClientException("test")))

    eventually {
      exitCode.isCompleted should ===(true)
      val ec = Await.result(exitCode, 0.second)
      ec should ===(ExitCodes.ActionConsumerExitCode)
    }
  }

  property("Operator returns error code on failure") {
    val controller = new ConfigMapTestController[IO] {
      override def onInit(): IO[Unit] = IO.raiseError(new RuntimeException("test exception"))
    }
    val (operator, _) = configMapOperator[IO](controller)
    forAll(Gen.const(1)) { _ => operator.run.unsafeRunSync() should ===(ExitCode.Error) }
  }

  private def closeCurrentWatcher[T](singleWatcher: mutable.Set[Watcher[T]], currentWatcher: Watcher[T]) = {
    singleWatcher.foreach { w =>
      val raiseException = arbitrary[Boolean].sample.getOrElse(fail("failed to generate boolean"))
      val ex = if (raiseException) new KubernetesClientException("test exception") else null
      w.onClose(ex)
    }
    eventually {
      //then
      singleWatcher.size should ===(1)
      currentWatcher should !==(
        getWatcherOrFail(singleWatcher)
      ) // waiting until the Set with single watcher is updated with new watcher after Operator restart
    }
  }

  property("ConfigMap Operator handles different events") {
    //given
    val controller = new ConfigMapTestController[IO]
    val (operator, singleWatcher) = configMapOperator[IO](controller)
    val allEvents = new ListBuffer[(Watcher.Action, Kerb, Metadata)]()

    //when
    val cancelable = startOperator(operator.run)

    //then
    controller.initialized should ===(true)

    forAll(WatcherAction.gen, CM.gen[Kerb]) { (action, cm) =>
      //when
      singleWatcher.foreach(_.eventReceived(action, cm))
      val (meta, spec) = getSpecAndMeta(cm)
      allEvents += ((action, spec, meta))
      //then
      eventually {
        controller.events.asScala.toList should ===(allEvents)
      }
    }

    cancelable.unsafeRunSync()
  }

  class FailureCounterController extends ConfigMapTestController[IO] {
    var failed: Int = 0

    override def onAdd(krb: CustomResource[Kerb, Unit]): IO[NewStatus[Unit]] = {
      if (krb.spec.failInTest)
        failed = failed + 1
      super.onAdd(krb)
    }

    override def onDelete(krb: CustomResource[Kerb, Unit]): IO[Unit] = {
      if (krb.spec.failInTest)
        failed = failed + 1
      super.onDelete(krb)
    }

    override def onModify(krb: CustomResource[Kerb, Unit]): IO[NewStatus[Unit]] = {
      if (krb.spec.failInTest)
        failed = failed + 1
      super.onModify(krb)
    }
  }

  property("Operator handles parser errors") {
    //given
    val controller = new FailureCounterController()
    val (operator, singleWatcher) = configMapOperator[IO](controller)
    val allEvents = new ListBuffer[(Watcher.Action, Kerb, Metadata)]()

    //when
    val cancelable = startOperator(operator.run)

    forAll(WatcherAction.gen, CM.gen[Kerb]) { (action, cm) =>
      val (meta, spec) = getSpecAndMeta(cm)

      if (spec.failInTest)
        cm.getData.put(ConfigMapParser.SpecificationKey, "error")

      //when
      singleWatcher.foreach(_.eventReceived(action, cm))

      //then
      if (!spec.failInTest) {
        allEvents += ((action, spec, meta))
        eventually {
          controller.events.asScala.toList should ===(allEvents)
        }
      } else
        controller.events.asScala.toSet should not contain ((action, spec, meta))

      controller.failed should ===(0)
    }

    cancelable.unsafeRunSync()
  }

  property("Operator handles controller failures") {
    //given
    val allEvents = new ListBuffer[(Watcher.Action, Kerb, Metadata)]()
    val controller: ConfigMapTestController[IO] = new ConfigMapTestController[IO] {
      val error: IO[NewStatus[Unit]] = IO.raiseError(new RuntimeException("test exception"))

      override def onAdd(krb: CustomResource[Kerb, Unit]): IO[NewStatus[Unit]] =
        if (krb.spec.failInTest)
          error
        else
          super.onAdd(krb)

      override def onDelete(krb: CustomResource[Kerb, Unit]): IO[Unit] =
        if (krb.spec.failInTest)
          error.void
        else
          super.onDelete(krb)

      override def onModify(krb: CustomResource[Kerb, Unit]): IO[NewStatus[Unit]] =
        if (krb.spec.failInTest)
          error
        else
          super.onModify(krb)
    }

    val (operator, singleWatcher) = configMapOperator[IO](controller)

    //when
    val cancelable = startOperator(operator.run)

    forAll(WatcherAction.gen, CM.gen[Kerb]) { (action, cm) =>
      val (meta, spec) = getSpecAndMeta(cm)
      //when
      singleWatcher.foreach(_.eventReceived(action, cm))
      //then
      if (!spec.failInTest) {
        allEvents += ((action, spec, meta))
        eventually {
          controller.events.asScala.toList should ===(allEvents)
        }
      }
    }

    cancelable.unsafeRunSync()
  }

  property("ConfigMap operator handles only supported ConfigMaps") {
    //given
    val allEvents = new ListBuffer[(Watcher.Action, Kerb, Metadata)]()
    val controller = new FailureCounterController() {
      override def isSupported(cm: ConfigMap): Boolean = {
        val spec = parseCM(cmParser, cm)
        !spec.failInTest
      }
    }
    val (operator, singleWatcher) = configMapOperator[IO](controller)

    //when
    val cancelable = startOperator(operator.run)

    forAll(WatcherAction.gen, CM.gen[Kerb]) { (action, cm) =>
      val (meta: Metadata, spec: Kerb) = getSpecAndMeta(cm)

      //when
      singleWatcher.foreach(_.eventReceived(action, cm))

      //then
      if (!spec.failInTest) {
        allEvents += ((action, spec, meta))
        eventually {
          controller.events.asScala.toList should ===(allEvents)
        }
      } else
        controller.events.asScala.toSet should not contain ((action, spec, meta))

      controller.failed should ===(0)
    }

    cancelable.unsafeRunSync()
  }
}
