package freya.resource

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import freya.watcher.AnyCustomResource
import freya.{AnyCustomResource, Kerb, Status}
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class CrdParserJacksonTest extends AnyPropSpec with ScalaCheckPropertyChecks with Matchers {
  val parser = new CrdParser
  val mapper = new ObjectMapper
  mapper.registerModule(DefaultScalaModule)

  import freya.json.jackson._

  property("CrdParser parses valid spec") {
    forAll(AnyCustomResource.gen[Kerb]("kerb")) { anyCr =>
      val kerb = anyCr.getSpec
      anyCr.setSpec(mapper.writeValueAsString(kerb))
      val parsed = parser.parse[Kerb, Status](anyCr)
      parsed should ===(Right((kerb, Some(anyCr.getStatus))))
    }
  }

  property("CrdParser returns error result when spec is invalid") {
    forAll { s: String =>
      val anyCr = new AnyCustomResource()
      anyCr.setSpec(s)
      val parsed = parser.parse[Kerb, Status](anyCr)
      parsed.isLeft should ===(true)
    }
  }
}
