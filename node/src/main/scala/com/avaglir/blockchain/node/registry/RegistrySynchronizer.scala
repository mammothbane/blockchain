package com.avaglir.blockchain.node.registry

import java.time.temporal.ChronoUnit
import java.time.{Instant, Duration => JDuration}
import java.util.concurrent.TimeUnit

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated.{Node, UnitMessage}
import com.avaglir.blockchain.node._
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.util.Success

class RegistrySynchronizer(snode: SNode) extends BgService with LazyLogging {
  import snode._

  val heartbeatTimeoutSec = 1
  val nodeExpirationSec = 30

  val lastHeartbeats = mutable.Map.empty[Int, Instant]
  val lastInfo = mutable.Map.empty[Int, Instant]

  private implicit val execContext: ExecutionContextExecutor = ExecutionContext.global

  override def run(): Unit = {
    val heartbeat = Future {
      logger.debug("-> heartbeat")

      val nodesHeartbeat = nodes.values.par.map { node =>
        node -> node.registryFutureStub.heartbeat(UnitMessage.getDefaultInstance).asScala
      }.seq.toMap

      Thread.sleep(heartbeatTimeoutSec * 1000)

      val (goodNodes, _) = nodesHeartbeat.partition { case (_, v) => v.isCompleted }
      // TODO: cancel the bad heartbeats -- might want to switch back to guava futures
      goodNodes.keySet.map { _.hash }.foreach { x => lastHeartbeats(x) = Instant.now }

      val deleteHashes = nodes.keySet
        .map { key => key -> lastHeartbeats.getOrElseUpdate(key, Instant.now) }
        .filter { case (_, time) => JDuration.between(time, Instant.now).compareTo(JDuration.of(nodeExpirationSec, ChronoUnit.SECONDS)) > 0 }
        .unzip
        ._1

      if (deleteHashes.nonEmpty) logger.info(s"deleting ${deleteHashes.size} nodes: ${deleteHashes.map { nodes(_).pretty }.mkString(", ")}")

      blocking { nodes.synchronized { nodes --= deleteHashes } }

      lastHeartbeats   // accept nodes that have been timed out for double the expiration time
        .filter { case (_, time) => JDuration.between(time, Instant.now).compareTo(JDuration.ofSeconds(2*nodeExpirationSec)) > 0 }
        .unzip._1
        .foreach { lastHeartbeats.remove }
    }

    val info = Future {
      logger.debug("-> info")

      val futures = nodes.values
        .filter { node =>
          val lastSeen = lastInfo.getOrElseUpdate(node.hash, Instant.now)
          val diff = JDuration.between(lastSeen, Instant.now)
          !node.hasInfo || diff.compareTo(JDuration.of(2, ChronoUnit.MINUTES)) > 0
        }
        .map { node =>
          node.registryFutureStub
            .info(UnitMessage.getDefaultInstance)
            .asScala
            .map { info =>
              blocking { nodes.synchronized {
                if (nodes.contains(node.hash)) {
                  nodes(node.hash) = Node.newBuilder(node).setInfo(info).build
                  lastInfo(node.hash) = Instant.now
                  logger.info(s"updated info for ${nodes(node.hash).pretty}")
                }
              }}
            }
        }

      Await.ready(Future.sequence(futures), Duration(5, TimeUnit.SECONDS))
        .recover { case t => logger.error(s"info failed: ${t.getClass} :: ${t.getMessage} ") }
    }

    val exchange = Future {
      logger.debug("-> exchange")

      val exchanges = nodes.values.par.map { node =>
        val p = Promise[Unit]()

        val obs = node.registryStub.exchange(registryService.exchangeObserver({ () => p.complete(Success(Unit)) }, p.failure))
        (nodes.values.toSeq :+ selfNode).foreach { obs.onNext }
        obs.onCompleted()

        p.future
      }.seq

      Await.ready(Future.sequence(exchanges), Duration(5, TimeUnit.SECONDS))
        .recover { case t => logger.error(s"exchange failed: ${t.getClass} :: ${t.getMessage} ") }
    }

    Await.ready(Future.sequence(List(heartbeat, info, exchange)), Duration(10, TimeUnit.SECONDS))
      .recover { case t => logger.error(s"registry sync failed: ${t.getClass} :: ${t.getMessage} ") }
      .foreach { _ => logger.debug("registry sync done") }

  }
}
