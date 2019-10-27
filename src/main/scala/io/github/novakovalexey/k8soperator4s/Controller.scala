package io.github.novakovalexey.k8soperator4s

import cats.effect.Sync
import io.github.novakovalexey.k8soperator4s.common.Metadata

import scala.annotation.unused

abstract class Controller[F[_]: Sync, T] {
  def onAdd(@unused entity: T, @unused metadata: Metadata): F[Unit] =
    Sync[F].unit

  def onDelete(@unused entity: T, @unused metadata: Metadata): F[Unit] =
    Sync[F].unit

  def onModify(@unused entity: T, @unused metadata: Metadata): F[Unit] =
    Sync[F].unit

  def onInit(): F[Unit] =
    Sync[F].unit
}
