package io.github.novakovalexey.k8soperator4s

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.github.novakovalexey.k8soperator4s.common.{CrdConfig, Metadata, Namespace}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object TestOperator extends App with LazyLogging {
  val client = new DefaultKubernetesClient
  val cfg = CrdConfig(classOf[Krb], Namespace("yp-kss"), "io.github.novakov-alexey")
  val operator = new CrdOperator[Krb](client, cfg) {

    override def onAdd(krb: Krb, meta: Metadata): Unit =
      logger.info(s"new krb added: $krb, $meta")

    override def onDelete(krb: Krb, meta: Metadata): Unit =
      logger.info(s"krb deleted: $krb, $meta")
  }

  val scheduler = new Scheduler[Krb](client, operator)
  val future = scheduler.start()
  val f = future.map { _ =>
    println("here>>>>>>>>>>>>>>>>>>>>>")
    Thread.sleep(5000)
    println("there<<<<<<<<<<<<<<<<<<<<<")
  }

  Await.ready(f, 1.minute)
  println("here ......... 3")
//  scheduler.stop()
//  client.close()
}

final case class Principal(name: String, password: String, value: String = "")
final case class Keytab(secret: String, key: String, principal: String, realm: String)
final case class Krb(principals: List[Principal], keytabs: List[Keytab])
