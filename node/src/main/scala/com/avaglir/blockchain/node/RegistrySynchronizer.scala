package com.avaglir.blockchain.node

import java.util.concurrent.{TimeUnit, TimeoutException}

import com.avaglir.blockchain.generated.UnitMessage
import com.avaglir.blockchain._
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.Future

object RegistrySynchronizer extends BgService with LazyLogging {
  val heartbeatTimeoutSec = 1
  val nodeExpirationSec = 30

  override def run(): Unit = {
    // heartbeat
    Future {
      logger.debug("running heartbeat")

      val nodesHeartbeat = nodes.values.par.map { node =>
        node -> node.registryFutureStub.heartbeat(UnitMessage.getDefaultInstance).asScala
      }.seq.toMap

      Thread.sleep(heartbeatTimeoutSec * 1000)

      val badNodes = nodesHeartbeat.filter { case (_, v) => !v.isCompleted }.keySet
      if (badNodes.isEmpty) {
        logger.info("no bad nodes")
      } else {
        logger.info(s"bad nodes: ${badNodes.mkString(", ")}")

        nodes.synchronized {
          nodes --= badNodes.map { _.hash }
        }
      }
    }


    Future {

    }
  }
}
