package freya.internal.resource

import cats.effect.Sync
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.{DefaultScalaModule, ScalaObjectMapper}
import com.typesafe.scalalogging.LazyLogging
import freya.Metadata
import freya.internal.resource.ConfigMapParser.SpecificationKey
import io.fabric8.kubernetes.api.model.ConfigMap

import scala.jdk.CollectionConverters._
import scala.util.Try

private[freya] object ConfigMapParser {
  val SpecificationKey = "config"

  def apply[F[_]: Sync](): F[ConfigMapParser] =
    Sync[F].delay(new ConfigMapParser)
}

private[freya] class ConfigMapParser extends LazyLogging {
  val mapper = new ObjectMapper(new YAMLFactory()) with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)

  private def parseYaml[T](clazz: Class[T], yamlDoc: String): Either[Throwable, T] = {
    val spec = Try(mapper.readValue(yamlDoc, clazz)).toEither

    spec match {
      case Right(s) =>
        if (s == null) { // empty spec
          try {
            val emptySpec = clazz.getDeclaredConstructor().newInstance()
            Right(emptySpec)
          } catch {
            case e: InstantiationException =>
              val msg = "Failed to parse ConfigMap data"
              Left(new RuntimeException(msg, e))
            case e: IllegalAccessException =>
              val msg = s"Failed to instantiate ConfigMap data as $clazz"
              Left(new RuntimeException(msg, e))
          }
        } else
          Right(s)
      case Left(t) =>
        val msg =
          s"""Unable to parse yaml definition of ConfigMap, check if you don't have typo:
             |'
             |$yamlDoc
             |'
             |""".stripMargin
        Left(new RuntimeException(msg, t))
    }
  }

  def parseCM[T](clazz: Class[T], cm: ConfigMap): Either[Throwable, (T, Metadata)] =
    for {
      yaml <- cm.getData.asScala
        .get(SpecificationKey)
        .toRight(new RuntimeException(s"ConfigMap is missing '$SpecificationKey' key"))
      meta = Metadata(cm.getMetadata.getName, cm.getMetadata.getNamespace)
      parsed <- parseYaml(clazz, yaml).map(_ -> meta)
    } yield parsed
}
