# Freya

[![Build Status](https://travis-ci.org/novakov-alexey/freya.svg?branch=master)](https://travis-ci.org/novakov-alexey/freya)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/b91f0a22195e499c9d6bafd097c69dd6)](https://www.codacy.com/manual/novakov.alex/freya?utm_source=github.com&utm_medium=referral&utm_content=novakov-alexey/freya&utm_campaign=Badge_Grade)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.novakov-alexey/freya-core_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.github.novakov-alexey/freya-core_2.13)
<a href="https://typelevel.org/cats/"><img src="https://typelevel.org/cats/img/cats-badge-tiny.png" alt="Cats friendly" /></a> 

<img src="https://novakov-alexey.github.io/freya/img/freya_logo.png" alt="freya_logo" width="200"/>

Freya is a Scala library to implement custom controllers for Kubernetes (K8s) easily. 
An implementation of custom controller is also known as [Operator Pattern](https://kubernetes.io/docs/concepts/extend-kubernetes/operator/). 
Freya based on [fabric8 kubernetes client](https://github.com/fabric8io/kubernetes-client) and 
inspired by [abstract-operator](https://github.com/jvm-operators/abstract-operator) Java library.

Freya main features:
1. Two options to implement your Kubernetes Operator:
    - Custom Resource Definition (CRD) based
    - ConfigMap based
1. Scala Case Classes as Kubernetes resource specification. 
    Serialization and deserialization of case classes is done by Freya using
    Circe or Jackson Scala Module library.
1. Auto-restart of custom controller upon k8s api-server disconnect.
1. Auto-deployment of CRDs, no YAML files to be written.
1. Auto-deployment of JSON Schema for CRD validation.
1. Effect management and Functional Programming powered by Cats-Effect.    

## Examples	

-   Kerberos Operator: [https://github.com/novakov-alexey/krb-operator](https://github.com/novakov-alexey/krb-operator) 

## SBT dependency

Freya supports Scala 2.13 only at the moment.
Main dependency:

```scala
"io.github.novakov-alexey" %% "freya-core" % "@VERSION@" 
```

Second module has two options: circe or jackson. One of them needs to be added into your
dependencies to be able to read custom resource JSON/YAML text or write resource status as JSON

Circe:
```scala
"io.github.novakov-alexey" %% "freya-circe" % "@VERSION@" 
```

Jackson:
```scala
"io.github.novakov-alexey" %% "freya-jackson" % "@VERSION@"
```

## Documentation

Microsite: [https://novakov-alexey.github.io/freya/docs/](https://novakov-alexey.github.io/freya/docs/)
