package freya

import io.fabric8.kubernetes.client.Watcher.Action

import scala.collection.mutable

trait ControllerState {
  val events: mutable.Set[(Action, Kerb, Metadata)] = mutable.Set.empty
  val reconciledEvents: mutable.Set[(Kerb, Metadata)] = mutable.Set.empty
  var initialized: Boolean = false
}
