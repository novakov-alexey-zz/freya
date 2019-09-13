package io.github.novakovalexey.k8soperator4s

import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent._

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.utils.HttpClientUtils
import io.fabric8.kubernetes.client.{ConfigBuilder, DefaultKubernetesClient, KubernetesClient}
import io.github.novakovalexey.k8soperator4s.common.OperatorConfig._
import io.github.novakovalexey.k8soperator4s.common._
import okhttp3.{HttpUrl, Request}

import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success, Try}

object Scheduler {
  lazy val executors: ExecutorService = Executors.newFixedThreadPool(10)
}

class Scheduler[T: EntityInfo](operators: Operator[T]*) extends LazyLogging {
  val config: OperatorConfig = fromMap(System.getenv)
  val client: KubernetesClient = new DefaultKubernetesClient
  val isOpenShift: Boolean = checkIfOnOpenshift()

  def start(): Unit = {
    logger.info("Starting..")
    val future = run.exceptionally((ex: Throwable) => {
      logger.error("Unable to start operator for one or more namespaces", ex)
      System.exit(1)
      null
    })
  }

  private def run = {
    printInfo()

    if (isOpenShift) logger.info("{}OpenShift{} environment detected.", AnsiColors.ye, AnsiColors.xx)
    else logger.info("{}Kubernetes{} environment detected.", AnsiColors.ye, AnsiColors.xx)
    val futures = ArrayBuffer[CompletableFuture[_]]()

    if (SAME_NAMESPACE == config.namespaces.iterator.next) { // current namespace
      val namespace = client.getNamespace
      val future = runForNamespace(isOpenShift, namespace, config.reconciliationIntervalS, 0)
      futures += (future)
    } else if (ALL_NAMESPACES == config.namespaces.iterator.next) {
      val future = runForNamespace(isOpenShift, ALL_NAMESPACES, config.reconciliationIntervalS, 0)
      futures += future
    } else {
      config.namespaces.zipWithIndex.foldLeft(futures) {
        case (acc, (n, i)) =>
          val future = runForNamespace(isOpenShift, n, config.reconciliationIntervalS, i)
          acc += future
      }
    }
    CompletableFuture.allOf(futures.toSeq: _*)
  }

  private def runForNamespace(isOpenShift: Boolean, namespace: String, reconInterval: Long, delay: Int) = {
    if (operators.isEmpty)
      logger.warn(
        "No suitable operators were found, make sure your class extends AbstractOperator and have @Singleton on it."
      )

    val futures = operators
      .map(o => new AbstractOperator[T](o, o.forKind, client, isOpenShift))
      .zipWithIndex
      .foldLeft(List[CompletableFuture[_]]()) {
        case (acc, (operator, i: Int)) =>
          if (!operator.isEnabled) {
            logger.info("Skipping initialization of {} operator", operator.getClass)
            acc
          } else {

            val future: CompletableFuture[_] = operator.start
              .thenApply(res => {
                logger.info("{} started in namespace {}", operator.getName, namespace)
                res
              })
              .exceptionally(ex => {
                logger.error("{} in namespace {} failed to start", operator.getName, namespace, ex.getCause)
                System.exit(1)
                null
              })

            val s = Executors.newScheduledThreadPool(1)
            val realDelay = (delay * operators.length) + i + 2

            s.scheduleAtFixedRate(
              () => {
                try {
                  operator.fullReconciliation()
                  operator.setFullReconciliationRun(true)
                } catch {
                  case t: Throwable =>
                    logger.warn("error during full reconciliation: {}", t.getMessage)
                    t.printStackTrace()
                }
              },
              realDelay,
              reconInterval,
              SECONDS
            )

            logger.info(
              s"full reconciliation for ${operator.getName} scheduled (periodically each $reconInterval seconds)"
            )
            logger.info("the first full reconciliation for {} is happening in {} seconds", operator.getName, realDelay)

            acc :+ future
          }
      }

    CompletableFuture.allOf(futures: _*)
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
      val success = response.isSuccessful

      if (success) logger.info("{} returned {}. We are on OpenShift.", url, response.code)
      else logger.info("{} returned {}. We are not on OpenShift. Assuming, we are on Kubernetes.", url, response.code)

      success
    } match {
      case Success(value) => value
      case Failure(e) =>
        e.printStackTrace()
        logger.error("Failed to distinguish between Kubernetes and OpenShift")
        logger.warn("Let's assume we are on K8s")
        false
    }
  }

  private def printInfo(): Unit = {
    var gitSha = "unknown"
    var version = "unknown"
    try {
      version = Option(classOf[Scheduler[T]].getPackage.getImplementationVersion).getOrElse(version)
//      gitSha = Option(Manifests.read("Implementation-Build")).orElse(gitSha)
    } catch {
      case _: Exception =>
      // ignore, not critical
    }

    logger.info(
      "\n{}Operator{} has started in version {}{}{}.\n",
      AnsiColors.re,
      AnsiColors.xx,
      AnsiColors.gr,
      version,
      AnsiColors.xx
    )
    if (!gitSha.isEmpty) logger.info("Git sha: {}{}{}", AnsiColors.ye, gitSha, AnsiColors.xx)
    logger.info("==================\n")
  }
}
