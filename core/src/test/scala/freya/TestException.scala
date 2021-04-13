package freya

import io.fabric8.kubernetes.client.WatcherException

import scala.util.control.NoStackTrace

case class TestException(msg: String) extends WatcherException(msg) with NoStackTrace
