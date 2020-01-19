package freya.resource

import freya.watcher.AnyCustomResource
import freya.{Kerb, KerbStatus, AnyCustomResource}
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class CrdParserTest extends AnyPropSpec with ScalaCheckPropertyChecks with Matchers {
  val parser = new CrdParser

  property("CrdParser parses valid spec") {
    forAll(AnyCustomResource.gen[Kerb]("kerb")) { spec =>
      val parsed = parser.parse(classOf[Kerb], classOf[KerbStatus], spec)
      parsed should ===(Right((spec.getSpec, spec.getStatus)))
    }
  }

  property("CrdParser returns error result when spec is invalid") {
    forAll { s: String =>
      val spec = new AnyCustomResource()
      spec.setSpec(s)
      val parsed = parser.parse(classOf[Kerb], classOf[KerbStatus], spec)
      parsed.isLeft should ===(true)
    }
  }
}
