---
layout: docs
title: Reconcile events
permalink: docs/reconcile-events/
position: 5
---

# Reconcile events

Freya can start your operator with parallel reconciler thread, which is puling current 
resources (CRs or ConfigMaps) at specified time interval. This feature allows to pro-actively check
existing resources and make sure that desired configuration is reflected in terms of Kubernetes objects.
It is also useful, when your controller failed to handle real-time event. It can process such event later,
once reconcile process is getting desired resources and pushes them to controller, so that controller can process those 
events second or n-th time. Reconciler always returns all resources regardless they were already handled
by your operator or not. Thus, it is important that your operators works in `idempotent` manner. 

Using `Kerb` example:

```scala mdoc:reset-object
final case class Principal(name: String, password: String, value: String = "")
final case class Kerb(realm: String, principals: List[Principal])
```

```scala mdoc:compile-only
import freya.Configuration.CrdConfig
import freya.K8sNamespace.Namespace
import freya.models._
import freya.json.jackson._
import freya.{Controller, Operator}
import cats.syntax.functor._
import cats.effect._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.DefaultKubernetesClient

val cfg = CrdConfig(Namespace("test"), prefix = "io.myorg.kerboperator")
val client = IO(new DefaultKubernetesClient)

// p.s. use IOApp as in previous examples instead of below timer and cs values
implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global) 
implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

// override reconcile method

class KerbController[F[_]](implicit F: ConcurrentEffect[F]) 
  extends Controller[F, Kerb, Unit] with LazyLogging {

  override def reconcile(krb: CustomResource[Kerb, Unit]): F[NoStatus] =
    F.delay(logger.info(s"Kerb to reconcile: ${krb.spec}, ${krb.metadata}")).void 
}

Operator
  .ofCrd[IO, Kerb](cfg, client, new KerbController[IO])
  .withReconciler(1.minute)
  .withRestart()
``` 

Above configuration will call controller's `reconcile` method every minute, since operator start, in case at least
one CR/ConfigMap resource is found.
