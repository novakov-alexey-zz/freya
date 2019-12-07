package io.github.novakovalexey.k8soperator

import cats.effect.IO
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import io.github.novakovalexey.k8soperator.common.CrdHelper
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

class CrdDeployerTest extends AsyncFlatSpec with Matchers with BeforeAndAfterAll {
  val server = new KubernetesServer(false, true)

  it should "deploy CRD" in {
      val client = server.getClient
      val cfg = CrdConfig(classOf[Kerb], AllNamespaces, prefix)
      val crd = CrdHelper.deployCrd[IO, Kerb](client, cfg, None)
      crd.map { c =>
        c.getMetadata.getName should ===(s"${cfg.getPluralCaseInsensitive}.$prefix")
      }.unsafeToFuture()
    }

  override protected def beforeAll(): Unit = server.before()

  override protected def afterAll(): Unit = server.after()
}
