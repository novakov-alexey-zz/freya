package freya.watcher

import cats.effect.{ConcurrentEffect, ExitCode}
import cats.effect.concurrent.MVar
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.Watcher.Action._
import io.fabric8.kubernetes.client.{KubernetesClientException, Watcher}
import freya.internal.AnsiColors._
import freya.watcher.AbstractWatcher.{Channel, _}
import freya.watcher.WatcherMaker.ConsumerSignal
import freya.watcher.actions.{FailedAction, OkAction, OperatorAction}
import freya.errors.{OperatorError, ParseResourceError, WatcherClosedError}
import freya.{Controller, K8sNamespace, Metadata}

object AbstractWatcher {
  type Channel[F[_], T] = MVar[F, Either[OperatorError[T], OperatorAction[T]]]
  val WatcherClosedSignal = 2
}

abstract class AbstractWatcher[F[_], T, C <: Controller[F, T]] protected (
  val namespace: K8sNamespace,
  val kind: String,
  val controller: C,
  channel: Channel[F, T],
)(implicit F: ConcurrentEffect[F])
    extends LazyLogging with WatcherMaker[F] {

  protected def enqueueAction(
    wAction: Watcher.Action,
    errorOrEntity: Either[OperatorError[T], (T, Metadata)],
    resource: HasMetadata
  ): Unit = {
    val action = errorOrEntity.map {
      case (entity, meta) => OkAction[T](wAction, entity, meta, resource.getMetadata.getNamespace)
    }
    unsafeRun(channel.put(action))
  }

  protected def consumer(channel: Channel[F, T]): ConsumerSignal[F] =
    for {
      action <- channel.take
      _ <- F.delay(logger.debug(s"consuming action $action"))
      s <- action match {
        case Right(a) =>
          handleAction(a) *> consumer(channel)
        case Left(e) =>
          e match {
            case WatcherClosedError(e) =>
              logger.error("K8s closed socket, so closing consumer as well", e)
              ExitCode(WatcherClosedSignal).pure[F]
            case pre: ParseResourceError[T] =>
              handleAction(FailedAction(pre.action, pre.t, pre.resource)) *> consumer(channel)
          }
      }
    } yield s

  protected def handleAction(action: OperatorAction[T]): F[Unit] =
    (action match {
      case OkAction(wAction, entity, meta, namespace) =>
        wAction match {
          case ADDED =>
            F.delay(logger.info(s"Event received ${gr}ADDED$xx kind=$kind name=${meta.name} in namespace '$namespace'")) *>
              controller.onAdd(entity, meta) *>
              F.delay(logger.info(s"Event ${gr}ADDED$xx for kind=$kind name=${meta.name} has been handled"))

          case DELETED =>
            F.delay(
              logger.info(s"Event received ${gr}DELETED$xx kind=$kind name=${meta.name} in namespace '$namespace'")
            ) *>
              controller.onDelete(entity, meta) *>
              F.delay(logger.info(s"Event ${gr}DELETED$xx for kind=$kind name=${meta.name} has been handled"))

          case MODIFIED =>
            F.delay(
              logger.info(s"Event received ${gr}MODIFIED$xx kind=$kind name=${meta.name} in namespace=$namespace")
            ) *>
              controller.onModify(entity, meta) *>
              F.delay(logger.info(s"Event ${gr}MODIFIED$xx for kind=$kind name=${meta.name} has been handled"))

          case ERROR =>
            F.delay(
              logger.error(s"Event received ${re}ERROR$xx for kind=$kind name=${meta.name} in namespace '$namespace'")
            )
        }
      case FailedAction(wAction, e, resource) =>
        F.delay(logger.error(s"Failed action $wAction for resource $resource", e))
    }).handleErrorWith(e => F.delay(logger.error(s"Controller failed to handle action: $action", e)) *> F.unit)

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
