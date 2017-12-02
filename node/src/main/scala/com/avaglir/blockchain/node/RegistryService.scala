package com.avaglir.blockchain.node

import com.avaglir.blockchain.generated._
import io.grpc.stub.StreamObserver
import org.apache.logging.log4j.scala.Logging

import scala.collection.mutable

object RegistryService extends RegistryGrpc.RegistryImplBase with Logging {
  val joinImpl: (Node) => UnitMessage = (node: Node) => {
    nodes.synchronized {
      if (!(nodes contains node.hash) || !nodes(node.hash).hasInfo) {
        nodes(node.hash) = node
      }
    }

    UnitMessage.getDefaultInstance
  }

  val leaveImpl: (Node) => UnitMessage = (node: Node) => {
    nodes.synchronized {
      nodes -= node.hash
    }

    UnitMessage.getDefaultInstance
  }

  val heartbeatImpl: (UnitMessage) => UnitMessage = (_: UnitMessage) => UnitMessage.getDefaultInstance

  val infoImpl: (UnitMessage) => Node.NodeInfo = (_: UnitMessage) => {
    Node.NodeInfo
      .newBuilder
      .setName(Main.config.name)
      .setUpSince(Main.startEpochMillis)
      .build
  }

  override def exchange(responseObserver: StreamObserver[Node]): StreamObserver[Node] = {
    nodes.values.foreach { responseObserver.onNext }
    responseObserver.onCompleted()

    new StreamObserver[Node] {
      val acc = mutable.Set.empty[Node]

      override def onCompleted(): Unit = {
        nodes.synchronized {
          acc.foreach { node =>
            if (!(nodes contains node.hash) || !nodes(node.hash).hasInfo) {
              nodes(node.hash) = node
            }
          }
        }
      }

      override def onNext(value: Node): Unit = { acc += value }

      override def onError(t: Throwable): Unit = {
        logger.warn(s"encountered error exchanging with remote: $t")
      }
    }
  }

  override def join(request: Node, responseObserver: StreamObserver[UnitMessage]): Unit = joinImpl.asJava(request, responseObserver)
  override def leave(request: Node, responseObserver: StreamObserver[UnitMessage]): Unit = leaveImpl.asJava(request, responseObserver)
  override def heartbeat(request: UnitMessage, responseObserver: StreamObserver[UnitMessage]): Unit = heartbeatImpl.asJava(request, responseObserver)
  override def info(request: UnitMessage, responseObserver: StreamObserver[Node.NodeInfo]): Unit = infoImpl.asJava(request, responseObserver)
}
