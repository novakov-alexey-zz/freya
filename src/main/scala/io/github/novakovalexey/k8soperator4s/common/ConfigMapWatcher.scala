package io.github.novakovalexey.k8soperator4s.common

import cats.effect.Effect
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}
import io.github.novakovalexey.k8soperator4s.Operator
import io.github.novakovalexey.k8soperator4s.resource.HasDataHelper

import scala.jdk.CollectionConverters._

object ConfigMapWatcher {
  def defaultConvert[T](clazz: Class[T], cm: ConfigMap): (T, Metadata) =
    HasDataHelper.parseCM(clazz, cm)
}

final case class ConfigMapWatcher[F[_]: Effect, T](
  override val namespace: Namespaces,
  override val kind: String,
  override val handler: Operator[F, T],
  client: KubernetesClient,
  selector: Map[String, String],
  isSupported: ConfigMap => Boolean,
  convert: ConfigMap => (T, Metadata),
  recreateWatcher: KubernetesClientException => F[Unit]
) extends AbstractWatcher[F, T](namespace, kind, handler, recreateWatcher) {

  io.fabric8.kubernetes.internal.KubernetesDeserializer.registerCustomKind("v1#ConfigMap", classOf[ConfigMap])

  override def watch: Watch =
    createConfigMapWatch

  private def createConfigMapWatch: Watch = {
    val inAllNs = AllNamespaces == namespace
    val watchable = {
      val cms = client.configMaps
      if (inAllNs) cms.inAnyNamespace.withLabels(selector.asJava)
      else cms.inNamespace(namespace.value).withLabels(selector.asJava)
    }

    val watch = watchable.watch(new Watcher[ConfigMap]() {
      override def eventReceived(action: Watcher.Action, cm: ConfigMap): Unit = {
        if (isSupported(cm)) {
          logger.info("ConfigMap in namespace {} was {}\nCM:\n{}\n", namespace, action, cm)
          val (entity, meta) = convert(cm)

          if (entity == null)
            logger.error(s"something went wrong, unable to parse $kind definition")

          if (action == Watcher.Action.ERROR)
            logger.error(s"Failed ConfigMap $cm in namespace $namespace")
          else
            unsafeRun(
              handleAction(
                action,
                entity,
                meta,
                if (inAllNs) cm.getMetadata.getNamespace
                else namespace.value
              )
            )
        } else logger.error(s"Unknown CM kind: ${cm.toString}")
      }

      override def onClose(e: KubernetesClientException): Unit =
        ConfigMapWatcher.super.onClose(e)
    })

    logger.info(s"ConfigMap watcher running for labels $selector")
    watch
  }

}
