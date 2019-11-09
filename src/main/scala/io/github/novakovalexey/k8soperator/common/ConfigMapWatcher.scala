package io.github.novakovalexey.k8soperator.common

import cats.effect.{Effect, Sync}
import cats.syntax.apply._
import cats.syntax.functor._
import fs2.Stream
import fs2.concurrent.Queue
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}
import io.github.novakovalexey.k8soperator.{AllNamespaces, ConfigMapController, Metadata, Namespaces}

import scala.jdk.CollectionConverters._

final case class ConfigMapWatcher[F[_]: Effect, T](
  override val namespace: Namespaces,
  override val kind: String,
  override val controller: ConfigMapController[F, T],
  client: KubernetesClient,
  selector: Map[String, String],
  convert: ConfigMap => Either[Throwable, (T, Metadata)],
  q: Queue[F, OperatorAction[T]]
) extends AbstractWatcher[F, T, ConfigMapController[F, T]](namespace, kind, controller, q) {

  override def watch: F[(Watch, Stream[F, Unit])] =
    Sync[F].delay(
      io.fabric8.kubernetes.internal.KubernetesDeserializer.registerCustomKind("v1#ConfigMap", classOf[ConfigMap])
    ) *>
      createConfigMapWatch

  private def createConfigMapWatch: F[(Watch, Stream[F, Unit])] = {
    val watchable = {
      val cms = client.configMaps
      if (AllNamespaces == namespace) cms.inAnyNamespace.withLabels(selector.asJava)
      else cms.inNamespace(namespace.value).withLabels(selector.asJava)
    }

    val watch = Sync[F].delay(watchable.watch(new Watcher[ConfigMap]() {
      override def eventReceived(action: Watcher.Action, cm: ConfigMap): Unit = {
        if (controller.isSupported(cm)) {
          logger.debug(s"ConfigMap in namespace $namespace was $action\nConfigMap:\n$cm\n")
          val converted = convert(cm)
          enqueueAction(action, converted, cm, None)
        } else logger.error(s"Unknown ConfigMap kind: ${cm.toString}")
      }

      override def onClose(e: KubernetesClientException): Unit =
        ConfigMapWatcher.super.onClose(e)
    }))

    Sync[F].delay(logger.info(s"ConfigMap watcher running for labels $selector")) *> watch.map(
      _ -> q.dequeue.evalMap(handleEvent)
    )
  }

}
