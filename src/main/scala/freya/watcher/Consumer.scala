package freya.watcher

import cats.effect.ConcurrentEffect
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.{Controller, ExitCodes}
import freya.errors.{ParseReconcileError, ParseResourceError, WatcherClosedError}
import freya.internal.AnsiColors.{gr, re, xx}
import freya.ExitCodes.ConsumerExitCode
import freya.watcher.AbstractWatcher.Channel
import freya.watcher.actions._
import io.fabric8.kubernetes.client.Watcher.Action.{ADDED, DELETED, ERROR, MODIFIED}

class Consumer[F[_], T](val controller: Controller[F, T], val kind: String)(implicit F: ConcurrentEffect[F])
    extends LazyLogging {

  private[freya] def consume(channel: Channel[F, T]): F[ConsumerExitCode] =
    for {
      action <- channel.take
      _ <- F.delay(logger.debug(s"consuming action $action"))
      s <- action match {
        case Right(a) =>
          handleAction(a) *> consume(channel)
        case Left(e) =>
          e match {
            case WatcherClosedError(e) =>
              logger.error("K8s closed socket, so closing consumer as well", e)
              ExitCodes.WatcherClosedExitCode.pure[F]
            case ParseResourceError(a, t, r) =>
              handleAction(FailedAction(a, t, r)) *> consume(channel)
            case ParseReconcileError(t, r) =>
              handleAction(FailedReconcileAction(t, r)) *> consume(channel)
          }
      }
    } yield s

  private def handleAction(oAction: OperatorAction[T]): F[Unit] =
    (oAction match {
      case ServerAction(wAction, resource, meta, namespace) =>
        wAction match {
          case ADDED =>
            F.delay(logger.info(s"Event received ${gr}ADDED$xx kind=$kind name=${meta.name} in namespace '$namespace'")) *>
                controller.onAdd(resource, meta) *>
                F.delay(logger.info(s"Event ${gr}ADDED$xx for kind=$kind name=${meta.name} has been handled"))

          case DELETED =>
            F.delay(
              logger.info(s"Event received ${gr}DELETED$xx kind=$kind name=${meta.name} in namespace '$namespace'")
            ) *>
                controller.onDelete(resource, meta) *>
                F.delay(logger.info(s"Event ${gr}DELETED$xx for kind=$kind name=${meta.name} has been handled"))

          case MODIFIED =>
            F.delay(
              logger.info(s"Event received ${gr}MODIFIED$xx kind=$kind name=${meta.name} in namespace=$namespace")
            ) *>
                controller.onModify(resource, meta) *>
                F.delay(logger.info(s"Event ${gr}MODIFIED$xx for kind=$kind name=${meta.name} has been handled"))

          case ERROR =>
            F.delay(
              logger.error(s"Event received ${re}ERROR$xx for kind=$kind name=${meta.name} in namespace '$namespace'")
            )
        }
      case ReconcileAction(resource, meta) =>
        controller.reconcile(resource, meta)

      case FailedAction(action, t, resource) =>
        F.delay(logger.error(s"Failed action $action for resource $resource", t))

      case FailedReconcileAction(t, resource) =>
        F.delay(logger.error(s"Failed reconcile action for resource $resource", t))

    }).handleErrorWith(e => F.delay(logger.error(s"Controller failed to handle action: $oAction", e)) *> F.unit)
}
