package io.github.novakovalexey.k8soperator.common.crd

import io.fabric8.kubernetes.client.CustomResource

class InfoClass[T] extends CustomResource {
  private var spec: T = _

  def getSpec: T = spec

  def setSpec(spec: T): Unit =
    this.spec = spec

  override def toString: String =
    super.toString + s", spec: $spec"
}
