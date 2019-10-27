package io.github.novakovalexey.k8soperator4s

import java.net.URL

import cats.effect.{ConcurrentEffect, ExitCode, Resource}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import fs2.Stream
import fs2.concurrent.Queue
import io.fabric8.kubernetes.client._
import io.fabric8.kubernetes.client.utils.HttpClientUtils
import io.github.novakovalexey.k8soperator4s.common.AnsiColors._
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

  def ofCrd[F[_], T](
    operator: Operator[F, T],
    cfg: CrdConfig[T],
    client: KubernetesClient = new DefaultKubernetesClient()
  )(implicit F: ConcurrentEffect[F]): Scheduler[F, T] =
    new Scheduler[F, T](new CrdOperator[F, T](operator, cfg, client))

  def ofConfigMap[F[_], T](
    operator: Operator[F, T],
    cfg: ConfigMapConfig[T],
    client: KubernetesClient = new DefaultKubernetesClient()
  )(implicit F: ConcurrentEffect[F]): Scheduler[F, T] =
    new Scheduler[F, T](new ConfigMapOperator[F, T](operator, cfg, client))
}

private[k8soperator4s] class Scheduler[F[_], T](operator: AbstractOperator[F, T])(implicit F: ConcurrentEffect[F])
    extends LazyLogging {

  trait StopHandler {
    def stop(): F[Unit]
  }

  private val operatorName = s"'${operator.cfg.customKind.getOrElse(operator.cfg.forKind.getSimpleName)}' operator"
  private val namespace =
    if (operator.cfg.namespace == CurrentNamespace) operator.clientNamespace
    else operator.cfg.namespace

  def run: F[ExitCode] =
    Resource
      .make(start) {
        case (_, s) =>
          s.stop *> F.delay(println("Operator stopped"))
      }
      .use {
        case (s, _) =>
          s.compile.drain.as(ExitCode.Success)
      }

  def start: F[(Stream[F, Unit], StopHandler)] =
    for {
      _ <- F.delay {
        if (operator.isOpenShift) logger.info(s"${ye}OpenShift$xx environment detected.")
        else logger.info(s"${ye}Kubernetes$xx environment detected.")
      }
      watchAndStream <- runForNamespace.onError {
        case ex: Throwable =>
          F.delay(logger.error(s"Unable to start operator for $namespace namespace", ex))
      }
      (watch, stream) = watchAndStream

    } yield (stream, () => F.delay(watch.close()))

  private def runForNamespace: F[(Watch, Stream[F, Unit])] =
    F.defer(startOperator) <* F
      .delay(
        logger
          .info(s"${re}Operator $operatorName$xx has been started in namespace '$namespace'")
      )
      .onError {
        case e: Throwable =>
          F.delay(logger.error(s"$operatorName in namespace $namespace failed to start", e))
      }

  private def startOperator: F[(Watch, Stream[F, Unit])] = operator.cfg.validate match {
    case Left(e) =>
      F.raiseError(new RuntimeException(s"Unable to initialize the operator correctly: $e"))
    case Right(()) =>
      for {
        _ <- F.delay(logger.info(s"Starting $operatorName for namespace $namespace"))
        _ <- onInit()
        watchAndStream <- startWatcher
        _ <- F.delay(logger.info(s"$gr$operatorName running$xx for namespace $namespace"))
      } yield watchAndStream
  }

  private def startWatcher: F[(Watch, Stream[F, Unit])] =
    for {
      q <- Queue.unbounded[F, OperatorEvent[T]]
      watchAndStream <- operator.makeWatcher(q)
    } yield watchAndStream

  private def onInit(): F[Unit] =
    operator.onInit()
}
