package freya.watcher

import java.io.Closeable

import cats.effect.Effect
import cats.effect.concurrent.MVar2
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
  type Action[T, U] = Either[OperatorError, OperatorAction[T, U]]
  type NamespaceQueue[T, U] = mutable.Queue[Action[T, U]]
}

abstract class AbstractWatcher[F[_], T, U, C <: Controller[F, T, U]] protected (
  namespace: K8sNamespace,
  channels: Channels[F, T, U],
  stopFlag: MVar2[F, ConsumerExitCode],
  clientNamespace: String
)(implicit F: Effect[F])
    extends LazyLogging
    with WatcherMaker[F] {

  protected val targetNamespace: K8sNamespace = OperatorUtils.targetNamespace(clientNamespace, namespace)

  protected final def enqueueAction(
    namespace: String,
    wAction: Watcher.Action,
    errorOrResource: Either[OperatorError, CustomResource[T, U]]
  ): Unit = {
    val action = errorOrResource.map(r => WatcherAction[T, U](wAction, r))
    putActionBlocking(namespace, action)
  }

  private def putActionBlocking(namespace: String, action: Either[OperatorError, WatcherAction[T, U]]): Unit =
    runSync(channels.getOrCreateConsumer(namespace).flatMap(_.putAction(action)))

  private def runSync[A](f: F[A]): A =
    F.toIO(f).unsafeRunSync()

  protected def onClose(e: KubernetesClientException): Unit = {
    val error = if (e != null) {
      F.delay(logger.error(s"Watcher closed with exception in namespace '$namespace'", e)) *>
        e.some.pure[F]
    } else {
      F.delay(logger.warn(s"Watcher closed in namespace '$namespace''")) *> none[KubernetesClientException].pure[F]
    }
    runSync(for {
      e <- error
      _ <- channels.putForAll(Left(WatcherClosedError(e)))
      _ <- stopFlag.put(ExitCodes.ActionConsumerExitCode)
    } yield ())
  }
}
