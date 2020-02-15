package freya.resource

import freya.watcher.AnyCustomResource
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import freya.Kerb
import freya.Status
import io.circe.syntax._
import freya.json.circe._

class CrdParserCirceTest extends AnyPropSpec with ScalaCheckPropertyChecks with Matchers with CirceCodecs {

  val parser = new CrdParser

  property("CrdParser parses valid spec") {
    forAll(freya.AnyCustomResource.gen[Kerb]("kerb")) { anyCr =>
      //given
      val kerb = anyCr.getSpec
      val status = anyCr.getStatus
      anyCr.setSpec(kerb.asInstanceOf[Kerb].asJson.toString())
      anyCr.setStatus(status.asInstanceOf[Status].asJson.toString())
      //when
      val parsed = parser.parse[Kerb, Status](anyCr)
      //then
      parsed should ===(Right((kerb, Some(status))))
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
