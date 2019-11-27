package io.github.novakovalexey.k8soperator

import cats.effect.{ConcurrentEffect, ContextShift, IO, Sync, Timer}
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.dsl.Watchable
import io.fabric8.kubernetes.client.{Watch, Watcher}
import io.github.novakovalexey.k8soperator.common.crd.InfoClass
import io.github.novakovalexey.k8soperator.common.watcher.WatchMaker.ConsumerSignal
import io.github.novakovalexey.k8soperator.common.watcher.{CrdWatcherContext, CustomResourceWatcher}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{AsyncFlatSpec, Matchers}

import scala.collection.mutable
import scala.concurrent.ExecutionContext

class CrdOperatorTest extends AsyncFlatSpec with Matchers with Eventually {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
  implicit val patienceCfg: PatienceConfig = PatienceConfig(scaled(Span(5, Seconds)), scaled(Span(50, Millis)))

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

  implicit def crd[F[_]: ConcurrentEffect, T](
    implicit watchable: Watchable[Watch, Watcher[InfoClass[T]]]
  ): CrdWatchMaker[F, T] =
    (context: CrdWatcherContext[F, T]) =>
      new CustomResourceWatcher(context) {
        override def watch: F[(Watch, ConsumerSignal[F])] =
          registerWatcher(watchable)
    }

  implicit def crdDeployer[F[_]: Sync, T]: CrdDeployer[F, T] =
    (_, _: CrdConfig[T], _: Option[Boolean]) => Sync[F].pure(new CustomResourceDefinition())

  class KrbController[F[_]](implicit F: ConcurrentEffect[F]) extends Controller[F, Krb2] with LazyLogging {
    val addEvents: mutable.Set[(Krb2, Metadata)] = mutable.Set.empty

    override def onAdd(krb: Krb2, meta: Metadata): F[Unit] =
      F.delay {
        logger.info(s"new Krb added: $krb, $meta")
        addEvents += ((krb, meta))
      }

    override def onDelete(krb: Krb2, meta: Metadata): F[Unit] =
      F.delay(logger.info(s"Krb deleted: $krb, $meta"))
  }

  it should "handle different events" in {
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

    val spec = Krb2("EXAMPLE.COM", List(Principal("user1", "static", "123456")))
    val ic = new InfoClass[Krb2]
    ic.setSpec(spec)
    val meta = new ObjectMeta()
    meta.setName("my-krb")
    meta.setNamespace("my-namespace")
    ic.setMetadata(meta)

    //when
    singleWatcher.foreach(_.eventReceived(Watcher.Action.ADDED, ic))

    //then
    cancel.map { _ =>
      eventually {
        assert(controller.addEvents.exists(_._1 == spec))
      }
    }.unsafeToFuture()
  }
}
