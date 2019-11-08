package io.github.novakovalexey.k8soperator.common.crd

import io.fabric8.kubernetes.client.CustomResource

class InfoClass[U] extends CustomResource {
  private var spec: U = _

  def getSpec: U = spec

  def setSpec(spec: U): Unit = {
    this.spec = spec
  }
}
