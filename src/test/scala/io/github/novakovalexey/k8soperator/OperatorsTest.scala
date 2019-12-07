package io.github.novakovalexey.k8soperator

import cats.effect.{ConcurrentEffect, IO, Sync, Timer}
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.dsl.Watchable
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}
import io.github.novakovalexey.k8soperator.Controller.ConfigMapController
import io.github.novakovalexey.k8soperator.generators.arbitrary
import io.github.novakovalexey.k8soperator.internal.resource.ConfigMapParser
import io.github.novakovalexey.k8soperator.watcher.WatcherMaker.{Consumer, ConsumerSignal}
import io.github.novakovalexey.k8soperator.watcher._
import org.scalactic.anyvals.PosInt
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.scalacheck.{Checkers, ScalaCheckPropertyChecks}

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class OperatorsTest extends AnyPropSpec with Matchers with Eventually with Checkers with ScalaCheckPropertyChecks {
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
  implicit val patienceCfg: PatienceConfig = PatienceConfig(scaled(Span(5, Seconds)), scaled(Span(50, Millis)))
  val cfg: CrdConfig[Kerb] = CrdConfig(classOf[Kerb], Namespace("test"), prefix, checkK8sOnStartup = false)

  def client[F[_]: Sync]: F[KubernetesClient] =
    Sync[F].delay(new JavaK8sClientMock())

  def makeWatchable[T, U]: (Watchable[Watch, Watcher[U]], mutable.Set[Watcher[U]]) = {
    val singleWatcher: mutable.Set[Watcher[U]] = mutable.Set.empty

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
        override def watch: F[(Consumer, ConsumerSignal[F])] =
          registerWatcher(watchable)
      }

  implicit def crdWatch[F[_]: ConcurrentEffect, T](
    implicit watchable: Watchable[Watch, Watcher[InfoClass[T]]]
  ): CrdWatchMaker[F, T] =
    (context: CrdWatcherContext[F, T]) =>
      new CustomResourceWatcher(context) {
        override def watch: F[(Consumer, ConsumerSignal[F])] =
          registerWatcher(watchable)
      }

  implicit def crdDeployer[F[_]: Sync, T]: CrdDeployer[F, T] =
    (_, _: CrdConfig[T], _: Option[Boolean]) => Sync[F].pure(new CustomResourceDefinition())

  def configMapOperator[F[_]: ConcurrentEffect](
    controller: ConfigMapController[F, Kerb]
  ): (Operator[F, Kerb], mutable.Set[Watcher[ConfigMap]]) = {
    val (fakeWatchable, singleWatcher) = makeWatchable[Kerb, ConfigMap]
    implicit val watchable: Watchable[Watch, Watcher[ConfigMap]] = fakeWatchable
    val cfg = ConfigMapConfig(classOf[Kerb], AllNamespaces, prefix, checkK8sOnStartup = false)

    Operator.ofConfigMap[F, Kerb](cfg, client[F], controller) -> singleWatcher
  }

  def crdOperator[F[_]: ConcurrentEffect](
    controller: Controller[F, Kerb]
  ): (Operator[F, Kerb], mutable.Set[Watcher[InfoClass[Kerb]]]) = {
    val (fakeWatchable, singleWatcher) = makeWatchable[Kerb, InfoClass[Kerb]]
    implicit val watchable: Watchable[Watch, Watcher[InfoClass[Kerb]]] = fakeWatchable

    Operator.ofCrd[F, Kerb](cfg, client[F], controller) -> singleWatcher
  }

  property("Crd Operator handles different events") {
    //given
    val controller = new CrdTestController[IO]
    val (operator, singleWatcher) = crdOperator[IO](controller)

    //when
    val cancelable = startOperator(operator.run)

    //then
    controller.initialized should ===(true)

    forAll(WatcherAction.gen, InfoClass.gen[Kerb](cfg.getKind)) { (action, crd) =>
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

  property("Crd Operator handles different events on restarts") {
    //given
    val controller = new CrdTestController[IO]
    val (operator, singleWatcher) = crdOperator[IO](controller)
    val maxRestarts = PosInt(20)

    //when
    val cancelable = startOperator(operator.withRestart(Retry(maxRestarts, 0.seconds)))
    var oldWatcher = singleWatcher.head

    //then
    controller.initialized should ===(true)

    forAll(WatcherAction.gen, InfoClass.gen[Kerb](cfg.getKind), arbitrary[Boolean], minSuccessful(maxRestarts)) {
      (action, crd, close) =>
        //when
        if (close)
          closeCurrentWatcher[InfoClass[Kerb]](singleWatcher, oldWatcher)

        oldWatcher = singleWatcher.head

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
    val cancelable = startOperator(operator.withRestart(Retry(maxRestarts, 0.seconds)))
    var currentWatcher = singleWatcher.head

    //then
    controller.initialized should ===(true)
    val parser = ConfigMapParser[IO]().unsafeRunSync()

    forAll(WatcherAction.gen, CM.gen[Kerb], arbitrary[Boolean], minSuccessful(maxRestarts)) { (action, cm, close) =>
      //when
      if (close)
        closeCurrentWatcher[ConfigMap](singleWatcher, currentWatcher)

      currentWatcher = singleWatcher.head

      //when
      singleWatcher.foreach(_.eventReceived(action, cm))

      val meta = Metadata(cm.getMetadata.getName, cm.getMetadata.getNamespace)
      val spec = parseCM(parser, cm)

      //then
      eventually {
        controller.events should contain((action, spec, meta))
      }
    }

    cancelable.unsafeRunSync()
  }

  private def closeCurrentWatcher[T](singleWatcher: mutable.Set[Watcher[T]], currentWatcher: Watcher[T]) = {
    singleWatcher.foreach { w =>
      val ex = if (arbitrary[Boolean].sample.get) new KubernetesClientException("test exception") else null
      w.onClose(ex)
    }
    eventually {
      //then
      singleWatcher.size should ===(1)
      currentWatcher should !==(singleWatcher.head) // waiting until the Set with single watcher is updated with new watcher after Operator restart
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

    val parser = ConfigMapParser[IO]().unsafeRunSync()
    forAll(WatcherAction.gen, CM.gen[Kerb]) { (action, cm) =>
      //when
      singleWatcher.foreach(_.eventReceived(action, cm))
      val meta = Metadata(cm.getMetadata.getName, cm.getMetadata.getNamespace)
      val spec = parseCM(parser, cm)

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
    val parser = ConfigMapParser[IO]().unsafeRunSync()

    forAll(WatcherAction.gen, CM.gen[Kerb]) { (action, cm) =>
      val meta = Metadata(cm.getMetadata.getName, cm.getMetadata.getNamespace)
      val spec = parseCM(parser, cm)

      if (spec.failInTest)
        cm.getData.put("config", "error")

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

    val parser = ConfigMapParser[IO]().unsafeRunSync()
    forAll(WatcherAction.gen, CM.gen[Kerb]) { (action, cm) =>
      val meta = Metadata(cm.getMetadata.getName, cm.getMetadata.getNamespace)
      val spec = parseCM(parser, cm)
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
    val parser = ConfigMapParser[IO]().unsafeRunSync()

    val controller = new CountingFailureFlagController() {
      override def isSupported(cm: ConfigMap): Boolean = {
        val spec = parseCM(parser, cm)
        !spec.failInTest
      }
    }
    val (operator, singleWatcher) = configMapOperator[IO](controller)

    //when
    val cancelable = startOperator(operator.run)

    forAll(WatcherAction.gen, CM.gen[Kerb]) { (action, cm) =>
      val meta = Metadata(cm.getMetadata.getName, cm.getMetadata.getNamespace)
      val spec = parseCM(parser, cm)

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
