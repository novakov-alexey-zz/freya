package freya.resource

import freya.json.jackson._
import freya.watcher.AnyCustomResource
import freya.{AnyCustomResource, Kerb, Status}
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class CrdParserJacksonTest extends AnyPropSpec with ScalaCheckPropertyChecks with Matchers {
  val parser = new CrdParser

  property("CrdParser parses valid spec") {
    forAll(AnyCustomResource.gen[Kerb]()) { case (anyCr, spec, status) =>
      val parsed = parser.parse[Kerb, Status](anyCr)
      val expected = Right((spec, Some(status), anyCr.getMetadata))
      parsed should ===(expected)
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
