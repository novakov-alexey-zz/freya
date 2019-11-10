package io.github.novakovalexey.k8soperator.common

import cats.effect.concurrent.MVar
import cats.effect.{ConcurrentEffect, Sync}
//import cats.syntax.apply._
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
//import fs2.concurrent.Queue
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.Watcher.Action
import io.fabric8.kubernetes.client.Watcher.Action._
import io.fabric8.kubernetes.client.{KubernetesClientException, Watch, Watcher}
import io.github.novakovalexey.k8soperator.common.AnsiColors._
import io.github.novakovalexey.k8soperator.{Controller, Metadata, Namespaces}

sealed trait OperatorAction[T]
final case class OkAction[T](watcherAction: Action, entity: T, meta: Metadata, namespace: String)
    extends OperatorAction[T]
final case class FailedAction[T](action: Action, e: Throwable, info: HasMetadata) extends OperatorAction[T]

abstract class AbstractWatcher[F[_], T, C <: Controller[F, T]] protected (
  val namespace: Namespaces,
  val kind: String,
  val controller: C,
  channel: MVar[F, OperatorAction[T]],
)(implicit F: ConcurrentEffect[F])
    extends LazyLogging {

  def watch: F[(Watch, F[Unit])]

  protected def enqueueAction(
    wAction: Watcher.Action,
    errorOrEntity: Either[Throwable, (T, Metadata)],
    resource: HasMetadata,
    spec: Option[T]
  ): Unit = {
    val action = errorOrEntity match {
      case Right((entity, meta)) =>
        val ns = resource.getMetadata.getNamespace
        OkAction[T](wAction, entity, meta, ns)
      case Left(t) =>
        val e =
          new RuntimeException(s"something went wrong, unable to parse '$kind' definition from: $spec", t)
        FailedAction[T](wAction, e, resource)
    }
    unsafeRun(channel.put(action))
  }

  protected def consumer(channel: MVar[F, OperatorAction[T]]): F[Unit] = {
    for {
      a <- channel.take
      _ <- Sync[F].delay(logger.debug(s"consuming action $a"))
      _ <- handleAction(a)
      //TODO: analyze result and stop recursion in case of closed watcher ?
      r <- consumer(channel)
    } yield r
  }

  protected def handleAction(action: OperatorAction[T]): F[Unit] =
    action match {
      case OkAction(wAction, entity, meta, namespace) =>
        wAction match {
          case ADDED =>
            F.delay(logger.info(s"Event received ${gr}ADDED$xx kind=$kind name=${meta.name} in namespace '$namespace'")) *>
              controller.onAdd(entity, meta) *>
              F.delay(logger.info(s"Event ${gr}ADDED$xx for kind=$kind name=${meta.name} has been handled"))

          case DELETED =>
            F.delay(
              logger.info(s"Event received ${gr}DELETED$xx kind=$kind name=${meta.name} in namespace '$namespace'")
            ) *>
              controller.onDelete(entity, meta) *>
              F.delay(logger.info(s"Event ${gr}DELETED$xx for kind=$kind name=${meta.name} has been handled"))

          case MODIFIED =>
            F.delay(
              logger.info(s"Event received ${gr}MODIFIED$xx kind=$kind name=${meta.name} in namespace=$namespace")
            ) *>
              controller.onModify(entity, meta) *>
              F.delay(logger.info(s"Event ${gr}MODIFIED$xx for kind=$kind name=${meta.name} has been handled"))

          case ERROR =>
            F.delay(
              logger.error(s"Event received ${re}ERROR$xx for kind=$kind name=${meta.name} in namespace '$namespace'")
            )
        }
      case FailedAction(wAction, e, info) =>
        F.delay(logger.error(s"Failed action $wAction with spec: $info", e))
    }

  protected def unsafeRun(f: F[Unit]): Unit =
    F.toIO(f).unsafeRunAsyncAndForget()

  protected[common] def onClose(e: KubernetesClientException): Unit =
    if (e != null) {
      logger.error(s"Watcher closed with exception in namespace '$namespace'", e)
      //TODO: signal with end of stream
    } else
      logger.info(s"Watcher closed in namespace $namespace")
}
