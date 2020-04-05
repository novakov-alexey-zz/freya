package freya

import freya.internal.OperatorUtils
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import cats.effect.IO

class OperatorUtilsTest extends AsyncFlatSpec with Matchers with BeforeAndAfter {
  val server = new KubernetesServer(false, false)
  val openShiftPath = "/apis%2Froute.openshift.io%2Fv1"

  before {
    server.before()
  }

  after {
    server.after()
  }

  it should "return true on OpenShift" in {
      server.expect().withPath(openShiftPath).andReturn(200, "ok").once()
      val client = server.getClient
      OperatorUtils.checkIfOnOpenshift[IO](client.getMasterUrl).map {
        case (isOpenShift, _) => isOpenShift should ===(true)
      }.unsafeRunSync
    }

  it should "return false on K8s" in {
      server.expect().withPath(openShiftPath).andReturn(404, "nok").once()
      val client = server.getClient
      OperatorUtils.checkIfOnOpenshift[IO](client.getMasterUrl).map {
        case (isOpenShift, _) => isOpenShift should ===(false)
      }.unsafeRunSync
    }
}
