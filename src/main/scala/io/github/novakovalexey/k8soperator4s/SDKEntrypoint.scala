package io.github.novakovalexey.k8soperator4s

import java.util.concurrent.{ExecutorService, Executors}

object SDKEntrypoint {
  lazy val executors: ExecutorService = Executors.newFixedThreadPool(10)

}
