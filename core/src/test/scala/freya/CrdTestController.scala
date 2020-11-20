package freya

import cats.effect.{ConcurrentEffect, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.models.{CustomResource, Metadata, NewStatus}
import io.fabric8.kubernetes.client.Watcher.Action

import scala.concurrent.duration.{FiniteDuration, _}

class CrdTestController[F[_]](delay: FiniteDuration = 0.seconds)(
  implicit override val F: ConcurrentEffect[F],
  T: Timer[F]
) extends Controller[F, Kerb, Status]
    with LazyLogging
    with ControllerState {

  private def getStatus(ready: Boolean): F[NewStatus[Status]] =
    F.pure(Some(Status(ready)))

  override def onAdd(krb: CustomResource[Kerb, Status]): F[NewStatus[Status]] =
    T.sleep(delay) *> save(Action.ADDED, krb.spec, krb.metadata) *> getStatus(krb.spec.failInTest)

  override def onDelete(krb: CustomResource[Kerb, Status]): F[Unit] =
    T.sleep(delay) *> save(Action.DELETED, krb.spec, krb.metadata).void

  override def onModify(krb: CustomResource[Kerb, Status]): F[NewStatus[Status]] =
    T.sleep(delay) *> save(Action.MODIFIED, krb.spec, krb.metadata) *> getStatus(krb.spec.failInTest)

  override def reconcile(krb: CustomResource[Kerb, Status]): F[NewStatus[Status]] =
    T.sleep(delay) *> F.delay(reconciledEvents += ((krb.spec, krb.metadata))) *> getStatus(krb.spec.failInTest)

  def save(action: Action, spec: Kerb, meta: Metadata): F[Unit] =
    F.delay(events.add((action, spec, meta))).void

  override def onInit(): F[Unit] =
    F.delay {
      this.initialized = true
      logger.debug("Controller initialized")
    }
}
