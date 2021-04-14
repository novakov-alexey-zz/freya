package freya.resource

import cats.implicits.catsSyntaxEitherId
import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import freya.watcher.AnyCustomResource
import freya.{CustomResourceParser, JsonReader}
import io.fabric8.kubernetes.api.model.ObjectMeta

import scala.util.{Failure, Success, Try}

private[freya] object CrdParser {
  def apply(): CrdParser = new CrdParser
}

private[freya] class CrdParser extends CustomResourceParser {
  val mapper = new ObjectMapper
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

  def parse[T: JsonReader, U: JsonReader](cr: AnyRef): Either[(Throwable, String), (T, Option[U], ObjectMeta)] = {
    val s = cr match {
      case s: String => s
      case _ => mapper.writeValueAsString(cr)
    }
    parseStr[T, U](s).left.map((_, s))
  }

  def parseStr[T: JsonReader, U: JsonReader](cr: String): Either[Throwable, (T, Option[U], ObjectMeta)] =
    for {
      anyCr <- Try(mapper.readValue(cr, classOf[AnyCustomResource])).toEither
      spec <- parseProperty[T](anyCr.getSpec.value, "spec")
      status <- Option(anyCr.getStatus) match {
        case None => None.asRight[Throwable]
        case Some(s) => parseProperty[U](s.value, "status").map(Some(_))
      }
    } yield (spec, status, anyCr.getMetadata)

  private def parseProperty[T: JsonReader](property: String, name: String) = {
    val read = implicitly[JsonReader[T]]
    val parsed = read.fromString(property)
    lazy val clazz = read.targetClass

    parsed match {
      case Right(s) =>
        if (s == null) { // empty spec
          Try {
            val emptySpec = clazz.getDeclaredConstructor().newInstance()
            Right(emptySpec)
          } match {
            case Success(s) => s
            case Failure(e: InstantiationException) =>
              val msg = s"Failed to instantiate ${clazz.getCanonicalName} from CRD.$name"
              Left(new RuntimeException(msg, e))
            case Failure(e: IllegalAccessException) =>
              val msg = s"Failed to instantiate ${clazz.getCanonicalName} from CRD.$name"
              Left(new RuntimeException(msg, e))
            case Failure(e) =>
              Left(new RuntimeException(s"Failed to parse ${clazz.getCanonicalName} from CRD.$name", e))
          }
        } else
          Right(s)
      case Left(t) =>
        val msg = s"Failed to convert CRD.$name to ${clazz.getCanonicalName}"
        Left(new RuntimeException(msg, t))
    }
  }
}
