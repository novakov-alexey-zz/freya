package freya

import cats.effect.syntax.concurrent._
import cats.effect.{ConcurrentEffect, Fiber, Resource}
import cats.implicits._

trait IOUtils {

  def startR[F[_]: ConcurrentEffect, A](io: F[A]): Resource[F, Fiber[F, A]] =
    Resource(io.start.fproduct(_.cancel))

  def par2[F[_], A, B](ioa: F[A], iob: F[B])(implicit F: ConcurrentEffect[F]): F[Either[A, B]] =
    (startR(ioa), startR(iob)).tupled.use {
      case (fa, fb) =>
        F.racePair(fa.join.attempt, fb.join.attempt).flatMap {
          case Left((Left(ex), _)) =>
            F.raiseError(ex)
          case Right((_, Left(ex))) =>
            F.raiseError(ex)
          case Left((Right(a), fb2)) =>
            fb2.cancel *> Either.left[A, B](a).pure[F]
          case Right((fa2, Right(b))) =>
            fa2.cancel *> Either.right[A, B](b).pure[F]
        }
    }
}
