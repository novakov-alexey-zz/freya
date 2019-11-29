package io.github.novakovalexey.k8soperator

import cats.effect.{ConcurrentEffect, ContextShift, IO, Sync, Timer}
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.Watcher.Action
import io.fabric8.kubernetes.client.dsl.Watchable
import io.fabric8.kubernetes.client.{Watch, Watcher}
import io.github.novakovalexey.k8soperator.watcher.WatcherMaker.{Consumer, ConsumerSignal}
import io.github.novakovalexey.k8soperator.watcher.{CrdWatcherContext, CustomResourceWatcher, InfoClass}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{Matchers, PropSpec}
import org.scalatestplus.scalacheck.{Checkers, ScalaCheckPropertyChecks}

import scala.collection.mutable
import scala.concurrent.ExecutionContext

class CrdOperatorTest extends PropSpec with Matchers with Eventually with Checkers with ScalaCheckPropertyChecks {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
  implicit val patienceCfg: PatienceConfig = PatienceConfig(scaled(Span(5, Seconds)), scaled(Span(50, Millis)))
  implicit lazy val arbInfoClass: Arbitrary[Krb2] = Arbitrary(Krb2.gen)

  def makeWatchable[T]: (Watchable[Watch, Watcher[InfoClass[T]]], mutable.Set[Watcher[InfoClass[T]]]) = {
    val singleWatcher = mutable.Set.empty[Watcher[InfoClass[T]]]

    val watchable = new Watchable[Watch, Watcher[InfoClass[T]]] {
      override def watch(watcher: Watcher[InfoClass[T]]): Watch = {
        singleWatcher += watcher
        () =>
          singleWatcher -= watcher
      }

      override def watch(resourceVersion: String, watcher: Watcher[InfoClass[T]]): Watch =
        watch(watcher)
    }

    (watchable, singleWatcher)
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

  class KrbController[F[_]](implicit F: ConcurrentEffect[F]) extends Controller[F, Krb2] {
    val addEvents: mutable.Set[(Action, Krb2, Metadata)] = mutable.Set.empty
    var initialized: Boolean = false

    override def onAdd(krb: Krb2, meta: Metadata): F[Unit] =
      F.delay(addEvents += ((Action.ADDED, krb, meta)))

    override def onDelete(krb: Krb2, meta: Metadata): F[Unit] =
      F.delay(addEvents += ((Action.DELETED, krb, meta)))

    override def onModify(krb: Krb2, meta: Metadata): F[Unit] =
      F.delay(addEvents += ((Action.MODIFIED, krb, meta)))

    override def onInit(): F[Unit] =
      F.delay(this.initialized = true)
  }

  property("handle different events") {
    //given
    val (fakeWatchable, singleWatcher) = makeWatchable[Krb2]
    implicit val watchable: Watchable[Watch, Watcher[InfoClass[Krb2]]] = fakeWatchable
    val client = IO(new JavaK8sClientMock())
    val cfg = CrdConfig(classOf[Krb2], Namespace("yp-kss"), "io.github.novakov-alexey")
    val controller = new KrbController[IO]
    // launch operator in background
    val cancel = Operator
      .ofCrd[IO, Krb2](cfg, client, controller)
      .run
      .unsafeRunCancelable {
        case Right(ec) =>
          println(s"Operator stopped with exit code: $ec")
        case Left(t) =>
          println("Failed to start operator")
          t.printStackTrace()
      }

    controller.initialized should ===(true)

    forAll(WatcherAction.gen, InfoClass.gen[Krb2]) { (action, resource) =>
      //when
      singleWatcher.foreach(_.eventReceived(action, resource))
      val meta = Metadata(resource.getMetadata.getName, resource.getMetadata.getNamespace)

      //then
      eventually {
        controller.addEvents should contain((action, resource.getSpec, meta))
      }
    }

    cancel.unsafeRunSync()
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
    def gen[T](implicit a: Arbitrary[T]): Gen[InfoClass[T]] =
      for {
        spec <- Arbitrary.arbitrary[T]
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
}
