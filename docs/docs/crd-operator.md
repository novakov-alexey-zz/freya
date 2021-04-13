---
title: CRD Operator
custom_edit_url: https://github.com/novakov-alexey/freya/edit/master/docs/docs/crd-operator.md
---

<!-- # Table of Contents
1. [Implementation Steps with Freya](#implementation-steps-with-freya)
2. [Event Dispatching](#event-dispatching)
3. [Restart Configuration](#restart-configuration)
4. [Deploy CRD Manually](#deploy-crd-manually)
5. [CRD Helper](#crd-helper)

# CRD Operator -->

Further in the documentation, _Controller_ and _Operator_ definitions are used as synonymous.

Let's take an example of some controller like [Kerberos principal](http://web.mit.edu/KERBEROS/krb5-1.5/krb5-1.5.4/doc/krb5-user/What-is-a-Kerberos-Principal_003f.html)
list, which needs to be propagated to KDC database. 

Using Custom Resource option, our target Custom Resource will look like this:  

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

Freya does not require to write YAML files for your custom resources definitions, nor for customer resource
instances and ConfigMaps at all. CRD in K8s will be created automatically based on case classes you define.

For the sake of example, we are not going to create any container with Kerberos server running in it, but just showing 
how Freya can help to watch our custom resources or ConfigMaps. Particular controller actions to be implemented by 
controller author using **fabric8** kubernetes-client. Freya is only a facilitator between K8s api-server and 
your custom controller actions.

## Implementation Steps with Freya

There are 3 steps to implement a CRD Operator:

### 1 . Define resource specification as a hierarchy of case classes 

Above Kerberos spec can be designed as two case classes `Kerb` and `Principal`

```scala mdoc:reset-object
final case class Principal(name: String, password: String, value: String = "")
final case class Kerb(realm: String, principals: List[Principal])
```

According to Kubernetes API, every custom resource may have optional property `status`. In order to model
status, we will define one more case class. Name and properties of this class can be anything. Basically, 
it can define its own hierarchy of case classes.

```scala mdoc
final case class Status(ready: Boolean)
```

### 2 . Implement your actions for Add, Modify, Delete events 

Just extend `freya.Controller` abstract class:

```scala mdoc
import com.typesafe.scalalogging.LazyLogging
import cats.effect.Async
import cats.syntax.apply._
import freya.Controller
import freya.models.{CustomResource, NewStatus}

class KerbController[F[_]](implicit F: Async[F]) 
  extends Controller[F, Kerb, Status] with LazyLogging {

  override def onAdd(krb: CustomResource[Kerb, Status]): F[NewStatus[Status]] =
    F.delay(
      logger.info(s"new Krb added: ${krb.spec}, ${krb.metadata}")
    ) *> F.pure(Some(Status(true)))

  override def onDelete(krb: CustomResource[Kerb, Status]): F[Unit] =
    F.delay(logger.info(s"Krb deleted: ${krb.spec}, ${krb.metadata}"))

  override def onModify(krb: CustomResource[Kerb, Status]): F[NewStatus[Status]] =
    F.delay(
        logger.info(s"Krb modified: ${krb.spec}, ${krb.metadata}")
    ) *> F.pure(Some(Status(true)))
  
  override def onInit(): F[Unit] =
    F.delay(logger.info(s"init completed"))
}
```

where ```type NewStatus[U] = Option[U]```

All methods have default implementation as `F.pure(None)` or `F.unit`, so override only necessary methods for your custom controller.

`onInit` - is called before controller is started. In terms **fabric8** client, **onInit** is called before watcher 
is started to watch for custom resources or ConfigMap resources.

`onAdd`, `onDelete`, `onModify` - are called whenever corresponding event is triggered by Kubernetes api-server.

`onAdd` and `onModify` - allows to set new custom resource status by returning a value of `F[Option[U]]` in these methods.
`U` is a type of status case class.

### 3 . Start your operator

```scala mdoc
import cats.effect.{ExitCode, IO, IOApp}
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import freya.K8sNamespace.Namespace
import freya.Operator
import freya.Configuration.CrdConfig
import freya.json.jackson._

object KerbCrdOperator extends IOApp {
  //implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] = {
    val client = IO(new DefaultKubernetesClient)
    val cfg = CrdConfig(Namespace("test"), prefix = "io.myorg.kerboperator")

    Operator
      .ofCrd[IO, Kerb, Status](cfg, client, new KerbController[IO])
      .run
  }
}
```

Operator's `run` method returns an `IO[ExitCode]`, which is running a web-socket connection to Kubernetes api-server.
Returned `IO` value is a long-running and server-like task, which terminates only if K8s api-server closes client 
connection. Running Operator is watching for events:
 - for customer resources with `Kerb` kind and apiGroup `io.myorg.kerboperator/v1`, in case of CRD Operator
 - for ConfigMap kind with label `io.myorg.kerboperator/kind=Kerb`, in case of ConfigMap Operator

## Event Dispatching

![Freya Runtime](/img/freya_runtime.png)

Freya dispatches api-server events concurrently accross different namespaces, but in original order within the same namespace. Supplied controller will be called concurrently, thus any state variables of the controller needs to be thread-safe or atomic. In order to use single-threaded dispatch, one can set `false` to `Configuration#concurrentController`. 

Concurrent dispatching is maintaining in-memory queues per namespace to buffer received events for a short time. These events are dispatched one by one to the controller. 

## Restart configuration

Freya can automatically restart your operator in case of any failure during the CRs/ConfigMaps event listening.
In terms Cats-Effect IO, once IO task is completed, which means Freya Operator has exited from its normal
listening process, it will be restarted with the same parameters. There are few options to control restart behaviour.

### Retry infinitely with random delay

```scala mdoc:compile-only
import cats.effect.IO
import freya.Retry.Infinite
import freya.Operator
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

val cfg = CrdConfig(Namespace("test"), prefix = "io.myorg.kerboperator")
val client = IO(new DefaultKubernetesClient)

Operator
  .ofCrd[IO, Kerb, Status](cfg, client, new KerbController[IO])
   .withRestart(Infinite(minDelay = 1.second, maxDelay = 10.seconds))
```

`Infinite` type will restart operator infinitely making random delay between retries within `[minDelay, maxDelay)` time range.

### Retry with fixed number of restarts

```scala mdoc:compile-only
import cats.effect.IO
import freya.Retry.Times
import freya.Operator
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

val cfg = CrdConfig(Namespace("test"), prefix = "io.myorg.kerboperator")
val client = IO(new DefaultKubernetesClient)

Operator
  .ofCrd[IO, Kerb, Status](cfg, client, new KerbController[IO])
   .withRestart(Times(maxRetries = 3, delay = 2.seconds, multiplier = 2))
```

Above configuration will lead to the following delay in seconds: 2, 4 and 8. `multiplier` is used to 
calculate next delay by `previous delay * multiplier`.

## Deploy CRD manually

In order to disable automatic deployment of Custom Resource Definition as well as OpenAPi schema, one can
set false in `freya.Configuration.CrdConfig.deployCrd = false`. Operator will expect to find a CRD in K8s during the startup, it 
won't try to deploy new CRD, even if CRD is not found. However, what may happen in case CRD is not found and `deployCrd`
is `false`, then operator will fail and return failed `IO` value immediately. Freya Operator can't work without CRD being
retrieved from K8s api-server. 

Manual deployment of CRD is usually done with YAML files using tools like `kubectl`.   

## Controller Helpers

Both types of controllers can be constructed using helper as input parameter. Helper has several useful properties and
method to retrieve current resources for CRD or ConfigMap kinds. Although, the same functionality can be written
within Operator code manually.

### CRD Helper

```scala mdoc:compile-only
import cats.effect.IO
import freya.CrdHelper
import freya.models.NoStatus 
import scala.concurrent.ExecutionContext

val cfg = CrdConfig(Namespace("test"), prefix = "io.myorg.kerboperator")
val client = IO(new DefaultKubernetesClient)
val controller = (helper: CrdHelper[IO, Kerb, NoStatus]) =>
  new Controller[IO, Kerb, NoStatus] {

    override def onInit(): IO[Unit] =
      helper.currentResources().fold(
        IO.raiseError, // refusing to process
        r =>
            IO(r.foreach {
                case Left((error, r)) => 
                  println(s"Failed to parse CR instances $r, error = $error")
                case Right(resource) => 
                  println(s"current ${cfg.getKind} CRs: ${resource.spec}")
            })
      )
  }

Operator
  .ofCrd[IO, Kerb, NoStatus](cfg, client, controller)
  .withRestart()
```

`CrdHelper` provides several properties such as: 

-   `freya.Configuration.CrdConfig` - configuration which is passed on operator construction
-   `io.fabric8.kubernetes.client.KubernetesClient` - K8s client
-   `Option[Boolean]` - isOpenShift property
-   `io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition` - custom resource definition object
-   `freya.resource.CrdParser` - CR parser to parse `freya.watcher.AnyCustomResource#spec` to target `T` kind.