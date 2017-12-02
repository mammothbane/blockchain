package com.avaglir.blockchain.node

import java.util.concurrent.{TimeUnit, TimeoutException}

import com.avaglir.blockchain.generated.UnitMessage
import org.apache.logging.log4j.scala.Logging

import scala.util.{Failure, Try}

object RegistrySynchronizer extends BgService with Logging {
  val heartbeatTimeoutSec = 1
  val nodeExpirationInterval = 3

  var index = 0

  override def run(): Unit = {
    logger.debug("running heartbeat")

    val badNodes = nodes.par.map { node =>
      node.registryFutureStub.heartbeat(UnitMessage.getDefaultInstance)
    }.filter { fut =>
      Try { fut.get(heartbeatTimeoutSec, TimeUnit.SECONDS) } match {
        case _: Failure[TimeoutException] => true
        case Failure(e) => throw e
        case _ => false
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
