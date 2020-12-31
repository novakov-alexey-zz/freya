package freya

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.Watcher.Action
import io.fabric8.kubernetes.client.WatcherException

object errors {
  sealed trait OperatorError
  final case class WatcherClosedError(e: Option[WatcherException]) extends OperatorError
  final case class ParseResourceError(action: Action, t: Throwable, resource: HasMetadata) extends OperatorError
  final case class ParseReconcileError(t: Throwable, resource: HasMetadata) extends OperatorError
}
