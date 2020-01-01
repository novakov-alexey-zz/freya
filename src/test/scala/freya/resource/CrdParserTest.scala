package freya.resource

import freya.watcher.SpecClass
import freya.{Kerb, SpecClass}
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class CrdParserTest extends AnyPropSpec with ScalaCheckPropertyChecks with Matchers {
  val parser = new CrdParser

  property("CrdParser parses valid spec") {
    forAll(SpecClass.gen[Kerb]("kerb")) { spec =>
      val parsed = parser.parse(classOf[Kerb], spec)
      parsed should ===(Right(spec.getSpec))
    }
  }

  property("CrdParser returns error result when spec is invalid") {
    forAll { s: String =>
      val spec = new SpecClass()
      spec.setSpec(s)
      val parsed = parser.parse(classOf[Kerb], spec)
      parsed.isLeft should ===(true)
    }
  }
}
