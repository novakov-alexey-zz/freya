package freya

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import freya.internal.crd.{InfoClassDoneable, InfoList}
import freya.internal.resource.ConfigMapParser
import freya.watcher.InfoClass
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfter, DoNotDiscover, Ignore}
import org.scalatestplus.scalacheck.{Checkers, ScalaCheckPropertyChecks}

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

  property("Crd operator handles different events") {
    val client = server.getClient
    val cfg = CrdConfig(classOf[Kerb], AllNamespaces, prefix)

    val controller = new CrdTestController[IO]
    val operator = Operator.ofCrd[IO, Kerb](cfg, IO.pure(client), controller).run
    val cancelable = startOperator(operator)
    val crd = client.customResourceDefinitions.withName("kerbs.io.github.novakov-alexey").get()
    val krbClient = client
      .customResources(crd, classOf[InfoClass[Kerb]], classOf[InfoList[Kerb]], classOf[InfoClassDoneable[Kerb]])

    forAll(WatcherAction.gen, InfoClass.gen[Kerb](cfg.getKind)) { (action, ic) =>
      val ns = new NamespaceBuilder().withNewMetadata.withName(ic.getMetadata.getNamespace).endMetadata.build
      client.namespaces().create(ns)

      krbClient
        .inNamespace(ic.getMetadata.getNamespace)
        .create(ic)

      val meta = Metadata(ic.getMetadata.getName, ic.getMetadata.getNamespace)

      eventually {
        controller.events should contain((action, ic.getSpec, meta))
      }
    }

    cancelable.unsafeRunSync()
  }

  property("ConfigMap operator handles different events") {
    val client = server.getClient
    val cfg = ConfigMapConfig(classOf[Kerb], AllNamespaces, prefix, checkK8sOnStartup = false)
    val controller = new ConfigMapTestController[IO]
    val operator = Operator.ofConfigMap[IO, Kerb](cfg, IO.pure(client), controller).run
    val cancelable = startOperator(operator)

    val parser = new ConfigMapParser()
    forAll(WatcherAction.gen, CM.gen[Kerb]) { (action, cm) =>
      //when
      val ns = new NamespaceBuilder().withNewMetadata.withName(cm.getMetadata.getNamespace).endMetadata.build
      client.namespaces().create(ns)
      client.configMaps().inNamespace(cm.getMetadata.getNamespace).create(cm)

      val meta = Metadata(cm.getMetadata.getName, cm.getMetadata.getNamespace)
      val spec = parseCM(parser, cm)
      //then
      eventually {
        controller.events should contain((action, spec, meta))
      }
    }

    cancelable.unsafeRunSync()
  }

  before {
    server.before()
  }

  after {
    server.after()
  }
}
