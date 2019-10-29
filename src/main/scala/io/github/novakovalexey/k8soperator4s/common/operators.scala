package io.github.novakovalexey.k8soperator4s.common

import cats.effect.{Effect, Sync}
import fs2.Stream
import fs2.concurrent.Queue
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.{KubernetesClient, Watch}
import io.github.novakovalexey.k8soperator4s.{ConfigMapController, _}
import io.github.novakovalexey.k8soperator4s.common.AbstractOperator.getKind
import io.github.novakovalexey.k8soperator4s.common.crd.{CrdDeployer, InfoClass, InfoClassDoneable, InfoList}
import io.github.novakovalexey.k8soperator4s.resource.{ConfigMapParser, CrdParser, Labels}

import scala.jdk.CollectionConverters._
import scala.util.Try

object AbstractOperator {
  def getKind[T](cfg: OperatorCfg[T]): String =
    cfg.customKind.getOrElse(cfg.forKind.getSimpleName)
}

sealed abstract class AbstractOperator[F[_]: Effect, T](
  val client: KubernetesClient,
  val cfg: OperatorCfg[T],
  val isOpenShift: Boolean
)  {

  protected val kind: String = getKind[T](cfg)

  val clientNamespace: Namespaces = Namespace(client.getNamespace)

  def close(): Unit = client.close()

  def onInit(): F[Unit]

  def watch: F[(Watch, Stream[F, Unit])]
}

class ConfigMapOperator[F[_]: Effect, T](
  controller: ConfigMapController[F, T],
  cfg: ConfigMapConfig[T],
  client: KubernetesClient,
  isOpenShift: Boolean,
  q: Queue[F, OperatorEvent[T]]
) extends AbstractOperator[F, T](client, cfg, isOpenShift) {

  private val selector = Labels.forKind(kind, cfg.prefix)

  protected def currentConfigMaps: Map[Metadata, T] = {
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
      // ignore this CM, if it is not convertible
      .flatMap(item => Try(Option(convert(item))).getOrElse(None))
      .map { case (entity, meta) => meta -> entity }
      .toMap
  }

  protected def convert(cm: ConfigMap): (T, Metadata) =
    ConfigMapParser.parseCM(cfg.forKind, cm)

  override def onInit(): F[Unit] = controller.onInit()

  def watch: F[(Watch, Stream[F, Unit])] =
    ConfigMapWatcher[F, T](cfg.namespace, kind, controller, client, selector, convert, q).watch
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
}

class CrdOperator[F[_]: Effect, T](
  controller: CrdController[F, T],
  cfg: CrdConfig[T],
  client: KubernetesClient,
  isOpenShift: Boolean,
  crd: CustomResourceDefinition,
  q: Queue[F, OperatorEvent[T]]
) extends AbstractOperator[F, T](client, cfg, isOpenShift) {

  protected def currentResources: Map[Metadata, T] = {
    val crds = {
      val _crds =
        client.customResources(crd, classOf[InfoClass[T]], classOf[InfoList[T]], classOf[InfoClassDoneable[T]])
      if (AllNamespaces == cfg.namespace) _crds.inAnyNamespace else _crds.inNamespace(cfg.namespace.value)
    }

    crds.list.getItems.asScala.toList
    // ignore this CRD, if it is not convertible
      .flatMap(i => Try(Option(convertCr(i))).getOrElse(None))
      .map { case (entity, meta) => meta -> entity }
      .toMap
  }

  protected def convertCr(info: InfoClass[_]): (T, Metadata) =
    (CrdParser.parse(cfg.forKind, info), Metadata(info.getMetadata.getName, info.getMetadata.getNamespace))

  override def onInit(): F[Unit] = controller.onInit()

  override def watch: F[(Watch, Stream[F, Unit])] =
    CustomResourceWatcher[F, T](cfg.namespace, getKind(cfg), controller, convertCr, q, client, crd).watch
}
