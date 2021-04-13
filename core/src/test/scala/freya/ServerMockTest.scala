package freya

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.LazyLogging
import freya.K8sNamespace.AllNamespaces
import freya.internal.crd.AnyCrList
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
    val operator = Operator.ofCrd[IO, Kerb, Status](cfg, IO.pure(client), controller)
    startOperator(operator.run)
    val krbClient = client.customResources(classOf[AnyCustomResource], classOf[AnyCrList])

    forAll(WatcherAction.gen, AnyCustomResource.gen[Kerb](cfg.getKind)) { case (action, (cr, spec, _)) =>
      val ns = new NamespaceBuilder().withNewMetadata.withName(cr.getMetadata.getNamespace).endMetadata.build
      client.namespaces().create(ns)

      krbClient
        .inNamespace(cr.getMetadata.getNamespace)
        .create(cr)

      val meta = MetadataApi.translate(cr.getMetadata)

      eventually {
        controller.events should contain((action, spec, meta))
      }
    }

    operator.stop.unsafeRunSync()
  }

  property("ConfigMap operator handles different events") {
    import freya.yaml.jackson._
    val client = server.getClient
    val cfg = Configuration.ConfigMapConfig(AllNamespaces, prefix, checkOpenshiftOnStartup = false)
    val controller = new ConfigMapTestController[IO]
    val operator = Operator.ofConfigMap[IO, Kerb](cfg, IO.pure(client), controller)
    startOperator(operator.run)

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

    operator.stop.unsafeRunSync()
  }
}
