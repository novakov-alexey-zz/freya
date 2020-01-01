package freya.watcher

import cats.effect.{ConcurrentEffect, Sync}
import cats.implicits._
import freya.Controller.ConfigMapController
import freya.errors.{OperatorError, ParseResourceError}
import freya.internal.api.ConfigMapApi
import freya.models.Resource
import freya.ExitCodes.ConsumerExitCode
import freya.watcher.AbstractWatcher.{Channel, CloseableWatcher}
import freya.{Controller, K8sNamespace}
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.dsl.Watchable
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}
import io.fabric8.kubernetes.internal.KubernetesDeserializer

final case class ConfigMapWatcherContext[F[_]: ConcurrentEffect, T](
  namespace: K8sNamespace,
  kind: String,
  controller: ConfigMapController[F, T],
  consumer: Consumer[F, T],
  convert: ConfigMap => Resource[T],
  channel: Channel[F, T],
  client: KubernetesClient,
  selector: (String, String)
)

class ConfigMapWatcher[F[_]: ConcurrentEffect, T](context: ConfigMapWatcherContext[F, T])
    extends AbstractWatcher[F, T, Controller[F, T]](context.namespace, context.channel, context.client.getNamespace) {

  private val configMapApi = new ConfigMapApi(context.client)

  override def watch: F[(CloseableWatcher, F[ConsumerExitCode])] =
    Sync[F].delay(
      KubernetesDeserializer.registerCustomKind("v1#ConfigMap", classOf[ConfigMap]) //TODO: why internal API is called?
    ) *> {
      val watchable = configMapApi.select(configMapApi.in(targetNamespace), context.selector)
      registerWatcher(watchable)
    }

  protected[freya] def registerWatcher(
    watchable: Watchable[Watch, Watcher[ConfigMap]]
  ): F[(CloseableWatcher, F[ConsumerExitCode])] = {

    val watch = Sync[F].delay(watchable.watch(new Watcher[ConfigMap]() {
      override def eventReceived(action: Watcher.Action, cm: ConfigMap): Unit = {
        if (context.controller.isSupported(cm)) {
          logger.debug(s"ConfigMap in namespace $targetNamespace was $action\nConfigMap:\n$cm\n")
          val converted = context.convert(cm).leftMap[OperatorError[T]] {
            case (t, resource) =>
              ParseResourceError(action, t, resource)
          }
          enqueueAction(action, converted, cm)
        } else logger.debug(s"Unsupported ConfigMap skipped: ${cm.toString}")
      }

      override def onClose(e: KubernetesClientException): Unit =
        ConfigMapWatcher.super.onClose(e)
    }))

    Sync[F].delay(logger.info(s"ConfigMap watcher running for labels ${context.selector}")) *> watch.map(
      _ -> context.consumer.consume(context.channel)
    )
  }
}
