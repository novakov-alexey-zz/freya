package io.github.novakovalexey.k8soperator4s.common

import cats.effect.{Effect, Sync}
import cats.syntax.functor._
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import fs2.concurrent.Queue
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}
import io.github.novakovalexey.k8soperator4s.Controller
import io.github.novakovalexey.k8soperator4s.common.crd.{InfoClass, InfoClassDoneable, InfoList}

object CustomResourceWatcher {

  def defaultConvert[T](clazz: Class[T], info: InfoClass[_]): T = {
    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)

    var infoSpec = mapper.convertValue(info.getSpec, clazz)

    if (infoSpec == null) { // empty spec
      try infoSpec = clazz.getDeclaredConstructor().newInstance()
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

final case class CustomResourceWatcher[F[_]: Effect, T](
  override val namespace: Namespaces,
  override val kind: String,
  override val handler: Controller[F, T],
  convertCr: InfoClass[_] => (T, Metadata),
  q: Queue[F, OperatorEvent[T]],
  client: KubernetesClient,
  crd: CustomResourceDefinition
) extends AbstractWatcher[F, T](namespace, kind, handler) {

  override def watch: F[(Watch, fs2.Stream[F, Unit])] =
    createCustomResourceWatch

  protected def createCustomResourceWatch: F[(Watch, fs2.Stream[F, Unit])] = {
    val inAllNs = AllNamespaces == namespace
    val watchable = {
      val crds =
        client.customResources(crd, classOf[InfoClass[T]], classOf[InfoList[T]], classOf[InfoClassDoneable[T]])
      if (inAllNs) crds.inAnyNamespace
      else crds.inNamespace(namespace.value)
    }

    val watch = Sync[F].delay(watchable.watch(new Watcher[InfoClass[T]]() {
      override def eventReceived(action: Watcher.Action, info: InfoClass[T]): Unit = {
        logger.info(s"Custom resource in namespace $namespace was $action\nCR:\n$info")

        val (entity, meta) = convertCr(info)
        if (entity == null)
          logger.error(s"something went wrong, unable to parse '$kind' definition")

        if (action == Watcher.Action.ERROR)
          logger.error(s"Failed Custom resource $info in namespace $namespace")
        else {
          val ns = if (inAllNs) info.getMetadata.getNamespace else namespace.value
          val event = OperatorEvent[T](action, entity, meta, ns)
          unsafeRun(q.enqueue1(event))
        }
      }

      override def onClose(e: KubernetesClientException): Unit =
        CustomResourceWatcher.super.onClose(e)
    }))

    logger.info(s"CustomResource watcher running for kinds '$kind'")
    watch.map(_ -> q.dequeue.evalMap(handleEvent))
  }

}
