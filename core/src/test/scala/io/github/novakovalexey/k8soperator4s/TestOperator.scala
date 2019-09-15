package io.github.novakovalexey.k8soperator4s

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.github.novakovalexey.k8soperator4s.common.{Operator, OperatorCfg}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object TestOperator extends App with LazyLogging {
  val operator: Operator[Krb] = new Operator[Krb]() {

    override def onAdd(krb: Krb, namespace: String): Unit =
      logger.info(s"new krb added: $krb")

    override def onDelete(krb: Krb, namespace: String): Unit =
      logger.info(s"krb deleted: $krb")
  }

  val cfg = OperatorCfg(classOf[Krb], "yp-kss", "io.github.novakov-alexey", crd = true)

  val client = new DefaultKubernetesClient
  val scheduler = new Scheduler[Krb](client, cfg, operator)
  val future = scheduler.start()
  val f = future.map { ws =>
    println("here....................")
    Thread.sleep(5000)
    println("here....................2")
  }

  Await.ready(f, 1.minute)
  println("here ......... 3")
//  scheduler.stop()
//  client.close()
}

final case class Principal(name: String, password: String, value: String = "")
final case class Keytab(secret: String, key: String, principal: String, realm: String)
final case class Krb(name: String, namespace: String, principals: List[Principal], keytabs: List[Keytab])
