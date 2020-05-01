package freya

import scala.util.control.NoStackTrace

case class TestException(msg: String) extends NoStackTrace
