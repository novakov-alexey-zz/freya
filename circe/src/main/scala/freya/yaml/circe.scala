package freya.yaml

import freya.YamlReader
import io.circe.Decoder
import io.circe.yaml.parser

import scala.reflect.ClassTag

object circe {

  implicit def circeYamlRead[T: Decoder: ClassTag]: YamlReader[T] = new YamlReader[T] {
    private val clazz: Class[T] =
      implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]]

    override def fromString(yaml: String): Either[Throwable, T] =
      parser.parse(yaml).flatMap(_.as[T])

    override def targetClass: Class[T] = clazz
  }
}
