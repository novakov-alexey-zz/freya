# Freya

Freya is a small Scala library to implement custom controllers for Kubernetes easily. 
Implementation of custom controller is also known as **Operator Pattern**. 
Freya is based on [fabric8 kubernetes client](https://github.com/fabric8io/kubernetes-client).

Main features:
1. Two ways to implement your Kubernetes Operator with Freya:
    - Custom Resource Definition based (CRD)
    - ConfigMap based
1. Case Classes as Kubernetes resource specification. 
    Serialization and deserialization of cases classes is done automatically by Freya and it is powered by
    Jackson Scala Module library.
1. Auto-restart of custom-controller upon k8s api-server disconnect.
1. Auto-deployment of CRDs, no YAML files to be written.
1. Auto-deployment of Json Schema for CRD validation.
1. Effect management and Functional Programming is powered by Cats-Effect.    

## SBT dependency

```scala
"io.github.novakov-alexey" %% "freya" % "0.1.0"
``` 

## How to use

### CRD Operator

Let's take an example of Kerberos principal list, which needs to be propagated to KDC database. 
Our target Custom Resource will look like this:  

```yaml
apiVersion: io.myorg.kerboperator/v1
kind: Kerb
metadata:
  name: my-krb1
  namespace: test
spec:
  realm: EXAMPLE.COM
  principals:
    - name: client1
      password: static
      value: mypass
    - name: user2
      password: static
      value: mypass2
``` 

Freya does not require to write YAML files for your custom resources definitions at all. CRD in K8s will be
created automatically based on case classes your define.

We are not going to create any container Kerberos server, but just showing how Freya can help to watch 
our custom resource. Particular controller actions to be implemented by controller author. Freya is only
facilitator between K8s api-server and your custom controller actions.

####Implementation Steps with Freya:

1 . Define custom specification as a hierarchy of case classes. Let's map above Kerberos CR spec as two 
case classes `Kerb` and `Principal`

```scala
final case class Principal(name: String, password: String, value: String = "")
final case class Kerb(realm: String, principals: List[Principal])
```

2 . Implement your actions for Add, Modify, Delete events by implementing
`io.github.novakovalexey.k8soperator.Controller` trait:

```scala
import freya.Controller

class KerbController[F[_]](implicit override val F: ConcurrentEffect[F]) 
  extends Controller[F, Kerb] with LazyLogging {

  override def onAdd(krb: Kerb, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"new Kerb added: $krb, $meta"))

  override def onDelete(krb: Kerb, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"Kerb deleted: $krb, $meta"))

  override def onModify(krb: Kerb, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"Kerb modified: $krb, $meta"))
}
```

All trait methods are stubbed with `F.unit`. Override only necessary methods for your custom controller.

3 . Start your operator

```scala
import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, IO, IOApp}
import io.fabric8.kubernetes.client.DefaultKubernetesClient

import scala.concurrent.ExecutionContext

object KerbOperator extends IOApp {
  implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] = {
    val client = IO(new DefaultKubernetesClient)
    val cfg = CrdConfig(classOf[Kerb], Namespace("test"), "io.myorg.kerboperator")

    Operator
      .ofCrd[IO, Kerb](cfg, client, new KerbController[IO])
      .run
  }
}
```

`run` method on Operator.ofCrd(....) will return an `IO[ExitCode]`,
 which is running web-socket client for Kubernetes API-Server. Operator is watching for events with `Kerb` kind
 and group `io.myorg.kerboperator`

### ConfigMap Operator

