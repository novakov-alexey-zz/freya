package freya.watcher

import cats.Parallel
import cats.effect.{ConcurrentEffect, Sync, Timer}
import cats.implicits._
import freya.ExitCodes.ConsumerExitCode
import freya.errors.{OperatorError, ParseResourceError}
import freya.internal.kubeapi.CrdApi
import freya.models.Resource
import freya.watcher.AbstractWatcher.CloseableWatcher
import freya.{Controller, K8sNamespace}
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.dsl.Watchable
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}

final case class CrdWatcherContext[F[_]: ConcurrentEffect, T, U](
  ns: K8sNamespace,
  kind: String,
  channels: Channels[F, T, U],
  convertCr: AnyCustomResource => Resource[T, U],
  client: KubernetesClient,
  crd: CustomResourceDefinition
)

class CustomResourceWatcher[F[_]: ConcurrentEffect: Parallel: Timer, T, U](context: CrdWatcherContext[F, T, U])
    extends AbstractWatcher[F, T, U, Controller[F, T, U]](context.ns, context.channels, context.client.getNamespace) {

  private val crdApi = new CrdApi(context.client, context.crd)

  override def watch: F[(CloseableWatcher, F[ConsumerExitCode])] = {
    val watchable = crdApi.resourcesIn[T](targetNamespace)
    registerWatcher(watchable)
  }

  protected[freya] def registerWatcher(
    watchable: Watchable[Watch, Watcher[AnyCustomResource]]
  ): F[(CloseableWatcher, F[ConsumerExitCode])] = {
    val startWatcher = Sync[F].delay(watchable.watch(new Watcher[AnyCustomResource]() {

      override def eventReceived(action: Watcher.Action, cr: AnyCustomResource): Unit = {
        logger.debug(s"Custom resource in namespace '${cr.getMetadata.getNamespace}' was $action\nCR spec:\n$cr")

        val converted = context.convertCr(cr).leftMap[OperatorError] {
          case (t, resource) =>
            ParseResourceError(action, t, resource)
        }
        enqueueAction(cr.getMetadata.getNamespace, action, converted)

        logger.debug(s"action enqueued: $action")
      }

      override def onClose(e: KubernetesClientException): Unit =
        CustomResourceWatcher.super.onClose(e)
    }))

    Sync[F].delay(logger.info(s"CustomResource watcher is running for kinds '${context.kind}'")) *> startWatcher.map(
      _ -> context.channels.stopFlag.read
    )
  }
}
