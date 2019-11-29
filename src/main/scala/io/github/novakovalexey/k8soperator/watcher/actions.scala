package io.github.novakovalexey.k8soperator.watcher

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.Watcher.Action
import io.github.novakovalexey.k8soperator.Metadata

object actions {

  sealed trait OperatorAction[T]
  final case class OkAction[T](watcherAction: Action, entity: T, meta: Metadata, namespace: String)
      extends OperatorAction[T]
  final case class FailedAction[T](action: Action, t: Throwable, resource: HasMetadata) extends OperatorAction[T]
}
