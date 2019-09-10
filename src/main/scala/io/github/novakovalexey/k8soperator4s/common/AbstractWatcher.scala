package io.github.novakovalexey.k8soperator4s.common

import java.util.concurrent.CompletableFuture

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.Watcher.Action._
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch, Watcher}
import io.github.novakovalexey.k8soperator4s.SDKEntrypoint
import io.github.novakovalexey.k8soperator4s.common.AnsiColors._
import io.github.novakovalexey.k8soperator4s.common.OperatorConfig.ALL_NAMESPACES
import io.github.novakovalexey.k8soperator4s.common.crd.{InfoClass, InfoClassDoneable, InfoList}

import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.control.NonFatal

abstract class AbstractWatcher[T <: EntityInfo] protected (
  val isCrd: Boolean,
  val namespace: String,
  val entityName: String,
  val client: KubernetesClient,
  val crd: CustomResourceDefinition,
  val selector: Map[String, String],
  val onAdd: (T, String) => Unit,
  val onDelete: (T, String) => Unit,
  val onModify: (T, String) => Unit,
  val isSupported: ConfigMap => Boolean,
  val convert: ConfigMap => T,
  val convertCr: InfoClass[_] => T
) extends LazyLogging
    {

  private var watch: Watch = _
  protected var fullReconciliationRun = false

  def watchF(): CompletableFuture[AbstractWatcher[T]]

  protected def createConfigMapWatch: CompletableFuture[Watch] = {
    val cf: CompletableFuture[Watch] =
      CompletableFuture.supplyAsync(
        () => {
          val inAllNs = ALL_NAMESPACES == namespace
          val watchable = {
            val cms = client.configMaps
            if (inAllNs) cms.inAnyNamespace.withLabels(selector.asJava)
            else cms.inNamespace(namespace).withLabels(selector.asJava)
          }

          watchable.watch(new Watcher[ConfigMap]() {
            override def eventReceived(action: Watcher.Action, cm: ConfigMap): Unit = {
              if (isSupported(cm)) {
                logger.info("ConfigMap in namespace {} was {}\nCM:\n{}\n", namespace, action, cm)
                val entity = convert.apply(cm)
                if (entity == null)
                  logger.error("something went wrong, unable to parse {} definition", entityName)
                if (action == ERROR)
                  logger.error("Failed ConfigMap {} in namespace{} ", cm, namespace)
                else
                  handleAction(
                    action,
                    entity,
                    if (inAllNs) cm.getMetadata.getNamespace
                    else namespace
                  )
              } else logger.error("Unknown CM kind: {}", cm.toString)
            }

            override def onClose(e: KubernetesClientException): Unit = {
              if (e != null) {
                logger.error("Watcher closed with exception in namespace {}", namespace, e)
                recreateWatcher()
              } else logger.info("Watcher closed in namespace {}", namespace)
            }
          })
        },
        SDKEntrypoint.executors
      )

    cf.thenApply[Watch](w => {
        logger.info("ConfigMap watcher running for labels {}", selector)
        w
      })
      .exceptionally((e: Throwable) => {
        logger.error("ConfigMap watcher failed to start", e.getCause)
        null
      })
  }

  protected def createCustomResourceWatch: CompletableFuture[Watch] = {
    val cf: CompletableFuture[Watch] =
      CompletableFuture.supplyAsync(
        () => {
          val inAllNs = ALL_NAMESPACES == namespace
          val watchable = {
            val crds =
              client.customResources(crd, classOf[InfoClass[T]], classOf[InfoList[T]], classOf[InfoClassDoneable[T]])
            if (inAllNs) crds.inAnyNamespace
            else crds.inNamespace(namespace)
          }

          val watch = watchable.watch(new Watcher[InfoClass[T]]() {
            override def eventReceived(action: Watcher.Action, info: InfoClass[T]): Unit = {
              logger.info("Custom resource in namespace {} was {}\nCR:\n{}", namespace, action, info)
              val entity = convertCr.apply(info)
              if (entity == null)
                logger.error("something went wrong, unable to parse {} definition", entityName)
              if (action == ERROR)
                logger.error("Failed Custom resource {} in namespace{} ", info, namespace)
              else
                handleAction(
                  action,
                  entity,
                  if (inAllNs) info.getMetadata.getNamespace
                  else namespace
                )
            }

            override def onClose(e: KubernetesClientException): Unit = {
              if (e != null) {
                logger.error("Watcher closed with exception in namespace {}", namespace, e)
                recreateWatcher()
              } else logger.info("Watcher closed in namespace {}", namespace)
            }
          })
          this.watch = watch
          watch
        },
        SDKEntrypoint.executors
      )

    cf.thenApply[Watch](w => {
        logger.info("CustomResource watcher running for kinds {}", entityName)
        w
      })
      .exceptionally((e: Throwable) => {
        logger.error("CustomResource watcher failed to start", e.getCause)
        null
      })
  }

  private def recreateWatcher(): Unit = {
    this.watch.close()

    val configMapWatch =
      if (isCrd) createCustomResourceWatch
      else createConfigMapWatch

    val crdOrCm =
      if (isCrd) "CustomResource"
      else "ConfigMap"

    configMapWatch
      .thenApply((res: Watch) => {
        logger.info("{} watch recreated in namespace {}", crdOrCm, namespace)
        this.watch = res
        res
      })
      .exceptionally((e: Throwable) => {
        logger.error("Failed to recreate {} watch in namespace {}", crdOrCm, namespace)
        null
      })
  }

  private def handleAction(action: Watcher.Action, entity: T, ns: String): Unit = {
    if (fullReconciliationRun) {
      val name = entity.name
      Try(action).collect {
        case ADDED =>
          logger.info("{}creating{} {}:  \n{}\n", gr, xx, entityName, name)
          onAdd(entity, ns)
          logger.info("{} {} has been  {}created{}", entityName, name, gr, xx)

        case DELETED =>
          logger.info("{}deleting{} {}:  \n{}\n", gr, xx, entityName, name)
          onDelete(entity, ns)
          logger.info("{} {} has been  {}deleted{}", entityName, name, gr, xx)

        case MODIFIED =>
          logger.info("{}modifying{} {}:  \n{}\n", gr, xx, entityName, name)
          onModify(entity, ns)
          logger.info("{} {} has been  {}modified{}", entityName, name, gr, xx)

        case _ =>
          logger.error("Unknown action: {} in namespace {}", action, namespace)
      }.recover {
        case NonFatal(e) =>
          logger.warn("{}Error{} when reacting on event, cause: {}", re, xx, e.getMessage)
          e.printStackTrace()
      }
    }
  }

  def close(): Unit = {
    logger.info(s"Stopping ${if (isCrd) "CustomResourceWatch" else "ConfigMapWatch"} for namespace $namespace")
    watch.close()
    client.close()
  }

  def setFullReconciliationRun(fullReconciliationRun: Boolean): Unit =
    this.fullReconciliationRun = fullReconciliationRun
}
