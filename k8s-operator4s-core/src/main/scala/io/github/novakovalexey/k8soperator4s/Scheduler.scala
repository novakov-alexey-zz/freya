package io.github.novakovalexey.k8soperator4s

import java.util.concurrent.atomic.AtomicReference

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.utils.HttpClientUtils
import io.fabric8.kubernetes.client.{ConfigBuilder, KubernetesClient, KubernetesClientException, Watch}
import io.github.novakovalexey.k8soperator4s.common._
import okhttp3.{HttpUrl, Request}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object Scheduler extends LazyLogging {
  def checkIfOnOpenshift(client: KubernetesClient): Boolean =
    Try {
      val kubernetesApi = client.getMasterUrl
      val urlBuilder = new HttpUrl.Builder().host(kubernetesApi.getHost)

      if (kubernetesApi.getPort == -1) urlBuilder.port(kubernetesApi.getDefaultPort)
      else urlBuilder.port(kubernetesApi.getPort)

      if (kubernetesApi.getProtocol == "https") urlBuilder.scheme("https")

      val url = urlBuilder.addPathSegment("apis/route.openshift.io/v1").build()

      val httpClient = HttpClientUtils.createHttpClient(new ConfigBuilder().build)
      val response = httpClient.newCall(new Request.Builder().url(url).build).execute
      response.body().close()
      httpClient.connectionPool().evictAll()
      val success = response.isSuccessful

      if (success) logger.info(s"$url returned ${response.code}. We are on OpenShift.")
      else logger.info(s"$url returned ${response.code}. We are not on OpenShift. Assuming, we are on Kubernetes.")

      success
    }.fold(e => {
      logger.error("Failed to distinguish between Kubernetes and OpenShift", e)
      logger.warn("Let's assume we are on K8s")
      false
    }, identity)
}

class Scheduler[T](client: KubernetesClient, operator: Operator[T])(implicit ec: ExecutionContext) extends LazyLogging {

  private val isOpenShift: Boolean = operator.isOpenShift
  private val watcher: AtomicReference[Option[Watch]] = new AtomicReference(None)

  private val kind = operator.cfg.customKind.getOrElse(operator.cfg.forKind.getSimpleName)
  private val operatorName = s"'$kind' operator"
  private val namespace =
    if (operator.cfg.namespace == SameNamespace) Namespace(client.getNamespace)
    else operator.cfg.namespace

  def start(): Future[Watch] = {
    if (isOpenShift) logger.info(s"${AnsiColors.ye}OpenShift${AnsiColors.xx} environment detected.")
    else logger.info(s"${AnsiColors.ye}Kubernetes${AnsiColors.xx} environment detected.")

    val f = runForNamespace(isOpenShift, namespace)
    f.failed.foreach { ex: Throwable =>
      logger.error(s"Unable to start operator for $namespace namespace", ex)
    }
    f
  }

  def stop(): Future[Unit] =
    watcher.getAndSet(None) match {
      case Some(w) =>
        logger.info(s"Stopping '$operatorName' for namespace '$namespace'")
        Future(w.close())
      case None =>
        logger.debug(s"No watcher to close for '$operatorName' with namespace '$namespace'")
        Future.successful(())
    }

  private def runForNamespace(isOpenShift: Boolean, namespace: Namespaces): Future[Watch] =
    Future(startOperator).flatMap {
      case Right(w) =>
        logger.info(
          s"${AnsiColors.re}Operator $operatorName${AnsiColors.xx} has been started in namespace '$namespace'"
        )
        Future.successful(w)
      case Left(e) =>
        logger.error(s"$operatorName in namespace ${namespace.value} failed to start", e)
        Future.failed(e)
    }

  private def startOperator: Either[Throwable, Watch] = operator.cfg.validate match {
    case Left(e) =>
      Left(new RuntimeException(s"Unable to initialize the operator correctly: $e"))
    case Right(()) =>
      logger.info(s"Starting $operatorName for namespace $namespace")
      onInit()
      startWatcher.map { w =>
        logger.info(
          s"${AnsiColors.gr}$operatorName running${AnsiColors.xx} for namespace ${if (AllNamespaces == namespace) "'all'"
          else namespace}"
        )
        w
      }
  }

  private def startWatcher = {
    val watch = operator.watcher(recreateWatcher)
    val maybeOldWatch = watcher.getAndSet(watch.toOption)
    maybeOldWatch.foreach { w =>
      logger.warn(s"Closing old watcher for $namespace namespace")
      w.close()
    }
    watch
  }

  protected def recreateWatcher(e: KubernetesClientException): Unit = {
    val w = Future(startWatcher)
    logger.info(s"${operator.watchName} watch recreated in namespace $namespace")

    w.failed.map { e: Throwable =>
      logger.error(s"Failed to recreate ${operator.watchName} watch in namespace $namespace", e)
    }
  }

  protected def onAdd(entity: T, metadata: Metadata): Unit =
    onAction(entity, metadata, operator.onAdd)

  protected def onDelete(entity: T, metadata: Metadata): Unit =
    onAction(entity, metadata, operator.onDelete)

  protected def onModify(entity: T, metadata: Metadata): Unit =
    onAction(entity, metadata, operator.onModify)

  private def onAction(entity: T, metadata: Metadata, handler: (T, Metadata) => Unit): Unit =
    handler(entity, metadata)

  private def onInit(): Unit =
    operator.onInit()
}
