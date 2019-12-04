package io.github.novakovalexey.k8soperator.internal

import java.net.URL

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.utils.HttpClientUtils
import okhttp3.{HttpUrl, Request}

import scala.util.Try

private[k8soperator] object OperatorUtils extends LazyLogging {

  def checkIfOnOpenshift(masterURL: URL): (Boolean, Int) =
    Try {
      val urlBuilder = new HttpUrl.Builder().host(masterURL.getHost)

      if (masterURL.getPort == -1) urlBuilder.port(masterURL.getDefaultPort)
      else urlBuilder.port(masterURL.getPort)

      if (masterURL.getProtocol == "https") urlBuilder.scheme("https") else urlBuilder.scheme("http")

      val url = urlBuilder.addPathSegment("apis/route.openshift.io/v1").build()

      val httpClient = HttpClientUtils.createHttpClient(new ConfigBuilder().build)
      val response = httpClient.newCall(new Request.Builder().url(url).build).execute
      response.body().close()
      httpClient.connectionPool().evictAll()
      val success = response.isSuccessful

      (success, response.code)
    }.fold(e => {
      logger.error("Failed to distinguish between Kubernetes and OpenShift", e)
      (false, -1)
    }, identity)

}
