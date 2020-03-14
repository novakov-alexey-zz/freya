package freya.watcher

import java.io.Closeable

import cats.effect.ConcurrentEffect
import cats.effect.concurrent.MVar
import com.typesafe.scalalogging.LazyLogging
import freya.ExitCodes.ConsumerExitCode
import freya._
import freya.errors.{OperatorError, WatcherClosedError}
import freya.internal.OperatorUtils
import freya.models.CustomResource
import freya.watcher.actions._
import io.fabric8.kubernetes.client.{KubernetesClientException, Watcher}

import scala.annotation.tailrec

object AbstractWatcher {
  type CloseableWatcher = Closeable
  type Channel[F[_], T, U] = MVar[F, Either[OperatorError, OperatorAction[T, U]]]
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
    wAction: Watcher.Action,
    errorOrResource: Either[OperatorError, CustomResource[T, U]]
  ): Unit = {
    val action = errorOrResource.map(r => ServerAction[T, U](wAction, r))
    putActionBlocking(action)
  }

  @tailrec
  private def putActionBlocking(action: Either[OperatorError, ServerAction[T, U]]): Unit = {
    val maybeChannel = runSync(channels.findFreeChannel)
    maybeChannel match {
      case Some(ch) => runAsync(ch.put(action))
      case None =>
        Thread.sleep(5000)
        putActionBlocking(action)
    }
  }

  private def runSync[A](f: F[A]): A =
    F.toIO(f).unsafeRunSync()

  private def runAsync(f: F[Unit]): Unit =
    F.toIO(f).unsafeRunAsync {
      case Right(_) => ()
      case Left(t) => logger.error("Could not evaluate effect", t)
    }

  protected def onClose(e: KubernetesClientException): Unit = {
    val err = if (e != null) {
      logger.error(s"Watcher closed with exception in namespace '$namespace'", e)
      Some(e)
    } else {
      logger.warn(s"Watcher closed in namespace '$namespace''")
      None
    }
    runSync(channels.putForAll(Left(WatcherClosedError(err))))
  }

  protected def runActionConsumers: F[ConsumerExitCode] =
    channels.startConsumers

  protected def runFeedbackConsumer: F[ConsumerExitCode] =
    channels.startFeedbackConsumers
}
