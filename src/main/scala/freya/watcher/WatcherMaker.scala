package freya.watcher

import freya.ExitCodes.ConsumerExitCode
import freya.watcher.AbstractWatcher.CloseableWatcher

trait WatcherMaker[F[_]] {
  def watch: F[(CloseableWatcher, F[ConsumerExitCode])]
}
