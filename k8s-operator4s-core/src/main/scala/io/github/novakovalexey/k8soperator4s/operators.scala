package io.github.novakovalexey.k8soperator4s

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.{DefaultKubernetesClient, KubernetesClient, KubernetesClientException, Watch}
import io.github.novakovalexey.k8soperator4s.Operator.ReadClient
import io.github.novakovalexey.k8soperator4s.common._
import io.github.novakovalexey.k8soperator4s.common.crd.{CrdDeployer, InfoClass, InfoClassDoneable, InfoList}
import io.github.novakovalexey.k8soperator4s.resource.LabelsHelper

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._
import scala.util.Try

object Operator {
  type ReadClient = () => _ <: KubernetesClient
}

sealed abstract class Operator[T](readClient: ReadClient, val cfg: OperatorCfg[T])(
  implicit ex: ExecutionContext
) extends LazyLogging {

  private[k8soperator4s] val _client: KubernetesClient = readClient()
  protected val kind: String = cfg.customKind.getOrElse(cfg.forKind.getSimpleName)

  val isOpenShift: Boolean = Scheduler.checkIfOnOpenshift(_client)
  val clientNamespace: Namespaces = Namespace(_client.getNamespace)

  def onAdd(entity: T, metadata: Metadata): Unit = ()

  def onDelete(entity: T, metadata: Metadata): Unit = ()

  def onModify(entity: T, metadata: Metadata): Unit = ()

  def onInit(): Unit = ()

  def watcher(recreateWatcher: KubernetesClientException => Unit): Either[Throwable, Watch]

  def watchName: String
}

abstract class ConfigMapOperator[T](
  cfg: ConfigMapConfig[T],
  readClient: ReadClient = () => new DefaultKubernetesClient()
)(implicit ex: ExecutionContext)
    extends Operator[T](readClient, cfg) {

  private val selector = LabelsHelper.forKind(kind, cfg.prefix)

  protected def isSupported(cm: ConfigMap): Boolean = true

  def watchName: String = "ConfigMap"

  /**
   * Call this method in the concrete operator to obtain the desired state of the system.
   *
   * @return returns the set of 'T's that correspond to the CMs that have been created in the K8s
   */
  protected def currentConfigMaps: Map[Metadata, T] = {
    val cms = {
      val _cms = _client.configMaps
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

  override def watcher(recreateWatcher: KubernetesClientException => Unit): Either[Throwable, Watch] =
    Right(
      ConfigMapWatcher[T](
        cfg.namespace,
        kind,
        onAdd,
        onDelete,
        onModify,
        _client,
        selector,
        isSupported,
        convert,
        recreateWatcher
      ).watch
    )
}

abstract class CrdOperator[T](
  cfg: CrdConfig[T],
  readClient: ReadClient = () => new DefaultKubernetesClient()
)(implicit ex: ExecutionContext)
    extends Operator[T](readClient, cfg) {
  private val crdDeployer = new CrdDeployer[T]
  private val crd = deployCrd(cfg.asInstanceOf[CrdConfig[T]])

  /**
   * Call this method in the concrete operator to obtain the desired state of the system.
   *
   * @return returns the set of 'T's that correspond to the CRs that have been created in the K8s
   */
  protected def currentResources: Either[Throwable, Map[Metadata, T]] = crd.map { v =>
    val crds = {
      val _crds =
        _client.customResources(v, classOf[InfoClass[T]], classOf[InfoList[T]], classOf[InfoClassDoneable[T]])
      if (AllNamespaces == cfg.namespace) _crds.inAnyNamespace else _crds.inNamespace(cfg.namespace.value)
    }

    crds.list.getItems.asScala.toList
    // ignore this CR if not convertible
      .flatMap(i => Try(Option(convertCr(i))).getOrElse(None))
      .map { case (entity, meta) => meta -> entity }
      .toMap
  }

  def watchName: String = "CustomResource"

  protected def convertCr(info: InfoClass[_]): (T, Metadata) =
    (
      CustomResourceWatcher.defaultConvert(cfg.forKind, info),
      Metadata(info.getMetadata.getName, info.getMetadata.getNamespace)
    )

  private def deployCrd(crd: CrdConfig[T]) = crdDeployer.initCrds(
    _client,
    cfg.prefix,
    kind,
    crd.shortNames,
    crd.pluralName,
    crd.additionalPrinterColumns,
    cfg.forKind,
    isOpenShift
  )

  override def watcher(recreateWatcher: KubernetesClientException => Unit): Either[Throwable, Watch] =
    crd.map(
      v =>
        CustomResourceWatcher[T](cfg.namespace, kind, onAdd, onDelete, onModify, convertCr, _client, v, recreateWatcher).watch
    )

}
