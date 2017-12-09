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
    logger.debug("-> exch")

    val exchanges = nodes.values.par.map { node =>
      val p = Promise[Unit]()

      val obs = node.registryStub.exchange(registryService.exchangeObserver({ () => p.complete(Success(Unit)) }, p.failure))
      (nodes.values.toSeq :+ selfNode).foreach {
        obs.onNext
      }
      obs.onCompleted()

      p.future
    }.seq

    Await.ready(Future.sequence(exchanges), Duration(5, TimeUnit.SECONDS))
      .recover { case t => logger.error(s"exchange failed: ${t.getClass} :: ${t.getMessage} ") }
  }
}
