package freya.watcher

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Async, Sync}
import cats.syntax.all._
import freya.ExitCodes.ConsumerExitCode
import freya.K8sNamespace.AllNamespaces
import freya.errors.{OperatorError, ParseResourceError}
import freya.internal.kubeapi.CrdApi
import freya.models.Resource
import freya.watcher.AbstractWatcher.CloseableWatcher
import freya.{Controller, K8sNamespace}
import io.fabric8.kubernetes.api.model.ListOptions
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition
import io.fabric8.kubernetes.client.dsl.Watchable
import io.fabric8.kubernetes.client.{KubernetesClient, Watch, Watcher, WatcherException}

final case class CrdWatcherContext[F[_], T, U](
  ns: K8sNamespace,
  kind: String,
  channels: Channels[F, T, U],
  convertCr: String => Resource[T, U],
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

  private def makeWatchable: Watchable[Watcher[String]] = {
    val AnyName: String = null
    val AnyLabels: java.util.Map[String, String] = null
    val AnyResourceVersion: String = null
    val namespace = if (AllNamespaces == targetNamespace) None else Some(targetNamespace.value)

    new Watchable[Watcher[String]] {
      private val cr = crdApi.rawResource

      override def watch(watcher: Watcher[String]): Watch =
        cr.watch(namespace.orNull, AnyName, AnyLabels, AnyResourceVersion, watcher)

      override def watch(options: ListOptions, watcher: Watcher[String]): Watch =
        cr.watch(namespace.orNull, AnyName, AnyLabels, options, watcher)

      override def watch(resourceVersion: String, watcher: Watcher[String]): Watch =
        cr.watch(namespace.orNull, AnyName, AnyLabels, resourceVersion, watcher)
    }
  }

  override def watch: F[(CloseableWatcher, F[ConsumerExitCode])] = {
    val watchable = makeWatchable
    registerWatcher(watchable)
  }

  protected[freya] def registerWatcher(
    watchable: Watchable[Watcher[String]]
  ): F[(CloseableWatcher, F[ConsumerExitCode])] = {
    val startWatcher = Sync[F].blocking(watchable.watch(new Watcher[String]() {

      override def eventReceived(action: Watcher.Action, cr: String): Unit = {
        logger.debug(s"Custom resource received:\n$cr")

        val converted = context.convertCr(cr).leftMap[OperatorError] { case (t, resource) =>
          ParseResourceError(action, t, resource)
        }
        val namespace = converted.map(_.metadata.namespace).getOrElse(Channels.UnparsedNamespace)
        enqueueAction(namespace, action, converted)

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
