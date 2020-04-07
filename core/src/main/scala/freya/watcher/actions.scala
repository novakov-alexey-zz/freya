package freya.watcher

import freya.models.CustomResource
import io.fabric8.kubernetes.client.Watcher.Action

object actions {
  sealed abstract class OperatorAction[T, U](val resource: CustomResource[T, U])
  final case class WatcherAction[T, U](action: Action, override val resource: CustomResource[T, U])
      extends OperatorAction[T, U](resource)
  final case class ReconcileAction[T, U](override val resource: CustomResource[T, U])
      extends OperatorAction[T, U](resource)
}
