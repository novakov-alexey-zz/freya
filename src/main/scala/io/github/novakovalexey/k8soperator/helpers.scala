package io.github.novakovalexey.k8soperator

import cats.effect.Effect
import cats.implicits._
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.novakovalexey.k8soperator.internal.api.{ConfigMapApi, CrdApi}
import io.github.novakovalexey.k8soperator.internal.resource.{ConfigMapParser, CrdParser, Labels}
import io.github.novakovalexey.k8soperator.watcher.InfoClass

import scala.annotation.unused

sealed abstract class AbstractHelper[F[_]: Effect, T](val client: KubernetesClient, val cfg: OperatorCfg[T]) {
  val kind: String = cfg.getKind
  val clientNamespace: K8sNamespace = Namespace(client.getNamespace)
}

object ConfigMapHelper {
  def convertCm[T](kind: Class[T], parser: ConfigMapParser)(cm: ConfigMap): Either[Throwable, (T, Metadata)] =
    parser.parseCM(kind, cm)
}

class ConfigMapHelper[F[_]: Effect, T](
  cfg: ConfigMapConfig[T],
  client: KubernetesClient,
  @unused isOpenShift: Option[Boolean],
  parser: ConfigMapParser
) extends AbstractHelper[F, T](client, cfg) {

  val selector: Map[String, String] = Labels.forKind(cfg.getKind, cfg.prefix)
  private val cmApi = new ConfigMapApi(client)

  def currentConfigMaps: Either[List[Throwable], Map[Metadata, T]] = {
    val cms = cmApi.in(cfg.namespace)

    cmApi
      .list(cms, selector)
      .map(ConfigMapHelper.convertCm(cfg.forKind, parser)(_).toValidatedNec)
      .sequence
      .map(_.map { case (entity, meta) => meta -> entity }.toMap)
      .toEither
      .leftMap(_.toList)
  }
}

object CrdHelper {

  def convertCr[T](kind: Class[T], parser: CrdParser)(info: InfoClass[T]): Either[Throwable, (T, Metadata)] =
    for {
      spec <- parser.parse(kind, info)
      meta <- Right(Metadata(info.getMetadata.getName, info.getMetadata.getNamespace))
    } yield (spec, meta)
}

class CrdHelper[F[_]: Effect, T](
  cfg: CrdConfig[T],
  client: KubernetesClient,
  @unused val isOpenShift: Option[Boolean],
  val crd: CustomResourceDefinition,
  val parser: CrdParser
) extends AbstractHelper[F, T](client, cfg) {
  private val crdApi = new CrdApi(client)

  def currentResources: Either[List[Throwable], Map[Metadata, T]] = {
    val crds = crdApi.in[T](cfg.namespace, crd)

    crdApi
      .list(crds)
      .map(CrdHelper.convertCr(cfg.forKind, parser)(_).toValidatedNec)
      .sequence
      .map(_.map { case (entity, meta) => meta -> entity }.toMap)
      .toEither
      .leftMap(_.toList)
  }
}
