package io.github.novakovalexey.k8soperator4s

import java.net.URL
import java.util.concurrent.atomic.AtomicReference

import cats.effect.ConcurrentEffect
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.utils.HttpClientUtils
import io.fabric8.kubernetes.client.{ConfigBuilder, KubernetesClientException, Watch}
import io.github.novakovalexey.k8soperator4s.common._
import okhttp3.{HttpUrl, Request}

import scala.util.Try

object Scheduler extends LazyLogging {
  def checkIfOnOpenshift(masterURL: URL): Boolean =
    Try {
      val urlBuilder = new HttpUrl.Builder().host(masterURL.getHost)

      if (masterURL.getPort == -1) urlBuilder.port(masterURL.getDefaultPort)
      else urlBuilder.port(masterURL.getPort)

      if (masterURL.getProtocol == "https") urlBuilder.scheme("https")

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

class Scheduler[F[_], T](operator: AbstractOperator[F, T])(implicit F: ConcurrentEffect[F]) extends LazyLogging {

  private val watcher: AtomicReference[Option[Watch]] = new AtomicReference(None)

  private val kind = operator.cfg.customKind.getOrElse(operator.cfg.forKind.getSimpleName)
  private val operatorName = s"'$kind' operator"
  private val namespace =
    if (operator.cfg.namespace == CurrentNamespace) operator.clientNamespace
    else operator.cfg.namespace

  def start(): F[Watch] =
    F.delay {
      if (operator.isOpenShift) logger.info(s"${AnsiColors.ye}OpenShift${AnsiColors.xx} environment detected.")
      else logger.info(s"${AnsiColors.ye}Kubernetes${AnsiColors.xx} environment detected.")
    } *>
      runForNamespace(namespace).onError {
        case ex: Throwable =>
          logger.error(s"Unable to start operator for $namespace namespace", ex)
          F.unit
      }

  def stop(): F[Unit] =
    watcher.getAndSet(None) match {
      case Some(w) =>
        F.delay(logger.info(s"Stopping '$operatorName' for namespace '$namespace'")) *>
          F.delay(w.close())
      case None =>
        F.delay(logger.debug(s"No watcher to close for '$operatorName' with namespace '$namespace'"))
    }

  private def runForNamespace(namespace: Namespaces): F[Watch] =
    F.defer(startOperator) <* F
      .delay(
        logger
          .info(s"${AnsiColors.re}Operator $operatorName${AnsiColors.xx} has been started in namespace '$namespace'")
      )
      .onError {
        case e: Throwable =>
          F.delay(logger.error(s"$operatorName in namespace ${namespace.value} failed to start", e))
      }

  private def startOperator: F[Watch] = operator.cfg.validate match {
    case Left(e) =>
      F.raiseError(new RuntimeException(s"Unable to initialize the operator correctly: $e"))
    case Right(()) =>
      F.delay(logger.info(s"Starting $operatorName for namespace $namespace")) *>
        onInit() *>
        startWatcher <* F.delay(
        logger.info(
          s"${AnsiColors.gr}$operatorName running${AnsiColors.xx} for namespace ${if (AllNamespaces == namespace) "'all'"
          else namespace}"
        )
      )
  }

  private def startWatcher: F[Watch] =
    for {
      w <- operator.watcher(recreateWatcher)
      oldWatch <- F.delay(watcher.getAndSet(w.some))
      _ <- oldWatch match {
        case Some(old) => F.delay(logger.warn(s"Closing old watcher for $namespace namespace")) *> F.delay(old.close())
        case None => F.unit
      }
    } yield w

  protected def recreateWatcher(e: KubernetesClientException): F[Unit] =
    for {
      _ <- F.delay(logger.error(s"Recreating watcher due to error: $e"))
      _ <- startWatcher
      _ <- F.delay(logger.info(s"${operator.watchName} watch recreated in namespace $namespace")).onError {
        case e: Throwable =>
          F.delay(logger.error(s"Failed to recreate ${operator.watchName} watch in namespace $namespace", e))
      }
    } yield ()

  private def onInit(): F[Unit] =
    operator.onInit()
}
