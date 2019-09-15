package io.github.novakovalexey.k8soperator4s

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.utils.HttpClientUtils
import io.fabric8.kubernetes.client.{ConfigBuilder, KubernetesClient, KubernetesClientException, Watch}
import io.github.novakovalexey.k8soperator4s.common._
import io.github.novakovalexey.k8soperator4s.common.crd.{CrdDeployer, InfoClass, InfoClassDoneable, InfoList}
import io.github.novakovalexey.k8soperator4s.resource.LabelsHelper
import okhttp3.{HttpUrl, Request}

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.Try

object Scheduler extends LazyLogging {
  def checkIfOnOpenshift(client: KubernetesClient): Boolean = {
    Try {
      val kubernetesApi = client.getMasterUrl
      val urlBuilder = new HttpUrl.Builder().host(kubernetesApi.getHost)

      if (kubernetesApi.getPort == -1) urlBuilder.port(kubernetesApi.getDefaultPort)
      else urlBuilder.port(kubernetesApi.getPort)

      if (kubernetesApi.getProtocol == "https") urlBuilder.scheme("https")

      val url = urlBuilder.addPathSegment("apis/route.openshift.io/v1").build()

      val httpClient = HttpClientUtils.createHttpClient(new ConfigBuilder().build)
      val response = httpClient.newCall(new Request.Builder().url(url).build).execute
      response.body().close()
      httpClient.connectionPool().evictAll()
      val success = response.isSuccessful

      if (success) logger.info(s"$url returned ${response.code}. We are on OpenShift.")
      else logger.info(s"$url returned ${response.code}. We are not on OpenShift. Assuming, we are on Kubernetes.")

      success
    }.fold(e => {
      logger.error("Failed to distinguish between Kubernetes and OpenShift", e)
      logger.warn("Let's assume we are on K8s")
      false
    }, identity)
  }
}

class Scheduler[T](client: KubernetesClient, cfg: OperatorCfg[T], operator: Operator[T])(implicit ec: ExecutionContext)
    extends LazyLogging {

  private val isOpenShift: Boolean = Scheduler.checkIfOnOpenshift(client)
  private val watchers = TrieMap[String, Watch]()

  private val crdDeployer: CrdDeployer[T] = new CrdDeployer[T]
  private val kind: String = cfg.customKind.getOrElse(cfg.forKind.getSimpleName)
  private val operatorName = s"'$kind' operator"

  private val selector = LabelsHelper.forKind(kind, cfg.prefix)

  private val isCrd: Boolean = cfg.isInstanceOf[CrdConfig[T]]
  private lazy val crd: CustomResourceDefinition = if (isCrd) deployCrd(cfg.asInstanceOf[CrdConfig[T]]) else null

  def start(): Future[Watch] = {
    if (isOpenShift) logger.info(s"${AnsiColors.ye}OpenShift${AnsiColors.xx} environment detected.")
    else logger.info(s"${AnsiColors.ye}Kubernetes${AnsiColors.xx} environment detected.")

    val f = runForNamespace(isOpenShift, cfg.namespace)
    f.failed.foreach { ex: Throwable =>
      logger.error("Unable to start operator for one or more namespaces", ex)
    }
    f
  }

  def stop(): Future[Int] = {
    val ws = watchers.toList.map {
      case (o, w) =>
        logger.info(s"Stopping '$o' for namespace '${cfg.namespace}'")
        Future(w.close())
    }
    Future.sequence(ws).map(_.length)
  }

  private def runForNamespace(isOpenShift: Boolean, namespace: String): Future[Watch] = {
    val f = Future(startOperator).flatMap {
      case Right(w) =>
        watchers.put(kind, w)
        logger.info(
          s"${AnsiColors.re}Operator $operatorName${AnsiColors.xx} has been started in namespace '$namespace'"
        )
        Future.successful(w)
      case Left(e) => Future.failed(e)
    }

    f.failed.foreach { e =>
      logger.error(s"$operatorName in namespace $namespace failed to start", e)
    }
    f
  }

  private def deployCrd(crd: CrdConfig[T]) = crdDeployer.initCrds(
    client,
    cfg.prefix,
    kind,
    crd.shortNames,
    crd.pluralName,
    crd.additionalPrinterColumnNames,
    crd.additionalPrinterColumnPaths,
    crd.additionalPrinterColumnTypes,
    cfg.forKind,
    isOpenShift
  )

  private def onInit(): Unit =
    operator.onInit()

  private def startOperator: Either[Throwable, Watch] = {
    val ok = cfg.validate

    if (!ok) {
      val t = new RuntimeException(
        "Unable to initialize the operator correctly, some mandatory configuration fields are missing."
      )
      Left(t)
    } else {
      logger.info(s"Starting $operatorName for namespace ${cfg.namespace}")

      onInit()

      val watch = startWatcher
      logger.info(
        s"${AnsiColors.gr}$operatorName running${AnsiColors.xx} for namespace ${Option(cfg.namespace).getOrElse("'all'")}"
      )

      Right(watch)
    }
  }

  private def startWatcher: Watch = {
    if (isCrd) {
      CustomResourceWatcher[T](cfg.namespace, kind, onAdd, onDelete, onModify, convertCr, client, crd, recreateWatcher).watch
    } else {
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
    }
  }

  protected def recreateWatcher(e: KubernetesClientException): Unit = {
    val crdOrCm = if (isCrd) "CustomResource" else "ConfigMap"
    val w = Future(startWatcher).map { w =>
      watchers.put(kind, w)
      w
    }
    logger.info(s"$crdOrCm watch recreated in namespace ${cfg.namespace}")

    w.failed.map { e: Throwable =>
      logger.error(s"Failed to recreate $crdOrCm watch in namespace ${cfg.namespace}", e)
    }
  }

  /**
   * Call this method in the concrete operator to obtain the desired state of the system. This can be especially handy
   * during the fullReconciliation. Rule of thumb is that if you are overriding <code>fullReconciliation</code>, you
   * should also override this method and call it from <code>fullReconciliation()</code> to ensure that the real state
   * is the same as the desired state.
   *
   * @return returns the set of 'T's that correspond to the CMs or CRs that have been created in the K8s
   */
  protected def getDesiredSet: Set[(T, Metadata)] =
    if (isCrd) {
      val crds = {
        val _crds =
          client.customResources(crd, classOf[InfoClass[T]], classOf[InfoList[T]], classOf[InfoClassDoneable[T]])
        if (OperatorCfg.ALL_NAMESPACES == cfg.namespace) _crds.inAnyNamespace else _crds.inNamespace(cfg.namespace)
      }

      crds.list.getItems.asScala.toList
      // ignore this CR if not convertible
        .flatMap(i => Try(Some(convertCr(i))).getOrElse(None))
        .toSet

    } else {
      val cms = {
        val _cms = client.configMaps
        if (OperatorCfg.ALL_NAMESPACES == cfg.namespace) _cms.inAnyNamespace
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

  protected def onAdd(entity: T, namespace: String): Unit =
    onAction(entity, namespace, operator.onAdd)

  protected def onDelete(entity: T, namespace: String): Unit =
    onAction(entity, namespace, operator.onDelete)

  protected def onModify(entity: T, namespace: String): Unit =
    onAction(entity, namespace, operator.onModify)

  private def onAction(entity: T, namespace: String, handler: (T, String) => Unit): Unit =
    handler(entity, namespace)

  protected def isSupported(cm: ConfigMap) = true

  protected def convert(cm: ConfigMap): (T, Metadata) = ConfigMapWatcher.defaultConvert(cfg.forKind, cm)

  protected def convertCr(info: InfoClass[_]): (T, Metadata) =
    (
      CustomResourceWatcher.defaultConvert(cfg.forKind, info),
      Metadata(info.getMetadata.getName, info.getMetadata.getNamespace)
    )
}
