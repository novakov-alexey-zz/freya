package freya.resource

import freya.json.circe._
import freya.watcher.AnyCustomResource
import freya.{Kerb, Status}
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class CrdParserCirceTest extends AnyPropSpec with ScalaCheckPropertyChecks with Matchers with CirceCodecs {
  val parser = new CrdParser

  property("CrdParser parses valid spec") {
    forAll(freya.AnyCustomResource.gen[Kerb]("kerb")) { case (anyCr, spec, status) =>
      //when
      val parsed = parser.parse[Kerb, Status](anyCr)
      //then
      parsed should ===(Right((spec, Some(status))))
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
