package io.github.novakovalexey.k8soperator4s

import cats.effect.Sync
import io.fabric8.kubernetes.api.model.ConfigMap

import scala.annotation.unused

abstract class Controller[F[_], T](implicit F: Sync[F]) {
  def onAdd(@unused entity: T, @unused metadata: Metadata): F[Unit] = F.unit

  def onDelete(@unused entity: T, @unused metadata: Metadata): F[Unit] = F.unit

  def onModify(@unused entity: T, @unused metadata: Metadata): F[Unit] = F.unit

  def onInit(): F[Unit] = F.unit
}

abstract class ConfigMapController[F[_]: Sync, T] extends Controller[F, T] {
  def isSupported(@unused cm: ConfigMap): Boolean = true
}
