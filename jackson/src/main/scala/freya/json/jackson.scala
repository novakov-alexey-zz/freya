package freya.json

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import freya.{JsonReader, JsonWriter}

import scala.reflect.ClassTag
import scala.util.Try

object jackson {
  private lazy val mapper = {
    val m = new ObjectMapper
    m.registerModule(DefaultScalaModule)
    m
  }

  implicit def jacksonRead[T: ClassTag]: JsonReader[T] = new JsonReader[T] {


    private val clazz: Class[T] =
      implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]]

    override def fromString(json: String): Either[Throwable, T] =
      Try(mapper.readValue(json, clazz)).toEither

    override def targetClass: Class[T] = clazz
  }

  implicit def jacksonWriter[T]: JsonWriter[T] = (v: T) => mapper.writeValueAsString(v)
}
