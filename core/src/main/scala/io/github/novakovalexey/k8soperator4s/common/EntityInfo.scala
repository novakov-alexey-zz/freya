package io.github.novakovalexey.k8soperator4s.common

trait EntityInfo[T] {
  val name: String
  val namespace: String

  def copyOf(t: T, name: String = name, namespace: String = namespace): T
}
