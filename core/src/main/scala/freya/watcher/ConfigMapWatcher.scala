package freya.watcher

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Async, Sync}
import cats.implicits._
import freya.ExitCodes.ConsumerExitCode
import freya.errors.{OperatorError, ParseResourceError}
import freya.internal.kubeapi.ConfigMapApi
import freya.models.Resource
import freya.watcher.AbstractWatcher.CloseableWatcher
import freya.{CmController, Controller, K8sNamespace}
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.dsl.Watchable
import io.fabric8.kubernetes.client.{KubernetesClient, Watcher, WatcherException}
import io.fabric8.kubernetes.internal.KubernetesDeserializer

final case class ConfigMapWatcherContext[F[_], T](
  namespace: K8sNamespace,
  kind: String,
  controller: CmController[F, T],
  channels: Channels[F, T, Unit],
  convert: ConfigMap => Resource[T, Unit],
  client: KubernetesClient,
  selector: Map[String, String],
  stopFlag: Queue[F, ConsumerExitCode],
  dispatcher: Dispatcher[F]
) {
  def toAbstractContext: AbstractWatcherContext[F, T, Unit] =
    AbstractWatcherContext(namespace, client.getNamespace, channels, stopFlag, dispatcher)
}

class ConfigMapWatcher[F[_]: Async, T](context: ConfigMapWatcherContext[F, T])
    extends AbstractWatcher[F, T, Unit, Controller[F, T, Unit]](context.toAbstractContext) {

  private val configMapApi = new ConfigMapApi(context.client)

  override def watch: F[(CloseableWatcher, F[ConsumerExitCode])] =
    Sync[F].delay(
      KubernetesDeserializer.registerCustomKind("v1#ConfigMap", classOf[ConfigMap]) //TODO: why internal API is called?
    ) *> {
      val watchable = configMapApi.select(configMapApi.in(targetNamespace), context.selector)
      registerWatcher(watchable)
    }

  protected[freya] def registerWatcher(
    watchable: Watchable[Watcher[ConfigMap]]
  ): F[(CloseableWatcher, F[ConsumerExitCode])] = {

    val startWatcher = Sync[F].blocking(watchable.watch(new Watcher[ConfigMap]() {
      override def eventReceived(action: Watcher.Action, cm: ConfigMap): Unit = {
        if (context.controller.isSupported(cm)) {
          logger.debug(s"ConfigMap in namespace $targetNamespace was $action\nConfigMap:\n$cm\n")
          val converted = context.convert(cm).leftMap[OperatorError] { case (t, resource) =>
            ParseResourceError(action, t, resource)
          }
          enqueueAction(cm.getMetadata.getNamespace, action, converted)
        } else logger.debug(s"Unsupported ConfigMap skipped: ${cm.toString}")
      }

      override def onClose(e: WatcherException): Unit =
        ConfigMapWatcher.super.onClose(e)
    }))

    for {
      _ <- Sync[F].delay(logger.info(s"ConfigMap watcher running for labels ${context.selector}"))
      watcher <- startWatcher
      stopFlag = context.stopFlag.take
    } yield (watcher, stopFlag)
  }
}
