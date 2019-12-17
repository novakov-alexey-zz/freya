# Freya

![](https://github.com/novakov-alexey/freya/workflows/Scala%20CI/badge.svg?branch=master)

Freya is a Scala library to implement custom controllers for Kubernetes (K8s) easily. 
Implementation of custom controller is also known as **Operator Pattern**. 
Freya is based on [fabric8 kubernetes client](https://github.com/fabric8io/kubernetes-client) and 
inspired by [abstract-operator](https://github.com/jvm-operators/abstract-operator) Java library.

Freya main features:
1. Two options to implement your Kubernetes Operator:
    - Custom Resource Definition (CRD) based
    - ConfigMap based
1. Scala Case Classes as Kubernetes resource specification. 
    Serialization and deserialization of case classes is done automatically by Freya and it is powered by
    Jackson Scala Module library.
1. Auto-restart of custom controller upon k8s api-server disconnect.
1. Auto-deployment of CRDs, no YAML files to be written.
1. Auto-deployment of Json Schema for CRD validation.
1. Effect management and Functional Programming is powered by Cats-Effect.    

## SBT dependency

```scala
"io.github.novakov-alexey" %% "freya" % "0.1.0" // for Scala 2.13 only at the moment
``` 

## How to use

N.B. : further in the documentation, _Controller_ and _Operator_ definitions are used as synonymous.

Let's take an example of some controller like Kerberos principal list, which needs to be propagated to KDC database. 

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

Using ConfigMap option:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: my-krb1
  namespace: test
  labels:
    io.myorg.kerboperator/kind: Kerb
data:
  config: |
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
 ConfigMap is a native resource, so no definition needs to be created in Kubernetes at all.

For the sake of example, we are not going to create any container with Kerberos server running in it, but just showing 
how Freya can help to watch our custom resources or ConfigMaps. Particular controller actions to be implemented by 
controller author using **fabric8** kubernetes-client. Freya is only a facilitator between K8s api-server and 
your custom controller actions.

### Implementation Steps with Freya

There are 3 steps to implement CRD or ConfigMap Operator:

1 . Define resource specification as a hierarchy of case classes. Above Kerberos spec can be designed as two 
case classes `Kerb` and `Principal`

```scala
final case class Principal(name: String, password: String, value: String = "")
final case class Kerb(realm: String, principals: List[Principal])
```

2 . Implement your actions for Add, Modify, Delete events by extending
`freya.Controller` abstract class:

Crd Controller option:

```scala
import com.typesafe.scalalogging.LazyLogging
import cats.effect.ConcurrentEffect
import freya.{Controller, Metadata}

class KerbController[F[_]](implicit F: ConcurrentEffect[F]) 
  extends Controller[F, Kerb] with LazyLogging {

  override def onAdd(krb: Kerb, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"new Kerb added: $krb, $meta"))

  override def onDelete(krb: Kerb, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"Kerb deleted: $krb, $meta"))

  override def onModify(krb: Kerb, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"Kerb modified: $krb, $meta"))
  
  override def onInit(): F[Unit] =
    F.delay(logger.info(s"init completed"))
}
```

ConfigMap Controller option:

```scala
import cats.effect.ConcurrentEffect
import io.fabric8.kubernetes.api.model.ConfigMap
import freya.{Controller, CmController}

class KrbCmController[F[_]](implicit F: ConcurrentEffect[F]) 
  extends Controller[F, Kerb] with CmController {

  // override onAdd, onDelete, onModify like in Crd Controller 

  override def isSupported(cm: ConfigMap): Boolean =
    cm.getMetadata.getName.startsWith("krb")
}
```

`CmController` trait adds `isSupported` method, which allows to skip particular ConfigMaps if they do not 
satisfy to logical condition.

All methods have default implementation as  `F.unit`, so override only necessary methods for your custom controller.

`onInit` - is called before controller is started. In terms **fabric8** client, **onInit** is called before watcher 
is started to watch for custom resources or config map resources.

`onAdd`, `onDelete`, `onModify` - are called whenever corresponding event is triggered by Kubernetes api-server.

3 . Start your operator

Crd Operator option: 

```scala
import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, IO, IOApp}
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import freya.K8sNamespace.Namespace
import freya.Operator
import freya.OperatorCfg.Crd

object KerbCrdOperator extends IOApp {
  implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] = {
    val client = IO(new DefaultKubernetesClient)
    val cfg = Crd(classOf[Kerb], Namespace("test"), prefix = "io.myorg.kerboperator")

    Operator
      .ofCrd[IO, Kerb](cfg, client, new KerbController[IO])
      .run
  }
}
```

ConfigMap Operator option:

```scala
import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, IO, IOApp}
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import freya.K8sNamespace.Namespace
import freya.OperatorCfg.ConfigMap
import freya.Operator

object KerbCmOperator extends IOApp {
  implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] = {
    val client = IO(new DefaultKubernetesClient)
    
    // ... the same API as for Crd Operator, but with own configuration and constructor
    val cfg = ConfigMap(classOf[Kerb], Namespace("test"), prefix = "io.myorg.kerboperator")

    Operator
      .ofConfigMap[IO, Kerb](cfg, client, new KrbCmController[IO])
      .run
  }
}
```

Operator's `run` method returns an `IO[ExitCode]`, which is running a web-socket connection to Kubernetes api-server.
Returned `IO` value is a long-running and server-like task, which terminates only if K8s api-server closes client connection.   
Running Operator is watching for events with `Kerb` kind and apiGroup `io.myorg.kerboperator/v1` in case of CRD Operator or 
native ConfigMap kind with label `io.myorg.kerboperator/kind=Kerb` in case of ConfigMap Operator.

## Configuration

Crd Operator:

```scala
import freya.OperatorCfg.Crd
import freya.K8sNamespace.Namespace
import freya.AdditionalPrinterColumn

Crd(
  // CRD kind to register and watch
  forKind = classOf[Kerb], 
  // namespace to watch for events in
  namespace = Namespace("test"), 
  // CRD api prefix 
  prefix = "io.myorg.kerboperator",
  // Check on startup whether current K8s is an OpenShift   
  checkK8sOnStartup = true, 
  // if None, then kind name is taken from case class name, i.e. Kerb
  customKind = Some("Kerberos"),
  // deploy CRD on startup, if no CRD already exists in K8s
  deployCrd = true,
  // short names for CRD when using kubectl, like kubectl get kr (instead of kerb) 
  shortNames = List("kr"),
  // plural name for CRD when using kubectl
  pluralName = "kerbs",
  // columns to be printed when using kubectl
  additionalPrinterColumns = List(
    AdditionalPrinterColumn(name = "realm", columnType = "string", jsonPath = "realm")
  )
)
```

ConfigMap Operator:

```scala
import freya.OperatorCfg.ConfigMap
import freya.K8sNamespace.AllNamespaces

ConfigMap(
  // ConfigMap label value to watch for event
  forKind = classOf[Kerb], 
  // namespace to watch for events in
  namespace = AllNamespaces, 
  // CRD api prefix 
  prefix = "io.myorg.kerboperator",
  // Check on startup whether current K8s is an OpenShift    
  checkK8sOnStartup = true, 
  // if None, then `kind` name is a case class name, i.e. Kerb
  customKind = Some("Kerberos")
)
```

## Restart configuration

Operator can be launched with restart configuration. In case Operator web-socket connection
is closed, then it will be restarted according to `Retry` configuration.

### Retry with infinitely with random delay

```scala
import cats.effect.IO
import freya.Retry.Infinite
import freya.Operator
import scala.concurrent.duration._

Operator
  .ofCrd[IO, Kerb](cfg, client, new KrbController[IO])
   .withRestart(Infinite(minDelay = 1.second, maxDelay = 10.seconds))
```

`Infinity` type will restart operator infinitely making random delay between retries within `[minDelay, maxDelay)` time range.

### Retry with fixed number of restarts

```scala
import cats.effect.IO
import freya.Retry.Times
import freya.Operator
import scala.concurrent.duration._

Operator
  .ofCrd[IO, Kerb](cfg, client, new KrbController[IO])
   .withRestart(Times(maxRetries = 3, delay = 2.seconds, multiplier = 2))
```

Above configuration will lead to the following delay in seconds: 2, 4 and 8. `multiplier` is used to 
calculate next delay by `previous delay * multiplier`.

## Deploy JSON Schema

Put JSON file in CLASSPATH at `schema/<kind>.{json|js}` path, in order to deploy JSON schema as `OpenApi v.3` together with 
CRD definition automatically during the Operator startup.

For Kerberos Operator example, JSON Schema looks the following.

At resources/schema/kerb.json:

```json
{
  "type": "object",
  "properties": {
    "spec": {
      "type": "object",
      "properties": {
        "realm": {
          "type": "string"
        },
        "principals": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "name": {
                "type": "string"
              },
              "password": {
                "type": "string"
              },
              "value": {
                "type": "string"
              }
            },
            "required": [
              "name",
              "password"
            ]
          }
        }
      },
      "required": [
        "realm",
        "principals"        
      ]
    }
  }
}
``` 

## Deploy CRD manually

In order to disable automatic deployment of Custom Resource Definition as well as OpenAPi schema, one can
set false in `OperatorCfg.Crd#deployCrd = false`. Operator will expect to find a CRD in K8s during the startup, it 
won't try to deploy new CRD, even if CRD is not found. However, what may happen in case CRD is not found and `deployCrd` is to `false`, operator will fail and return failed `IO` value immediately. Freya Operator can't work properly without CRD being retrivied from K8s api-server.   

## Controller Helpers

Both types of controllers can be constructed using helper as input parameter. Helper has several useful properties and
method to retrieve current resources for CRD or ConfigMap kinds. Although, the same functionality can be written
within Operator code manually.

### CRD Helper

```scala
val controller = (helper: CrdHelper[IO, Kerb]) =>
  new Controller[IO, Kerb] {

    override def onInit(): IO[Unit] =
      IO {
        helper.currentResources.fold(
          errors => logger.error("Failed to get current CRD instances", errors.map(_.getMessage).mkString("\n")),
          crds => logger.info(s"current ${cfg.getKind} CRDs: $crds")
        )
      }
  }

Operator
  .ofCrd[IO, Kerb](cfg, client)(controller)
  .withRestart()
```

`CrdHelper` provides several properties such as: 
  - `freya.Operator.Crd` - configuration which is passed on operator construction
  - `io.fabric8.kubernetes.client.KubernetesClient` - K8s client
  - `Option[Boolean]` - isOpenShift property
  - `io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition` - CR definition object
  - `freya.resource.CrdParser` - CRD parser to parse `SpecClass#spec` to target `T` kind.

### ConfigMap Helper

`ConfigMapHelper` provides the same functionality as `CrdHelper`, but with respect to ConfigMap kind:

`currentConfigMaps` - a method to return current current ConfigMap resources based on passed earlier Operator 
configuration

Properties:
  - `freya.Operator.ConfigMap` - configuration that which is passed on operator construction 
  - `io.fabric8.kubernetes.client.KubernetesClient` - K8s client
  - `Option[Boolean]` - isOpenShift property
  - `freya.resource.ConfigMapParser` - ConfigMap parser to parse `config` key of data map to target `T` kind
 
### fabric8 Kubernetes dependencies

Freya depends on two fabric8 modules such as kubernetes-client and kubernetes-model. Client, which needs to
be passed as parameter to Freya operator is not going to be closed upon controller close event or restarts. 
Client should be managed separately, when it comes to shutdown of the operator by some event. 

fabric8 kubernets-client
has its own pool of HTTP connections and it is powered by OkHttp library internally. This HTTP connection pool does not use Cats `ContextShift` (or Scala Global ExecutionContext) at all. Cats `ContextShift` is used by Freya to dispatch events from K8s to custom controlller.

### Logging

Freya is using TypeSafe scala-logging as frontend library. Backend or implementation logging library 
should be provided by custom controller runtime, for example `logback-classic`.

### Future work

1. Add cross-build for Scala 2.12
1. Decouple CRD and ConfigMap Operators and make separate Scala modules for them.
