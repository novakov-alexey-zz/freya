package io.github.novakovalexey.k8soperator4s.common

import java.util.concurrent.CompletableFuture

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client._
import io.github.novakovalexey.k8soperator4s.common.OperatorConfig.ALL_NAMESPACES
import io.github.novakovalexey.k8soperator4s.common.crd.{CrdDeployer, InfoClass, InfoClassDoneable, InfoList}
import io.github.novakovalexey.k8soperator4s.resource.LabelsHelper

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
class AbstractOperator[T: EntityInfo](
  operator: Operator[T],
  infoClass: Class[T],
  client: KubernetesClient,
  isOpenshift: Boolean = false
) extends LazyLogging {
  private val crdDeployer: CrdDeployer[T] = new CrdDeployer

  protected var entityName = ""
  protected var shortNames = List.empty[String]
  protected var pluralName: String = ""
  protected var enabled = true
  protected var additionalPrinterColumnNames = Array.empty[String]
  protected var additionalPrinterColumnPaths = Array.empty[String]
  protected var additionalPrinterColumnTypes = Array.empty[String]
  protected var fullReconciliationRun = false

  private var selector = Map.empty[String, String]
  private var operatorName = ""
  private var crd: CustomResourceDefinition = _
  private var watch: AbstractWatcher[T] = _

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
    onAction(entity, operator.namespace, operator.onAdd)

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
    operator.onDelete(entity, operator.namespace)

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
    operator.onDelete(entity, operator.namespace)
    operator.onAdd(entity, operator.namespace)
  }

  protected def onModify(entity: T, namespace: String): Unit = {
    onAction(entity, namespace, operator.onModify)
  }

  private def onAction(entity: T, namespace: String, handler: (T, String) => Unit): Unit = {
    handler(entity, namespace)
  }

  /**
   * Override this method to do arbitrary work before the operator starts listening on configmaps or custom resources.
   */
  protected def onInit(): Unit = {
    // no-op by default
  }

  /**
   * Override this method to do a full reconciliation.
   */
  def fullReconciliation(): Unit = {
    // no-op by default
  }

  /**
   * Implicitly only those configmaps with given prefix and kind are being watched, but you can provide additional
   * 'deep' checking in here.
   *
   * @param cm ConfigMap that is about to be checked
   * @return true if cm is the configmap we are interested in
   */
  protected def isSupported(cm: ConfigMap) = true

  /**
   * If true, start the watcher for this operator. Otherwise it's considered as disabled.
   *
   * @return enabled
   */
  def isEnabled: Boolean = enabled

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
  protected def convert(cm: ConfigMap): T = ConfigMapWatcher.defaultConvert(infoClass, cm)

  protected def convertCr(info: InfoClass[_]): T = CustomResourceWatcher.defaultConvert(infoClass, info)

  def getName: String = operatorName

  /**
   * Starts the operator and creates the watch
   *
   * @return CompletableFuture
   */
  def start: CompletableFuture[AbstractWatcher[T]] = {
    initInternals()
    selector = LabelsHelper.forKind(entityName, operator.prefix)
    val ok = checkIntegrity
    if (!ok) {
      logger.error("Unable to initialize the operator correctly, some mandatory fields are missing.")
      return CompletableFuture.completedFuture(null)
    }

    logger.info("Starting {} for namespace {}", operatorName, operator.namespace)
    if (operator.isCrd)
      crd = crdDeployer.initCrds(
        client,
        operator.prefix,
        entityName,
        shortNames,
        pluralName,
        additionalPrinterColumnNames,
        additionalPrinterColumnPaths,
        additionalPrinterColumnTypes,
        infoClass,
        isOpenshift
      )
    // onInit() can be overridden in child operators
    onInit()

    initializeWatcher
      .thenApply[AbstractWatcher[T]](w => {
        watch = w
        logger.info(
          s"${AnsiColors.gr}$operatorName running${AnsiColors.xx} for namespace ${Option(operator.namespace).getOrElse("'all'")}"
        )
        w
      })
      .exceptionally((e: Throwable) => {
        logger.error(s"$operatorName startup failed for namespace ${operator.namespace}", e.getCause)
        null
      })
  }

  private def initializeWatcher: CompletableFuture[AbstractWatcher[T]] = {
    if (operator.isCrd) {
      CustomResourceWatcher[T](operator.namespace, entityName, client, crd, onAdd, onDelete, onModify, convertCr)
        .watchF()
    } else {
      ConfigMapWatcher[T](
        operator.namespace,
        entityName,
        client,
        selector,
        onAdd,
        onDelete,
        onModify,
        isSupported,
        convert
      ).watchF()
    }
  }

  private def checkIntegrity = {
    var ok = infoClass != null
    ok = ok && entityName != null && !entityName.isEmpty
    ok = ok && operator.prefix != null && !operator.prefix.isEmpty //&& prefix.endsWith("/")
    ok = ok && operatorName != null && operatorName.endsWith("operator")
    ok = ok && additionalPrinterColumnNames == null || (additionalPrinterColumnPaths != null && (additionalPrinterColumnNames.length == additionalPrinterColumnPaths.length) && (additionalPrinterColumnTypes == null || additionalPrinterColumnNames.length == additionalPrinterColumnTypes.length))
    ok
  }

  private def initInternals()
    : Unit = { // prefer "named" for the entity name, otherwise "entityName" and finally the converted class name.
    if (operator.name != null && !operator.name.isEmpty) entityName = operator.name
    else if (entityName != null && !entityName.isEmpty) {
      // ok case
    } else if (infoClass != null) entityName = infoClass.getSimpleName
    else entityName = ""

    operatorName = s"'$entityName' operator"
  }

  def stop(): Unit = {
    logger.info(s"Stopping $operatorName for namespace ${operator.namespace}")
    watch.close()
    client.close()
  }

  /**
   * Call this method in the concrete operator to obtain the desired state of the system. This can be especially handy
   * during the fullReconciliation. Rule of thumb is that if you are overriding <code>fullReconciliation</code>, you
   * should also override this method and call it from <code>fullReconciliation()</code> to ensure that the real state
   * is the same as the desired state.
   *
   * @return returns the set of 'T's that correspond to the CMs or CRs that have been created in the K8s
   */
  protected def getDesiredSet: Set[T] = {
    if (operator.isCrd) {
      val crds = {
        val _crds =
          client.customResources(crd, classOf[InfoClass[T]], classOf[InfoList[T]], classOf[InfoClassDoneable[T]])
        if (ALL_NAMESPACES == operator.namespace) _crds.inAnyNamespace else _crds.inNamespace(operator.namespace)
      }

      crds.list.getItems.asScala.toList
      // ignore this CR if not convertible
        .flatMap(item => Try(Some(convertCr(item))).getOrElse(None))
        .toSet
    } else {
      val cms = {
        val _cms = client.configMaps
        if (ALL_NAMESPACES == operator.namespace) _cms.inAnyNamespace
        else _cms.inNamespace(operator.namespace)
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

  def setEntityName(entityName: String): Unit =
    this.entityName = entityName

  def setEnabled(enabled: Boolean): Unit =
    this.enabled = enabled

  def setFullReconciliationRun(fullReconciliationRun: Boolean): Unit = {
    this.fullReconciliationRun = fullReconciliationRun
    this.watch.setFullReconciliationRun(true)
  }
}
