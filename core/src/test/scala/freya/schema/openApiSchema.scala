package freya.schema

import java.io.FileWriter

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.github.andyglow.json.JsonFormatter
import com.github.andyglow.jsonschema.AsValue
import com.kjetland.jackson.jsonSchema.JsonSchemaGenerator
import freya.Kerb
import json._
import json.schema.Version

import scala.util.Using

/**
 * Examples on how to generate JSON Schema for a 'spec' root case class
 */
object ScalaJsonSchema extends App {
  val schema = Json.schema[Kerb]
  val kerb = JsonFormatter.format(AsValue.schema(schema, Version.Draft04()))
  Using.resource(new FileWriter("src/test/resources/schema/kerb.json")) { w =>
    w.write("{\n  \"title\": \"Kerb\",\n  \"type\": \"object\",\n  \"properties\": {\n    \"spec\": ")
    w.write(kerb)
    w.write("    \n  }\n}")
  }
}

object JacksonJsonSchema extends App {
  val objectMapper = new ObjectMapper
  objectMapper.registerModule(DefaultScalaModule)

  val jsonSchemaGenerator = new JsonSchemaGenerator(objectMapper)
  val jsonSchema = jsonSchemaGenerator.generateJsonSchema(classOf[Kerb])

  val jsonSchemaAsString = objectMapper.writeValueAsString(jsonSchema)
  println(jsonSchemaAsString)
}
