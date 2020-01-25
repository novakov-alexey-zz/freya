package freya

import cats.effect.IO
import freya.Configuration.CrdConfig
import freya.K8sNamespace.Namespace
import freya.internal.crd.Deployer
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

class CrdDeployerTest extends AsyncFlatSpec with Matchers with BeforeAndAfterAll {
  val server = new KubernetesServer(false, true)

  it should "deploy CRD" in {
    val client = server.getClient
    val cfg = CrdConfig(Namespace("test"), prefix)
    val crd = Deployer.deployCrd[IO, Kerb](client, cfg, None)
    crd.map(_.getMetadata.getName should ===(s"${cfg.kindPluralCaseInsensitive}.$prefix")).unsafeToFuture()
  }

  override protected def beforeAll(): Unit = server.before()

  override protected def afterAll(): Unit = server.after()
}
