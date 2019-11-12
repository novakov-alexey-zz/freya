package io.github.novakovalexey.k8soperator.common.watcher

import cats.effect.{ConcurrentEffect, Sync}
import cats.implicits._
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}
import io.github.novakovalexey.k8soperator.common.watcher.AbstractWatcher.Channel
import io.github.novakovalexey.k8soperator.errors.{OperatorError, ParseResourceError}
import io.github.novakovalexey.k8soperator.{AllNamespaces, ConfigMapController, K8sNamespace, Metadata}

import scala.jdk.CollectionConverters._

final case class ConfigMapWatcher[F[_]: ConcurrentEffect, T](
  override val namespace: K8sNamespace,
  override val kind: String,
  override val controller: ConfigMapController[F, T],
  client: KubernetesClient,
  selector: Map[String, String],
  convert: ConfigMap => Either[Throwable, (T, Metadata)],
  channel: Channel[F, T]
) extends AbstractWatcher[F, T, ConfigMapController[F, T]](namespace, kind, controller, channel) {

  override def watch: F[(Watch, F[Unit])] =
    Sync[F].delay(
      io.fabric8.kubernetes.internal.KubernetesDeserializer.registerCustomKind("v1#ConfigMap", classOf[ConfigMap])
    ) *>
      createConfigMapWatch

  private def createConfigMapWatch: F[(Watch, F[Unit])] = {
    val watchable = {
      val cms = client.configMaps
      if (AllNamespaces == namespace) cms.inAnyNamespace.withLabels(selector.asJava)
      else cms.inNamespace(namespace.value).withLabels(selector.asJava)
    }

    val watch = Sync[F].delay(watchable.watch(new Watcher[ConfigMap]() {
      override def eventReceived(action: Watcher.Action, cm: ConfigMap): Unit = {
        if (controller.isSupported(cm)) {
          logger.debug(s"ConfigMap in namespace $namespace was $action\nConfigMap:\n$cm\n")
          val converted = convert(cm).leftMap[OperatorError[T]](t => ParseResourceError(action, t, cm))
          enqueueAction(action, converted, cm)
        } else logger.error(s"Unknown ConfigMap kind: ${cm.toString}")
      }

      override def onClose(e: KubernetesClientException): Unit =
        ConfigMapWatcher.super.onClose(e)
    }))

    Sync[F].delay(logger.info(s"ConfigMap watcher running for labels $selector")) *> watch.map(_ -> consumer(channel))
  }

}
