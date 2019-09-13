package io.github.novakovalexey.k8soperator4s.common

import java.util.concurrent.CompletableFuture

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.{KubernetesClient, Watch}
import io.github.novakovalexey.k8soperator4s.resource.HasDataHelper

object ConfigMapWatcher {
  def defaultConvert[T: EntityInfo](clazz: Class[T], cm: ConfigMap): T = HasDataHelper.parseCM(clazz, cm)
}

final case class ConfigMapWatcher[T: EntityInfo](
  override val namespace: String,
  override val entityName: String,
  override val client: KubernetesClient,
  override val selector: Map[String, String],
  override val onAdd: (T, String) => Unit,
  override val onDelete: (T, String) => Unit,
  override val onModify: (T, String) => Unit,
  predicate: ConfigMap => Boolean,
  override val convert: ConfigMap => T
) extends AbstractWatcher[T](
      true,
      namespace,
      entityName,
      client,
      null,
      selector,
      onAdd,
      onDelete,
      onModify,
      predicate,
      convert,
      null
    ) {

  io.fabric8.kubernetes.internal.KubernetesDeserializer.registerCustomKind("v1#ConfigMap", classOf[ConfigMap])

  override def watchF(): CompletableFuture[AbstractWatcher[T]] =
    createConfigMapWatch.thenApply((_: Watch) => this)
}
