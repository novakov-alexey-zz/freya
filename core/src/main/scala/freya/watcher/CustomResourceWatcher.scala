package freya.watcher

import cats.effect.{Async, Sync}
import cats.effect.std.{Dispatcher, Queue}
import cats.syntax.all._
import freya.ExitCodes.ConsumerExitCode
import freya.errors.{OperatorError, ParseResourceError}
import freya.internal.kubeapi.CrdApi
import freya.models.Resource
import freya.watcher.AbstractWatcher.CloseableWatcher
import freya.{Controller, K8sNamespace}
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition
import io.fabric8.kubernetes.client.dsl.Watchable
import io.fabric8.kubernetes.client.{KubernetesClient, Watcher, WatcherException}

final case class CrdWatcherContext[F[_], T, U](
  ns: K8sNamespace,
  kind: String,
  channels: Channels[F, T, U],
  convertCr: AnyCustomResource => Resource[T, U],
  client: KubernetesClient,
  crd: CustomResourceDefinition,
  stopFlag: Queue[F, ConsumerExitCode],
  dispatcher: Dispatcher[F]
) {
  def toAbstractContext: AbstractWatcherContext[F, T, U] =
    AbstractWatcherContext(ns, client.getNamespace, channels, stopFlag, dispatcher)
}

class CustomResourceWatcher[F[_]: Async, T, U](context: CrdWatcherContext[F, T, U])
    extends AbstractWatcher[F, T, U, Controller[F, T, U]](context.toAbstractContext) {

  private val crdApi = new CrdApi(context.client, context.crd)

  override def watch: F[(CloseableWatcher, F[ConsumerExitCode])] = {
    val watchable = crdApi.resourcesIn[T](targetNamespace)
    registerWatcher(watchable)
  }

  protected[freya] def registerWatcher(
    watchable: Watchable[Watcher[AnyCustomResource]]
  ): F[(CloseableWatcher, F[ConsumerExitCode])] = {
    val startWatcher = Sync[F].blocking(watchable.watch(new Watcher[AnyCustomResource]() {

      override def eventReceived(action: Watcher.Action, cr: AnyCustomResource): Unit = {
        logger.debug(s"Custom resource in namespace '${cr.getMetadata.getNamespace}' was $action\nCR spec:\n$cr")

        val converted = context.convertCr(cr).leftMap[OperatorError] { case (t, resource) =>
          ParseResourceError(action, t, resource)
        }
        enqueueAction(cr.getMetadata.getNamespace, action, converted)

        logger.debug(s"action enqueued: $action")
      }

      override def onClose(e: WatcherException): Unit =
        CustomResourceWatcher.super.onClose(e)
    }))

    for {
      _ <- Sync[F].delay(logger.info(s"CustomResource watcher is running for kinds '${context.kind}'"))
      watcher <- startWatcher
      stopFlag = context.stopFlag.take <* Sync[F].delay(logger.debug("Stop flag is triggered. Stopping watcher"))
    } yield (watcher, stopFlag)
  }
}
