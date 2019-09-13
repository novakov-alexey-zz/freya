package io.github.novakovalexey.k8soperator4s.common

import java.io.IOException

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import io.fabric8.kubernetes.api.model.apiextensions.JSONSchemaProps

object JSONSchemaReader {
  def readSchema(infoClass: Class[_]): JSONSchemaProps = {
    val mapper = new ObjectMapper
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    val chars = infoClass.getSimpleName.toCharArray
    chars(0) = Character.toLowerCase(chars(0))
    val urlJson = "/schema/" + new String(chars) + ".json"
    val urlJS = "/schema/" + new String(chars) + ".js"
    val in = Option(infoClass.getResource(urlJson))

    in.orElse(Option(infoClass.getResource(urlJS))) match {
      case None => null
      case Some(i) =>
        try mapper.readValue(i, classOf[JSONSchemaProps])
        catch {
          case e: IOException =>
            e.printStackTrace()
            null
        }
    }
  }
}
