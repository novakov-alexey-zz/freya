package freya

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import freya.K8sNamespace.AllNamespaces
import freya.internal.crd.{AnyCrDoneable, AnyCrList}
import freya.internal.kubeapi.MetadataApi
import freya.resource.ConfigMapParser
import freya.watcher.AnyCustomResource
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfter, DoNotDiscover, Ignore}
import org.scalatestplus.scalacheck.{Checkers, ScalaCheckPropertyChecks}

// Ignoring, because mock library has bug related to watchers.
@DoNotDiscover
@Ignore
class ServerMockTest
    extends AnyPropSpec
    with Matchers
    with Checkers
    with ScalaCheckPropertyChecks
    with Eventually
    with BeforeAndAfter
    with LazyLogging {

  implicit val patienceCfg: PatienceConfig = PatienceConfig(scaled(Span(25, Seconds)), scaled(Span(50, Millis)))

  val server = new KubernetesServer(false, true)

  before {
    server.before()
  }

  after {
    server.after()
  }

  property("Crd operator handles different events") {
    import freya.json.jackson._
    val client = server.getClient
    val cfg = Configuration.CrdConfig(AllNamespaces, prefix)

    val controller = new CrdTestController[IO]
    val operator = Operator.ofCrd[IO, Kerb, Status](cfg, IO.pure(client), controller).run
    val cancelable = startOperator(operator)
    val crd = client.customResourceDefinitions.withName("kerbs.io.github.novakov-alexey").get()
    val krbClient = client
      .customResources(
        crd,
        classOf[AnyCustomResource],
        classOf[AnyCrList],
        classOf[AnyCrDoneable]
      )

    forAll(WatcherAction.gen, AnyCustomResource.gen[Kerb](cfg.getKind)) { (action, ic) =>
      val ns = new NamespaceBuilder().withNewMetadata.withName(ic.getMetadata.getNamespace).endMetadata.build
      client.namespaces().create(ns)

      krbClient
        .inNamespace(ic.getMetadata.getNamespace)
        .create(ic)

      val meta = MetadataApi.translate(ic.getMetadata)

      eventually {
        controller.events should contain((action, ic.getSpec, meta))
      }
    }

    cancelable.unsafeRunSync()
  }

  property("ConfigMap operator handles different events") {
    import freya.yaml.jackson._
    val client = server.getClient
    val cfg = Configuration.ConfigMapConfig(AllNamespaces, prefix, checkK8sOnStartup = false)
    val controller = new ConfigMapTestController[IO]
    val operator = Operator.ofConfigMap[IO, Kerb](cfg, IO.pure(client), controller).run
    val cancelable = startOperator(operator)

    val parser = new ConfigMapParser()
    forAll(WatcherAction.gen, CM.gen[Kerb]) { (action, cm) =>
      //when
      val ns = new NamespaceBuilder().withNewMetadata.withName(cm.getMetadata.getNamespace).endMetadata.build
      client.namespaces().create(ns)
      client.configMaps().inNamespace(cm.getMetadata.getNamespace).create(cm)

      val meta = MetadataApi.translate(cm.getMetadata)
      val spec = parseCM(parser, cm)
      //then
      eventually {
        controller.events should contain((action, spec, meta))
      }
    }

    cancelable.unsafeRunSync()
  }
}
