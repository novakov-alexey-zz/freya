package freya.watcher

import cats.effect.{ConcurrentEffect, Sync}
import cats.implicits._
import freya.ExitCodes.ConsumerExitCode
import freya.errors.{OperatorError, ParseResourceError}
import freya.internal.api.CrdApi
import freya.models.Resource
import freya.watcher.AbstractWatcher.{Channel, CloseableWatcher}
import freya.{Controller, K8sNamespace}
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.dsl.Watchable
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}

final case class CrdWatcherContext[F[_]: ConcurrentEffect, T, U](
  ns: K8sNamespace,
  kind: String,
  consumer: ActionConsumer[F, T, U],
  feedback: FeedbackConsumerAlg[F],
  convertCr: AnyCustomResource => Resource[T, U],
  channel: Channel[F, T, U],
  client: KubernetesClient,
  crd: CustomResourceDefinition
)

class CustomResourceWatcher[F[_]: ConcurrentEffect, T, U](context: CrdWatcherContext[F, T, U])
    extends AbstractWatcher[F, T, U, Controller[F, T, U]](context.ns, context.channel, context.client.getNamespace) {

  private val crdApi = new CrdApi(context.client)

  override def watch: F[(CloseableWatcher, F[ConsumerExitCode])] = {
    val watchable = crdApi.in[T](targetNamespace, context.crd)
    registerWatcher(watchable)
  }

  protected[freya] def registerWatcher(
    watchable: Watchable[Watch, Watcher[AnyCustomResource]]
  ): F[(CloseableWatcher, F[ConsumerExitCode])] = {
    val watch = Sync[F].delay(watchable.watch(new Watcher[AnyCustomResource]() {

      override def eventReceived(action: Watcher.Action, cr: AnyCustomResource): Unit = {
        logger.debug(s"Custom resource in namespace '${cr.getMetadata.getNamespace}' was $action\nCR spec:\n$cr")

        val converted = context.convertCr(cr).leftMap[OperatorError] {
          case (t, resource) =>
            ParseResourceError(action, t, resource)
        }
        enqueueAction(action, converted)

        logger.debug(s"action enqueued: $action")
      }

      override def onClose(e: KubernetesClientException): Unit =
        CustomResourceWatcher.super.onClose(e)
    }))

    Sync[F].delay(logger.info(s"CustomResource watcher running for kinds '${context.kind}'")) *> watch.map(
      _ -> context.consumer.consume(context.channel) *> context.feedback.consume
    )
  }
}
