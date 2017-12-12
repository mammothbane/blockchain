package com.avaglir.blockchain.node.registry

import java.time.{Instant, Duration => JDuration}
import java.util.concurrent.TimeUnit

import com.avaglir.blockchain._
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

  val lastExchanges = mutable.Map.empty[Int, Instant]

  private implicit val execContext: ExecutionContextExecutor = ExecutionContext.global

  override def run(): Unit = {
    val contactNodes = if (liveNodes.nonEmpty) liveNodes else {
      logger.warn("no live nodes for registry sync - falling back on initial node set")
      initNodes
    }

    logger.debug(s"-> exch (${contactNodes.size} target(s))")

    val exchanges = contactNodes.values.par.map { node =>
      val p = Promise[Unit]()

      val obs = node.registryStub.exchange(registryService.exchangeObserver({ () => p.complete(Success(Unit)) }, p.failure))
      val toSend = liveNodes.values.toSeq :+ selfNode
      toSend.foreach { node =>
        logger.debug(s"sending ${node.pretty}")
        obs.onNext(node)
      }
      obs.onCompleted()

      p.future.map { _ => lastExchanges(node.hash) = Instant.now }
    }.seq

    Await.ready(Future.sequence(exchanges), Duration(5, TimeUnit.SECONDS))
      .recover { case t => logger.error(s"exchange failed: ${t.getClass} :: ${t.getMessage} ") }

    val diffs = lastExchanges.mapValues { inst => JDuration.between(inst, Instant.now) }

    val expired = liveNodes.keySet.intersect(diffs.keySet).filter { hash => diffs(hash).compareTo(JDuration.ofSeconds(registrySynchronizer.nodeExpirationSec)) > 0 }

    if (expired.nonEmpty) {
      logger.info(s"removing nodes: ${expired.map { hash => liveNodes(hash).pretty }.mkString(",")}")
      blocking { liveNodes.synchronized { liveNodes --= expired }}
    }

    val cull = diffs.filter { case (_, diff) => diff.compareTo(JDuration.ofSeconds(2*registrySynchronizer.nodeExpirationSec)) > 0}.keySet
    lastExchanges --= cull
  }
}
