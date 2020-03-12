package freya

import cats.effect.Sync
import cats.syntax.apply._
import freya.Controller.noStatus
import freya.models.{CustomResource, NewStatus, NoStatus}
import io.fabric8.kubernetes.api.model.ConfigMap

import scala.annotation.unused

object Controller {
  def noStatus[F[_], U](implicit F: Sync[F]): F[Option[U]] = F.pure(None)
}

abstract class Controller[F[_], T, U](implicit val F: Sync[F]) {

  implicit def unitToNoStatus(unit: F[Unit]): F[NoStatus] =
    unit *> noStatus

  def onAdd(@unused resource: CustomResource[T, U]): F[NewStatus[U]] = noStatus

  def onModify(@unused resource: CustomResource[T, U]): F[NewStatus[U]] = noStatus

  def reconcile(@unused resource: CustomResource[T, U]): F[NewStatus[U]] = noStatus

  def onDelete(@unused resource: CustomResource[T, U]): F[Unit] = F.unit

  def onInit(): F[Unit] = F.unit
}

abstract class CmController[F[_], T](implicit override val F: Sync[F]) extends Controller[F, T, Unit] {
  def isSupported(@unused cm: ConfigMap): Boolean = true
}
