package io.github.novakovalexey.k8soperator

import cats.effect.IO
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import io.github.novakovalexey.k8soperator.common.ConfigMapHelper
import io.github.novakovalexey.k8soperator.internal.resource.ConfigMapParser
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.{Checkers, ScalaCheckPropertyChecks}

import scala.collection.mutable

class OperatorHelperTest
    extends AnyPropSpec
    with Matchers
    with Checkers
    with ScalaCheckPropertyChecks
    with Eventually
    with BeforeAndAfter {

  val server = new KubernetesServer(false, true)

  property("helper should return current ConfigMaps") {
    //given
    val cfg = ConfigMapConfig(classOf[Krb2], AllNamespaces, prefix, checkK8sOnStartup = false)
    val client = server.getClient
    val parser = new ConfigMapParser()
    val helper = new ConfigMapHelper[IO, Krb2](cfg, client, None, parser)

    val maps = helper.currentConfigMaps
    maps should be(Right(Map.empty))

    val currentCms = mutable.Map.empty[Metadata, Krb2]

    forAll(CM.gen[Krb2](helper.selector)) { cm =>
      //when
      val ns = new NamespaceBuilder().withNewMetadata.withName(cm.getMetadata.getNamespace).endMetadata.build
      client.namespaces().create(ns)
      client.configMaps().inNamespace(cm.getMetadata.getNamespace).create(cm)

      val meta = Metadata(cm.getMetadata.getName, cm.getMetadata.getNamespace)
      val spec = parseCM(parser, cm)

      currentCms += (meta -> spec)
      //then
      helper.currentConfigMaps should be(Right(currentCms))
    }
  }

  before {
    server.before()
  }

  after {
    server.after()
  }
}
