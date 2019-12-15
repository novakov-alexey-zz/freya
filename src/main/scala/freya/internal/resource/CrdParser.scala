package freya.internal.resource

import cats.effect.Sync
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import freya.watcher.SpecClass

import scala.util.Try

private[freya] object CrdParser {
  def apply[F[_]: Sync](): F[CrdParser] =
    Sync[F].delay(new CrdParser)
}

private[freya] class CrdParser {
  private val mapper = new ObjectMapper
  mapper.registerModule(DefaultScalaModule)

  def parse[T](clazz: Class[T], info: SpecClass[T]): Either[Throwable, T] = {
    val spec = Try(mapper.convertValue(info.getSpec, clazz)).toEither

    spec match {
      case Right(s) =>
        if (s == null) { // empty spec
          try {
            val emptySpec = clazz.getDeclaredConstructor().newInstance()
            Right(emptySpec)
          } catch {
            case e: InstantiationException =>
              val msg = "Failed to parse CRD spec"
              Left(new RuntimeException(msg, e))
            case e: IllegalAccessException =>
              val msg = "Failed to instantiate CRD spec"
              Left(new RuntimeException(msg, e))
          }
        } else
          Right(s)
      case Left(t) =>
        val msg = "Failed to convert CRD spec"
        Left(new RuntimeException(msg, t))
    }
  }
}
