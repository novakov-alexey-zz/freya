package io.github.novakovalexey.k8soperator.common.crd

import java.io.IOException

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.apiextensions.JSONSchemaProps

import scala.util.Try

object JSONSchemaReader extends LazyLogging {
  def readSchema(infoClass: Class[_]): Option[JSONSchemaProps] = {
    val mapper = new ObjectMapper
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    val chars = infoClass.getSimpleName.toCharArray
    chars(0) = Character.toLowerCase(chars(0))
    val name = new String(chars)
    val urlJson = s"/schema/$name.json"
    val urlJS = s"/schema/$name.js"
    val in = Option(infoClass.getResource(urlJson))

    in.orElse(Option(infoClass.getResource(urlJS))).flatMap { i =>
      Try(Option(mapper.readValue(i, classOf[JSONSchemaProps]))).recover {
        case e: IOException =>
          logger.error(s"Failed to read JSON schema from $i", e)
          None
      }.toOption.flatten
    }
  }
}
