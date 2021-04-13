package freya.watcher

import cats.effect.Async
import cats.effect.std.{Dispatcher, Queue}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.ExitCodes.ConsumerExitCode
import freya._
import freya.errors.{OperatorError, WatcherClosedError}
import freya.internal.OperatorUtils
import freya.models.CustomResource
import freya.watcher.actions._
import io.fabric8.kubernetes.client.{Watcher, WatcherException}

import java.io.Closeable
import scala.collection.mutable
import scala.util.Try

private[watcher] final case class AbstractWatcherContext[F[_], T, U](
  namespace: K8sNamespace,
  clientNamespace: String,
  channels: Channels[F, T, U],
  stopFlag: Queue[F, ConsumerExitCode],
  dispatcher: Dispatcher[F]
)

object AbstractWatcher {
  type CloseableWatcher = Closeable
  type Action[T, U] = Either[OperatorError, OperatorAction[T, U]]
  type NamespaceQueue[T, U] = mutable.Queue[Action[T, U]]
}

abstract class AbstractWatcher[F[_], T, U, C <: Controller[F, T, U]] protected (
  context: AbstractWatcherContext[F, T, U]
)(implicit F: Async[F])
    extends LazyLogging
    with WatcherMaker[F] {

  protected val targetNamespace: K8sNamespace =
    OperatorUtils.targetNamespace(context.clientNamespace, context.namespace)

  protected final def enqueueAction(
    namespace: String,
    wAction: Watcher.Action,
    errorOrResource: Either[OperatorError, CustomResource[T, U]]
  ): Unit = {
    val action = errorOrResource.map(r => WatcherAction[T, U](wAction, r))
    putActionBlocking(namespace, action)
  }

  private def putActionBlocking(namespace: String, action: Either[OperatorError, WatcherAction[T, U]]): Unit =
    runSync(context.channels.getOrCreateConsumer(namespace).flatMap(_.putAction(action)))

  private def runSync[A](f: F[Unit]): Unit =
    Try(context.dispatcher.unsafeRunSync(f)).toEither match {
      case Left(t) =>
        logger.error("Failed to evaluate passed effect synchronously", t)
      case _ => ()
    }

  protected def onClose(e: WatcherException): Unit = {
    val error = if (e != null) {
      F.delay(logger.error(s"Watcher closed with exception in namespace '${context.namespace}'", e)) *>
        e.some.pure[F]
    } else {
      F.delay(logger.warn(s"Watcher closed in namespace '${context.namespace}''")) *> none[WatcherException].pure[F]
    }
    runSync(for {
      e <- error
      _ <- context.channels.putForAll(Left(WatcherClosedError(e)))
      _ <- context.stopFlag.offer(ExitCodes.ActionConsumerExitCode)
    } yield ())
  }
}
