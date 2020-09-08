package freya.watcher

import cats.effect.concurrent.{MVar, MVar2, Ref}
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging

import scala.collection.immutable.Queue

object BlockingQueue {
  def create[F[_]: Concurrent, A](size: Int, name: String): F[BlockingQueue[F, A]] =
    for {
      signal <- MVar.empty[F, Unit]
      queue <- Ref.of(Queue.empty[A])
    } yield new BlockingQueue[F, A](name, size, queue, signal)
}

private[freya] class BlockingQueue[F[_], A](
  name: String,
  capacity: Int,
  queue: Ref[F, Queue[A]],
  signal: MVar2[F, Unit]
)(implicit F: Sync[F])
    extends LazyLogging {

  private[freya] def produce(a: A): F[Unit] =
    for {
      (added, length) <- queue.modify { q =>
        if (q.length < capacity) {
          val uq = q.enqueue(a)
          (uq, (true, uq.length))
        } else
          (q, (false, q.length))
      }
      _ <-
        if (added) signal.tryPut(()).void
        else
          F.delay(logger.debug(s"Queue $name is full($length), waiting for free space")) *>
            signal.put(()) *> produce(a)
    } yield ()

  private[freya] def consume(c: A => F[Boolean]): F[Unit] =
    for {
      elem <- queue.modify(q => q.dequeueOption.map { case (a, q) => q -> a.some }.getOrElse(q -> None))
      _ <- elem match {
        case Some(e) => c(e).flatMap(continue => signal.tryTake *> F.whenA(continue)(consume(c)))
        case _ => signal.take *> consume(c)
      }
    } yield ()

  private[freya] def length: F[Int] =
    queue.get.map(_.length)
}
