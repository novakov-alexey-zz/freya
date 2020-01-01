package freya.watcher

import cats.effect.{ConcurrentEffect, Sync}
import cats.implicits._
import freya.errors.{OperatorError, ParseResourceError}
import freya.internal.api.CrdApi
import freya.models.Resource
import freya.ExitCodes.ConsumerExitCode
import freya.watcher.AbstractWatcher.{Channel, CloseableWatcher}
import freya.{Controller, K8sNamespace}
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.dsl.Watchable
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}

final case class CrdWatcherContext[F[_]: ConcurrentEffect, T](
  ns: K8sNamespace,
  kind: String,
  consumer: Consumer[F, T],
  convertCr: SpecClass => Resource[T],
  channel: Channel[F, T],
  client: KubernetesClient,
  crd: CustomResourceDefinition
)

class CustomResourceWatcher[F[_]: ConcurrentEffect, T](context: CrdWatcherContext[F, T])
    extends AbstractWatcher[F, T, Controller[F, T]](context.ns, context.channel, context.client.getNamespace) {

  private val crdApi = new CrdApi(context.client)

  override def watch: F[(CloseableWatcher, F[ConsumerExitCode])] = {
    val watchable = crdApi.in[T](targetNamespace, context.crd)
    registerWatcher(watchable)
  }

  protected[freya] def registerWatcher(
    watchable: Watchable[Watch, Watcher[SpecClass]]
  ): F[(CloseableWatcher, F[ConsumerExitCode])] = {
    val watch = Sync[F].delay(watchable.watch(new Watcher[SpecClass]() {

      override def eventReceived(action: Watcher.Action, spec: SpecClass): Unit = {
        logger.debug(s"Custom resource in namespace ${spec.getMetadata.getNamespace} was $action\nCR spec:\n$spec")

        val converted = context.convertCr(spec).leftMap[OperatorError[T]] {
          case (t, resource) =>
            ParseResourceError[T](action, t, resource)
        }
        enqueueAction(action, converted, spec)

        logger.debug(s"action enqueued: $action")
      }

      override def onClose(e: KubernetesClientException): Unit =
        CustomResourceWatcher.super.onClose(e)
    }))

    Sync[F].delay(logger.info(s"CustomResource watcher running for kinds '${context.kind}'")) *> watch.map(
      _ -> context.consumer.consume(context.channel)
    )
  }
}
