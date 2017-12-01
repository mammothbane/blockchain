package com.avaglir.blockchain.node

import com.avaglir.blockchain.generated._
import io.grpc.stub.StreamObserver

object BlockchainService extends BlockchainGrpc.BlockchainImplBase {
  val blockMined: Block => BlockMinedResponse = _ => BlockMinedResponse.newBuilder.build

  override def blockMined(request: Block, responseObserver: StreamObserver[BlockMinedResponse]): Unit = blockMined.asJava(request, responseObserver)
  override def allBlocks(req: UnitMessage, obs: StreamObserver[Block]): Unit = { obs.onCompleted() }
  override def lastBlock(request: UnitMessage, responseObserver: StreamObserver[Block]): Unit = super.lastBlock(request, responseObserver)
  override def ancestor(request: Block, responseObserver: StreamObserver[BlockLookupResponse]): Unit = super.ancestor(request, responseObserver)
}
