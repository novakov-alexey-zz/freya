---
layout: docs
title: ConfigMap Operator
permalink: docs/configmap-operator/
position: 3
---


# ConfigMap Operator

Please first look at CRD Operator documentation. It contains more common information, which is applicable to ConfigMap
operator too.

`ConfigMap` is a native resource, so that no custom definition needs to be created in Kubernetes before we start creating
custom resources.

Using `ConfigMap` option, our target custom resource will look like this:

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

Please note, above `data.config` section does not define any Custom Resources in terms of Kubernetes, we just leverage 
a plain `ConfigMap` to emulate custom resource.


## Implementation Steps with Freya

There are 3 steps to implement ConfigMap Operator:

### 1 . Define resource specification as a hierarchy of case classes. 

```scala mdoc:reset-object
final case class Principal(name: String, password: String, value: String = "")
final case class Kerb(realm: String, principals: List[Principal])
```

### 2 . Implement your actions for Add, Modify, Delete events

```scala mdoc
import freya.CmController
import cats.effect.ConcurrentEffect
import io.fabric8.kubernetes.api.model.ConfigMap

class KrbCmController[F[_]](implicit F: ConcurrentEffect[F]) 
  extends CmController[F, Kerb] {

  // override onAdd, onDelete, onModify like in Crd Controller 

  override def isSupported(cm: ConfigMap): Boolean =
    cm.getMetadata.getName.startsWith("krb")
}
```

`CmController` class adds `isSupported` method, which allows to skip particular ConfigMaps if they do not 
satisfy to logical condition.

### 3 . Start your operator

```scala mdoc:compile-only
import cats.effect.{ContextShift, ExitCode, IO, IOApp}
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import freya.K8sNamespace.Namespace
import freya.Configuration.ConfigMapConfig
import freya.Operator
import freya.yaml.jackson._

object KerbCmOperator extends IOApp {
  implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] = {
    val client = IO(new DefaultKubernetesClient)
    
    // ... the same API as for Crd Operator, but with own configuration and constructor
    val cfg = ConfigMapConfig(Namespace("test"), prefix = "io.myorg.kerboperator")

    Operator
      .ofConfigMap[IO, Kerb](cfg, client, new KrbCmController[IO])
      .run
  }
}
```

### ConfigMap Helper

`ConfigMapHelper` provides the same functionality as `CrdHelper`, but with respect to ConfigMap kind:

`currentConfigMaps` - a method to return current current ConfigMap resources based on passed earlier Operator 
configuration

Properties:

-   `freya.Configuration.ConfigMapConfig` - configuration which is passed on operator construction 
-   `io.fabric8.kubernetes.client.KubernetesClient` - K8s client
-   `Option[Boolean]` - isOpenShift property
-   `freya.resource.ConfigMapParser` - ConfigMap parser to parse `config` key of data map to target `T` kind