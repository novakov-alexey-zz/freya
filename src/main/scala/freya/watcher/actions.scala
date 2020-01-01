package freya.watcher

import freya.Metadata
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.Watcher.Action

object actions {

  sealed trait OperatorAction[T]
  final case class ServerAction[T](action: Action, resource: T, meta: Metadata, namespace: String)
      extends OperatorAction[T]
  final case class ReconcileAction[T](resource: T, meta: Metadata) extends OperatorAction[T]
  final case class FailedReconcileAction[T](t: Throwable, resource: HasMetadata) extends OperatorAction[T]
  final case class FailedAction[T](action: Action, t: Throwable, resource: HasMetadata) extends OperatorAction[T]
}
