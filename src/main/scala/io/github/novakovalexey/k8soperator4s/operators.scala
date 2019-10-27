package io.github.novakovalexey.k8soperator4s

import cats.effect.{Effect, Sync}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import fs2.concurrent.Queue
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.{KubernetesClient, Watch}
import io.github.novakovalexey.k8soperator4s.common._
import io.github.novakovalexey.k8soperator4s.common.crd.{CrdDeployer, InfoClass, InfoClassDoneable, InfoList}
import io.github.novakovalexey.k8soperator4s.resource.LabelsHelper

import scala.annotation.unused
import scala.jdk.CollectionConverters._
import scala.util.Try

abstract class Operator[F[_]: Sync, T] {
  def onAdd(@unused entity: T, @unused metadata: Metadata): F[Unit] =
    Sync[F].unit

  def onDelete(@unused entity: T, @unused metadata: Metadata): F[Unit] =
    Sync[F].unit

  def onModify(@unused entity: T, @unused metadata: Metadata): F[Unit] =
    Sync[F].unit

  def onInit(): F[Unit] =
    Sync[F].unit
}

sealed abstract class AbstractOperator[F[_]: Effect, T](
  val handler: Operator[F, T],
  private[k8soperator4s] val client: KubernetesClient,
  val cfg: OperatorCfg[T]
) extends LazyLogging {
  protected val kind: String = cfg.customKind.getOrElse(cfg.forKind.getSimpleName)

  //TODO: side-effect
  val isOpenShift: Boolean = Scheduler.checkIfOnOpenshift(client.getMasterUrl)
  val clientNamespace: Namespaces = Namespace(client.getNamespace)

  val watchName: String

  protected[k8soperator4s] def onAdd(entity: T, metadata: Metadata): F[Unit] =
    handler.onAdd(entity, metadata)

  protected[k8soperator4s] def onDelete(entity: T, metadata: Metadata): F[Unit] =
    handler.onDelete(entity, metadata)

  protected[k8soperator4s] def onModify(entity: T, metadata: Metadata): F[Unit] =
    handler.onModify(entity, metadata)

  protected[k8soperator4s] def onInit(): F[Unit] =
    handler.onInit()

  protected[k8soperator4s] def makeWatcher(q: Queue[F, OperatorEvent[T]]): F[(Watch, fs2.Stream[F, Unit])]

  //TODO: who is calling this?
  def close(): Unit = client.close()
}

class ConfigMapOperator[F[_]: Effect, T](operator: Operator[F, T], cfg: ConfigMapConfig[T], client: KubernetesClient)
    extends AbstractOperator[F, T](operator, client, cfg) {

  private val selector = LabelsHelper.forKind(kind, cfg.prefix)

  protected def isSupported(@unused cm: ConfigMap): Boolean = true

  val watchName: String = "ConfigMap"

  /**
   * Call this method in the concrete operator to obtain the desired state of the system.
   *
   * @return returns the set of 'T's that correspond to the CMs that have been created in the K8s
   */
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
      // ignore this CM if not convertible
      .flatMap(item => Try(Option(convert(item))).getOrElse(None))
      .map { case (entity, meta) => meta -> entity }
      .toMap
  }

  protected def convert(cm: ConfigMap): (T, Metadata) =
    ConfigMapWatcher.defaultConvert(cfg.forKind, cm)

  override protected[k8soperator4s] def makeWatcher(q: Queue[F, OperatorEvent[T]]): F[(Watch, fs2.Stream[F, Unit])] =
    ConfigMapWatcher[F, T](cfg.namespace, kind, operator, client, selector, isSupported, convert, q).watch

}

class CrdOperator[F[_]: Effect, T](handler: Operator[F, T], cfg: CrdConfig[T], client: KubernetesClient)
    extends AbstractOperator[F, T](handler, client, cfg) {

  //TODO: side-effect
  private val crd = deployCrd(cfg)

  val watchName: String = "CustomResource"

  /**
   * Call this method in the concrete operator to obtain the desired state of the system.
   *
   * @return returns the set of 'T's that correspond to the CRs that have been created in the K8s
   */
  protected def currentResources: Either[Throwable, Map[Metadata, T]] = crd.map { v =>
    val crds = {
      val _crds =
        client.customResources(v, classOf[InfoClass[T]], classOf[InfoList[T]], classOf[InfoClassDoneable[T]])
      if (AllNamespaces == cfg.namespace) _crds.inAnyNamespace else _crds.inNamespace(cfg.namespace.value)
    }

    crds.list.getItems.asScala.toList
    // ignore this CR if not convertible
      .flatMap(i => Try(Option(convertCr(i))).getOrElse(None))
      .map { case (entity, meta) => meta -> entity }
      .toMap
  }

  protected def convertCr(info: InfoClass[_]): (T, Metadata) =
    (
      CustomResourceWatcher.defaultConvert(cfg.forKind, info),
      Metadata(info.getMetadata.getName, info.getMetadata.getNamespace)
    )

  private def deployCrd(crd: CrdConfig[T]) = CrdDeployer.initCrds[T](
    client,
    cfg.prefix,
    kind,
    crd.shortNames,
    crd.pluralName,
    crd.additionalPrinterColumns,
    cfg.forKind,
    isOpenShift
  )

  override protected[k8soperator4s] def makeWatcher(q: Queue[F, OperatorEvent[T]]): F[(Watch, fs2.Stream[F, Unit])] =
    for {
      c <- Sync[F].fromEither(crd)
      w <- CustomResourceWatcher[F, T](cfg.namespace, kind, handler, convertCr, q, client, c).watch
    } yield w

}
