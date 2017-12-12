package com.avaglir.blockchain.node

import java.lang.{Long => JLong}

import com.avaglir.blockchain.generated._
import com.typesafe.scalalogging.LazyLogging
import io.grpc.stub.StreamObserver

class BlockchainService(snode: SNode) extends BlockchainGrpc.BlockchainImplBase with LazyLogging {
  import snode._

  override def lastBlock(request: UnitMessage, responseObserver: StreamObserver[Block]): Unit = {
    assert(blockchain.nonEmpty)

    responseObserver.onNext(blockchain.last)
    responseObserver.onCompleted()
  }

  override def sync(request: SyncRequest, responseObserver: StreamObserver[Block]): Unit = {
    assert(blockchain.nonEmpty)

    if (JLong.compareUnsigned(request.getFromBlock, blockchain.last.getBlockIndex) >= 0) {
      responseObserver.onCompleted()
      return
    }

    val headIndex = blockchain.head.getBlockIndex
    val chainOffset = request.getFromBlock - headIndex

    (0 until chainOffset).foreach { idx =>
      responseObserver.onNext(blockchain(idx.toInt))
    }
    responseObserver.onCompleted()
  }

  override def exchangePendingTxns(responseObserver: StreamObserver[Transaction]): StreamObserver[Transaction] =
    new StreamObserver[Transaction] {
      override def onCompleted(): Unit = ???
      override def onError(t: Throwable): Unit = logger.error(s"exchanging pending transactions: $t")

      override def onNext(value: Transaction): Unit = {

      }
    }

  override def syncback(responseObserver: StreamObserver[Block]): StreamObserver[SyncBackProgress] = super.syncback(responseObserver)
}
