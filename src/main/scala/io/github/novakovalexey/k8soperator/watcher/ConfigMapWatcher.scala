package io.github.novakovalexey.k8soperator.watcher

import cats.effect.{ConcurrentEffect, Sync}
import cats.implicits._
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.dsl.Watchable
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}
import io.github.novakovalexey.k8soperator.Controller.ConfigMapController
import io.github.novakovalexey.k8soperator.errors.{OperatorError, ParseResourceError}
import io.github.novakovalexey.k8soperator.internal.api.ConfigMapApi
import io.github.novakovalexey.k8soperator.watcher.AbstractWatcher.Channel
import io.github.novakovalexey.k8soperator.watcher.WatcherMaker.{Consumer, ConsumerSignal}
import io.github.novakovalexey.k8soperator.{Controller, K8sNamespace, Metadata}

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
      io.fabric8.kubernetes.internal.KubernetesDeserializer.registerCustomKind("v1#ConfigMap", classOf[ConfigMap])
    ) *> {
        val watchable = configMapApi.one(configMapApi.in(namespace), context.selector)
        registerWatcher(watchable)
      }

  protected[k8soperator] def registerWatcher(
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
