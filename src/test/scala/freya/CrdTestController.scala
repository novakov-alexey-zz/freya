package freya

import cats.effect.ConcurrentEffect
import cats.syntax.apply._
import com.typesafe.scalalogging.LazyLogging
import freya.models.{CustomResource, NewStatus}
import io.fabric8.kubernetes.client.Watcher.Action

class CrdTestController[F[_]](implicit override val F: ConcurrentEffect[F])
    extends Controller[F, Kerb, Status]
    with LazyLogging
    with ControllerState {

  private def getStatus(ready: Boolean): F[NewStatus[Status]] =
    F.pure(Some(Status(ready)))

  override def onAdd(krb: CustomResource[Kerb, Status]): F[NewStatus[Status]] =
    F.delay(events += ((Action.ADDED, krb.spec, krb.metadata))) *> getStatus(krb.spec.failInTest)

  override def onDelete(krb: CustomResource[Kerb, Status]): F[Unit] =
    F.delay(events += ((Action.DELETED, krb.spec, krb.metadata)))

  override def onModify(krb: CustomResource[Kerb, Status]): F[NewStatus[Status]] =
    F.delay(events += ((Action.MODIFIED, krb.spec, krb.metadata))) *> getStatus(krb.spec.failInTest)

  override def reconcile(krb: CustomResource[Kerb, Status]): F[NewStatus[Status]] =
    F.delay(reconciledEvents += ((krb.spec, krb.metadata))) *> getStatus(krb.spec.failInTest)

  override def onInit(): F[Unit] =
    F.delay {
      this.initialized = true
      logger.debug("Controller initialized")
    }
}
