package io.github.novakovalexey.k8soperator.common

import cats.effect.concurrent.MVar
import cats.effect.{ConcurrentEffect, Sync}
import cats.implicits._
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}
import io.github.novakovalexey.k8soperator._
import io.github.novakovalexey.k8soperator.common.crd.{InfoClass, InfoClassDoneable, InfoList}

final case class CustomResourceWatcher[F[_]: ConcurrentEffect, T](
  override val namespace: Namespaces,
  override val kind: String,
  override val controller: Controller[F, T],
  convertCr: InfoClass[T] => Either[Throwable, (T, Metadata)],
  channel: MVar[F, OperatorAction[T]],
  client: KubernetesClient,
  crd: CustomResourceDefinition
) extends AbstractWatcher[F, T, Controller[F, T]](namespace, kind, controller, channel) {

  override def watch: F[(Watch, F[Unit])] = {
    val watchable = {
      val crds =
        client.customResources(crd, classOf[InfoClass[T]], classOf[InfoList[T]], classOf[InfoClassDoneable[T]])
      if (AllNamespaces == namespace) crds.inAnyNamespace
      else crds.inNamespace(namespace.value)
    }

    val watch = Sync[F].delay(watchable.watch(new Watcher[InfoClass[T]]() {
      override def eventReceived(action: Watcher.Action, info: InfoClass[T]): Unit = {
        logger.debug(s"Custom resource in namespace $namespace was $action\nCR:\n$info")
        val converted = convertCr(info)
        enqueueAction(action, converted, info, Some(info.getSpec))
        logger.debug(s"action enqueued: $action")
      }

      override def onClose(e: KubernetesClientException): Unit =
        CustomResourceWatcher.super.onClose(e)
    }))

    Sync[F].delay(logger.info(s"CustomResource watcher running for kinds '$kind'")) *> watch.map(_ -> consumer(channel))
  }
}
