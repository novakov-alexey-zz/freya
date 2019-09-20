package io.github.novakovalexey.k8soperator4s

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException, Watch}
import io.github.novakovalexey.k8soperator4s.common._
import io.github.novakovalexey.k8soperator4s.common.crd.{CrdDeployer, InfoClass, InfoClassDoneable, InfoList}
import io.github.novakovalexey.k8soperator4s.resource.LabelsHelper

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._
import scala.util.Try

sealed abstract class Operator[T](client: KubernetesClient, val cfg: OperatorCfg[T])(implicit ex: ExecutionContext)
    extends LazyLogging {
  protected val isOpenShift: Boolean = Scheduler.checkIfOnOpenshift(client)
  protected val kind: String = cfg.customKind.getOrElse(cfg.forKind.getSimpleName)

  def onAdd(entity: T, metadata: Metadata): Unit = ()

  def onDelete(entity: T, metadata: Metadata): Unit = ()

  def onModify(entity: T, metadata: Metadata): Unit = ()

  def onInit(): Unit = ()

  def watcher(recreateWatcher: KubernetesClientException => Unit): Either[Throwable, Watch]

  def watchName: String
}

abstract class ConfigMapOperator[T](client: KubernetesClient, cfg: ConfigMapConfig[T])(implicit ex: ExecutionContext)
    extends Operator[T](client, cfg) {

  private val selector = LabelsHelper.forKind(kind, cfg.prefix)

  protected def isSupported(cm: ConfigMap): Boolean = true

  def watchName: String = "ConfigMap"

  /**
   * Call this method in the concrete operator to obtain the desired state of the system.
   *
   * @return returns the set of 'T's that correspond to the CMs that have been created in the K8s
   */
  protected def getDesiredSet: Set[(T, Metadata)] = {
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
      .toSet
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
        client,
        selector,
        isSupported,
        convert,
        recreateWatcher
      ).watch
    )
}

abstract class CrdOperator[T](client: KubernetesClient, cfg: CrdConfig[T])(implicit ex: ExecutionContext)
    extends Operator[T](client, cfg) {
  private val crdDeployer = new CrdDeployer[T]
  private val crd = deployCrd(cfg.asInstanceOf[CrdConfig[T]])

  /**
   * Call this method in the concrete operator to obtain the desired state of the system.
   *
   * @return returns the set of 'T's that correspond to the CRs that have been created in the K8s
   */
  protected def getDesiredSet: Either[Throwable, Set[(T, Metadata)]] = crd.map { v =>
    val crds = {
      val _crds =
        client.customResources(v, classOf[InfoClass[T]], classOf[InfoList[T]], classOf[InfoClassDoneable[T]])
      if (AllNamespaces == cfg.namespace) _crds.inAnyNamespace else _crds.inNamespace(cfg.namespace.value)
    }

    crds.list.getItems.asScala.toList
    // ignore this CR if not convertible
      .flatMap(i => Try(Option(convertCr(i))).getOrElse(None))
      .toSet
  }

  def watchName: String = "CustomResource"

  protected def convertCr(info: InfoClass[_]): (T, Metadata) =
    (
      CustomResourceWatcher.defaultConvert(cfg.forKind, info),
      Metadata(info.getMetadata.getName, info.getMetadata.getNamespace)
    )

  private def deployCrd(crd: CrdConfig[T]) = crdDeployer.initCrds(
    client,
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
        CustomResourceWatcher[T](cfg.namespace, kind, onAdd, onDelete, onModify, convertCr, client, v, recreateWatcher).watch
    )

}
