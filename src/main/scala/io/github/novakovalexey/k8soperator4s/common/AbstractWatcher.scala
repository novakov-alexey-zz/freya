package io.github.novakovalexey.k8soperator4s.common

import cats.effect.Effect
import cats.syntax.apply._
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.Watcher.Action
import io.fabric8.kubernetes.client.Watcher.Action._
import io.fabric8.kubernetes.client.{KubernetesClientException, Watch}
import io.github.novakovalexey.k8soperator4s.Operator
import io.github.novakovalexey.k8soperator4s.common.AnsiColors._

final case class OperatorEvent[T](action: Action, entity: T, meta: Metadata, namespace: String)

abstract class AbstractWatcher[F[_], T] protected (
  val namespace: Namespaces,
  val kind: String,
  val handler: Operator[F, T]
)(implicit F: Effect[F])
    extends LazyLogging {

  def watch: F[(Watch, fs2.Stream[F, Unit])]

  protected def handleEvent(event: OperatorEvent[T]): F[Unit] =
    event.action match {
      case ADDED =>
        F.delay(logger.info(s"Event received ${gr}ADDED$xx kind=$kind name=${event.meta.name} in namespace=$namespace")) *>
          handler.onAdd(event.entity, event.meta) *>
          F.delay(logger.info(s"Event ${gr}ADDED$xx for kind=$kind name=${event.meta.name} has been handled"))

      case DELETED =>
        F.delay(
          logger.info(s"Event received ${gr}DELETED$xx kind=$kind name=${event.meta.name} in namespace=$namespace")
        ) *>
          handler.onDelete(event.entity, event.meta) *>
          F.delay(logger.info("Event {}DELETED{} for kind={} name={}  has been handled", gr, xx, kind, event.meta.name))

      case MODIFIED =>
        F.delay(
          logger.info(s"Event received ${gr}MODIFIED$xx kind=$kind name=${event.meta.name} in namespace=$namespace")
        ) *>
          handler.onModify(event.entity, event.meta) *>
          F.delay(logger.info("Event {}MODIFIED{} for kind={} name={} has been handled", gr, xx, kind, event.meta.name))

      case _ =>
        F.delay(logger.error(s"Unknown action: ${event.action} in namespace '$namespace'"))
    }

  protected def unsafeRun(f: F[Unit]): Unit =
    Effect[F].toIO(f).unsafeRunAsyncAndForget()

  protected[common] def onClose(e: KubernetesClientException): Unit =
    if (e != null) {
      logger.error(s"Watcher closed with exception in namespace '$namespace'", e)
    } else
      logger.info(s"Watcher closed in namespace $namespace")
}
