package freya

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, TimeUnit}
import cats.Parallel
import cats.effect.unsafe.implicits.global
import cats.effect.{Async, ExitCode, IO, Ref, Sync}
import cats.implicits._
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.scalalogging.LazyLogging
import freya.Configuration.{ConfigMapConfig, CrdConfig}
import freya.ExitCodes.ConsumerExitCode
import freya.K8sNamespace.{AllNamespaces, Namespace}
import freya.Retry.{Infinite, Times}
import freya.generators.arbitrary
import freya.internal.kubeapi.CrdApi.StatusUpdate
import freya.internal.kubeapi.MetadataApi
import freya.json.jackson._
import freya.models.{CustomResource, Metadata, NewStatus, Resource, ResourcesList}
import freya.resource.ConfigMapParser
import freya.watcher.AbstractWatcher.CloseableWatcher
import freya.watcher.FeedbackConsumer.FeedbackChannel
import freya.watcher._
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition
import io.fabric8.kubernetes.api.model.{ConfigMap, ListOptions}
import io.fabric8.kubernetes.client.Watcher.Action
import io.fabric8.kubernetes.client._
import io.fabric8.kubernetes.client.dsl.Watchable
import org.scalacheck.Gen
import org.scalactic.anyvals.PosInt
import org.scalactic.source
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.tagobjects.Slow
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.scalacheck.{Checkers, ScalaCheckPropertyChecks}

import scala.collection.immutable.Queue
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.jdk.CollectionConverters._

class OperatorsTest
    extends AnyPropSpec
    with Matchers
    with Eventually
    with Checkers
    with ScalaCheckPropertyChecks
    with BeforeAndAfter
    with LazyLogging {
  implicit val patienceCfg: PatienceConfig = PatienceConfig(scaled(Span(5, Seconds)), scaled(Span(50, Millis)))

  val crdCfg: CrdConfig = CrdConfig(Namespace("test"), prefix, checkOpenshiftOnStartup = false)
  val configMapCfg: ConfigMapConfig = ConfigMapConfig(AllNamespaces, prefix, checkOpenshiftOnStartup = false)
  val client = new DefaultKubernetesClient()
  val cmParser: ConfigMapParser = ConfigMapParser()

  val mapper = new ObjectMapper
  mapper.registerModule(DefaultScalaModule)

  def client[F[_]: Sync]: F[KubernetesClient] =
    Sync[F].pure(client)

  def makeWatchable[T, U]: (Watchable[Watcher[U]], mutable.Set[Watcher[U]]) = {
    val singleWatcher = concurrentHashSet[Watcher[U]]

    val watchable = new Watchable[Watcher[U]] {
      override def watch(watcher: Watcher[U]): Watch = {
        singleWatcher += watcher

        () => singleWatcher -= watcher
      }

      override def watch(resourceVersion: String, watcher: Watcher[U]): Watch =
        watch(watcher)

      override def watch(options: ListOptions, watcher: Watcher[U]): Watch =
        watch(watcher)
    }

    (watchable, singleWatcher)
  }

  implicit def cmWatch[F[_]: Async, T](implicit watchable: Watchable[Watcher[ConfigMap]]): ConfigMapWatchMaker[F, T] =
    (context: ConfigMapWatcherContext[F, T]) =>
      new ConfigMapWatcher(context) {
        override def watch: F[(CloseableWatcher, F[ConsumerExitCode])] =
          registerWatcher(watchable)
      }

  implicit def crdWatch[F[_]: Async, T, U](implicit watchable: Watchable[Watcher[String]]): CrdWatchMaker[F, T, U] =
    (context: CrdWatcherContext[F, T, U]) =>
      new CustomResourceWatcher(context) {
        override def watch: F[(CloseableWatcher, F[ConsumerExitCode])] =
          registerWatcher(watchable)
      }

  implicit def crdDeployer[F[_]: Sync]: CrdDeployer[F] = new CrdDeployer[F] {
    override def deploy[T: JsonReader](
      client: KubernetesClient,
      cfg: CrdConfig,
      isOpenShift: Option[Boolean]
    ): F[CustomResourceDefinition] =
      Sync[F].pure(new CustomResourceDefinition())
  }

  def configMapOperator[F[_]: Async: Parallel](
    controller: CmController[F, Kerb]
  ): (Operator[F, Kerb, Unit], mutable.Set[Watcher[ConfigMap]]) = {
    import freya.yaml.jackson._
    val (fakeWatchable, singleWatcher) = makeWatchable[Kerb, ConfigMap]
    implicit val watchable: Watchable[Watcher[ConfigMap]] = fakeWatchable

    Operator.ofConfigMap[F, Kerb](configMapCfg, client[F], controller) -> singleWatcher
  }

  private def concurrentHashSet[T]: mutable.Set[T] =
    java.util.Collections.newSetFromMap(new ConcurrentHashMap[T, java.lang.Boolean]).asScala

  def crdOperator[F[_]: Async: Parallel, T: JsonReader](
    controller: Controller[F, T, Status],
    cfg: CrdConfig = crdCfg
  ): (Operator[F, T, Status], mutable.Set[Watcher[String]], mutable.Set[StatusUpdate[Status]]) = {
    val (fakeWatchable, singleWatcher) = makeWatchable[T, String]
    implicit val watchable: Watchable[Watcher[String]] = fakeWatchable

    val status = mutable.Set.empty[StatusUpdate[Status]]
    implicit val feedbackConsumer: FeedbackConsumerMaker[F, Status] = testFeedbackConsumer[F](status)

    val operator = Operator.ofCrd[F, T, Status](cfg, client[F], controller)
    (operator, singleWatcher, status)
  }

  private def testFeedbackConsumer[F[_]: Async](
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
    startOperator(operator.run)

    //then
    eventually {
      controller.initialized should ===(true)
    }
    var currentWatcher = eventually {
      getWatcherOrFail(singleWatcher)
    }
    forAll(WatcherAction.gen, AnyCustomResource.gen[Kerb]()) { case (action, (anyCr, spec, _)) =>
      //when
      currentWatcher = getWatcherOrFail(singleWatcher)
      currentWatcher.eventReceived(action, mapper.writeValueAsString(anyCr))

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
    startOperator(operator.run)
    val currentWatcher = eventually {
      getWatcherOrFail(singleWatcher)
    }

    val start = System.currentTimeMillis()
    //then
    forAll(
      WatcherAction.gen,
      AnyCustomResource.gen[Kerb](),
      workers(PosInt.ensuringValid(parallelNamespaces)),
      minSuccessful(PosInt.ensuringValid(parallelNamespaces))
    ) { case (action, (anyCr, spec, _)) =>
      //when
      currentWatcher.eventReceived(action, mapper.writeValueAsString(anyCr))

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
    operator.stop.unsafeRunSync()
    duration
  }

  property("Crd Operator dispatches same namespace events in original sequence", Slow) {
    //given
    val parallelNamespaces = 10
    val controllerEvents = Ref.of[IO, Queue[(Action, Kerb, Metadata)]](Queue.empty).unsafeRunSync()
    val controller = new CrdTestController[IO]() {
      override def save(action: Watcher.Action, spec: Kerb, meta: Metadata): IO[Unit] =
        controllerEvents.update(_.enqueue((action, spec, meta)))
    }
    val (operator, singleWatcher, _) = crdOperator[IO, Kerb](controller, crdCfg)

    def actionAndResourceGen(namespace: String) =
      for {
        a <- WatcherAction.gen
        r <- AnyCustomResource.gen[Kerb](ObjectMetaTest.constNamespaceGen(namespace))
      } yield (a, r)

    //when
    startOperator(operator.run)
    //then
    (0 until parallelNamespaces).toList.map { namespace =>
      IO {
        val ns = namespace.toString
        val list = Gen.choose(1, 20).flatMap(n => Gen.listOfN(n, actionAndResourceGen(ns))).sample.toList.flatten
        //when
        val currentEvents = list.zipWithIndex.map { case ((action, (anyCr, spec, _)), i) =>
          // mutate current spec to have an index to test event ordering
          val specWithIndex = spec.copy(index = i)
          anyCr.setSpec(StringProperty(mapper.writeValueAsString(specWithIndex)))

          singleWatcher.foreach(_.eventReceived(action, mapper.writeValueAsString(anyCr)))

          val meta = MetadataApi.translate(anyCr.getMetadata)
          (action, specWithIndex, meta)
        }

        //then
        def loop(waitTime: FiniteDuration): IO[Unit] =
          for {
            namespaceEvents <- groupByNamespace(controllerEvents)
            currentNamespaceEvents = namespaceEvents.get(ns).map(_.toList).getOrElse(Nil)
            _ <- IO(currentNamespaceEvents should contain allElementsOf currentEvents).void.recoverWith { e =>
              IO.sleep(1.second) *> {
                if (waitTime > 0.seconds)
                  loop(waitTime - 1.second)
                else
                  IO {
                    System.err.println(
                      s"[received events in namespace '$ns' (size: ${currentNamespaceEvents.length}, " +
                        s"other keys: ${namespaceEvents.keys}) do not contain elements $currentEvents]"
                    )
                  } *> IO.raiseError(e)
              }
            }
          } yield ()

        loop(15.seconds)
      }
    }.parSequence_.unsafeRunSync()

    groupByNamespace(controllerEvents).unsafeRunSync().foreach { case (namespace, queue) =>
      queue.toList.foldLeft(0) { case (acc, (_, kerb, _)) =>
        withClue(s"[namespace: $namespace, ${queue.toList.map { case (_, spec, _) =>
          spec.index
        }}] ") {
          acc should ===(kerb.index)
          acc + 1
        }
      }
    }

    operator.stop.unsafeRunSync()
  }

  private def groupByNamespace(controllerEvents: Ref[IO, Queue[(Action, Kerb, Metadata)]]) =
    controllerEvents.get.map(_.groupBy { case (_, _, meta) =>
      meta.namespace
    })

  private def checkStatus[T](status: mutable.Set[StatusUpdate[Status]], statusFlag: Boolean, meta: Metadata) = {
    val cr = StatusUpdate(meta, Status(statusFlag))
    status should contain(cr)
  }

  property("Crd Operator gets event from reconciler process") {
    //given
    val controller = new CrdTestController[IO]
    implicit val (fakeWatchable, _) = makeWatchable[Kerb, String]

    val testResources = new mutable.ArrayBuffer[Resource[Kerb, Status]]()
    implicit val helper: CrdHelperMaker[IO, Kerb, Status] = (context: CrdHelperContext) =>
      new CrdHelper[IO, Kerb, Status](context) {
        override def currentResources(namespace: K8sNamespace): Either[Throwable, ResourcesList[Kerb, Status]] =
          Right(testResources.toList)
      }

    val statusSet = mutable.Set.empty[StatusUpdate[Status]]
    implicit val feedbackConsumer: FeedbackConsumerMaker[IO, Status] = testFeedbackConsumer[IO](statusSet)

    val operator = Operator.ofCrd[IO, Kerb, Status](crdCfg, client[IO], controller).withReconciler(1.millis)
    //when
    startOperator(operator.run)

    forAll(AnyCustomResource.gen[Kerb]()) { case (anyCr, spec, status) =>
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
  }

  property("ConfigMap Operator gets event from reconciler process") {
    import freya.yaml.jackson._
    //given
    val controller = new ConfigMapTestController[IO]
    implicit val (fakeWatchable, _) = makeWatchable[Kerb, ConfigMap]

    val testResources = new mutable.ArrayBuffer[Resource[Kerb, Unit]]()
    implicit val helper: ConfigMapHelperMaker[IO, Kerb] = (context: ConfigMapHelperContext) =>
      new ConfigMapHelper[IO, Kerb](context) {
        override def currentResources(namespace: K8sNamespace): Either[Throwable, ResourcesList[Kerb, Unit]] =
          Right(testResources.toList)
      }

    val operator = Operator.ofConfigMap[IO, Kerb](configMapCfg, client[IO], controller).withReconciler(1.millis)
    //when
    startOperator(operator.run)

    forAll(CM.gen[Kerb]) { cm =>
      val (meta, spec) = getSpecAndMeta(cm)
      testResources += Right(CustomResource(meta, spec, None))
      //then
      eventually {
        controller.reconciledEvents should contain((spec, meta))
      }
    }
  }

  property("Crd Operator handles different events on restarts") {
    //given
    val controller = new CrdTestController[IO]
    val (operator, singleWatcher, _) = crdOperator[IO, Kerb](controller)
    val maxRestarts = PosInt(20)
    val allEvents = new ListBuffer[(Watcher.Action, Kerb, Metadata)]()
    //when

    startOperator(operator.withRestart(Times(maxRestarts, 0.seconds)))

    var oldWatcher = eventually {
      getWatcherOrFail(singleWatcher)
    }

    //then
    controller.initialized should ===(true)

    forAll(
      WatcherAction.gen,
      AnyCustomResource.gen[Kerb](),
      arbitrary[Boolean],
      minSuccessful(maxRestarts)
    ) { case (action, (anyCr, spec, _), close) =>
      //when
      if (close)
        closeCurrentWatcher(singleWatcher, oldWatcher)

      oldWatcher = getWatcherOrFail(singleWatcher)
      singleWatcher.foreach(_.eventReceived(action, mapper.writeValueAsString(anyCr)))

      val meta = Metadata.fromObjectMeta(anyCr.getMetadata)
      allEvents += ((action, spec, meta))
      //then
      eventually {
        controller.events.asScala.toList should ===(allEvents)
      }
    }
    operator.stop.unsafeRunSync()
  }

  property("ConfigMap Operator handles different events on restarts") {
    //given
    val controller = new ConfigMapTestController[IO]
    val (operator, singleWatcher) = configMapOperator[IO](controller)
    val maxRestarts = PosInt(20)
    val allEvents = new ListBuffer[(Watcher.Action, Kerb, Metadata)]()

    //when
    startOperator(operator.withRestart(Infinite(0.seconds, 1.seconds)))
    var currentWatcher = eventually {
      getWatcherOrFail(singleWatcher)
    }

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

    operator.stop.unsafeRunSync()
  }

  private def getSpecAndMeta(cm: ConfigMap) = {
    val meta = toMetadata(cm)
    val spec = parseCM(cmParser, cm)
    (meta, spec)
  }

  private def getWatcherOrFail[T](set: mutable.Set[Watcher[T]])(implicit pos: source.Position): Watcher[T] =
    set.headOption.getOrElse(fail("there must be at least one watcher"))

  property("Operators restarts n times in case of failure") {
    //given
    val controller = new ConfigMapTestController[IO]
    val (operator, singleWatcher) = configMapOperator[IO](controller)
    val maxRestarts = PosInt(3)
    val allEvents = new ListBuffer[(Watcher.Action, Kerb, Metadata)]()

    //when
    val exitCode = startOperator(operator.withRestart(Times(maxRestarts, 0.seconds)))
    var currentWatcher = eventually {
      getWatcherOrFail(singleWatcher)
    }

    forAll(WatcherAction.gen, CM.gen[Kerb], minSuccessful(maxRestarts)) { (action, cm) =>
      //when
      closeCurrentWatcher(singleWatcher, currentWatcher)
      currentWatcher = getWatcherOrFail(singleWatcher)
      currentWatcher.eventReceived(action, cm)

      //then
      val (meta, spec) = getSpecAndMeta(cm)
      allEvents += ((action, spec, meta))

      eventually {
        controller.events.asScala.toList should ===(allEvents)
      }
    }

    currentWatcher.onClose(TestException("test"))

    eventually {
      exitCode.isCompleted should be(true)
      val ec = Await.result(exitCode, 0.second)
      ec should ===(ExitCodes.ActionConsumerExitCode)
    }
  }

  property("Operator returns error code on failure") {
    val controller = new ConfigMapTestController[IO] {
      override def onInit(): IO[Unit] = IO.raiseError(TestException("test exception"))
    }
    val (operator, _) = configMapOperator[IO](controller)
    forAll(Gen.const(1)) { _ => operator.run.unsafeRunSync() should ===(ExitCode.Error) }
  }

  private def closeCurrentWatcher[T](singleWatcher: mutable.Set[Watcher[T]], currentWatcher: Watcher[T])(implicit
    pos: source.Position
  ) = {
    singleWatcher.foreach { w =>
      val raiseException = arbitrary[Boolean].sample.getOrElse(fail("failed to generate boolean"))
      val ex = if (raiseException) Some(TestException("test exception")) else None
      w.onClose(ex.orNull)
    }
    eventually {
      //then
      singleWatcher.size should be(1)
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
    startOperator(operator.run)

    //then
    eventually {
      controller.initialized should ===(true)
    }

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

    operator.stop.unsafeRunSync()
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
    startOperator(operator.run)

    val currentWatcher = eventually {
      getWatcherOrFail(singleWatcher)
    }

    forAll(WatcherAction.gen, CM.gen[Kerb]) { (action, cm) =>
      val (meta, spec) = getSpecAndMeta(cm)

      if (spec.failInTest)
        cm.getData.put(ConfigMapParser.SpecificationKey, "error")

      //when
      currentWatcher.eventReceived(action, cm)

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

    operator.stop.unsafeRunSync()
  }

  property("Operator handles controller failures") {
    //given
    val allEvents = new ListBuffer[(Watcher.Action, Kerb, Metadata)]()
    val controller: ConfigMapTestController[IO] = new ConfigMapTestController[IO] {
      val error: IO[NewStatus[Unit]] = IO.raiseError(TestException("test exception"))

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
    startOperator(operator.run)
    val currentWatcher = eventually {
      getWatcherOrFail(singleWatcher)
    }

    forAll(WatcherAction.gen, CM.gen[Kerb]) { (action, cm) =>
      val (meta, spec) = getSpecAndMeta(cm)
      //when
      currentWatcher.eventReceived(action, cm)
      //then
      if (!spec.failInTest) {
        allEvents += ((action, spec, meta))
        eventually {
          controller.events.asScala.toList should ===(allEvents)
        }
      }
    }

    operator.stop.unsafeRunSync()
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
    startOperator(operator.run)
    val currentWatcher = eventually {
      getWatcherOrFail(singleWatcher)
    }

    forAll(WatcherAction.gen, CM.gen[Kerb]) { (action, cm) =>
      val (meta: Metadata, spec: Kerb) = getSpecAndMeta(cm)

      //when
      currentWatcher.eventReceived(action, cm)

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

    operator.stop.unsafeRunSync()
  }
}
