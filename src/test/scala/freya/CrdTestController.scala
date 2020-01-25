package freya

import cats.syntax.apply._
import cats.effect.ConcurrentEffect
import com.typesafe.scalalogging.LazyLogging
import freya.models.{CustomResource, NewStatus}
import io.fabric8.kubernetes.client.Watcher.Action

import scala.collection.mutable

class CrdTestController[F[_]](implicit override val F: ConcurrentEffect[F])
    extends Controller[F, Kerb, Status]
    with LazyLogging {
  val events: mutable.Set[(Action, Kerb, Metadata)] = mutable.Set.empty
  val reconciledEvents: mutable.Set[(Kerb, Metadata)] = mutable.Set.empty
  var initialized: Boolean = false

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
