package com.avaglir.blockchain.node

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated._
import io.grpc.stub.StreamObserver

class BlockchainService extends BlockchainGrpc.BlockchainImplBase {
  val newTxn: Transaction => TransactionResponse = tx => {
    import TransactionResponse.Data._

    TransactionResponse
      .newBuilder
      .setData(if (tx.validate) OK else INVALID_SIGNATURE)
      .build
  }
  override def newTransaction(request: Transaction, responseObserver: StreamObserver[TransactionResponse]): Unit = newTxn.asJava(request, responseObserver)

  val blockMined: Block => BlockMinedResponse = _ => BlockMinedResponse.newBuilder.build
  override def blockMined(request: Block, responseObserver: StreamObserver[BlockMinedResponse]): Unit = blockMined.asJava(request, responseObserver)

  override def blocks(req: UnitMessage, obs: StreamObserver[Block]): Unit = { obs.onCompleted() }
}
