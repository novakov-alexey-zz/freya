package freya

import cats.Parallel
import cats.effect.{Effect, Sync, Timer}
import freya.Configuration.CrdConfig
import freya.ExitCodes.ConsumerExitCode
import freya.internal.crd.Deployer
import freya.watcher.AbstractWatcher.CloseableWatcher
import freya.watcher.FeedbackConsumer.FeedbackChannel
import freya.watcher._
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient

trait WatcherMaker[F[_]] {
  def watch: F[(CloseableWatcher, F[ConsumerExitCode])]
}

trait CrdWatchMaker[F[_], T, U] {
  def make(context: CrdWatcherContext[F, T, U]): WatcherMaker[F]
}

object CrdWatchMaker {
  implicit def crd[F[_]: Effect: Parallel: Timer, T, U]: CrdWatchMaker[F, T, U] =
    (context: CrdWatcherContext[F, T, U]) => new CustomResourceWatcher(context)
}

trait ConfigMapWatchMaker[F[_], T] {
  def make(context: ConfigMapWatcherContext[F, T]): WatcherMaker[F]
}

object ConfigMapWatchMaker {
  implicit def cm[F[_]: Effect: Parallel: Timer, T]: ConfigMapWatchMaker[F, T] =
    (context: ConfigMapWatcherContext[F, T]) => new ConfigMapWatcher(context)
}

trait CrdDeployer[F[_]] {
  def deployCrd[T: JsonReader](
    client: KubernetesClient,
    cfg: CrdConfig,
    isOpenShift: Option[Boolean]
  ): F[CustomResourceDefinition]
}

object CrdDeployer {
  implicit def deployer[F[_]: Sync]: CrdDeployer[F] = new CrdDeployer[F] {
    override def deployCrd[T: JsonReader](
      client: KubernetesClient,
      cfg: CrdConfig,
      isOpenShift: Option[Boolean]
    ): F[CustomResourceDefinition] =
      Deployer.deployCrd[F, T](client, cfg, isOpenShift)
  }
}

trait FeedbackConsumerMaker[F[_], T] {
  def make(
    client: KubernetesClient,
    crd: CustomResourceDefinition,
    channel: FeedbackChannel[F, T]
  ): FeedbackConsumerAlg[F, T]
}

object FeedbackConsumerMaker {
  implicit def consumer[F[_]: Effect, T: JsonWriter]: FeedbackConsumerMaker[F, T] =
    (client: KubernetesClient, crd: CustomResourceDefinition, channel: FeedbackChannel[F, T]) =>
      new FeedbackConsumer[F, T](client, crd, channel)
}
