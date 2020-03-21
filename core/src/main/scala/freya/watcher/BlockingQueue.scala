package freya.watcher

import java.util.concurrent.ConcurrentLinkedQueue

import cats.effect.Sync
import cats.effect.concurrent.MVar
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging

object BlockingQueue {
  def apply[F[_]: Sync, A](size: Int, name: String, signal: MVar[F, Unit]): BlockingQueue[F, A] =
    new BlockingQueue[F, A](name, size, new ConcurrentLinkedQueue[A](), signal)
}

private[freya] class BlockingQueue[F[_]: Sync, A](
  name: String,
  size: Int,
  queue: ConcurrentLinkedQueue[A],
  signal: MVar[F, Unit]
) extends LazyLogging {

  private[freya] def consume(c: A => F[Boolean]): F[Unit] =
    if (!queue.isEmpty) {
      c(queue.poll()).flatMap(continue => Sync[F].whenA(continue)(signal.take >> consume(c)))
    } else signal.take >> consume(c)

  private[freya] def produce(a: A): F[Unit] =
    if (queue.size() < size) {
      queue.add(a).pure[F] >> signal.put(())
    } else
      Sync[F].delay(logger.debug(s"Queue $name is full(${queue.size}), waiting for space")) >> signal
        .put(()) >> produce(a)
}
