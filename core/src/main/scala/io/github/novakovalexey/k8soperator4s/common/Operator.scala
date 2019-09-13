package io.github.novakovalexey.k8soperator4s.common

trait Operator[T] {
  val namespace: String = OperatorConfig.ALL_NAMESPACES
  val prefix: String
  val forKind : Class[T]
  val isCrd: Boolean

  def name: String = forKind.getSimpleName

  def onAdd(entity: T, namespace: String): Unit = ()

  def onDelete(entity: T, namespace: String): Unit = ()

  def onModify(entity: T, namespace: String): Unit = ()
}
