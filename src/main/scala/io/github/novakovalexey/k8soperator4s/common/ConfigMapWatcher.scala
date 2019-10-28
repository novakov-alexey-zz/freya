package io.github.novakovalexey.k8soperator4s.common

import cats.effect.{Effect, Sync}
import cats.syntax.apply._
import cats.syntax.functor._
import fs2.Stream
import fs2.concurrent.Queue
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}
import io.github.novakovalexey.k8soperator4s.{AllNamespaces, ConfigMapController, Metadata, Namespaces}

import scala.jdk.CollectionConverters._

final case class ConfigMapWatcher[F[_]: Effect, T](
  override val namespace: Namespaces,
  override val kind: String,
  override val controller: ConfigMapController[F, T],
  client: KubernetesClient,
  selector: Map[String, String],
  convert: ConfigMap => (T, Metadata),
  q: Queue[F, OperatorEvent[T]]
) extends AbstractWatcher[F, T, ConfigMapController[F, T]](namespace, kind, controller) {

  override def watch: F[(Watch, Stream[F, Unit])] =
    Sync[F].delay(
      io.fabric8.kubernetes.internal.KubernetesDeserializer.registerCustomKind("v1#ConfigMap", classOf[ConfigMap])
    ) *>
      createConfigMapWatch

  private def createConfigMapWatch: F[(Watch, Stream[F, Unit])] = {
    val inAllNs = AllNamespaces == namespace
    val watchable = {
      val cms = client.configMaps
      if (inAllNs) cms.inAnyNamespace.withLabels(selector.asJava)
      else cms.inNamespace(namespace.value).withLabels(selector.asJava)
    }

    val watch = Sync[F].delay(watchable.watch(new Watcher[ConfigMap]() {
      override def eventReceived(action: Watcher.Action, cm: ConfigMap): Unit = {
        if (controller.isSupported(cm)) {
          logger.info(s"ConfigMap in namespace $namespace was $action\nConfigMap:\n$cm\n")
          val (entity, meta) = convert(cm)

          if (entity == null)
            logger.error(s"something went wrong, unable to parse $kind definition")

          if (action == Watcher.Action.ERROR)
            logger.error(s"Failed ConfigMap $cm in namespace $namespace")
          else {
            val ns = if (inAllNs) cm.getMetadata.getNamespace else namespace.value
            val event = OperatorEvent[T](action, entity, meta, ns)
            unsafeRun(q.enqueue1(event))
          }
        } else logger.error(s"Unknown CM kind: ${cm.toString}")
      }

      override def onClose(e: KubernetesClientException): Unit =
        ConfigMapWatcher.super.onClose(e)
    }))

    logger.info(s"ConfigMap watcher running for labels $selector")

    watch.map(_ -> q.dequeue.evalMap(handleEvent))
  }

}
