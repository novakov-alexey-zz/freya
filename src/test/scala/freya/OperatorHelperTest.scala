package freya

import cats.effect.IO
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import freya.Configuration.CrdConfig
import freya.K8sNamespace.{AllNamespaces, Namespace}
import freya.internal.api.MetadataApi
import freya.internal.crd.{AnyCrDoneable, AnyCrList, Deployer}
import freya.models.{CustomResource, Resource}
import freya.resource.{ConfigMapParser, CrdParser}
import freya.watcher.AnyCustomResource
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import io.fabric8.kubernetes.client.utils.Serialization
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.scalacheck.{Checkers, ScalaCheckPropertyChecks}

import scala.collection.mutable

class OperatorHelperTest
    extends AnyPropSpec
    with Matchers
    with Checkers
    with ScalaCheckPropertyChecks
    with Eventually
    with BeforeAndAfter {

  implicit val patienceCfg: PatienceConfig = PatienceConfig(scaled(Span(5, Seconds)), scaled(Span(50, Millis)))
  val server = new KubernetesServer(false, true)
  val testNs: Namespace = Namespace("test")

  before {
    server.before()
  }

  after {
    server.after()
  }

  property("ConfigMap helper should return current ConfigMaps in all namespaces") {
    testCmHelper(AllNamespaces)
  }

  property("ConfigMap helper should return current ConfigMaps in test namespace") {
    testCmHelper(testNs)
  }

  private def testCmHelper(ns: K8sNamespace) = {
    //given
    val cfg = Configuration.ConfigMapConfig(ns, prefix)
    val client = server.getClient
    val parser = new ConfigMapParser()
    val context = ConfigMapHelperContext(cfg, client, None, parser)
    val helper = new ConfigMapHelper[IO, Kerb](context)

    val maps = helper.currentResources
    maps should ===(Right(List.empty))

    val currentCms = mutable.ArrayBuffer.empty[Resource[Kerb, Unit]]
    val namespace = new NamespaceBuilder().withNewMetadata.withName(ns.value).endMetadata.build
    client.namespaces().create(namespace)

    forAll(CM.gen[Kerb](helper.selector)) { cm =>
      //when
      cm.getMetadata.setNamespace(ns.value)
      client.configMaps().inNamespace(cm.getMetadata.getNamespace).create(cm)

      val meta = MetadataApi.translate(cm.getMetadata)
      val spec = parseCM(parser, cm)

      currentCms += Right(CustomResource(spec, meta, None))
      //then
      helper.currentResources.map(_.toSet) should ===(Right(currentCms.toSet))
    }
  }

  ignore("Crd helper should return current CRDs") {
    //given
    val cfg = CrdConfig(testNs, prefix, checkK8sOnStartup = false)
    val client = new DefaultKubernetesClient() // mock server does not work properly with CRDs
    Serialization.jsonMapper().registerModule(DefaultScalaModule)

    val parser = new CrdParser()
    val crd = Deployer.deployCrd[IO, Kerb](client, cfg, None).unsafeRunSync()
    val context = CrdHelperContext(cfg, client, None, crd, parser)
    val helper = new CrdHelper[IO, Kerb, Status](context)
    val krbClient = client
      .customResources(crd, classOf[AnyCustomResource], classOf[AnyCrList], classOf[AnyCrDoneable])

    //when
    krbClient.inNamespace(testNs.value).delete()

    val maps = helper.currentResources
    //then
    maps should be(Right(Map.empty))

    val currentCrds = mutable.Map.empty[Metadata, Kerb]
    val ns = new NamespaceBuilder().withNewMetadata.withName(testNs.value).endMetadata.build
    client.namespaces().createOrReplace(ns)

    forAll(AnyCustomResource.gen[Kerb](cfg.getKind)) { ic =>
      ic.getMetadata.setNamespace(testNs.value)
      //when
      krbClient
        .inNamespace(ic.getMetadata.getNamespace)
        .createOrReplace(ic)

      val meta = MetadataApi.translate(ic.getMetadata)
      currentCrds += (meta -> ic.getSpec.asInstanceOf[Kerb])
      //then
      eventually {
        helper.currentResources should be(Right(currentCrds))
      }
    }

    krbClient.inNamespace(testNs.value).delete()
  }
}
