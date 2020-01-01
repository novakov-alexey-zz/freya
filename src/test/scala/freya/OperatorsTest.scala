package freya

import java.util.concurrent.ConcurrentHashMap

import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, IO, Sync, Timer}
import freya.Configuration.CrdConfig
import freya.Controller.ConfigMapController
import freya.K8sNamespace.{AllNamespaces, Namespace}
import freya.Retry.Times
import freya.generators.arbitrary
import freya.models.{Resource, ResourcesList}
import freya.resource.ConfigMapParser
import freya.signals.ConsumerSignal
import freya.watcher.AbstractWatcher.CloseableWatcher
import freya.watcher._
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
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

import scala.collection.mutable
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
  implicit val patienceCfg: PatienceConfig = PatienceConfig(scaled(Span(10, Seconds)), scaled(Span(50, Millis)))

  val crdCfg = CrdConfig(classOf[Kerb], Namespace("test"), prefix, checkK8sOnStartup = false)
  val configMapcfg = Configuration.ConfigMapConfig(classOf[Kerb], AllNamespaces, prefix, checkK8sOnStartup = false)
  val server = new KubernetesServer(false, false)
  val cmParser = ConfigMapParser[IO]().unsafeRunSync()

  before {
    server.before()
  }

  after {
    server.after()
  }

  def client[F[_]: Sync]: F[KubernetesClient] =
    Sync[F].pure(server.getClient)

  def makeWatchable[T, U]: (Watchable[Watch, Watcher[U]], mutable.Set[Watcher[U]]) = {
    val singleWatcher =
      java.util.Collections.newSetFromMap(new ConcurrentHashMap[Watcher[U], java.lang.Boolean]).asScala

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

  implicit def cmWatch[F[_]: ConcurrentEffect, T](
    implicit watchable: Watchable[Watch, Watcher[ConfigMap]]
  ): ConfigMapWatchMaker[F, T] =
    (context: ConfigMapWatcherContext[F, T]) =>
      new ConfigMapWatcher(context) {
        override def watch: F[(CloseableWatcher, F[ConsumerSignal])] =
          registerWatcher(watchable)
      }

  implicit def crdWatch[F[_]: ConcurrentEffect, T](
    implicit watchable: Watchable[Watch, Watcher[SpecClass]]
  ): CrdWatchMaker[F, T] =
    (context: CrdWatcherContext[F, T]) =>
      new CustomResourceWatcher(context) {
        override def watch: F[(CloseableWatcher, F[ConsumerSignal])] =
          registerWatcher(watchable)
      }

  implicit def crdDeployer[F[_]: Sync, T]: CrdDeployer[F, T] =
    (_, _: CrdConfig[T], _: Option[Boolean]) => Sync[F].pure(new CustomResourceDefinition())

  def configMapOperator[F[_]: ConcurrentEffect: Timer: ContextShift](
    controller: ConfigMapController[F, Kerb]
  ): (Operator[F, Kerb], mutable.Set[Watcher[ConfigMap]]) = {
    val (fakeWatchable, singleWatcher) = makeWatchable[Kerb, ConfigMap]
    implicit val watchable: Watchable[Watch, Watcher[ConfigMap]] = fakeWatchable

    Operator.ofConfigMap[F, Kerb](configMapcfg, client[F], controller) -> singleWatcher
  }

  def crdOperator[F[_]: ConcurrentEffect: Timer: ContextShift](
    controller: Controller[F, Kerb]
  ): (Operator[F, Kerb], mutable.Set[Watcher[SpecClass]]) = {
    val (fakeWatchable, singleWatcher) = makeWatchable[Kerb, SpecClass]
    implicit val watchable: Watchable[Watch, Watcher[SpecClass]] = fakeWatchable

    Operator.ofCrd[F, Kerb](crdCfg, client[F], controller).withReconciler(1.millis) -> singleWatcher
  }

  property("Crd Operator handles different events") {
    //given
    val controller = new CrdTestController[IO]
    val (operator, singleWatcher) = crdOperator[IO](controller)

    //when
    val cancelable = startOperator(operator.run)

    //then
    controller.initialized should ===(true)

    forAll(WatcherAction.gen, SpecClass.gen[Kerb](crdCfg.getKind)) { (action, crd) =>
      //when
      singleWatcher.foreach(_.eventReceived(action, crd))

      val meta = Metadata(crd.getMetadata.getName, crd.getMetadata.getNamespace)
      //then
      eventually {
        controller.events should contain((action, crd.getSpec, meta))
      }
    }

    cancelable.unsafeRunSync()
  }

  property("Crd Operator gets event from reconciler process") {
    //given
    val controller = new CrdTestController[IO]
    implicit val (fakeWatchable, _) = makeWatchable[Kerb, SpecClass]

    val testResources = new mutable.ArrayBuffer[Resource[Kerb]]()
    implicit val helper: CrdHelperMaker[IO, Kerb] = (context: CrdHelperContext[Kerb]) =>
      new CrdHelper[IO, Kerb](context) {
        override def currentResources: Either[Throwable, ResourcesList[Kerb]] =
          Right(testResources.toList)
      }

    val operator = Operator.ofCrd[IO, Kerb](crdCfg, client[IO], controller).withReconciler(1.millis)
    //when
    val cancelable = startOperator(operator.run)

    forAll(SpecClass.gen[Kerb](crdCfg.getKind)) { crd =>
      val meta = Metadata(crd.getMetadata.getName, crd.getMetadata.getNamespace)
      testResources += Right((crd.getSpec.asInstanceOf[Kerb], meta))
      //then
      eventually {
        controller.reconciledEvents should contain((crd.getSpec, meta))
      }
    }

    cancelable.unsafeRunSync()
  }

  property("ConfigMap Operator gets event from reconciler process") {
    //given
    val controller = new ConfigMapTestController[IO]
    implicit val (fakeWatchable, _) = makeWatchable[Kerb, ConfigMap]

    val testResources = new mutable.ArrayBuffer[Resource[Kerb]]()
    implicit val helper: ConfigMapHelperMaker[IO, Kerb] = (context: ConfigMapHelperContext[Kerb]) =>
      new ConfigMapHelper[IO, Kerb](context) {
        override def currentResources: Either[Throwable, ResourcesList[Kerb]] =
          Right(testResources.toList)
      }

    val operator = Operator.ofConfigMap[IO, Kerb](configMapcfg, client[IO], controller).withReconciler(1.millis)
    //when
    val cancelable = startOperator(operator.run)

    forAll(CM.gen[Kerb]) { cm =>
      val meta = toMetadata(cm)
      val spec = parseCM(cmParser, cm)

      testResources += Right((spec, meta))
      //then
      eventually {
        controller.reconciledEvents should contain((spec, meta))
      }
    }

    cancelable.unsafeRunSync()
  }

  def toMetadata(cm: ConfigMap): Metadata =
    Metadata(cm.getMetadata.getName, cm.getMetadata.getNamespace)

  property("Crd Operator handles different events on restarts") {
    //given
    val controller = new CrdTestController[IO]
    val (operator, singleWatcher) = crdOperator[IO](controller)
    val maxRestarts = PosInt(20)

    //when
    val cancelable = startOperator(operator.withRestart(Times(maxRestarts, 0.seconds)))
    var oldWatcher = getWatcherOrFail(singleWatcher)

    //then
    controller.initialized should ===(true)

    forAll(WatcherAction.gen, SpecClass.gen[Kerb](crdCfg.getKind), arbitrary[Boolean], minSuccessful(maxRestarts)) {
      (action, crd, close) =>
        //when
        if (close)
          closeCurrentWatcher[SpecClass](singleWatcher, oldWatcher)

        oldWatcher = getWatcherOrFail(singleWatcher)

        //when
        singleWatcher.foreach(_.eventReceived(action, crd))

        val meta = Metadata(crd.getMetadata.getName, crd.getMetadata.getNamespace)

        //then
        eventually {
          controller.events should contain((action, crd.getSpec, meta))
        }
    }

    cancelable.unsafeRunSync()
  }

  property("ConfigMap Operator handles different events on restarts") {
    //given
    val controller = new ConfigMapTestController[IO]
    val (operator, singleWatcher) = configMapOperator[IO](controller)
    val maxRestarts = PosInt(20)

    //when
    val cancelable = startOperator(operator.withRestart(Times(maxRestarts, 0.seconds)))
    var currentWatcher = getWatcherOrFail(singleWatcher)

    //then
    controller.initialized should ===(true)

    forAll(WatcherAction.gen, CM.gen[Kerb], arbitrary[Boolean], minSuccessful(maxRestarts)) { (action, cm, close) =>
      //when
      if (close)
        closeCurrentWatcher[ConfigMap](singleWatcher, currentWatcher)

      currentWatcher = getWatcherOrFail(singleWatcher)

      //when
      singleWatcher.foreach(_.eventReceived(action, cm))

      val meta = toMetadata(cm)
      val spec = parseCM(cmParser, cm)

      //then
      eventually {
        controller.events should contain((action, spec, meta))
      }
    }

    cancelable.unsafeRunSync()
  }

  private def getWatcherOrFail[T](set: mutable.Set[Watcher[T]]): Watcher[T] =
    set.headOption.getOrElse(fail("there must be at least one watcher"))

  property("Operators restarts n times in case of failure") {
    //given
    val controller = new ConfigMapTestController[IO]
    val (operator, singleWatcher) = configMapOperator[IO](controller)
    val maxRestarts = PosInt(3)

    //when
    val exitCode = operator.withRestart(Times(maxRestarts, 0.seconds)).unsafeToFuture()

    var currentWatcher = getWatcherOrFail(singleWatcher)
    val parser = ConfigMapParser[IO]().unsafeRunSync()

    forAll(WatcherAction.gen, CM.gen[Kerb], minSuccessful(maxRestarts)) { (action, cm) =>
      //when
      closeCurrentWatcher(singleWatcher, currentWatcher)
      currentWatcher = getWatcherOrFail(singleWatcher)
      singleWatcher.foreach(_.eventReceived(action, cm))

      //then
      val meta = toMetadata(cm)
      val spec = parseCM(parser, cm)
      eventually {
        controller.events should contain((action, spec, meta))
      }
    }

    singleWatcher.foreach(_.onClose(new KubernetesClientException("test")))

    eventually {
      exitCode.isCompleted should ===(true)
      val ec = Await.result(exitCode, 0.second)
      ec should ===(signals.WatcherClosedSignal)
    }
  }

  property("Operator returns error code on failure") {
    val controller = new ConfigMapTestController[IO] {
      override def onInit(): IO[Unit] = IO.raiseError(new RuntimeException("test exception"))
    }
    val (operator, _) = configMapOperator[IO](controller)
    forAll(Gen.alphaLowerStr) { _ =>
      operator.run.unsafeRunSync() should ===(ExitCode.Error)
    }
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
      currentWatcher should !==(getWatcherOrFail(singleWatcher)) // waiting until the Set with single watcher is updated with new watcher after Operator restart
    }
  }

  property("ConfigMap Operator handles different events") {
    //given
    val controller = new ConfigMapTestController[IO]
    val (operator, singleWatcher) = configMapOperator[IO](controller)

    //when
    val cancelable = startOperator(operator.run)

    //then
    controller.initialized should ===(true)

    forAll(WatcherAction.gen, CM.gen[Kerb]) { (action, cm) =>
      //when
      singleWatcher.foreach(_.eventReceived(action, cm))
      val meta = toMetadata(cm)
      val spec = parseCM(cmParser, cm)

      //then
      eventually {
        controller.events should contain((action, spec, meta))
      }
    }

    cancelable.unsafeRunSync()
  }

  class CountingFailureFlagController extends ConfigMapTestController[IO] {
    var failed: Int = 0

    override def onAdd(krb: Kerb, meta: Metadata): IO[Unit] = {
      if (krb.failInTest)
        failed = failed + 1
      super.onAdd(krb, meta)
    }

    override def onDelete(krb: Kerb, meta: Metadata): IO[Unit] = {
      if (krb.failInTest)
        failed = failed + 1
      super.onDelete(krb, meta)
    }

    override def onModify(krb: Kerb, meta: Metadata): IO[Unit] = {
      if (krb.failInTest)
        failed = failed + 1
      super.onModify(krb, meta)
    }
  }

  property("Operator handles parser errors") {
    //given
    val controller = new CountingFailureFlagController()
    val (operator, singleWatcher) = configMapOperator[IO](controller)

    //when
    val cancelable = startOperator(operator.run)

    forAll(WatcherAction.gen, CM.gen[Kerb]) { (action, cm) =>
      val meta = toMetadata(cm)
      val spec = parseCM(cmParser, cm)

      if (spec.failInTest)
        cm.getData.put(ConfigMapParser.SpecificationKey, "error")

      //when
      singleWatcher.foreach(_.eventReceived(action, cm))

      //then
      if (!spec.failInTest)
        eventually {
          controller.events should contain((action, spec, meta))
        } else
        controller.events should not contain ((action, spec, meta))

      controller.failed should ===(0)
    }

    cancelable.unsafeRunSync()
  }

  property("Operator handles controller failures") {
    //given
    val controller: ConfigMapTestController[IO] = new ConfigMapTestController[IO] {
      val error: IO[Unit] = IO.raiseError(new RuntimeException("test exception"))

      override def onAdd(krb: Kerb, meta: Metadata): IO[Unit] =
        if (krb.failInTest)
          error
        else
          super.onAdd(krb, meta)

      override def onDelete(krb: Kerb, meta: Metadata): IO[Unit] =
        if (krb.failInTest)
          error
        else
          super.onDelete(krb, meta)

      override def onModify(krb: Kerb, meta: Metadata): IO[Unit] =
        if (krb.failInTest)
          error
        else
          super.onModify(krb, meta)
    }

    val (operator, singleWatcher) = configMapOperator[IO](controller)

    //when
    val cancelable = startOperator(operator.run)

    forAll(WatcherAction.gen, CM.gen[Kerb]) { (action, cm) =>
      val meta = toMetadata(cm)
      val spec = parseCM(cmParser, cm)
      //when
      singleWatcher.foreach(_.eventReceived(action, cm))

      //then
      if (!spec.failInTest)
        eventually {
          controller.events should contain((action, spec, meta))
        }
    }

    cancelable.unsafeRunSync()
  }

  property("ConfigMap operator handles only supported ConfigMaps") {
    //given
    val controller = new CountingFailureFlagController() {
      override def isSupported(cm: ConfigMap): Boolean = {
        val spec = parseCM(cmParser, cm)
        !spec.failInTest
      }
    }
    val (operator, singleWatcher) = configMapOperator[IO](controller)

    //when
    val cancelable = startOperator(operator.run)

    forAll(WatcherAction.gen, CM.gen[Kerb]) { (action, cm) =>
      val meta = toMetadata(cm)
      val spec = parseCM(cmParser, cm)

      //when
      singleWatcher.foreach(_.eventReceived(action, cm))

      //then
      if (!spec.failInTest)
        eventually {
          controller.events should contain((action, spec, meta))
        } else
        controller.events should not contain ((action, spec, meta))

      controller.failed should ===(0)
    }

    cancelable.unsafeRunSync()
  }
}
