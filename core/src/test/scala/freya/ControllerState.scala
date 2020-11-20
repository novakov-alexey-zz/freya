package freya

import java.util.concurrent.ConcurrentLinkedQueue

import freya.models.Metadata
import io.fabric8.kubernetes.client.Watcher.Action

import scala.collection.mutable

trait ControllerState {
  val events: ConcurrentLinkedQueue[(Action, Kerb, Metadata)] = new ConcurrentLinkedQueue
  val reconciledEvents: mutable.Set[(Kerb, Metadata)] = mutable.Set.empty
  var initialized: Boolean = false
}
