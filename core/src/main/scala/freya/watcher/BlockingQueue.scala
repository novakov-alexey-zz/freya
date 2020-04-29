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

private[freya] class BlockingQueue[F[_], A](
  name: String,
  size: Int,
  queue: ConcurrentLinkedQueue[A],
  signal: MVar[F, Unit]
)(implicit F: Sync[F])
    extends LazyLogging {

  private[freya] def consume(c: A => F[Boolean]): F[Unit] =
    if (!queue.isEmpty)
      c(queue.poll()).flatMap(continue => F.whenA(continue)(consume(c)))
    else signal.take >> consume(c)

  private[freya] def produce(a: A): F[Unit] =
    if (queue.size() < size)
      F.delay(queue.add(a)) >> signal.tryPut(()).void
    else
      F.delay(logger.debug(s"Queue $name is full(${queue.size}), waiting for free space")) >> signal
        .put(()) >> produce(a)
}
