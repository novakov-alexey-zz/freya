---
title: Resource parsing
custom_edit_url: https://github.com/novakov-alexey/freya/edit/master/docs/docs/resource-parsing.md
---

Custom resources or ConfigMaps are parsed into a user defined case class(es). Besides parsing, Freya converts
a user defined case class for status into a JSON string.

Supported libraries to parse JSON/YAML:

Circe:
```scala
"io.github.novakov-alexey" %% "freya-circe" % "@VERSION@" 
```

Jackson:
```scala
"io.github.novakov-alexey" %% "freya-jackson" % "@VERSION@"
```

## Usage

Add import statement at place of constructing your operator:

```scala mdoc
import freya.json.circe._
```

or

```scala mdoc:compile-only
import freya.json.jackson._
```

## Simple example

```scala mdoc:reset-object
final case class Principal(name: String, password: String, value: String = "")
final case class Kerb(realm: String, principals: List[Principal])
final case class Status(ready: Boolean = false)
```

### Jackson Simple example

Jackson Scala module allows parsing simple case classes (no ADT, recursion, etc.) automatically, i.e. no extra code
needs to be written. Thus, only import of the Freya Jackson module is needed.

```scala mdoc
import freya.json.jackson._
```

### Circe Simple example

Circe can derive its decoder/encoders automatically, when using its `generic` module with special import. 

```scala mdoc
import freya.json.circe._
// this import will derive required decoders/encoders 
// for your spec and status case classes
import io.circe.generic.auto._ 
```

Circe auto codecs derivation requires below module in your dependency settings:

```scala
"io.circe" %% "circe-generic" % circeVersion
```

## Advanced example

Let's use ADT (algebraic data types) to design custom resource case class for `Password` and
`Secret` properties. Also, some classes will have default values.

### Advanced example Jackson

Jackson provides several annotations to configure deserialisation for enum-like classes:

```scala mdoc:reset-object
import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id
import com.fasterxml.jackson.annotation.{JsonSubTypes, JsonTypeInfo}
import Secret.{Keytab, KeytabAndPassword}
import Password.{Static, Random}

@JsonTypeInfo(use = Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
  Array(
    new Type(value = classOf[Static], name = "static"), 
    new Type(value = classOf[Random], name = "random")
  )
)
sealed trait Password
object Password {
  final case class Static(value: String) extends Password
  final case class Random() extends Password
}

@JsonTypeInfo(use = Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
  Array(
    new Type(value = classOf[Keytab], name = "Keytab"), 
    new Type(value = classOf[KeytabAndPassword], name = "KeytabAndPassword")
  )
)
sealed trait Secret {
  val name: String
}
object Secret {
  final case class Keytab(name: String) extends Secret
  final case class KeytabAndPassword(name: String) extends Secret
}

final case class Principal(
  name: String, password: Password, 
  keytab: String, secret: Secret
)
final case class Krb(realm: String, principals: List[Principal])
final case class Status(
  processed: Boolean, lastPrincipalCount: Int, 
  totalPrincipalCount: Int, error: String = ""
)
```

### Advanced example Circe 

```scala mdoc:reset-object
sealed trait Password
final case class Static(value: String) extends Password
final case class Random() extends Password

sealed trait Secret {
  val name: String
}

final case class Keytab(name: String) extends Secret
final case class KeytabAndPassword(name: String) extends Secret

final case class Principal(
  name: String, password: Password = Random(), 
  keytab: String, secret: Secret
)
final case class Krb(realm: String, principals: List[Principal])
final case class Status(
  processed: Boolean, lastPrincipalCount: Int, 
  totalPrincipalCount: Int, error: String = ""
)
```


Circe provides generic-extras module to cope with above hierarchy of case classes:

```scala
// note: it is separate Circe module called generic-extras, 
// which has its own version.
"io.circe" %% "circe-generic-extras" % circeExtrasVersion
```


Define encoders and decoders. Let's wrap decoders into a Scala trait for later convenient injection into the operator 
construction site. However, usage of a trait is not necessarily:

```scala mdoc
import cats.syntax.functor._
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.auto._

trait Codecs {
  implicit val genConfig: Configuration =
    Configuration.default.withDiscriminator("type").withDefaults

  implicit val decodePassword: Decoder[Password] =
    List[Decoder[Password]](Decoder[Static].widen, Decoder.const(Random()).widen)
      .reduceLeft(_.or(_))

  implicit val decodeSecret: Decoder[Secret] =
    List[Decoder[Secret]](Decoder[Keytab].widen, Decoder[KeytabAndPassword].widen)
      .reduceLeft(_.or(_))
}
```

Then mixin or import all implicit values from the `Codecs` trait into the operator construction site. 