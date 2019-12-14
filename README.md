# Freya

Freya is a Scala library to implement custom controllers for Kubernetes easily. 
Implementation of custom controller is also known as **Operator Pattern**. 
Freya is based on [fabric8 kubernetes client](https://github.com/fabric8io/kubernetes-client).

Main features:
1. Two options to implement your Kubernetes Operator with Freya:
    - Custom Resource Definition (CRD) based
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

Let's take an example of Kerberos principal list, which needs to be propagated to KDC database. 

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

We are not going to create any container with Kerberos server running in it, but just showing how Freya can help to watch 
our custom resource or ConfigMaps. Particular controller actions to be implemented by controller author. Freya is only
a facilitator between K8s api-server and your custom controller actions.

#### Implementation Steps with Freya

There are 3 steps to implement CRD/ConfigMap Operator:

1 . Define resource specification as a hierarchy of case classes. Let's design above Kerberos spec as two 
case classes `Kerb` and `Principal`

```scala
final case class Principal(name: String, password: String, value: String = "")
final case class Kerb(realm: String, principals: List[Principal])
```

2 . Implement your actions for Add, Modify, Delete events by extending
`io.github.novakovalexey.k8soperator.Controller` abstract class:

Crd Controller option:

```scala
import com.typesafe.scalalogging.LazyLogging
import cats.effect.ConcurrentEffect
import freya.Controller

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
import io.fabric8.kubernetes.api.model.ConfigMap

class KrbCmController[F[_]](implicit F: ConcurrentEffect[F]) 
  extends Controller[F, Kerb] with CmController {

  // override onAdd, onDelete, onModify  

  override def isSupported(cm: ConfigMap): Boolean =
    cm.getMetadata.getName.startsWith("krb")
}
```

CmController adss `isSupported` method which allows to skip particular ConfigMaps if they do not 
satisfy to logical condition.

All methods have default implementation as  `F.unit`, so override only necessary methods for your custom controller.

`onInit` - is called before controller is started. In terms *fabric8* client, `onInit` is called before watcher 
is started to watch for custom resources or config map resources.

`onAdd`, `onDelete`, `onModify` - are called whenever corresponding event is triggered by Kubernetes api-server.

3 . Start your operator

Crd Operator option: 

```scala
import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, IO, IOApp}
import io.fabric8.kubernetes.client.DefaultKubernetesClient

import scala.concurrent.ExecutionContext

object KerbCrdOperator extends IOApp {
  implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] = {
    val client = IO(new DefaultKubernetesClient)
    val cfg = CrdConfig(classOf[Kerb], Namespace("test"), prefix = "io.myorg.kerboperator")

    Operator
      .ofCrd[IO, Kerb](cfg, client, new KerbController[IO])
      .run
  }
}
```

ConfigMap Operator option:

```scala
object KerbCmOperator extends IOApp {
  implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] = {
    val client = IO(new DefaultKubernetesClient)
    
    // ... the same API as for Crd Operator, but with own configuration and constructor
    val cfg = ConfigMapConfig(classOf[Kerb], Namespace("test"), prefix = "io.myorg.kerboperator")

    Operator
      .ofConfigMap[IO, Kerb](cfg, client, new KrbCmController[IO])
      .run
  }
}
```

`run` method on Operator type returns an `IO[ExitCode]`,
 which is running a web-socket client for Kubernetes api-server. Operator is watching for events with `Kerb` kind
 and apiGroup `io.myorg.kerboperator/v1` in case of CRD option or ConfigMap with label `io.myorg.kerboperator/kind=Kerb`
 in case of ConfigMap Operator option.