package io.github.novakovalexey.k8soperator

import cats.effect.ConcurrentEffect
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.Watcher.Action

import scala.collection.mutable

class CrdTestController[F[_]](implicit override val F: ConcurrentEffect[F])
  extends Controller[F, Krb2]
    with LazyLogging {
  val events: mutable.Set[(Action, Krb2, Metadata)] = mutable.Set.empty
  var initialized: Boolean = false

  override def onAdd(krb: Krb2, meta: Metadata): F[Unit] =
    F.delay(events += ((Action.ADDED, krb, meta)))

  override def onDelete(krb: Krb2, meta: Metadata): F[Unit] =
    F.delay(events += ((Action.DELETED, krb, meta)))

  override def onModify(krb: Krb2, meta: Metadata): F[Unit] =
    F.delay(events += ((Action.MODIFIED, krb, meta)))

  override def onInit(): F[Unit] =
    F.delay({
      this.initialized = true
      logger.debug("Controller initialized")
    })
}

