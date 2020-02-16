---
layout: docs
title: Future Work
permalink: docs/future
---

# Future work

1. Add cross-build for Scala 2.12
1. Decouple CRD and ConfigMap Operators and make separate Scala modules for them.
1. Handle different namespace events in parallel. Currently, Freya is dispatching api-server events using single queue 
across all namespaces.