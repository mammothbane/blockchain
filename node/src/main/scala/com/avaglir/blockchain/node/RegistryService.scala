package com.avaglir.blockchain.node

import com.avaglir.blockchain.generated._
import io.grpc.stub.StreamObserver

class RegistryService extends RegistryGrpc.RegistryImplBase {
  override def register(responseObserver: StreamObserver[RegisterResponse]): StreamObserver[Node] = super.register(responseObserver)

  override def lookup(request: UnitMessage, responseObserver: StreamObserver[LookupResponse]): Unit = {

  }

  override def heartbeat(request: UnitMessage, responseObserver: StreamObserver[UnitMessage]): Unit = {

  }
}
