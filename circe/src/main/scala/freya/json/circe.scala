package freya.json

import freya.{JsonReader, JsonWriter}
import io.circe.{Decoder, Encoder}
import io.circe.parser.decode
import io.circe.syntax._

import scala.reflect.ClassTag

object circe {
  implicit def circeRead[T: Decoder: ClassTag]: JsonReader[T] = new JsonReader[T] {
    private val clazz: Class[T] =
      implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]]

    override def fromString(json: String): Either[Throwable, T] = decode[T](json)

    override def targetClass: Class[T] = clazz
  }

  implicit def circeWriter[T: Encoder]: JsonWriter[T] = (v: T) => v.asJson.noSpaces
}
