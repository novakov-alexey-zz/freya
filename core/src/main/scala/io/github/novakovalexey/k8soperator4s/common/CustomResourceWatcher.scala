package io.github.novakovalexey.k8soperator4s.common

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}
import io.github.novakovalexey.k8soperator4s.common.OperatorConfig.ALL_NAMESPACES
import io.github.novakovalexey.k8soperator4s.common.crd.{InfoClass, InfoClassDoneable, InfoList}

import scala.concurrent.ExecutionContext

object CustomResourceWatcher {

  def defaultConvert[T](clazz: Class[T], info: InfoClass[_]): T = {
    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)

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
  }
}

final case class CustomResourceWatcher[T](
  override val namespace: String = ALL_NAMESPACES,
  override val entityName: String,
  override val onAdd: (T, String) => Unit,
  override val onDelete: (T, String) => Unit,
  override val onModify: (T, String) => Unit,
  convertCr: InfoClass[_] => (T, Metadata),
  client: KubernetesClient,
  crd: CustomResourceDefinition,
)(implicit ec: ExecutionContext)
    extends AbstractWatcher[T](true, namespace, entityName, onAdd, onDelete, onModify) {

  override def watch: Watch =
    createCustomResourceWatch

  protected def createCustomResourceWatch: Watch = {
    val inAllNs = ALL_NAMESPACES == namespace
    val watchable = {
      val crds =
        client.customResources(crd, classOf[InfoClass[T]], classOf[InfoList[T]], classOf[InfoClassDoneable[T]])
      if (inAllNs) crds.inAnyNamespace
      else crds.inNamespace(namespace)
    }

    val watch = watchable.watch(new Watcher[InfoClass[T]]() {
      override def eventReceived(action: Watcher.Action, info: InfoClass[T]): Unit = {
        logger.info(s"Custom resource in namespace $namespace was $action\nCR:\n$info")

        val (entity, meta) = convertCr(info)
        if (entity == null)
          logger.error("something went wrong, unable to parse {} definition", entityName)

        if (action == Watcher.Action.ERROR)
          logger.error(s"Failed Custom resource $info in namespace $namespace")
        else
          handleAction(
            action,
            entity,
            meta,
            if (inAllNs) info.getMetadata.getNamespace
            else namespace
          )
      }

      override def onClose(e: KubernetesClientException): Unit = {
        if (e != null) {
          logger.error(s"Watcher closed with exception in namespace $namespace", e)
          //recreateWatcher()
        } else
          logger.info(s"Watcher closed in namespace $namespace")
      }
    })

    logger.info(s"CustomResource watcher running for kinds '$entityName'")
    watch
  }

}
