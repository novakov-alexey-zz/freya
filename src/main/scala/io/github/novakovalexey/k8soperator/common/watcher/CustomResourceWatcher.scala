package io.github.novakovalexey.k8soperator.common.watcher

import cats.effect.{ConcurrentEffect, Sync}
import cats.implicits._
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.dsl.Watchable
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}
import io.github.novakovalexey.k8soperator.common.crd.{InfoClass, InfoClassDoneable, InfoList}
import io.github.novakovalexey.k8soperator.common.watcher.AbstractWatcher.Channel
import io.github.novakovalexey.k8soperator.common.watcher.WatchMaker.ConsumerSignal
import io.github.novakovalexey.k8soperator.errors.{OperatorError, ParseResourceError}
import io.github.novakovalexey.k8soperator.{AllNamespaces, Controller, K8sNamespace, Metadata}

final case class CrdWatcherContext[F[_]: ConcurrentEffect, T](
  ns: K8sNamespace,
  kind: String,
  controller: Controller[F, T],
  convertCr: InfoClass[T] => Either[Throwable, (T, Metadata)],
  channel: Channel[F, T],
  client: KubernetesClient,
  crd: CustomResourceDefinition
)

protected[k8soperator] class CustomResourceWatcher[F[_]: ConcurrentEffect, T](context: CrdWatcherContext[F, T])
    extends AbstractWatcher[F, T, Controller[F, T]](context.ns, context.kind, context.controller, context.channel) {

  override def watch: F[(Watch, ConsumerSignal[F])] = {
    val watchable = {
      val crds =
        context.client.customResources(
          context.crd,
          classOf[InfoClass[T]],
          classOf[InfoList[T]],
          classOf[InfoClassDoneable[T]]
        )
      if (AllNamespaces == namespace) crds.inAnyNamespace
      else crds.inNamespace(namespace.value)
    }

    registerWatcher(watchable)
  }

  protected[k8soperator] def registerWatcher(
    watchable: Watchable[Watch, Watcher[InfoClass[T]]]
  ): F[(Watch, ConsumerSignal[F])] = {
    val watch = Sync[F].delay(watchable.watch(new Watcher[InfoClass[T]]() {

      override def eventReceived(action: Watcher.Action, info: InfoClass[T]): Unit = {
        logger.debug(s"Custom resource in namespace $namespace was $action\nCR:\n$info")

        val converted = context.convertCr(info).leftMap[OperatorError[T]](t => ParseResourceError[T](action, t, info))
        enqueueAction(action, converted, info)

        logger.debug(s"action enqueued: $action")
      }

      override def onClose(e: KubernetesClientException): Unit =
        CustomResourceWatcher.super.onClose(e)
    }))

    Sync[F].delay(logger.info(s"CustomResource watcher running for kinds '$kind'")) *> watch.map(
      _ -> consumer(context.channel)
    )
  }
}
