package io.github.novakovalexey.k8soperator.resource

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import io.github.novakovalexey.k8soperator.common.crd.InfoClass

object CrdParser {

  def parse[T](clazz: Class[T], info: InfoClass[_]): T = {
    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)

    var infoSpec = mapper.convertValue(info.getSpec, clazz)

    if (infoSpec == null) { // empty spec
      try infoSpec = clazz.getDeclaredConstructor().newInstance()
      catch {
        case e: InstantiationException =>
          e.printStackTrace()
        case e: IllegalAccessException =>
          e.printStackTrace()
      }
    }
    infoSpec
  }
}
