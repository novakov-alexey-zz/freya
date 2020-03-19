package freya.watcher

import java.io.Closeable

import cats.effect.ConcurrentEffect
import cats.effect.concurrent.MVar
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.ExitCodes.ConsumerExitCode
import freya._
import freya.errors.{OperatorError, WatcherClosedError}
import freya.internal.OperatorUtils
import freya.models.CustomResource
import freya.watcher.actions._
import io.fabric8.kubernetes.client.{KubernetesClientException, Watcher}

import scala.collection.mutable

object AbstractWatcher {
  type CloseableWatcher = Closeable
  type Channel[F[_], T, U] = MVar[F, Either[OperatorError, OperatorAction[T, U]]]
  type NamespaceQueue[T, U] = mutable.Queue[Either[OperatorError, OperatorAction[T, U]]]
}

abstract class AbstractWatcher[F[_], T, U, C <: Controller[F, T, U]] protected (
  namespace: K8sNamespace,
  channels: Channels[F, T, U],
  clientNamespace: String
)(implicit F: ConcurrentEffect[F])
    extends LazyLogging
    with WatcherMaker[F] {

  val targetNamespace: K8sNamespace = OperatorUtils.targetNamespace(clientNamespace, namespace)

  protected final def enqueueAction(
    namespace: String,
    wAction: Watcher.Action,
    errorOrResource: Either[OperatorError, CustomResource[T, U]]
  ): Unit = {
    val action = errorOrResource.map(r => ServerAction[T, U](wAction, r))
    putActionBlocking(namespace, action)
  }

  private def putActionBlocking(namespace: String, action: Either[OperatorError, ServerAction[T, U]]): Unit = {
    val (consumer, starter) = channels.getConsumer(namespace) match {
      case Some(c) => c -> None
      case None =>
        val (consumer, starter) = runSync(channels.registerConsumer(namespace))
        consumer -> Some(starter)
    }
    starter.foreach(
      s =>
        runAsync[ConsumerExitCode](
          s,
          ec => logger.debug(s"action consumer for '$namespace' namespace stopped with exit code: $ec")
        )
    )
    runSync(consumer.putAction(action))
  }

  private def runSync[A](f: F[A]): A =
    F.toIO(f).unsafeRunSync()

  private def runAsync[A](f: F[A], fa: A => Unit): Unit =
    F.toIO(f).unsafeRunAsync {
      case Right(a) => fa(a)
      case Left(t) => logger.error("Could not evaluate effect", t)
    }

  protected def onClose(e: KubernetesClientException): Unit = {
    val err = if (e != null) {
      F.delay(logger.error(s"Watcher closed with exception in namespace '$namespace'", e)) *>
        e.some.pure[F]
    } else {
      F.delay(logger.warn(s"Watcher closed in namespace '$namespace''")) *> none[KubernetesClientException].pure[F]
    }
    runSync(for {
      e <- err
      _ <- channels.putForAll(Left(WatcherClosedError(e)))
      _ <- channels.stopFlag.put(ExitCodes.ActionConsumerExitCode)
    } yield ())
  }
}
