package freya

import cats.effect.{ConcurrentEffect, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.errors.ParseReconcileError
import freya.models.ResourcesList
import freya.signals.ReconcilerSignal
import freya.watcher.AbstractWatcher.Channel
import freya.watcher.actions.ReconcileAction

import scala.concurrent.duration._

class Reconciler[F[_], T](channel: Channel[F, T], currentResources: F[ResourcesList[T]])(
  implicit F: ConcurrentEffect[F],
  T: Timer[F]
) extends LazyLogging {

  def run(delay: FiniteDuration = 60.seconds): F[ReconcilerSignal] =
    F.suspend {
      for {
        _ <- T.sleep(delay)
        _ <- F.delay(logger.debug("Reconciler is running >>>>"))
        l <- currentResources
        _ <- l.map {
          case Left((t, resource)) => channel.put(Left(ParseReconcileError[T](t, resource)))
          case Right((e, m)) => channel.put(Right(ReconcileAction[T](e, m)))
        }.sequence
        ec <- run(delay)
      } yield ec
    }.recoverWith {
      case e =>
        F.delay(logger.error("Failed in reconciling loop", e)) *>
            signals.ReconcileExitCode.pure[F]
    }
}
