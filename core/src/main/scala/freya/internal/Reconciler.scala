package freya.internal

import cats.effect.{ConcurrentEffect, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.ExitCodes
import freya.ExitCodes.ReconcilerExitCode
import freya.errors.ParseReconcileError
import freya.models.ResourcesList
import freya.watcher.AbstractWatcher.Channel
import freya.watcher.actions.ReconcileAction

import scala.concurrent.duration._

private[freya] class Reconciler[F[_], T, U](
  delay: FiniteDuration,
  channel: Channel[F, T, U],
  currentResources: F[Either[Throwable, ResourcesList[T, U]]]
)(implicit F: ConcurrentEffect[F], T: Timer[F])
    extends LazyLogging {

  def run: F[ReconcilerExitCode] =
    F.suspend {
      for {
        _ <- T.sleep(delay)
        _ <- F.delay(logger.debug("Reconciler is running >>>>"))
        r <- currentResources
        _ <- publish(r)
        ec <- run
      } yield ec
    }.recoverWith {
      case e =>
        F.delay(logger.error("Failed in reconciling loop", e)) *>
            ExitCodes.ReconcileExitCode.pure[F]
    }

  private def publish(resources: Either[Throwable, ResourcesList[T, U]]): F[Unit] =
    resources.fold(t => F.delay(logger.error(s"Failed to get current resources", t)), _.map {
      case Left((t, resource)) => channel.put(Left(ParseReconcileError(t, resource)))
      case Right(resource) => channel.put(Right(ReconcileAction(resource)))
    }.sequence.void)
}
