package io.github.novakovalexey.k8soperator.common

import cats.effect.{Effect, Sync}
import cats.implicits._
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.novakovalexey.k8soperator._
import io.github.novakovalexey.k8soperator.internal.crd.{CrdDeployer, InfoClassDoneable, InfoList}
import io.github.novakovalexey.k8soperator.internal.resource.{ConfigMapParser, CrdParser, Labels}
import io.github.novakovalexey.k8soperator.watcher.InfoClass

import scala.annotation.unused
import scala.jdk.CollectionConverters._

sealed abstract class AbstractOperator[F[_]: Effect, T](val client: KubernetesClient, val cfg: OperatorCfg[T]) {
  val kind: String = cfg.getKind
  val clientNamespace: K8sNamespace = Namespace(client.getNamespace)
}

object ConfigMapOperator {
  def convertCm[T](kind: Class[T], parser: ConfigMapParser)(cm: ConfigMap): Either[Throwable, (T, Metadata)] =
    parser.parseCM(kind, cm)
}

class ConfigMapOperator[F[_]: Effect, T](
  cfg: ConfigMapConfig[T],
  client: KubernetesClient,
  @unused isOpenShift: Option[Boolean],
  parser: ConfigMapParser
) extends AbstractOperator[F, T](client, cfg) {

  val selector: Map[String, String] = Labels.forKind(cfg.getKind, cfg.prefix)

  def currentConfigMaps: Either[List[Throwable], Map[Metadata, T]] = {
    val cms = {
      val _cms = client.configMaps
      if (AllNamespaces == cfg.namespace) _cms.inAnyNamespace
      else _cms.inNamespace(cfg.namespace.value)
    }

    cms
      .withLabels(selector.asJava)
      .list
      .getItems
      .asScala
      .toList
      .map(ConfigMapOperator.convertCm(cfg.forKind, parser)(_).toValidatedNec)
      .sequence
      .map(_.map { case (entity, meta) => meta -> entity }.toMap)
      .toEither
      .leftMap(_.toList)
  }
}

object CrdOperator {

  def deployCrd[F[_]: Sync, T](
    client: KubernetesClient,
    cfg: CrdConfig[T],
    isOpenShift: Option[Boolean]
  ): F[CustomResourceDefinition] = CrdDeployer.initCrds[F, T](
    client,
    cfg.prefix,
    cfg.getKind,
    cfg.shortNames,
    cfg.pluralName,
    cfg.additionalPrinterColumns,
    cfg.forKind,
    isOpenShift
  )

  def convertCr[T](kind: Class[T], parser: CrdParser)(info: InfoClass[T]): Either[Throwable, (T, Metadata)] =
    for {
      spec <- parser.parse(kind, info)
      meta <- Right(Metadata(info.getMetadata.getName, info.getMetadata.getNamespace))
    } yield (spec, meta)
}

class CrdOperator[F[_]: Effect, T](
  cfg: CrdConfig[T],
  client: KubernetesClient,
  @unused val isOpenShift: Option[Boolean],
  val crd: CustomResourceDefinition,
  val parser: CrdParser
) extends AbstractOperator[F, T](client, cfg) {

  def currentResources: Either[List[Throwable], Map[Metadata, T]] = {
    val crds = {
      val _crds =
        client.customResources(crd, classOf[InfoClass[T]], classOf[InfoList[T]], classOf[InfoClassDoneable[T]])
      if (AllNamespaces == cfg.namespace) _crds.inAnyNamespace else _crds.inNamespace(cfg.namespace.value)
    }

    crds.list.getItems.asScala.toList
      .map(CrdOperator.convertCr(cfg.forKind, parser)(_).toValidatedNec)
      .sequence
      .map(_.map { case (entity, meta) => meta -> entity }.toMap)
      .toEither
      .leftMap(_.toList)
  }
}
