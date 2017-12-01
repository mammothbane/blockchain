package com.avaglir.blockchain.node

import com.avaglir.blockchain.generated._
import io.grpc.stub.StreamObserver

class RegistryService extends RegistryGrpc.RegistryImplBase {
  val joinImpl: (Node) => UnitMessage = (_: Node) => UnitMessage.getDefaultInstance
  override def join(request: Node, responseObserver: StreamObserver[UnitMessage]): Unit = joinImpl.asJava(request, responseObserver)

  val leaveImpl: (Node) => UnitMessage = (_: Node) => UnitMessage.getDefaultInstance
  override def leave(request: Node, responseObserver: StreamObserver[UnitMessage]): Unit = leaveImpl.asJava(request, responseObserver)

  val heartbeatImpl: (UnitMessage) => UnitMessage = (_: UnitMessage) => UnitMessage.getDefaultInstance
  override def heartbeat(request: UnitMessage, responseObserver: StreamObserver[UnitMessage]): Unit = heartbeatImpl.asJava(request, responseObserver)
}
