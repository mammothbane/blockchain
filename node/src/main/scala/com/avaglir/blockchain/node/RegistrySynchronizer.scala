package com.avaglir.blockchain.node

import java.util.concurrent.{TimeUnit, TimeoutException}

import com.avaglir.blockchain.generated.UnitMessage
import com.typesafe.scalalogging.LazyLogging

object RegistrySynchronizer extends BgService with LazyLogging {
  val heartbeatTimeoutSec = 1
  val nodeExpirationSec = 30



  override def run(): Unit = {
    logger.debug("running heartbeat")

    val badNodes = nodes.values.par.map { node =>
      node.registryFutureStub.heartbeat(UnitMessage.getDefaultInstance)
    }.filter { fut =>
      try {
        fut.get(heartbeatTimeoutSec, TimeUnit.SECONDS)
        false
      } catch {
        case _: TimeoutException => true
      }
    }.seq

    if (badNodes.isEmpty) {
      logger.info("no bad nodes")
    } else {

    }


    nodes.synchronized {
    }


  }
}
