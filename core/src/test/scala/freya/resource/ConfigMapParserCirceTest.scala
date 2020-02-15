package freya.resource

import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalatest.matchers.should.Matchers
import freya.Kerb
import freya.CM
import freya.yaml.circe._
import org.scalacheck.Gen

class ConfigMapParserCirceTest extends AnyPropSpec with ScalaCheckPropertyChecks with Matchers with CirceCodecs {
  val cmParser = new ConfigMapParser()

  property("ConfigMapParser parses valid spec") {
    forAll(CM.genBoth[Kerb](Map.empty)) { case (cm, kerb) =>
      val parsed = freya.parseCM(cmParser, cm)
      parsed should ===(kerb)
    }
  }

  property("ConfigMapParser returns error result when spec is invalid") {
    forAll(CM.gen[Kerb], Gen.alphaNumStr) { case (cm, s) =>
      cm.getData.put(ConfigMapParser.SpecificationKey, s)
      val parsed = cmParser.parseCM[Kerb](cm)
      parsed.isLeft should ===(true)
    }
  }
}