---
title: Structural Schema
# position: 6
---

<!-- # Structural Schema -->

In order to deploy Structural Schema, aka. JSON Schema, put JSON file in CLASSPATH at `schema/<kind>.{json|js}` path. 
Freya deploys JSON schema together with CR definition automatically during the Operator startup, 
**only if CRD is not found**.

For Kerberos Operator example, a JSON Schema is the following.

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
    },
    "status": {
      "type": "object",
      "properties": {
        "ready": {
          "type": "boolean"
        }
      }
    }
  }
}
```