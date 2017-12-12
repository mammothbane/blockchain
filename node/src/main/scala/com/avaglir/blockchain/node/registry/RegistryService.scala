package com.avaglir.blockchain.node.registry

import java.time.{Duration, Instant}

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated._
import com.avaglir.blockchain.node._
import com.typesafe.scalalogging.LazyLogging
import io.grpc.stub.StreamObserver

import scala.concurrent.blocking

class RegistryService(snode: SNode) extends RegistryGrpc.RegistryImplBase with LazyLogging {
  import snode._

  val leaveImpl: (Node) => UnitMessage = (node: Node) => {
    logger.info(s"<- leave (${node.pretty})")
    blocking { liveNodes.synchronized { liveNodes -= node.hash } }

    UnitMessage.getDefaultInstance
  }

  def exchangeObserver(completeFunction: () => Unit = () => {},
                       errFunction: (Throwable) => Unit = (_: Throwable) => {}): StreamObserver[Node] =
    new StreamObserver[Node] {
      override def onNext(node: Node): Unit = {
        val upToDate = liveNodes.contains(node.hash) && liveNodes(node.hash).hasInfo

        val diff = Duration.between(registrySynchronizer.lastExchanges.getOrElse(node.hash, Instant.now), Instant.now)
        val expired = diff.compareTo(Duration.ofSeconds(registrySynchronizer.nodeExpirationSec)) > 0

        if (!expired && node.hash != selfNode.hash && !upToDate) {
          blocking { liveNodes.synchronized { liveNodes(node.hash) = node } }
        }
      }

      override def onCompleted(): Unit = completeFunction()

      override def onError(t: Throwable): Unit = errFunction(t)
    }

  override def exchange(responseObserver: StreamObserver[Node]): StreamObserver[Node] = {
    logger.trace("<- exch")

    (liveNodes.values.toSeq :+ selfNode).foreach { responseObserver.onNext }

    exchangeObserver(responseObserver.onCompleted, t => logger.error(s"$t"))
  }

  override def leave(request: Node, responseObserver: StreamObserver[UnitMessage]): Unit = leaveImpl.asJava(request, responseObserver)
}
