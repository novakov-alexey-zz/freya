package io.github.novakovalexey.k8soperator

import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, IO, Sync, Timer}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.{DefaultScalaModule, ScalaObjectMapper}
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.api.model.{ConfigMap, ConfigMapBuilder, ObjectMeta}
import io.fabric8.kubernetes.client.Watcher.Action
import io.fabric8.kubernetes.client.dsl.Watchable
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}
import io.github.novakovalexey.k8soperator.Controller.ConfigMapController
import io.github.novakovalexey.k8soperator.internal.resource.ConfigMapParser
import io.github.novakovalexey.k8soperator.watcher.WatcherMaker.{Consumer, ConsumerSignal}
import io.github.novakovalexey.k8soperator.watcher._
import org.scalacheck.{Arbitrary, Gen}
import org.scalactic.anyvals.PosInt
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.scalacheck.{Checkers, ScalaCheckPropertyChecks}

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class OperatorsTest extends AnyPropSpec with Matchers with Eventually with Checkers with ScalaCheckPropertyChecks {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
  implicit val patienceCfg: PatienceConfig = PatienceConfig(scaled(Span(5, Seconds)), scaled(Span(50, Millis)))
  implicit lazy val arbInfoClass: Arbitrary[Krb2] = Arbitrary(Krb2.gen)
  implicit lazy val arbBooleab: Arbitrary[Boolean] = Arbitrary(Gen.oneOf(true, false))

  def arbitrary[T](implicit a: Arbitrary[T]): Gen[T] = a.arbitrary

  val prefix = "io.github.novakov-alexey"

  def client[F[_]: Sync]: F[KubernetesClient] =
    Sync[F].delay(new JavaK8sClientMock())

  def makeWatchable[T, U]: (Watchable[Watch, Watcher[U]], mutable.Set[Watcher[U]]) = {
    val singleWatcher: mutable.Set[Watcher[U]] = mutable.Set.empty

    val watchable = new Watchable[Watch, Watcher[U]] {
      override def watch(watcher: Watcher[U]): Watch = {
        singleWatcher += watcher

        () =>
          singleWatcher -= watcher
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

  class CrdTestController[F[_]](implicit override val F: ConcurrentEffect[F])
      extends Controller[F, Krb2]
      with LazyLogging {
    val events: mutable.Set[(Action, Krb2, Metadata)] = mutable.Set.empty
    var initialized: Boolean = false

    override def onAdd(krb: Krb2, meta: Metadata): F[Unit] =
      F.delay(events += ((Action.ADDED, krb, meta)))

    override def onDelete(krb: Krb2, meta: Metadata): F[Unit] =
      F.delay(events += ((Action.DELETED, krb, meta)))

    override def onModify(krb: Krb2, meta: Metadata): F[Unit] =
      F.delay(events += ((Action.MODIFIED, krb, meta)))

    override def onInit(): F[Unit] =
      F.delay({
        this.initialized = true
        logger.debug("Controller initialized")
      })
  }

  class ConfigMapTestController[F[_]: ConcurrentEffect] extends CrdTestController[F] with CMController {
    override def isSupported(cm: ConfigMap): Boolean = true
  }

  def configMapOperator[F[_]: ConcurrentEffect](controller: ConfigMapController[F, Krb2]) = {
    val (fakeWatchable, singleWatcher) = makeWatchable[Krb2, ConfigMap]
    implicit val watchable: Watchable[Watch, Watcher[ConfigMap]] = fakeWatchable
    val cfg = ConfigMapConfig(classOf[Krb2], AllNamespaces, prefix)

    Operator.ofConfigMap[F, Krb2](cfg, client[F], controller) -> singleWatcher
  }

  def crdOperator[F[_]: ConcurrentEffect](controller: Controller[F, Krb2]) = {
    val (fakeWatchable, singleWatcher) = makeWatchable[Krb2, InfoClass[Krb2]]
    implicit val watchable: Watchable[Watch, Watcher[InfoClass[Krb2]]] = fakeWatchable
    val cfg = CrdConfig(classOf[Krb2], Namespace("yp-kss"), prefix)

    Operator.ofCrd[F, Krb2](cfg, client[F], controller) -> singleWatcher
  }

  property("Crd Operator handles different events") {
    //given
    val controller = new CrdTestController[IO]
    val (operator, singleWatcher) = crdOperator[IO](controller)

    //when
    val cancelable = startOperator(operator.run)

    //then
    controller.initialized should ===(true)

    forAll(WatcherAction.gen, InfoClass.gen[Krb2]) { (action, crd) =>
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

    forAll(WatcherAction.gen, InfoClass.gen[Krb2], arbitrary[Boolean], minSuccessful(maxRestarts)) {
      (action, crd, close) =>
        //when
        if (close)
          closeCurrentWatcher[InfoClass[Krb2]](singleWatcher, oldWatcher)

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
    var oldWatcher = singleWatcher.head

    //then
    controller.initialized should ===(true)
    val parser = ConfigMapParser[IO]().unsafeRunSync()

    forAll(WatcherAction.gen, CM.gen[Krb2], arbitrary[Boolean], minSuccessful(maxRestarts)) { (action, cm, close) =>
      //when
      if (close)
        closeCurrentWatcher[ConfigMap](singleWatcher, oldWatcher)

      oldWatcher = singleWatcher.head

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

  private def closeCurrentWatcher[T](singleWatcher: mutable.Set[Watcher[T]], oldWatcher: Watcher[T]) = {
    singleWatcher.foreach { w =>
      val ex = if (arbitrary[Boolean].sample.get) new KubernetesClientException("test exception") else null
      w.onClose(ex)
    }
    eventually {
      //then
      singleWatcher.size should ===(1)
      oldWatcher should !==(singleWatcher.head) // checking that the Set with single watcher is updated with new watcher after restart
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
    forAll(WatcherAction.gen, CM.gen[Krb2]) { (action, cm) =>
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

  private def parseCM(parser: ConfigMapParser, cm: ConfigMap) =
    parser.parseCM(classOf[Krb2], cm).getOrElse(fail("Error when transforming ConfigMap to Krb2"))._1

  private def startOperator(io: IO[ExitCode]) =
    io.unsafeRunCancelable {
      case Right(ec) =>
        println(s"Operator stopped with exit code: $ec")
      case Left(t) =>
        println("Failed to start operator")
        t.printStackTrace()
    }

  object ObjectMeta {
    def apply(name: String, namespace: String): ObjectMeta = {
      val meta = new ObjectMeta()
      meta.setName(name)
      meta.setNamespace(namespace)
      meta
    }

    def gen: Gen[ObjectMeta] =
      for {
        name <- Gen.alphaNumStr
        namespace <- Gen.alphaNumStr
      } yield ObjectMeta(name, namespace)
  }

  object InfoClass {
    def gen[T: Arbitrary]: Gen[InfoClass[T]] =
      for {
        spec <- arbitrary[T]
        meta <- ObjectMeta.gen
      } yield {
        val ic = new InfoClass[T]
        ic.setSpec(spec)
        ic.setMetadata(meta)
        ic
      }
  }

  object WatcherAction {
    def gen: Gen[Action] =
      Gen.oneOf(Action.ADDED, Action.DELETED, Action.MODIFIED)
  }

  object CM {
    val mapper = new ObjectMapper(new YAMLFactory()) with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)

    def gen[T](implicit A: Arbitrary[T]): Gen[ConfigMap] =
      for {
        spec <- Arbitrary.arbitrary[T]
        meta <- ObjectMeta.gen
      } yield {

        new ConfigMapBuilder()
          .withMetadata(meta)
          .withData(Map("config" -> mapper.writeValueAsString(spec)).asJava)
          .build()
      }
  }
}
