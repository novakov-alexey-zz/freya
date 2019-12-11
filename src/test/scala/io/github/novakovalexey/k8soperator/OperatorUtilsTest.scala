package io.github.novakovalexey.k8soperator

import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import io.github.novakovalexey.k8soperator.internal.OperatorUtils
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

class OperatorUtilsTest extends AsyncFlatSpec with Matchers with BeforeAndAfter {
  val server = new KubernetesServer(false, false)
  val openShiftPath = "/apis%2Froute.openshift.io%2Fv1"

  it should "return true on OpenShift" in {
      server.expect().withPath(openShiftPath).andReturn(200, "ok").once()
      val client = server.getClient
      val (isOpenShift, _) = OperatorUtils.checkIfOnOpenshift(client.getMasterUrl)

      isOpenShift should ===(true)
    }

  it should "return false on K8s" in {
      server.expect().withPath(openShiftPath).andReturn(404, "nok").once()
      val client = server.getClient
      val (isOpenShift, _) = OperatorUtils.checkIfOnOpenshift(client.getMasterUrl)

      isOpenShift should ===(false)
    }

  before {
    server.before()
  }

  after {
    server.after()
  }
}
