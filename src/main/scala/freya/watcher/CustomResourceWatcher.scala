package freya.watcher

import cats.effect.{ConcurrentEffect, Sync}
import cats.implicits._
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.dsl.Watchable
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}
import freya.errors.{OperatorError, ParseResourceError}
import freya.internal.api.CrdApi
import freya.watcher.AbstractWatcher.Channel
import freya.watcher.WatcherMaker.{Consumer, ConsumerSignal}
import freya.{Controller, K8sNamespace, Metadata}

final case class CrdWatcherContext[F[_]: ConcurrentEffect, T](
  ns: K8sNamespace,
  kind: String,
  controller: Controller[F, T],
  convertCr: InfoClass[T] => Either[Throwable, (T, Metadata)],
  channel: Channel[F, T],
  client: KubernetesClient,
  crd: CustomResourceDefinition
)

class CustomResourceWatcher[F[_]: ConcurrentEffect, T](context: CrdWatcherContext[F, T])
    extends AbstractWatcher[F, T, Controller[F, T]](context.ns, context.kind, context.controller, context.channel) {

  private val crdApi = new CrdApi(context.client)

  override def watch: F[(Consumer, ConsumerSignal[F])] = {
    val watchable = crdApi.in[T](namespace, context.crd)
    registerWatcher(watchable)
  }

  protected[freya] def registerWatcher(
    watchable: Watchable[Watch, Watcher[InfoClass[T]]]
  ): F[(Consumer, ConsumerSignal[F])] = {
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
