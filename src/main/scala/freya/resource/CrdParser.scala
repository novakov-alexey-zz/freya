package freya.resource

import cats.effect.Sync
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import freya.watcher.SpecClass

import scala.util.{Failure, Success, Try}

private[freya] object CrdParser {
  def apply[F[_]: Sync](): F[CrdParser] =
    Sync[F].delay(new CrdParser)
}

private[freya] class CrdParser {
  private val mapper = new ObjectMapper
  mapper.registerModule(DefaultScalaModule)

  def parse[T](clazz: Class[T], info: SpecClass): Either[Throwable, T] = {
    val spec = Try(mapper.convertValue(info.getSpec, clazz)).toEither

    spec match {
      case Right(s) =>
        if (s == null) { // empty spec
          Try {
            val emptySpec = clazz.getDeclaredConstructor().newInstance()
            Right(emptySpec)
          } match {
            case Success(s) => s
            case Failure(e: InstantiationException) =>
              val msg = "Failed to instantiate CRD spec"
              Left(new RuntimeException(msg, e))
            case Failure(e: IllegalAccessException) =>
              val msg = "Failed to instantiate CRD spec"
              Left(new RuntimeException(msg, e))
            case Failure(e) =>
              Left(new RuntimeException("Failed to parse CRD sec", e))
          }
        } else
          Right(s)
      case Left(t) =>
        val msg = "Failed to convert CRD spec"
        Left(new RuntimeException(msg, t))
    }
  }
}
