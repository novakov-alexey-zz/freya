package freya.watcher

import cats.data.OptionT
import cats.effect.Effect
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.ExitCodes.ConsumerExitCode
import freya.errors.{OperatorError, ParseReconcileError, ParseResourceError, WatcherClosedError}
import freya.internal.AnsiColors.{gr, re, xx}
import freya.internal.kubeapi.CrdApi.StatusUpdate
import freya.models.{CustomResource, NewStatus}
import freya.watcher.AbstractWatcher.Action
import freya.watcher.actions._
import freya.{Controller, ExitCodes}
import io.fabric8.kubernetes.client.Watcher.Action.{ADDED, DELETED, ERROR, MODIFIED}

class ActionConsumer[F[_], T, U](
  name: String,
  controller: Controller[F, T, U],
  kind: String,
  queue: BlockingQueue[F, Action[T, U]],
  feedback: Option[FeedbackConsumerAlg[F, U]]
)(implicit F: Effect[F])
    extends LazyLogging {

  private val noStatus = F.pure[NewStatus[U]](None)
  private val continue = true.pure[F]
  private val stop = false.pure[F]
  private val stopFeedbackConsumer = Left(())

  private[freya] def putAction(action: Action[T, U]): F[Unit] =
    queue.produce(action)

  private[freya] def consume: F[ConsumerExitCode] =
    queue.consume(processAction) *> ExitCodes.ActionConsumerExitCode.pure[F]

  private def processAction(action: Action[T, U]): F[Boolean] =
    for {
      _ <- F.delay(logger.debug(s"consuming action $action"))
      ec <- action match {
        case Right(a) =>
          updateStatus(a.resource, handleAction(a)) *> continue
        case Left(e) =>
          handleError(e)
      }
    } yield ec

  private def handleError(e: OperatorError): F[Boolean] =
    e match {
      case WatcherClosedError(e) =>
        F.delay(logger.error(s"K8s closed socket, so closing consumer $name as well", e)) *>
            feedback.fold(F.unit)(_.put(stopFeedbackConsumer)) *> stop
      case ParseResourceError(a, t, r) =>
        F.delay(logger.error(s"Failed action $a for resource $r", t)) *> continue
      case ParseReconcileError(t, r) =>
        F.delay(logger.error(s"Failed 'reconcile' action for resource $r", t)) *> continue
    }

  private def handleAction(oAction: OperatorAction[T, U]): F[NewStatus[U]] =
    (oAction match {
      case WatcherAction(wAction, resource) =>
        wAction match {
          case ADDED =>
            F.delay(
              logger
                .debug(
                  s"Event received ${gr}ADDED$xx kind=$kind name=${resource.metadata.name} in '${resource.metadata.namespace}' namespace"
                )
            ) *>
                controller.onAdd(resource) <*
                F.delay(
                  logger.debug(s"Event ${gr}ADDED$xx for kind=$kind name=${resource.metadata.name} has been handled")
                )

          case DELETED =>
            F.delay(
              logger
                .debug(
                  s"Event received ${gr}DELETED$xx kind=$kind name=${resource.metadata.name} in '${resource.metadata.namespace}' namespace"
                )
            ) *>
                controller.onDelete(resource) *>
                F.delay(
                  logger.debug(s"Event ${gr}DELETED$xx for kind=$kind name=${resource.metadata.name} has been handled")
                ) *> F.pure(Option.empty[U])

          case MODIFIED =>
            F.delay(
              logger
                .debug(
                  s"Event received ${gr}MODIFIED$xx kind=$kind name=${resource.metadata.name} in '${resource.metadata.namespace}' namespace"
                )
            ) *>
                controller.onModify(resource) <*
                F.delay(
                  logger.debug(s"Event ${gr}MODIFIED$xx for kind=$kind name=${resource.metadata.name} has been handled")
                )

          case ERROR =>
            F.delay(
              logger.error(
                s"Event received ${re}ERROR$xx for kind=$kind name=${resource.metadata.name} in '${resource.metadata.namespace}' namespace"
              )
            ) *> noStatus
        }
      case ReconcileAction(resource) =>
        controller.reconcile(resource)

    }).handleErrorWith(e => F.delay(logger.error(s"Controller failed to handle action: $oAction", e)) *> noStatus)

  private def updateStatus(cr: CustomResource[T, U], status: F[NewStatus[U]]): F[Unit] =
    (for {
      s <- OptionT(status)
      f <- feedback.toOptionT[F]
      _ <- OptionT(f.put(StatusUpdate[U](cr.metadata, s).asRight[Unit]).map(_.some))
    } yield ()).value.void
}
