package io.github.novakovalexey.k8soperator4s.common

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}
import io.github.novakovalexey.k8soperator4s.resource.HasDataHelper

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

object ConfigMapWatcher {
  def defaultConvert[T](clazz: Class[T], cm: ConfigMap): (T, Metadata) =
    HasDataHelper.parseCM(clazz, cm)
}

final case class ConfigMapWatcher[T](
  override val namespace: Namespaces,
  override val kind: String,
  override val onAdd: (T, String) => Unit,
  override val onDelete: (T, String) => Unit,
  override val onModify: (T, String) => Unit,
  client: KubernetesClient,
  selector: Map[String, String],
  isSupported: ConfigMap => Boolean,
  convert: ConfigMap => (T, Metadata),
  recreateWatcher: KubernetesClientException => Unit
)(implicit ec: ExecutionContext)
    extends AbstractWatcher[T](false, namespace, kind, onAdd, onDelete, onModify) {

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
            logger.error("something went wrong, unable to parse {} definition", kind)

          if (action == Watcher.Action.ERROR)
            logger.error("Failed ConfigMap {} in namespace{} ", cm, namespace)
          else
            handleAction(
              action,
              entity,
              meta,
              if (inAllNs) cm.getMetadata.getNamespace
              else namespace.value
            )
        } else logger.error("Unknown CM kind: {}", cm.toString)
      }

      override def onClose(e: KubernetesClientException): Unit = {
        if (e != null) {
          logger.error("Watcher closed with exception in namespace {}", namespace, e)
          recreateWatcher(e)
        } else
          logger.info("Watcher closed in namespace {}", namespace)
      }
    })

    logger.info("ConfigMap watcher running for labels {}", selector)
    watch
  }

}
