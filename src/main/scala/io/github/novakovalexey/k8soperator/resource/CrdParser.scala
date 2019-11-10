package io.github.novakovalexey.k8soperator.resource

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import io.github.novakovalexey.k8soperator.common.crd.InfoClass

import scala.util.Try

object CrdParser {
  //TODO: side-effect
  val mapper = new ObjectMapper
  mapper.registerModule(DefaultScalaModule)

  def parse[T](clazz: Class[T], info: InfoClass[T]): Either[Throwable, T] = {
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
