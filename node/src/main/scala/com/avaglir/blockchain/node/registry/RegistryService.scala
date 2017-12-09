package com.avaglir.blockchain.node.registry

import java.time.{Duration, Instant}

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated._
import com.avaglir.blockchain.node._
import com.typesafe.scalalogging.LazyLogging
import io.grpc.stub.StreamObserver

import scala.collection.mutable
import scala.concurrent.blocking

class RegistryService(snode: SNode) extends RegistryGrpc.RegistryImplBase with LazyLogging {
  import snode._

  def joinImpl(node: Node): UnitMessage = {
    logger.debug(s"<- join (${node.pretty})")

    if (node.hash == selfNode.hash) return UnitMessage.getDefaultInstance

    registrySynchronizer.lastExchanges(node.hash) = Instant.now

    if (!(nodes.contains(node.hash) && nodes(node.hash).hasInfo)) {
      blocking { nodes.synchronized { nodes(node.hash) = node } }
    }

    UnitMessage.getDefaultInstance
  }

  val leaveImpl: (Node) => UnitMessage = (node: Node) => {
    logger.debug(s"<- leave (${node.pretty})")
    blocking { nodes.synchronized { nodes -= node.hash } }

    UnitMessage.getDefaultInstance
  }

  def exchangeObserver(completeFunction: () => Unit = () => {},
                       errFunction: (Throwable) => Unit = (_: Throwable) => {}): StreamObserver[Node] =
    new StreamObserver[Node] {
      val acc = mutable.Set.empty[Node]

      override def onNext(value: Node): Unit = { acc += value }

      override def onCompleted(): Unit = {
        val newNodes = acc.filter { node =>
          val upToDate = nodes.contains(node.hash) && nodes(node.hash).hasInfo

          val diff = Duration.between(registrySynchronizer.lastExchanges.getOrElse(node.hash, Instant.now), Instant.now)
          val expired = diff.compareTo(Duration.ofSeconds(registrySynchronizer.nodeExpirationSec)) <= 0

          node.hash != selfNode.hash && !upToDate && !expired
        }

        blocking { nodes.synchronized { newNodes.foreach { node => nodes(node.hash) = node } } }

        completeFunction()
      }

      override def onError(t: Throwable): Unit = errFunction(t)
    }

  override def exchange(responseObserver: StreamObserver[Node]): StreamObserver[Node] = {
    logger.debug("<- exch")

    (nodes.values.toSeq :+ selfNode).foreach { responseObserver.onNext }
    responseObserver.onCompleted()

    exchangeObserver()
  }

  override def join(request: Node, responseObserver: StreamObserver[UnitMessage]): Unit = (joinImpl _).asJava(request, responseObserver)
  override def leave(request: Node, responseObserver: StreamObserver[UnitMessage]): Unit = leaveImpl.asJava(request, responseObserver)
}
