package io.github.novakovalexey.k8soperator

import java.net.URL

import cats.effect.concurrent.MVar
import cats.effect.{ConcurrentEffect, ExitCode, Resource, Sync}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.utils.HttpClientUtils
import io.fabric8.kubernetes.client.{Watch, _}
import io.github.novakovalexey.k8soperator.common.AbstractOperator.getKind
import io.github.novakovalexey.k8soperator.common.AnsiColors._
import io.github.novakovalexey.k8soperator.common._
import io.github.novakovalexey.k8soperator.resource.Labels
import okhttp3.{HttpUrl, Request}

import scala.annotation.unused
import scala.util.Try

object Operator extends LazyLogging {

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

      if (success) logger.debug(s"$url returned ${response.code}. We are on OpenShift.")
      else logger.debug(s"$url returned ${response.code}. We are not on OpenShift. Assuming, we are on Kubernetes.")

      success
    }.fold(e => {
      logger.error("Failed to distinguish between Kubernetes and OpenShift", e)
      logger.warn("Let's assume we are on Kubernetes")
      false
    }, identity)

  def ofCrd[F[_], T](cfg: CrdConfig[T], client: KubernetesClient, controller: Controller[F, T])(
    implicit @unused F: ConcurrentEffect[F]
  ): Operator[F, T] =
    ofCrd[F, T](cfg, client)((_: CrdOperator[F, T]) => controller)

  def ofCrd[F[_], T](cfg: CrdConfig[T], client: KubernetesClient = new DefaultKubernetesClient())(
    controller: CrdOperator[F, T] => Controller[F, T]
  )(implicit F: ConcurrentEffect[F]): Operator[F, T] = {

    val pipeline = for {
      isOpenShift <- checkEnvAndConfig(client, cfg)
      crd <- CrdOperator.deployCrd(client, cfg, isOpenShift)
      m <- MVar[F].empty[OperatorAction[T]]
      op <- F.delay(new CrdOperator[F, T](cfg, client, isOpenShift, crd))
      ctl = controller(op)
      w <- F.delay(
        CustomResourceWatcher[F, T](
          cfg.namespace,
          AbstractOperator.getKind(cfg),
          ctl,
          CrdOperator.convertCr(cfg.forKind),
          m,
          client,
          crd
        ).watch
      )
    } yield createPipeline(op, ctl, w)

    new Operator[F, T](pipeline, client)
  }

  def ofConfigMap[F[_], T](
    controller: ConfigMapOperator[F, T] => ConfigMapController[F, T],
    cfg: ConfigMapConfig[T],
    client: KubernetesClient = new DefaultKubernetesClient()
  )(implicit F: ConcurrentEffect[F]): Operator[F, T] = {

    val pipeline = for {
      isOpenShift <- checkEnvAndConfig(client, cfg)
      m <- MVar[F].empty[OperatorAction[T]]
      op <- F.delay(new ConfigMapOperator[F, T](cfg, client, isOpenShift))
      ctl = controller(op)
      w <- F.delay(
        ConfigMapWatcher[F, T](
          cfg.namespace,
          getKind[T](cfg),
          ctl,
          client,
          Labels.forKind(getKind[T](cfg), cfg.prefix),
          ConfigMapOperator.convertCm(cfg.forKind),
          m
        ).watch
      )

    } yield createPipeline(op, ctl, w)

    new Operator[F, T](pipeline, client)
  }

  private def createPipeline[T, F[_]](op: AbstractOperator[F, T], ctl: Controller[F, T], w: F[(Watch, F[Unit])])(
    implicit F: ConcurrentEffect[F]
  ) =
    OperatorPipeline[F, T](op, w, F.defer(ctl.onInit()))

  private def checkEnvAndConfig[F[_]: Sync, T](client: KubernetesClient, cfg: OperatorCfg[T]): F[Boolean] =
    Sync[F].fromEither(cfg.validate.leftMap(new RuntimeException(_))) *> Sync[F].delay(
      Operator.checkIfOnOpenshift(client.getMasterUrl)
    )
}

private case class OperatorPipeline[F[_], T](
  operator: AbstractOperator[F, T],
  resources: F[(Watch, F[Unit])],
  onInit: F[Unit]
)

class Operator[F[_], T](pipeline: F[OperatorPipeline[F, T]], client: KubernetesClient)(implicit F: ConcurrentEffect[F])
    extends LazyLogging {

  trait StopHandler {
    def stop(): F[Unit]
  }

  def run: F[ExitCode] =
    Resource
      .make(start) {
        case (_, s) =>
          s.stop *> F.delay(logger.info(s"operator stopped"))
      }
      .use {
        case (channel, _) => channel.as(ExitCode.Success)
      }

  def start: F[(F[Unit], StopHandler)] =
    for {
      pipe <- pipeline

      name = AbstractOperator.getKind(pipe.operator.cfg)
      namespace = if (pipe.operator.cfg.namespace == CurrentNamespace) pipe.operator.clientNamespace
      else pipe.operator.cfg.namespace

      _ <- F.delay {
        if (pipe.operator.isOpenShift) logger.info(s"${ye}OpenShift$xx environment detected.")
        else logger.info(s"${ye}Kubernetes$xx environment detected.")
      }
      _ <- F.delay(logger.info(s"Starting operator $name for namespace $namespace"))
      _ <- pipe.onInit
      (watch, consumer) <- pipe.resources
      _ <- F
        .delay(
          logger
            .info(s"${re}Operator $name$xx was started in namespace '$namespace'")
        )
        .onError {
          case ex: Throwable =>
            F.delay(logger.error(s"Unable to start operator for $namespace namespace", ex))
        }
    } yield (consumer, stopHandler(watch))

  private def stopHandler(watch: Watch): StopHandler =
    () => F.delay(watch.close()) *> F.delay(client.close())
}
