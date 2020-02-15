package freya.internal.crd

import java.io.IOException

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.apiextensions.JSONSchemaProps

import scala.util.Try

private[freya] object JSONSchemaReader extends LazyLogging {
  val mapper = new ObjectMapper
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

  def readSchema(kind: String): Option[JSONSchemaProps] = {
    val chars = kind.toCharArray
    chars(0) = Character.toLowerCase(chars(0))
    val name = new String(chars)
    val urlJson = s"/schema/$name.json"
    lazy val urlJS = s"/schema/$name.js"
    val in = Option(getClass.getResource(urlJson))

    in.orElse(Option(getClass.getResource(urlJS))).flatMap { url =>
      Try(Option(mapper.readValue(url, classOf[JSONSchemaProps]))).recover {
        case e: IOException =>
          logger.error(s"Failed to read JSON schema from $url", e)
          None
      }.toOption.flatten
    }
  }
}
