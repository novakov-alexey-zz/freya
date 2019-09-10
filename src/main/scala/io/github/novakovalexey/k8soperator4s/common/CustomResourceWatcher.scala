package io.github.novakovalexey.k8soperator4s.common

import java.util.concurrent.CompletableFuture

import com.fasterxml.jackson.databind.ObjectMapper
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.{KubernetesClient, Watch}
import io.github.novakovalexey.k8soperator4s.common.OperatorConfig.ALL_NAMESPACES
import io.github.novakovalexey.k8soperator4s.common.crd.InfoClass

object CustomResourceWatcher {

  def defaultConvert[T <: EntityInfo](clazz: Class[T], info: InfoClass[_]): T = {
    val name = info.getMetadata.getName
    val namespace = info.getMetadata.getNamespace
    val mapper = new ObjectMapper
    var infoSpec = mapper.convertValue(info.getSpec, clazz)
    if (infoSpec == null) { // empty spec
      try infoSpec = clazz.newInstance
      catch {
        case e: InstantiationException =>
          e.printStackTrace()
        case e: IllegalAccessException =>
          e.printStackTrace()
      }
    }
    infoSpec
      .copy(name = Option(infoSpec.name).getOrElse(name), namespace = Option(infoSpec.namespace).getOrElse(namespace))
      .asInstanceOf[T]
  }
}

final case class CustomResourceWatcher[T <: EntityInfo](
  override val namespace: String = ALL_NAMESPACES,
  override val entityName: String,
  override val client: KubernetesClient,
  override val crd: CustomResourceDefinition,
  override val onAdd: (T, String) => Unit,
  override val onDelete: (T, String) => Unit,
  override val onModify: (T, String) => Unit,
  override val convertCr: InfoClass[_] => T
) // use via builder
    extends AbstractWatcher[T](
      true,
      namespace,
      entityName,
      client,
      crd,
      null,
      onAdd,
      onDelete,
      onModify,
      null,
      null,
      convertCr
    ) {

  override def watchF(): CompletableFuture[AbstractWatcher[T]] =
    createCustomResourceWatch.thenApply((_: Watch) => this)
}
