package io.github.novakovalexey.k8soperator4s.common

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client._
import io.github.novakovalexey.k8soperator4s.common.OperatorConfig.ALL_NAMESPACES
import io.github.novakovalexey.k8soperator4s.common.crd.{CrdDeployer, InfoClass, InfoClassDoneable, InfoList}
import io.github.novakovalexey.k8soperator4s.resource.LabelsHelper

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * This abstract class represents the extension point of the abstract-operator library.
 * By extending this class and overriding the methods, you will be able to watch on the
 * config maps or custom resources you are interested in and handle the life-cycle of your
 * objects accordingly.
 *
 *
 * [T] info class that captures the configuration of the objects we are watching
 */
class AbstractOperator[T](
  operator: Operator[T],
  cfg: OperatorCfg[T],
  client: KubernetesClient,
  isOpenshift: Boolean = false
)(implicit ec: ExecutionContext)
    extends LazyLogging {

  private val crdDeployer: CrdDeployer[T] = new CrdDeployer[T]
  protected val kind: String = cfg.customKind.getOrElse(cfg.forKind.getSimpleName)
  val operatorName = s"'$kind' operator"

  protected var shortNames = List.empty[String]
  protected var pluralName: String = ""
  protected var additionalPrinterColumnNames = Array.empty[String]
  protected var additionalPrinterColumnPaths = Array.empty[String]
  protected var additionalPrinterColumnTypes = Array.empty[String]

  private val selector = LabelsHelper.forKind(kind, cfg.prefix)
  private var crd: CustomResourceDefinition = _

  /**
   * In this method, the user of the abstract-operator is assumed to handle the creation of
   * a new entity of type T. This method is called when the config map or custom resource with given
   * type is created.
   * The common use-case would be creating some new resources in the
   * Kubernetes cluster (using @see this.client), like replication controllers with pod specifications
   * and custom images and settings. But one can do arbitrary work here, like calling external APIs, etc.
   *
   * @param entity entity that represents the config map (or CR) that has just been created.
   *               The type of the entity is passed as a type parameter to this class.
   */
  protected def onAdd(entity: T): Unit =
    onAction(entity, cfg.namespace, operator.onAdd)

  /**
   * Override this method if you want to manually handle the case when it watches for the events in the all
   * namespaces (<code>WATCH_NAMESPACE="*"</code>).
   *
   * @param entity    entity that represents the config map (or CR) that has just been created.
   *                  *            The type of the entity is passed as a type parameter to this class.
   * @param namespace namespace in which the resources should be created.
   */
  protected def onAdd(entity: T, namespace: String): Unit =
    onAction(entity, namespace, operator.onAdd)

  /**
   * This method should handle the deletion of the resource that was represented by the config map or custom resource.
   * The method is called when the corresponding config map or custom resource is deleted in the Kubernetes cluster.
   * Some suggestion what to do here would be: cleaning the resources, deleting some resources in K8s, etc.
   *
   * @param entity entity that represents the config map or custom resource that has just been created.
   *               The type of the entity is passed as a type parameter to this class.
   */
  protected def onDelete(entity: T): Unit =
    operator.onDelete(entity, cfg.namespace)

  protected def onDelete(entity: T, namespace: String): Unit =
    onAction(entity, namespace, operator.onDelete)

  /**
   * It's called when one modifies the configmap of type 'T' (that passes <code>isSupported</code> check) or custom resource.
   * If this method is not overriden, the implicit behavior is calling <code>onDelete</code> and <code>onAdd</code>.
   *
   * @param entity entity that represents the config map or custom resource that has just been created.
   *               The type of the entity is passed as a type parameter to this class.
   */
  protected def onModify(entity: T): Unit = {
    operator.onDelete(entity, cfg.namespace)
    operator.onAdd(entity, cfg.namespace)
  }

  protected def onModify(entity: T, namespace: String): Unit =
    onAction(entity, namespace, operator.onModify)

  private def onAction(entity: T, namespace: String, handler: (T, String) => Unit): Unit =
    handler(entity, namespace)

  /**
   * Override this method to do arbitrary work before the operator starts listening on configmaps or custom resources.
   */
  protected def onInit(): Unit =
    operator.onInit()

  /**
   * Implicitly only those configmaps with given prefix and kind are being watched, but you can provide additional
   * 'deep' checking in here.
   *
   * @param cm ConfigMap that is about to be checked
   * @return true if cm is the configmap we are interested in
   */
  protected def isSupported(cm: ConfigMap) = true

  /**
   * Converts the configmap representation into T.
   * Normally, you may want to call something like:
   *
   * <code>HasDataHelper.parseCM(FooBar.class, cm);</code> in this method, where FooBar is of type T.
   * This would parse the yaml representation of the configmap's config section and creates an object of type T.
   *
   * @param cm ConfigMap that is about to be converted to T
   * @return entity of type T
   */
  protected def convert(cm: ConfigMap): (T, Metadata) = ConfigMapWatcher.defaultConvert(cfg.forKind, cm)

  protected def convertCr(info: InfoClass[_]): (T, Metadata) =
    (
      CustomResourceWatcher.defaultConvert(cfg.forKind, info),
      Metadata(info.getMetadata.getName, info.getMetadata.getNamespace)
    )

  /**
   * Starts the operator and creates the watch
   *
   */
  def start: Watch = {
    val ok = checkIntegrity
    if (!ok) {
      val msg = "Unable to initialize the operator correctly, some mandatory fields are missing."
      logger.error(msg)
      throw new RuntimeException(msg)
    }

    logger.info(s"Starting $operatorName for namespace ${cfg.namespace}")

    if (cfg.crd)
      crd = crdDeployer.initCrds(
        client,
        cfg.prefix,
        kind,
        shortNames,
        pluralName,
        additionalPrinterColumnNames,
        additionalPrinterColumnPaths,
        additionalPrinterColumnTypes,
        cfg.forKind,
        isOpenshift
      )

    onInit()

    val watch = startWatcher

    logger.info(
      s"${AnsiColors.gr}$operatorName running${AnsiColors.xx} for namespace ${Option(cfg.namespace).getOrElse("'all'")}"
    )

    watch
  }

  private def startWatcher: Watch =
    if (cfg.crd) {
      CustomResourceWatcher[T](cfg.namespace, kind, onAdd, onDelete, onModify, convertCr, client, crd).watch
    } else {
      ConfigMapWatcher[T](cfg.namespace, kind, onAdd, onDelete, onModify, client, selector, isSupported, convert).watch
    }

  protected def recreateWatcher(): Watch = {
    val crdOrCm = if (cfg.crd) "CustomResource" else "ConfigMap"
    val w = startWatcher
    logger.info("{} watch recreated in namespace {}", crdOrCm, cfg.namespace)

//      .failed
//      .map((e: Throwable) => {
//        logger.error("Failed to recreate {} watch in namespace {}", crdOrCm, cfg.namespace)
//        null
//      })
    w
  }

  private def checkIntegrity = {
    var ok = cfg.forKind != null
    ok = ok && kind != null && !kind.isEmpty
    ok = ok && cfg.prefix != null && !cfg.prefix.isEmpty
    ok = ok && additionalPrinterColumnNames == null || (additionalPrinterColumnPaths != null
    && (additionalPrinterColumnNames.length == additionalPrinterColumnPaths.length)
    && (additionalPrinterColumnTypes == null || additionalPrinterColumnNames.length == additionalPrinterColumnTypes.length))
    ok
  }

  /**
   * Call this method in the concrete operator to obtain the desired state of the system. This can be especially handy
   * during the fullReconciliation. Rule of thumb is that if you are overriding <code>fullReconciliation</code>, you
   * should also override this method and call it from <code>fullReconciliation()</code> to ensure that the real state
   * is the same as the desired state.
   *
   * @return returns the set of 'T's that correspond to the CMs or CRs that have been created in the K8s
   */
  protected def getDesiredSet: Set[(T, Metadata)] = {
    if (cfg.crd) {
      val crds = {
        val _crds =
          client.customResources(crd, classOf[InfoClass[T]], classOf[InfoList[T]], classOf[InfoClassDoneable[T]])
        if (ALL_NAMESPACES == cfg.namespace) _crds.inAnyNamespace else _crds.inNamespace(cfg.namespace)
      }

      crds.list.getItems.asScala.toList
      // ignore this CR if not convertible
        .flatMap(i => Try(Some(convertCr(i))).getOrElse(None))
        .toSet
    } else {
      val cms = {
        val _cms = client.configMaps
        if (ALL_NAMESPACES == cfg.namespace) _cms.inAnyNamespace
        else _cms.inNamespace(cfg.namespace)
      }

      cms
        .withLabels(selector.asJava)
        .list
        .getItems
        .asScala
        .toList
        // ignore this CM if not convertible
        .flatMap(item => Try(Some(convert(item))).getOrElse(None))
        .toSet
    }
  }
}
