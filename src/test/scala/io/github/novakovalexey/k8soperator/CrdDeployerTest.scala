package io.github.novakovalexey.k8soperator

import cats.effect.{ContextShift, IO}
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import io.github.novakovalexey.k8soperator.common.CrdOperator
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext

class CrdDeployerTest extends AsyncFlatSpec with Matchers with BeforeAndAfterAll {

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  val server = new KubernetesServer(false, true)

  it should "deploy CRD" in {
      val client = server.getClient
      val prefix = "io.github.novakov-alexey"
      val cfg = CrdConfig(classOf[Krb2], AllNamespaces, prefix)
      val crd = CrdOperator.deployCrd[IO, Krb2](client, cfg, None)
      crd.map { c =>
        c.getMetadata.getName should ===(s"${cfg.getPluralCaseInsensitive}.$prefix")
      }.unsafeToFuture()
    }

  override protected def beforeAll(): Unit = server.before()

  override protected def afterAll(): Unit = server.after()
}
