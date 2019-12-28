package freya

import cats.effect.{ConcurrentEffect, ExitCode, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.AbstractHelper.ResourcesList
import freya.Reconciler.ReconcilerSignal
import freya.errors.ParseReconcileError
import freya.watcher.AbstractWatcher.Channel
import freya.watcher.actions.ReconcileAction

import scala.concurrent.duration._

object Reconciler {
  type ReconcilerSignal = ExitCode
  val ReconcileExitCode: ReconcilerSignal = ExitCode(3) //TODO: move to common place of exit codes
}

class Reconciler[F[_], T](channel: Channel[F, T], currentResources: F[ResourcesList[T]])(
  implicit F: ConcurrentEffect[F],
  T: Timer[F]
) extends LazyLogging {

  def run(delay: FiniteDuration = 10.seconds): F[ReconcilerSignal] =
    (F.suspend {
      T.sleep(delay) *> currentResources.flatMap { l =>
        l.map {
          case Left((t, resource)) => channel.put(Left(ParseReconcileError[T](t, resource)))
          case Right((e, m)) => channel.put(Right(ReconcileAction[T](e, m)))
        }.sequence
      } *> run()
    } *> ExitCode.Success.pure[F]).recoverWith {
      case e =>
        F.delay(logger.error("Failed in reconciling loop", e)) *>
            Reconciler.ReconcileExitCode.pure[F]
    }
}
