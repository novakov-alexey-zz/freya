package freya.resource

import cats.effect.Sync
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import freya.CustomResourceParser
import freya.watcher.AnyCustomResource

import scala.util.{Failure, Success, Try}

private[freya] object CrdParser {
  def apply[F[_]: Sync](): F[CrdParser] =
    Sync[F].delay(new CrdParser)
}

private[freya] class CrdParser extends CustomResourceParser {
  private val mapper = new ObjectMapper
  mapper.registerModule(DefaultScalaModule)

  def parse[T, U](specClass: Class[T], statusClass: Class[U], cr: AnyCustomResource): Either[Throwable, (T, U)] = {
    for {
      spec <- parseProperty(specClass, cr.getSpec, "spec")
      status <- parseProperty(statusClass, cr.getStatus, "status")
    } yield (spec, status)
  }

  private def parseProperty[U, T](spec: Class[T], any: AnyRef, name: String) = {
    val parsed = Try(mapper.convertValue(any, spec)).toEither

    parsed match {
      case Right(s) =>
        if (s == null) { // empty spec
          Try {
            val emptySpec = spec.getDeclaredConstructor().newInstance()
            Right(emptySpec)
          } match {
            case Success(s) => s
            case Failure(e: InstantiationException) =>
              val msg = s"Failed to instantiate CRD $name"
              Left(new RuntimeException(msg, e))
            case Failure(e: IllegalAccessException) =>
              val msg = s"Failed to instantiate CRD $name"
              Left(new RuntimeException(msg, e))
            case Failure(e) =>
              Left(new RuntimeException(s"Failed to parse CRD $name", e))
          }
        } else
          Right(s)
      case Left(t) =>
        val msg = s"Failed to convert CRD $name"
        Left(new RuntimeException(msg, t))
    }
  }
}
