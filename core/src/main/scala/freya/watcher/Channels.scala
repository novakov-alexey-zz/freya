package freya.watcher

import cats.Parallel
import cats.effect.{ConcurrentEffect, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.ExitCodes
import freya.ExitCodes.ConsumerExitCode
import freya.errors.OperatorError
import freya.watcher.AbstractWatcher.Channel
import freya.watcher.actions.{OperatorAction, ServerAction}

import scala.concurrent.duration._

class Channels[F[_]: Parallel: ConcurrentEffect, T, U](
  actionConsumers: List[ActionConsumer[F, T, U]],
  feedbackConsumers: List[FeedbackConsumerAlg[F]]
)(implicit T: Timer[F])
    extends LazyLogging {

  private[freya] def findFreeChannel: F[Option[Channel[F, T, U]]] =
    for {
      freeChannels <- actionConsumers.map(c => c.channel.isEmpty.map(_ -> c)).parSequence
      ch <- freeChannels.find(_._1).map(_._2.channel).pure[F]
    } yield ch

  private[freya] def putForAll(action: Either[OperatorError, ServerAction[T, U]]): F[Unit] = {
    def loop(channels: List[Channel[F, T, U]]): F[Unit] =
      for {
        list <- channels.map(c => c.tryPut(action).map(isSucceeded => (isSucceeded, c))).parSequence
        busyChannels <- list.filter(!_._1).pure[F]
        _ <- if (busyChannels.nonEmpty) {
          ConcurrentEffect[F].delay(logger.debug(s"Waiting for ${busyChannels.length} channels")) *> T.sleep(2.second) *> loop(
            busyChannels.map(_._2)
          )
        } else ().pure[F]
      } yield ()

    loop(actionConsumers.map(_.channel)).void
  }

  private[freya] def putAction(action: Either[OperatorError, OperatorAction[T, U]]): F[Boolean] =
    for {
      maybeChannel <- findFreeChannel
      succeeded <- maybeChannel match {
        case Some(ch) => ch.tryPut(action)
        case None => T.sleep(2.seconds) *> putAction(action)
      }
      res <- if (succeeded) succeeded.pure[F] else putAction(action)
    } yield res

  private[freya] def startConsumers: F[ConsumerExitCode] =
    actionConsumers.map(_.consume).parSequence *> ExitCodes.ActionConsumerExitCode.pure[F]

  private[freya] def startFeedbackConsumers: F[ConsumerExitCode] =
    feedbackConsumers.map(_.consume).parSequence *> ExitCodes.FeedbackExitCode.pure[F]
}
