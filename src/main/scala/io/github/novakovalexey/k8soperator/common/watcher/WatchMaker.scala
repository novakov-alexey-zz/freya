package io.github.novakovalexey.k8soperator.common.watcher

import io.fabric8.kubernetes.client.Watch
import io.github.novakovalexey.k8soperator.common.watcher.WatchMaker.ConsumerSignal

object WatchMaker {
  type ConsumerSignal[F[_]] = F[Int]
}

trait WatchMaker[F[_]] {
  def watch: F[(Watch, ConsumerSignal[F])]
}
