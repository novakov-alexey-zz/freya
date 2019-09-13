package io.github.novakovalexey.k8soperator4s

import com.typesafe.scalalogging.LazyLogging
import io.github.novakovalexey.k8soperator4s.common.{EntityInfo, Operator}

object TestOperator extends App with LazyLogging {
  val operator: Operator[Krb] = new Operator[Krb]() {

    override def onAdd(krb: Krb, namespace: String): Unit =
      logger.info(s"new krb added: $krb")

    override def onDelete(krb: Krb, namespace: String): Unit =
      logger.info(s"krb deleted: $krb")

    override val forKind: Class[Krb] = classOf[Krb]
    override val prefix: String = "io.github.novakov-alexey"
    override val isCrd: Boolean = true
  }

  implicit val entityInfo = new EntityInfo[Krb] {
    override val name: String = "krb"
    override val namespace: String = "yp-kss"

    override def copyOf(t: Krb, name: String, namespace: String): Krb = t.copy(name, namespace)
  }

  val scheduler = new Scheduler[Krb](operator)
  scheduler.start()
}

final case class Principal(name: String, password: String, value: String = "")
final case class Keytab(secret: String, key: String, principal: String, realm: String)
final case class Krb(name: String, namespace: String, principals: List[Principal], keytabs: List[Keytab])
