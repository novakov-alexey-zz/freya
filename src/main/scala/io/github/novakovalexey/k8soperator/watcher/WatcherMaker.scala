package io.github.novakovalexey.k8soperator.watcher

import java.io.Closeable

import cats.effect.ExitCode
import io.github.novakovalexey.k8soperator.watcher.WatcherMaker.{Consumer, ConsumerSignal}

object WatcherMaker {
  type ConsumerSignal[F[_]] = F[ExitCode]
  type Consumer = Closeable
}

trait WatcherMaker[F[_]] {
  def watch: F[(Consumer, ConsumerSignal[F])]
}
