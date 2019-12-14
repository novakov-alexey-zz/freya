package freya.watcher

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.Watcher.Action
import freya.Metadata

object actions {

  sealed trait OperatorAction[T]
  final case class OkAction[T](watcherAction: Action, entity: T, meta: Metadata, namespace: String)
      extends OperatorAction[T]
  final case class FailedAction[T](action: Action, t: Throwable, resource: HasMetadata) extends OperatorAction[T]
}
