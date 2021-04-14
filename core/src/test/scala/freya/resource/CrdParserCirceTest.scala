package freya.resource

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import freya.json.circe._
import freya.watcher.AnyCustomResource
import freya.{Kerb, Status}
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class CrdParserCirceTest extends AnyPropSpec with ScalaCheckPropertyChecks with Matchers with CirceCodecs {
  val parser = new CrdParser
  val mapper = new ObjectMapper
  mapper.registerModule(DefaultScalaModule)

  property("CrdParser parses valid spec") {
    forAll(freya.AnyCustomResource.gen[Kerb]()) { case (anyCr, spec, status) =>
      //when
      val parsed = parser.parseStr[Kerb, Status](mapper.writeValueAsString(anyCr))
      //then
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
