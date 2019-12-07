package io.github.novakovalexey.k8soperator

import cats.effect.IO
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import io.github.novakovalexey.k8soperator.common.{ConfigMapHelper, CrdHelper}
import io.github.novakovalexey.k8soperator.internal.crd.{InfoClassDoneable, InfoList}
import io.github.novakovalexey.k8soperator.internal.resource.{ConfigMapParser, CrdParser}
import io.github.novakovalexey.k8soperator.watcher.InfoClass
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

  property("ConfigMap helper should return current ConfigMaps") {
    //given
    val cfg = ConfigMapConfig(classOf[Kerb], AllNamespaces, prefix, checkK8sOnStartup = false)
    val client = server.getClient
    val parser = new ConfigMapParser()
    val helper = new ConfigMapHelper[IO, Kerb](cfg, client, None, parser)

    val maps = helper.currentConfigMaps
    maps should be(Right(Map.empty))

    val currentCms = mutable.Map.empty[Metadata, Kerb]

    forAll(CM.gen[Kerb](helper.selector)) { cm =>
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

  ignore("Crd helper should return current Crds") {
    //given
    val cfg = CrdConfig(classOf[Kerb], Namespace("test"), prefix, checkK8sOnStartup = false)
    val client = new DefaultKubernetesClient() // mock server does not work properly with CRDs
    val parser = new CrdParser()
    val crd = CrdHelper.deployCrd[IO, Kerb](client, cfg, None).unsafeRunSync()
    val helper = new CrdHelper[IO, Kerb](cfg, client, None, crd, parser)
    val krbClient = client
      .customResource(crd, classOf[InfoClass[Kerb]], classOf[InfoList[Kerb]], classOf[InfoClassDoneable[Kerb]])

    val maps = helper.currentResources
    maps should be(Right(Map.empty))

    val currentCrds = mutable.Map.empty[Metadata, Kerb]

    forAll(InfoClass.gen[Kerb](cfg.getKind)) { ic =>
      ic.getMetadata.setNamespace("test")
      //when
      val ns = new NamespaceBuilder().withNewMetadata.withName(ic.getMetadata.getNamespace).endMetadata.build
      client.namespaces().create(ns)
      krbClient
        .inNamespace(ic.getMetadata.getNamespace)
        .createOrReplace(ic)

      val meta = Metadata(ic.getMetadata.getName, ic.getMetadata.getNamespace)
      currentCrds += (meta -> ic.getSpec)
      //then
      eventually {
        helper.currentResources should be(Right(currentCrds))
      }

      krbClient
        .inNamespace(ic.getMetadata.getNamespace)
        .delete(ic)
    }
  }

  before {
    server.before()
  }

  after {
    server.after()
  }
}
