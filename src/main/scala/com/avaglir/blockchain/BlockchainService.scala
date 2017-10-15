package com.avaglir.blockchain

import com.avaglir.blockchain.generated._
import io.grpc.stub.StreamObserver

class BlockchainService extends BlockchainGrpc.BlockchainImplBase {
  val newTxn: Transaction => TransactionResponse = req =>
    TransactionResponse
      .newBuilder
      .setData(TransactionResponse.Data.OK)
      .build

  val blocks: UnitMessage => List[Block] = msg => Nil

  val blockMined: Block => BlockMinedResponse = block => BlockMinedResponse.newBuilder.build


  override def newTransaction(request: Transaction, responseObserver: StreamObserver[TransactionResponse]): Unit = newTxn.asJava
  override def blocks(req: UnitMessage, obs: StreamObserver[Block]): Unit = blocks.asJava
  override def blockMined(request: Block, responseObserver: StreamObserver[BlockMinedResponse]): Unit = blockMined.asJava
}
