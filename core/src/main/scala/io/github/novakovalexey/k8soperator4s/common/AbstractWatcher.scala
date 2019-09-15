package io.github.novakovalexey.k8soperator4s.common

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.Watch
import io.fabric8.kubernetes.client.Watcher.Action
import io.fabric8.kubernetes.client.Watcher.Action._
import io.github.novakovalexey.k8soperator4s.common.AnsiColors._

import scala.concurrent.ExecutionContext
import scala.util.Try
import scala.util.control.NonFatal

abstract class AbstractWatcher[T] protected (
  val isCrd: Boolean,
  val namespace: String,
  val kind: String,
  val onAdd: (T, String) => Unit,
  val onDelete: (T, String) => Unit,
  val onModify: (T, String) => Unit,
)(implicit ec: ExecutionContext)
    extends LazyLogging {

  def watch: Watch

  protected def handleAction(action: Action, entity: T, meta: Metadata, ns: String): Unit = {
    val name = meta.name
    Try(action).collect {
      case ADDED =>
        logger.info("{}creating{} {}:  \n{}\n", gr, xx, kind, name)
        onAdd(entity, ns)
        logger.info("{} {} has been  {}created{}", kind, name, gr, xx)

      case DELETED =>
        logger.info("{}deleting{} {}:  \n{}\n", gr, xx, kind, name)
        onDelete(entity, ns)
        logger.info("{} {} has been  {}deleted{}", kind, name, gr, xx)

      case MODIFIED =>
        logger.info("{}modifying{} {}:  \n{}\n", gr, xx, kind, name)
        onModify(entity, ns)
        logger.info("{} {} has been  {}modified{}", kind, name, gr, xx)

      case _ =>
        logger.error("Unknown action: {} in namespace {}", action, namespace)
    }.recover {
      case NonFatal(e) =>
        logger.warn(s"${re}Error${xx} when reacting on event", e)
    }
  }
}
