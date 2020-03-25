---
layout: docs
title: Configuration
permalink: docs/configuration/
position: 4
---

## Configuration

CRD Operator:

```scala mdoc:compile-only
import freya.Configuration.CrdConfig
import freya.K8sNamespace.Namespace
import freya.AdditionalPrinterColumn

CrdConfig(  
  // namespace to watch for events in
  namespace = Namespace("test"), 
  // CRD api prefix 
  prefix = "io.myorg.kerboperator",
  // size of in-memory queue for received events from Kubernetes api-server, once event queue is full, watcher starts
  // to back-pressure server web-socket, in case operators fails, all events will be gone, so operator needs to be started with reconciliation
  eventQueueSize = 10,
  // If true, different namespace events are dispatched concurrently, i.e. controller will be called concurrently
  // for different namespace events. 
  // The same namespace events will be dispatched in the same order they were originaly recieved from Kubernetes. 
  concurrentController = true,
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

```scala mdoc:compile-only
import freya.Configuration.ConfigMapConfig
import freya.K8sNamespace.AllNamespaces

ConfigMapConfig(  
  // namespace to watch for events in
  namespace = AllNamespaces, 
  // CRD api prefix 
  prefix = "io.myorg.kerboperator",
  // see doc in CrdConfig above
  eventQueueSize = 10,
  // see doc in CrdConfig above
  concurrentController = true,
  // Check on startup whether current K8s is an OpenShift    
  checkK8sOnStartup = true, 
  // if None, then `kind` name is a case class name, i.e. Kerb
  customKind = Some("Kerberos")
)
```