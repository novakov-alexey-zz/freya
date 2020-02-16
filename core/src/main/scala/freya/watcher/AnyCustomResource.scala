package freya.watcher

import io.fabric8.kubernetes.client.CustomResource

class AnyCustomResource extends CustomResource {
  private var spec: AnyRef = _
  private var status: AnyRef = _

  def getSpec: AnyRef = spec

  def setSpec(spec: AnyRef): Unit =
    this.spec = spec

  def getStatus: AnyRef = status

  def setStatus(status: AnyRef): Unit =
    this.status = status

  override def toString: String =
    super.toString + s", spec: $spec, status: $status"
}
