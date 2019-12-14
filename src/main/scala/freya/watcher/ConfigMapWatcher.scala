package freya.watcher

import cats.effect.{ConcurrentEffect, Sync}
import cats.implicits._
import freya.Controller.ConfigMapController
import freya.errors.{OperatorError, ParseResourceError}
import freya.internal.api.ConfigMapApi
import freya.watcher.AbstractWatcher.Channel
import freya.watcher.WatcherMaker.{Consumer, ConsumerSignal}
import freya.{Controller, K8sNamespace, Metadata}
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.dsl.Watchable
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}
import io.fabric8.kubernetes.internal.KubernetesDeserializer

final case class ConfigMapWatcherContext[F[_]: ConcurrentEffect, T](
  namespace: K8sNamespace,
  kind: String,
  controller: ConfigMapController[F, T],
  convert: ConfigMap => Either[Throwable, (T, Metadata)],
  channel: Channel[F, T],
  client: KubernetesClient,
  selector: Map[String, String]
)

class ConfigMapWatcher[F[_]: ConcurrentEffect, T](context: ConfigMapWatcherContext[F, T])
    extends AbstractWatcher[F, T, Controller[F, T]](
      context.namespace,
      context.kind,
      context.controller,
      context.channel
    ) {

  private val configMapApi = new ConfigMapApi(context.client)

  override def watch: F[(Consumer, ConsumerSignal[F])] =
    Sync[F].delay(
      KubernetesDeserializer.registerCustomKind("v1#ConfigMap", classOf[ConfigMap]) //TODO: why internal API is called?
    ) *> {
        val watchable = configMapApi.one(configMapApi.in(namespace), context.selector)
        registerWatcher(watchable)
      }

  protected[freya] def registerWatcher(
    watchable: Watchable[Watch, Watcher[ConfigMap]]
  ): F[(Consumer, ConsumerSignal[F])] = {

    val watch = Sync[F].delay(watchable.watch(new Watcher[ConfigMap]() {
      override def eventReceived(action: Watcher.Action, cm: ConfigMap): Unit = {
        if (context.controller.isSupported(cm)) {
          logger.debug(s"ConfigMap in namespace $namespace was $action\nConfigMap:\n$cm\n")
          val converted = context.convert(cm).leftMap[OperatorError[T]](t => ParseResourceError(action, t, cm))
          enqueueAction(action, converted, cm)
        } else logger.error(s"Unknown ConfigMap kind: ${cm.toString}")
      }

      override def onClose(e: KubernetesClientException): Unit =
        ConfigMapWatcher.super.onClose(e)
    }))

    Sync[F].delay(logger.info(s"ConfigMap watcher running for labels ${context.selector}")) *> watch.map(
      _ -> consumer(context.channel)
    )
  }

}
