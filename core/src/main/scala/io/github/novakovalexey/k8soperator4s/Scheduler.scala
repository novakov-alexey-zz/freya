package io.github.novakovalexey.k8soperator4s

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.utils.HttpClientUtils
import io.fabric8.kubernetes.client.{ConfigBuilder, KubernetesClient, Watch}
import io.github.novakovalexey.k8soperator4s.common._
import okhttp3.{HttpUrl, Request}

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class Scheduler[T](client: KubernetesClient, cfg: OperatorCfg[T], operator: Operator[T])(implicit ec: ExecutionContext)
    extends LazyLogging {
  val isOpenShift: Boolean = checkIfOnOpenshift()
  private val watchers = TrieMap[String, Watch]()

  def start(): Future[Watch] = {
    val f = run
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

  private def run: Future[Watch] = {
    if (isOpenShift) logger.info(s"${AnsiColors.ye}OpenShift${AnsiColors.xx} environment detected.")
    else logger.info(s"${AnsiColors.ye}Kubernetes${AnsiColors.xx} environment detected.")

    runForNamespace(isOpenShift, cfg.namespace)
  }

  private def runForNamespace(isOpenShift: Boolean, namespace: String): Future[Watch] = {
    val o = new AbstractOperator[T](operator, cfg, client, isOpenShift)
    val f = Future(o.start).map { w =>
      watchers.put(o.operatorName, w)

      logger.info(
        s"${AnsiColors.re}Operator ${o.operatorName}${AnsiColors.xx} has been started in namespace '$namespace'"
      )

      w
    }

    f.failed.foreach { ex =>
      logger.error("{} in namespace {} failed to start", o.operatorName, namespace, ex.getCause)
    }

    f
  }

  private def checkIfOnOpenshift(): Boolean = {
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
      else logger.info("{} returned {}. We are not on OpenShift. Assuming, we are on Kubernetes.", url, response.code)

      success
    }.fold(e => {
      logger.error("Failed to distinguish between Kubernetes and OpenShift", e)
      logger.warn("Let's assume we are on K8s")
      false
    }, identity)
  }
}
