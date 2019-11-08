package io.github.novakovalexey.k8soperator.common

import cats.effect.Effect
import cats.syntax.apply._
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.Watcher.Action
import io.fabric8.kubernetes.client.Watcher.Action._
import io.fabric8.kubernetes.client.{KubernetesClientException, Watch}
import io.github.novakovalexey.k8soperator.common.AnsiColors._
import io.github.novakovalexey.k8soperator.{Controller, Metadata, Namespaces}

final case class OperatorEvent[T](action: Action, entity: T, meta: Metadata, namespace: String)

abstract class AbstractWatcher[F[_], T, C <: Controller[F, T]] protected (
  val namespace: Namespaces,
  val kind: String,
  val controller: C
)(implicit F: Effect[F])
    extends LazyLogging {

  def watch: F[(Watch, fs2.Stream[F, Unit])]

  protected def handleEvent(event: OperatorEvent[T]): F[Unit] =
    event.action match {
      case ADDED =>
        F.delay(
          logger.info(s"Event received ${gr}ADDED$xx kind=$kind name=${event.meta.name} in namespace '$namespace'")
        ) *>
          controller.onAdd(event.entity, event.meta) *>
          F.delay(logger.info(s"Event ${gr}ADDED$xx for kind=$kind name=${event.meta.name} has been handled"))

      case DELETED =>
        F.delay(
          logger.info(s"Event received ${gr}DELETED$xx kind=$kind name=${event.meta.name} in namespace '$namespace'")
        ) *>
          controller.onDelete(event.entity, event.meta) *>
          F.delay(logger.info(s"Event ${gr}DELETED$xx for kind=$kind name=${event.meta.name} has been handled"))

      case MODIFIED =>
        F.delay(
          logger.info(s"Event received ${gr}MODIFIED$xx kind=$kind name=${event.meta.name} in namespace=$namespace")
        ) *>
          controller.onModify(event.entity, event.meta) *>
          F.delay(logger.info(s"Event ${gr}MODIFIED$xx for kind=$kind name=${event.meta.name} has been handled"))

      case ERROR =>
        F.delay(
          logger.error(s"Event received ${re}ERROR$xx for kind=$kind name=${event.meta.name} in namespace '$namespace'")
        )
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
