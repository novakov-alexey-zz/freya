package io.github.novakovalexey.k8soperator4s.common

import cats.effect.Effect
import cats.syntax.apply._
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.Watcher.Action
import io.fabric8.kubernetes.client.Watcher.Action._
import io.fabric8.kubernetes.client.{KubernetesClientException, Watch}
import io.github.novakovalexey.k8soperator4s.Operator
import io.github.novakovalexey.k8soperator4s.common.AnsiColors._

abstract class AbstractWatcher[F[_], T] protected (
  val namespace: Namespaces,
  val kind: String,
  val handler: Operator[F, T],
  recreateWatcher: KubernetesClientException => F[Unit]
)(implicit F: Effect[F])
    extends LazyLogging {

  def watch: Watch

  protected def handleAction(action: Action, entity: T, meta: Metadata, namespace: String): F[Unit] =
    action match {
      case ADDED =>
        F.delay(logger.info(s"Event received ${gr}ADDED$xx kind=$kind name=${meta.name} in namespace=$namespace")) *>
          handler.onAdd(entity, meta) *>
          F.delay(logger.info(s"Event ${gr}ADDED$xx for kind=$kind name=${meta.name} has been handled"))

      case DELETED =>
        F.delay(
          logger.info("Event received {}DELETED{} kind={} name={} in namespace={}", gr, xx, kind, meta.name, namespace)
        ) *>
          handler.onDelete(entity, meta) *>
          F.delay(logger.info("Event {}DELETED{} for kind={} name={}  has been handled", gr, xx, kind, meta.name))

      case MODIFIED =>
        F.delay(
          logger.info("Event received {}MODIFIED{} kind={} name={} in namespace={}", gr, xx, kind, meta.name, namespace)
        ) *>
          handler.onModify(entity, meta) *>
          F.delay(logger.info("Event {}MODIFIED{} for kind={} name={} has been handled", gr, xx, kind, meta.name))

      case _ =>
        F.delay(logger.error("Unknown action: {} in namespace '{}'", action, namespace))
    }

  protected def unsafeRun(f: F[Unit]): Unit =
    Effect[F].toIO(f).unsafeRunAsyncAndForget()

  protected[common] def onClose(e: KubernetesClientException): Unit =
    if (e != null) {
      logger.error(s"Watcher closed with exception in namespace '$namespace'", e)
      unsafeRun(recreateWatcher(e))
    } else
      logger.info(s"Watcher closed in namespace $namespace")
}
