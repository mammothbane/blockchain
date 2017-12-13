package com.avaglir.blockchain.node

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated.{ClientGrpc, Transaction, TransactionResponse}
import com.typesafe.scalalogging.LazyLogging
import io.grpc.stub.StreamObserver

class ClientService(val snode: SNode) extends ClientGrpc.ClientImplBase with LazyLogging {
  def submitImpl(tx: Transaction): TransactionResponse = {
    import TransactionResponse.Data._
    import snode._

    val resp = TransactionResponse.newBuilder

    tx.validate
      .flatMap { _ => if (tx.getBlockReward) Left("client cannot submit tx with block reward") else Right() }
      .fold[TransactionResponse](
        err => {
          logger.warn(s"received invalid transaction from client: $err")
          resp.setData(ERR).build
        },
        _ => {
          logger.debug(s"accepted transaction $tx")
          pendingTransactions.synchronized {
            pendingTransactions(tx.getSignature.key) = tx
          }

          resp.setData(OK).build
        }
    )

  }

  override def submitTransaction(request: Transaction, responseObserver: StreamObserver[TransactionResponse]): Unit =
    (submitImpl _).asJava(request, responseObserver)
}
