package io.github.novakovalexey.k8soperator.common

import cats.effect.{Effect, Sync}
import cats.implicits._
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.novakovalexey.k8soperator._
import io.github.novakovalexey.k8soperator.common.AbstractOperator.getKind
import io.github.novakovalexey.k8soperator.common.crd.{CrdDeployer, InfoClass, InfoClassDoneable, InfoList}
import io.github.novakovalexey.k8soperator.resource.{ConfigMapParser, CrdParser, Labels}

import scala.jdk.CollectionConverters._

object AbstractOperator {
  def getKind[T](cfg: OperatorCfg[T]): String =
    cfg.customKind.getOrElse(cfg.forKind.getSimpleName)
}

sealed abstract class AbstractOperator[F[_]: Effect, T](
  val client: KubernetesClient,
  val cfg: OperatorCfg[T],
  val isOpenShift: Boolean
) {

  val kind: String = getKind[T](cfg)
  val clientNamespace: K8sNamespace = Namespace(client.getNamespace)
}

object ConfigMapOperator {
  def convertCm[T](kind: Class[T])(cm: ConfigMap): Either[Throwable, (T, Metadata)] =
    ConfigMapParser.parseCM(kind, cm)
}

class ConfigMapOperator[F[_]: Effect, T](cfg: ConfigMapConfig[T], client: KubernetesClient, isOpenShift: Boolean)
    extends AbstractOperator[F, T](client, cfg, isOpenShift) {

  val selector: Map[String, String] = Labels.forKind(getKind[T](cfg), cfg.prefix)

  def currentConfigMaps: Either[Throwable, Map[Metadata, T]] = {
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
      .map(ConfigMapOperator.convertCm(cfg.forKind)(_))
      //TODO: use cats Validated to aggregate all errors
      .sequence
      .map { l =>
        l.map { case (entity, meta) => meta -> entity }.toMap
      }
  }
}

object CrdOperator {

  def deployCrd[F[_]: Sync, T](
    client: KubernetesClient,
    cfg: CrdConfig[T],
    isOpenShift: Boolean
  ): F[CustomResourceDefinition] = CrdDeployer.initCrds[F, T](
    client,
    cfg.prefix,
    getKind[T](cfg),
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
  isOpenShift: Boolean,
  crd: CustomResourceDefinition,
  parser: CrdParser
) extends AbstractOperator[F, T](client, cfg, isOpenShift) {

  def currentResources: Either[Throwable, Map[Metadata, T]] = {
    val crds = {
      val _crds =
        client.customResources(crd, classOf[InfoClass[T]], classOf[InfoList[T]], classOf[InfoClassDoneable[T]])
      if (AllNamespaces == cfg.namespace) _crds.inAnyNamespace else _crds.inNamespace(cfg.namespace.value)
    }

    crds.list.getItems.asScala.toList
      .map(CrdOperator.convertCr(cfg.forKind, parser)(_))
      //TODO: use cats Validated to aggregate all errors
      .sequence
      .map { l =>
        l.map { case (entity, meta) => meta -> entity }.toMap
      }

  }
}
