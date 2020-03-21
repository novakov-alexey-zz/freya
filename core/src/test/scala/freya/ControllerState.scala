package freya

import io.fabric8.kubernetes.client.Watcher.Action

import scala.collection.mutable

trait ControllerState {
  val events: mutable.ListBuffer[(Action, Kerb, Metadata)] = mutable.ListBuffer.empty
  val reconciledEvents: mutable.Set[(Kerb, Metadata)] = mutable.Set.empty
  var initialized: Boolean = false
}
