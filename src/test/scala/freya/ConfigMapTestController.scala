package freya

import cats.effect.ConcurrentEffect
import cats.implicits._
import freya.models.{CustomResource, NoStatus}
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.Watcher.Action

class ConfigMapTestController[F[_]: ConcurrentEffect] extends CmController[F, Kerb] with ControllerState {
  override def isSupported(cm: ConfigMap): Boolean = true

  override def onAdd(krb: CustomResource[Kerb, Unit]): F[NoStatus] =
    F.delay(events += ((Action.ADDED, krb.spec, krb.metadata))).void

  override def onDelete(krb: CustomResource[Kerb, Unit]): F[Unit] =
    F.delay(events += ((Action.DELETED, krb.spec, krb.metadata)))

  override def onModify(krb: CustomResource[Kerb, Unit]): F[NoStatus] =
    F.delay(events += ((Action.MODIFIED, krb.spec, krb.metadata))).void

  override def reconcile(krb: CustomResource[Kerb, Unit]): F[NoStatus] =
    F.delay(reconciledEvents += ((krb.spec, krb.metadata))).void

  override def onInit(): F[Unit] =
    F.delay {
      this.initialized = true
    }
}
