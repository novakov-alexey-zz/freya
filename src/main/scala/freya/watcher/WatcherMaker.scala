package freya.watcher

import freya.signals.ConsumerSignal
import freya.watcher.AbstractWatcher.CloseableWatcher

trait WatcherMaker[F[_]] {
  def watch: F[(CloseableWatcher, F[ConsumerSignal])]
}
