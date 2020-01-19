package freya

import freya.K8sNamespace.AllNamespaces
import freya.Configuration.CrdConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ValidateCfgTest extends AnyFlatSpec with Matchers {

  it should "validate Config" in {
      //given
      val cfg = CrdConfig[Kerb](AllNamespaces, prefix)
      //when
      val v = cfg.validate
      //then
      v.isRight should ===(true)
    }

  it should "validate kind" in {
      //given
      val cfg = CrdConfig[Kerb](AllNamespaces, "")
      //when
      val v = cfg.validate
      //then
      v.isLeft should ===(true)
    }

  it should "validate prefix" in {
      //given
      val cfg = CrdConfig[Kerb]( AllNamespaces, "")
      //when
      val v = cfg.validate
      //then
      v.isLeft should ===(true)

      //given
      val cfg2 = CrdConfig[Kerb](AllNamespaces, null)
      //when
      val v2 = cfg2.validate
      //then
      v2.isLeft should ===(true)
    }
}
