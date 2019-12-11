package io.github.novakovalexey.k8soperator.internal.crd

import java.io.IOException

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.apiextensions.JSONSchemaProps

import scala.util.Try

private[k8soperator] object JSONSchemaReader extends LazyLogging {
  val mapper = new ObjectMapper
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

  def readSchema(infoClass: Class[_]): Option[JSONSchemaProps] = {
    val chars = infoClass.getSimpleName.toCharArray
    chars(0) = Character.toLowerCase(chars(0))
    val name = new String(chars)
    val urlJson = s"/schema/$name.json"
    lazy val urlJS = s"/schema/$name.js"
    val in = Option(infoClass.getResource(urlJson))

    in.orElse(Option(infoClass.getResource(urlJS))).flatMap { url =>
      Try(Option(mapper.readValue(url, classOf[JSONSchemaProps]))).recover {
        case e: IOException =>
          logger.error(s"Failed to read JSON schema from $url", e)
          None
      }.toOption.flatten
    }
  }
}
