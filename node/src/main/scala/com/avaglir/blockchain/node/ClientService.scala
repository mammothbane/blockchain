package com.avaglir.blockchain.node

import com.avaglir.blockchain.generated.{ClientGrpc, Transaction, TransactionResponse}
import io.grpc.stub.StreamObserver

class ClientService extends ClientGrpc.ClientImplBase {
  val submitImpl: (Transaction) => TransactionResponse = (tx: Transaction) => {
    TransactionResponse.getDefaultInstance
  }

  override def submitTransaction(request: Transaction, responseObserver: StreamObserver[TransactionResponse]): Unit =
    submitImpl.asJava(request, responseObserver)
}
