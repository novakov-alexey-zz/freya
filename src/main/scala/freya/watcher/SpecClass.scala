package freya.watcher

import io.fabric8.kubernetes.client.CustomResource

class SpecClass[T] extends CustomResource {
  private var spec: T = _ //TODO: should be AnyRef type ?

  def getSpec: T = spec

  def setSpec(spec: T): Unit =
    this.spec = spec

  override def toString: String =
    super.toString + s", spec: $spec"
}
