# Freya

![](https://github.com/novakov-alexey/freya/workflows/Scala%20CI/badge.svg?branch=master)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/b91f0a22195e499c9d6bafd097c69dd6)](https://www.codacy.com/manual/novakov.alex/freya?utm_source=github.com&utm_medium=referral&utm_content=novakov-alexey/freya&utm_campaign=Badge_Grade)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.novakov-alexey/freya_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.github.novakov-alexey/freya_2.13)
<a href="https://typelevel.org/cats/"><img src="https://typelevel.org/cats/img/cats-badge-tiny.png" alt="Cats friendly" /></a> 

<img src="https://novakov-alexey.github.io/freya/img/freya_logo.png" alt="freya_logo" width="200"/>

Freya is a Scala library to implement custom controllers for Kubernetes (K8s) easily. 
An implementation of custom controller is also known as [Operator Pattern](https://kubernetes.io/docs/concepts/extend-kubernetes/operator/). 
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
1. Auto-deployment of JSON Schema for CRD validation.
1. Effect management and Functional Programming is powered by Cats-Effect.    

## Examples	

-   Kerberos Operator: [https://github.com/novakov-alexey/krb-operator](https://github.com/novakov-alexey/krb-operator) 

## SBT dependency

```scala
"io.github.novakov-alexey" %% "freya" % "@VERSION@" // for Scala 2.13 only at the moment
```

## Documentation

Microsite: [https://novakov-alexey.github.io/freya/docs/](https://novakov-alexey.github.io/freya/docs/)
