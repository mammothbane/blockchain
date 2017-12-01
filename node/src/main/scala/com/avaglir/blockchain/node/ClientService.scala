package com.avaglir.blockchain.node

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated.{ClientGrpc, Transaction, TransactionResponse}
import io.grpc.stub.StreamObserver

object ClientService extends ClientGrpc.ClientImplBase {

  def submitImpl(tx: Transaction): TransactionResponse = {
    import TransactionResponse.Data._

    val resp = TransactionResponse.newBuilder

    if (!tx.validate) { return resp.setData(INVALID_SIGNATURE).build() }

    pendingTransactions.synchronized {
      pendingTransactions(tx.getSignature.toByteArray) = tx
    }

    resp.setData(OK).build
  }

  override def submitTransaction(request: Transaction, responseObserver: StreamObserver[TransactionResponse]): Unit =
    (submitImpl _).asJava(request, responseObserver)
}
