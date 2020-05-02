package freya.watcher

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.implicits._
import com.typesafe.scalalogging.StrictLogging
import freya.ExitCodes.ConsumerExitCode
import freya.errors.OperatorError
import freya.watcher.actions.WatcherAction

import scala.collection.concurrent.TrieMap

object Channels {
  val AllNamespacesConsumer = "all"
}

class Channels[F[_]: Parallel, T, U](
  concurrentController: Boolean,
  newActionConsumer: (String, Option[FeedbackConsumerAlg[F, U]]) => F[ActionConsumer[F, T, U]],
  newFeedbackConsumer: () => Option[FeedbackConsumerAlg[F, U]]
)(implicit F: ConcurrentEffect[F])
    extends StrictLogging {

  private val actionConsumers = TrieMap.empty[String, ActionConsumer[F, T, U]]

  private[freya] def getOrCreateConsumer(namespace: String): F[ActionConsumer[F, T, U]] = {
    val name = if (concurrentController) namespace else Channels.AllNamespacesConsumer
    actionConsumers.get(name).fold(registerConsumer(name))(_.pure[F])
  }

  private def registerConsumer(namespace: String): F[ActionConsumer[F, T, U]] =
    for {
      feedbackConsumer <- newFeedbackConsumer().pure[F]
      consumer <- newActionConsumer(namespace, feedbackConsumer)
      previous <- F.delay(actionConsumers.putIfAbsent(namespace, consumer))
      consumer <- previous match {
        case Some(c) => c.pure[F]
        case None =>
          F.delay {
            val start = feedbackConsumer match {
              case Some(feedback) => F.race(consumer.consume, feedback.consume).map(_.merge)
              case None => consumer.consume
            }
            runAsync[ConsumerExitCode](
              F.delay(logger.info(s"Action consumer for '$namespace' namespace was started")) *> start,
              ec => logger.debug(s"Action consumer for '$namespace' namespace was stopped with exit code: $ec")
            )
            consumer
          }
      }
    } yield consumer

  private[freya] def putForAll(action: Either[OperatorError, WatcherAction[T, U]]): F[Unit] =
    actionConsumers.values.map(_.putAction(action)).toList.parSequence.void

  private def runAsync[A](f: F[A], fa: A => Unit): Unit =
    F.toIO(f).unsafeRunAsync {
      case Right(a) => fa(a)
      case Left(t) => logger.error("Could not evaluate effect", t)
    }
}
