package freya

import cats.effect.Sync
import io.fabric8.kubernetes.api.model.ConfigMap

import scala.annotation.unused

abstract class Controller[F[_], T](implicit val F: Sync[F]) {
  def onAdd(@unused entity: T, @unused metadata: Metadata): F[Unit] = F.unit

  def onDelete(@unused entity: T, @unused metadata: Metadata): F[Unit] = F.unit

  def onModify(@unused entity: T, @unused metadata: Metadata): F[Unit] = F.unit

  def reconcile(@unused entity: T, @unused metadata: Metadata): F[Unit] = F.unit

  def onInit(): F[Unit] = F.unit
}

object Controller {
  type ConfigMapController[F[_], T] = Controller[F, T] with CmController
}

trait CmController  {
  def isSupported(@unused cm: ConfigMap): Boolean = true
}
