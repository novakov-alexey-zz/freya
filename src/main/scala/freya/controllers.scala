package freya

import cats.syntax.apply._
import cats.effect.Sync
import freya.models.{CustomResource, NewStatus}
import io.fabric8.kubernetes.api.model.ConfigMap

import scala.annotation.unused

abstract class Controller[F[_], T, U](implicit val F: Sync[F]) {
  def onAdd(@unused resource: CustomResource[T, U]): F[NewStatus[U]] = F.pure(None)

  def onDelete(@unused resource: CustomResource[T, U]): F[NewStatus[U]] = F.pure(None)

  def onModify(@unused resource: CustomResource[T, U]): F[NewStatus[U]] = F.pure(None)

  def reconcile(@unused resource: CustomResource[T, U]): F[NewStatus[U]] = F.pure(None)

  def onInit(): F[Unit] = F.unit
}

abstract class CmController[F[_], T](implicit override val F: Sync[F]) extends Controller[F, T, Unit] {
  type NoStatus = NewStatus[Unit]

  implicit def unitToNoStatus(x: F[Unit]): F[NoStatus] =
    x *> F.pure(None)

  def isSupported(@unused cm: ConfigMap): Boolean = true
}
