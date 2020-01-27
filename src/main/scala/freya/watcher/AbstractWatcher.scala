package freya.watcher

import java.io.Closeable

import cats.effect.ConcurrentEffect
import cats.effect.concurrent.MVar
import com.typesafe.scalalogging.LazyLogging
import freya._
import freya.errors.{OperatorError, WatcherClosedError}
import freya.internal.OperatorUtils
import freya.models.CustomResource
import freya.watcher.AbstractWatcher.Channel
import freya.watcher.actions._
import io.fabric8.kubernetes.client.{KubernetesClientException, Watcher}

object AbstractWatcher {
  type CloseableWatcher = Closeable
  type Channel[F[_], T, U] = MVar[F, Either[OperatorError, OperatorAction[T, U]]]
}

abstract class AbstractWatcher[F[_], T, U, C <: Controller[F, T, U]] protected (
  namespace: K8sNamespace,
  channel: Channel[F, T, U],
  clientNamespace: String
)(implicit F: ConcurrentEffect[F])
    extends LazyLogging
    with WatcherMaker[F] {

  val targetNamespace: K8sNamespace = OperatorUtils.targetNamespace(clientNamespace, namespace)

  protected def enqueueAction(
    wAction: Watcher.Action,
    errorOrResource: Either[OperatorError, CustomResource[T, U]]
  ): Unit = {
    val action = errorOrResource.map(r => ServerAction[T, U](wAction, r))
    unsafeRun(channel.put(action))
  }

  protected def unsafeRun(f: F[Unit]): Unit =
    F.toIO(f).unsafeRunAsync {
      case Right(_) => ()
      case Left(t) => logger.error("Could not evaluate effect", t)
    }

  protected def onClose(e: KubernetesClientException): Unit = {
    val err = if (e != null) {
      logger.error(s"Watcher closed with exception in namespace '$namespace'", e)
      Some(e)
    } else {
      logger.warn(s"Watcher closed in namespace $namespace")
      None
    }
    unsafeRun(channel.put(Left(WatcherClosedError(err))))
  }
}
