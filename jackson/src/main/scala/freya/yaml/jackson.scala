package freya.yaml

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import freya.YamlReader

import scala.reflect.ClassTag
import scala.util.Try

object jackson {

  implicit def jacksonYamlReader[T: ClassTag]: YamlReader[T] = new YamlReader[T] {
    val mapper = new ObjectMapper(new YAMLFactory())
    mapper.registerModule(DefaultScalaModule)

    private val clazz: Class[T] =
      implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]]

    override def fromString(yaml: String): Either[Throwable, T] =
      Try(mapper.readValue(yaml, clazz)).toEither

    override def targetClass: Class[T] = clazz
  }
}
