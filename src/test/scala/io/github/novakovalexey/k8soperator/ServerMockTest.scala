package io.github.novakovalexey.k8soperator

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import io.github.novakovalexey.k8soperator.internal.crd.{InfoClassDoneable, InfoList}
import io.github.novakovalexey.k8soperator.internal.resource.ConfigMapParser
import io.github.novakovalexey.k8soperator.watcher.InfoClass
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
  val prefix = "io.github.novakov-alexey"

  property("Crd operator handles different events") {
    val client = server.getClient
    val cfg = CrdConfig(classOf[Krb2], AllNamespaces, prefix)

    val controller = new CrdTestController[IO]
    val operator = Operator.ofCrd[IO, Krb2](cfg, IO.pure(client), controller).run
    val cancelable = startOperator(operator)
    val crd = client.customResourceDefinitions.withName("krb2s.io.github.novakov-alexey").get()
    val krbClient = client
      .customResources(crd, classOf[InfoClass[Krb2]], classOf[InfoList[Krb2]], classOf[InfoClassDoneable[Krb2]])

    forAll(WatcherAction.gen, InfoClass.gen[Krb2]) { (action, ic) =>
      val ns = new NamespaceBuilder().withNewMetadata.withName(ic.getMetadata.getNamespace).endMetadata.build
      client.namespaces().create(ns)

      val value1 = krbClient
        .inNamespace(ic.getMetadata.getNamespace)
        .create(ic)

      logger.debug("value1: ", value1)
      val meta = Metadata(ic.getMetadata.getName, ic.getMetadata.getNamespace)

      eventually {
        controller.events should contain((action, ic.getSpec, meta))
      }
    }

    cancelable.unsafeRunSync()
  }

  property("ConfigMap operator handles different events") {
    val client = server.getClient
    val cfg = ConfigMapConfig(classOf[Krb2], AllNamespaces, prefix, checkK8sOnStartup = false)
    val controller = new ConfigMapTestController[IO]
    val operator = Operator.ofConfigMap[IO, Krb2](cfg, IO.pure(client), controller).run
    val cancelable = startOperator(operator)

    val parser = new ConfigMapParser()
    forAll(WatcherAction.gen, CM.gen[Krb2]) { (action, cm) =>
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
