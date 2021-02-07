---
title: Dependencies
---

<!-- # Dependencies -->

## fabric8 kubernetes-client

Freya depends on two fabric8 modules such as `kubernetes-client` and `kubernetes-model`. Client, which needs to
be passed as parameter to Freya operator is not going to be closed upon controller close event or restarts. 
Client should be managed separately, when it comes to shutdown of the operator by some event. 

fabric8 kubernetes-client has its own pool of HTTP connections and it is powered by OkHttp library internally. 
This HTTP connection pool does not use Cats `ContextShift` (or Scala Global ExecutionContext) at all. 
Cats `ContextShift` is used by Freya to dispatch events from K8s to custom controller.

## scala-logging

Freya is using TypeSafe scala-logging as frontend library. Backend or implementation logging library 
should be provided by custom controller runtime, for example `logback-classic`.
