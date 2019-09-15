package io.github.novakovalexey.k8soperator4s.common

case class Metadata(name: String, namespace: String)

trait Operator[T] {
  def onAdd(entity: T, namespace: String): Unit = ()

  def onDelete(entity: T, namespace: String): Unit = ()

  def onModify(entity: T, namespace: String): Unit = ()

  def onInit(): Unit = ()
}

case class OperatorCfg[T](
  forKind: Class[T],
  namespace: String,
  prefix: String,
  crd: Boolean,
  customKind: Option[String] = None
)
