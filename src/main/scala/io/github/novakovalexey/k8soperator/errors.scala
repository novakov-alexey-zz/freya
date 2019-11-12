package io.github.novakovalexey.k8soperator

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.Watcher.Action

object errors {
  sealed trait OperatorError[T]
  final case class WatcherClosedError[T](e: Option[KubernetesClientException]) extends OperatorError[T]
  final case class ParseResourceError[T](action: Action, t: Throwable, resource: HasMetadata) extends OperatorError[T]
}
