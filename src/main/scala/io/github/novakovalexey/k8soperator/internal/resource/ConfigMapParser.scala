package io.github.novakovalexey.k8soperator.internal.resource

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.ConfigMap
import io.github.novakovalexey.k8soperator.Metadata
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor
import org.yaml.snakeyaml.error.YAMLException

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

private[k8soperator] object ConfigMapParser extends LazyLogging {

  def parseYaml[T](clazz: Class[T], yamlDoc: String): Either[Throwable, T] =
    Try {
      val snake = new Yaml(new Constructor(clazz))
      snake.load[T](yamlDoc)
    }.orElse(Try(clazz.getDeclaredConstructor().newInstance())) match {
      case Success(v) =>
        Option(v).toRight(new RuntimeException(s"Failed to parse $yamlDoc as $clazz"))
      case e @ Failure(_: InstantiationException | _: IllegalAccessException) =>
        logger.error("failed to create new instance", e.exception)
        Left(e.exception)
      case Failure(e: YAMLException) =>
        val msg =
          s"""Unable to parse yaml definition of ConfigMap, check if you don't have typo:
             |'
             |$yamlDoc
             |'
             |""".stripMargin
        logger.error(msg)
        Left(new IllegalStateException(e))
      case Failure(e) =>
        logger.error("unexpected error", e)
        Left(e)
    }

  def parseCM[T](clazz: Class[T], cm: ConfigMap): Either[Throwable, (T, Metadata)] =
    for {
      yaml <- cm.getData.asScala.get("config").toRight(new RuntimeException("ConfigMap is missing 'config' key"))
      meta = Metadata(cm.getMetadata.getName, cm.getMetadata.getNamespace)
      parsed <- parseYaml(clazz, yaml).map(_ -> meta)
    } yield parsed
}
