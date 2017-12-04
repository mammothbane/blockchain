package com.avaglir.blockchain.node

import java.time.{Duration, Instant}

import com.avaglir.blockchain.generated._
import com.typesafe.scalalogging.LazyLogging
import io.grpc.stub.StreamObserver

import scala.collection.mutable
import scala.concurrent.blocking

object RegistryService extends RegistryGrpc.RegistryImplBase with LazyLogging {
  def joinImpl(node: Node): UnitMessage = {
    logger.debug("<- join")

    if (node.hash == selfNode.hash) return UnitMessage.getDefaultInstance

    RegistrySynchronizer.lastHeartbeats(node.hash) = Instant.now
    nodes.synchronized {
      if (!(nodes contains node.hash) || !nodes(node.hash).hasInfo) {
        nodes(node.hash) = node
      }
    }

    UnitMessage.getDefaultInstance
  }

  val leaveImpl: (Node) => UnitMessage = (node: Node) => {
    logger.debug("<- leave")
    nodes.synchronized {
      nodes -= node.hash
    }

    UnitMessage.getDefaultInstance
  }

  val heartbeatImpl: (UnitMessage) => UnitMessage = (_: UnitMessage) => {
    logger.debug("<- heartbeat")
    UnitMessage.getDefaultInstance
  }

  val infoImpl: (UnitMessage) => Node.NodeInfo = (_: UnitMessage) => {
    logger.debug("<- info")
    Node.NodeInfo
      .newBuilder
      .setName(Main.config.name)
      .setUpSince(Main.startEpochMillis)
      .build
  }

  def exchangeObserver(completeFunction: () => Unit = () => {},
                       errFunction: (Throwable) => Unit = (_: Throwable) => {}): StreamObserver[Node] =
    new StreamObserver[Node] {
      val acc = mutable.Set.empty[Node]

      override def onNext(value: Node): Unit = { acc += value }

      override def onCompleted(): Unit = {
        blocking { nodes.synchronized {
          acc
            .filter { node =>
              node.hash != selfNode.hash &&
                (!nodes.contains(node.hash) || !nodes(node.hash).hasInfo) &&
                { // ignore nodes that have timed out
                  val diff = Duration.between(RegistrySynchronizer.lastHeartbeats.getOrElse(node.hash, Instant.now), Instant.now)
                  diff.compareTo(Duration.ofSeconds(RegistrySynchronizer.nodeExpirationSec)) <= 0
                }
            }
            .foreach { node => nodes(node.hash) = node }
        } }

        completeFunction()
      }

      override def onError(t: Throwable): Unit = errFunction(t)
    }

  override def exchange(responseObserver: StreamObserver[Node]): StreamObserver[Node] = {
    logger.debug("<- exchange")

    (nodes.values.toSeq :+ selfNode).foreach { responseObserver.onNext }
    responseObserver.onCompleted()

    exchangeObserver()
  }

  override def join(request: Node, responseObserver: StreamObserver[UnitMessage]): Unit = (joinImpl _).asJava(request, responseObserver)
  override def leave(request: Node, responseObserver: StreamObserver[UnitMessage]): Unit = leaveImpl.asJava(request, responseObserver)
  override def heartbeat(request: UnitMessage, responseObserver: StreamObserver[UnitMessage]): Unit = heartbeatImpl.asJava(request, responseObserver)
  override def info(request: UnitMessage, responseObserver: StreamObserver[Node.NodeInfo]): Unit = infoImpl.asJava(request, responseObserver)
}
