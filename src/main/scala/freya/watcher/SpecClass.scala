package freya.watcher

import io.fabric8.kubernetes.client.CustomResource

class SpecClass extends CustomResource {
  private var spec: AnyRef = _

  def getSpec: AnyRef = spec

  def setSpec(spec: AnyRef): Unit =
    this.spec = spec

  override def toString: String =
    super.toString + s", spec: $spec"
}
