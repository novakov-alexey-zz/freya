package freya

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.Watcher.Action

object errors {
  sealed trait OperatorError
  final case class WatcherClosedError(e: Option[KubernetesClientException]) extends OperatorError
  final case class ParseResourceError(action: Action, t: Throwable, resource: HasMetadata) extends OperatorError
  final case class ParseReconcileError(t: Throwable, resource: HasMetadata) extends OperatorError
}
