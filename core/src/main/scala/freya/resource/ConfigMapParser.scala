package freya.resource

import com.typesafe.scalalogging.LazyLogging
import freya.YamlReader
import freya.resource.ConfigMapParser.SpecificationKey
import io.fabric8.kubernetes.api.model.{ConfigMap, ObjectMeta}

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

private[freya] object ConfigMapParser {
  val SpecificationKey = "config"

  def apply(): ConfigMapParser = new ConfigMapParser
}

private[freya] class ConfigMapParser extends LazyLogging {

  private def parseYaml[T: YamlReader](yamlDoc: String): Either[Throwable, T] = {
    val read = implicitly[YamlReader[T]]
    val spec = read.fromString(yamlDoc)
    lazy val clazz = read.targetClass

    spec match {
      case Right(s) =>
        if (s == null) { // empty spec
          Try {
            val emptySpec = clazz.getDeclaredConstructor().newInstance()
            Right(emptySpec)
          } match {
            case Success(s) => s
            case Failure(e: InstantiationException) =>
              val msg = "Failed to parse ConfigMap data"
              Left(new RuntimeException(msg, e))
            case Failure(e: IllegalAccessException) =>
              val msg = s"Failed to instantiate ConfigMap data as $clazz"
              Left(new RuntimeException(msg, e))
            case Failure(e) =>
              Left(new RuntimeException("Failed to parse ConfigMap yaml", e))
          }
        } else
          Right(s)
      case Left(t) =>
        val msg =
          s"""Unable to parse yaml definition of ConfigMap, check if you don't have a typo:
             |'
             |$yamlDoc
             |'
             |""".stripMargin
        Left(new RuntimeException(msg, t))
    }
  }

  def parseCM[T: YamlReader](cm: ConfigMap): Either[(Throwable, String), (T, ObjectMeta)] = {
    val data = cm.getData.asScala

    for {
      yaml: String <- data
        .get(SpecificationKey)
        .toRight((new RuntimeException(s"ConfigMap is missing '$SpecificationKey' key"), data.toString()))
      parsed <- parseYaml(yaml) match {
        case Right(r) => Right(r -> cm.getMetadata)
        case Left(t) => Left(t -> yaml)
      }
    } yield parsed
  }
}
