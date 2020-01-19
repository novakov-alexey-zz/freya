package freya

import cats.syntax.apply._
import cats.effect.ConcurrentEffect
import com.typesafe.scalalogging.LazyLogging
import freya.models.{CustomResource, NewStatus}
import io.fabric8.kubernetes.client.Watcher.Action

import scala.collection.mutable

class CrdTestController[F[_]](implicit override val F: ConcurrentEffect[F])
    extends Controller[F, Kerb, KerbStatus]
    with LazyLogging {
  val events: mutable.Set[(Action, Kerb, Metadata)] = mutable.Set.empty
  val reconciledEvents: mutable.Set[(Kerb, Metadata)] = mutable.Set.empty
  var initialized: Boolean = false

  private def noStatus: F[NewStatus[KerbStatus]] =
    F.pure(None)

  override def onAdd(krb: CustomResource[Kerb, KerbStatus]): F[NewStatus[KerbStatus]] =
    F.delay(events += ((Action.ADDED, krb.spec, krb.metadata))) *> noStatus

  override def onDelete(krb: CustomResource[Kerb, KerbStatus]): F[NewStatus[KerbStatus]] =
    F.delay(events += ((Action.DELETED, krb.spec, krb.metadata))) *> noStatus

  override def onModify(krb: CustomResource[Kerb, KerbStatus]): F[NewStatus[KerbStatus]] =
    F.delay(events += ((Action.MODIFIED, krb.spec, krb.metadata))) *> noStatus

  override def reconcile(krb: CustomResource[Kerb, KerbStatus]): F[NewStatus[KerbStatus]] =
    F.delay(reconciledEvents += ((krb.spec, krb.metadata))) *> noStatus

  override def onInit(): F[Unit] =
    F.delay {
      this.initialized = true
      logger.debug("Controller initialized")
    }
}
