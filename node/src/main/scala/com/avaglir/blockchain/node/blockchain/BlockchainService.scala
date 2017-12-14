package com.avaglir.blockchain.node.blockchain

import java.lang.{Long => JLong}

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated.InitData.LedgerEntry
import com.avaglir.blockchain.generated._
import com.avaglir.blockchain.node.SNode
import com.google.protobuf.ByteString
import com.typesafe.scalalogging.LazyLogging
import io.grpc.stub.StreamObserver

import scala.collection.JavaConverters._
import scala.collection.mutable

/**
  * Serve blockchain-related requests to remote clients.
  * @param snode The [[com.avaglir.blockchain.node.SNode]] to bind to.
  */
class BlockchainService(snode: SNode) extends BlockchainGrpc.BlockchainImplBase with LazyLogging {
  import snode._

  /**
    * Return information required to initialize another node.
    */
  override def retriveInitData(request: UnitMessage, responseObserver: StreamObserver[InitData]): Unit = {
    assert(blockchain.nonEmpty)

    val data = blockchain.synchronized { acceptedTransactions.synchronized { ledger.synchronized {
      InitData.newBuilder
        .setLastBlock(blockchain.last)
        .addAllAcceptedTransactions(acceptedTransactions.map { elt => ByteString.copyFrom(elt.b) }.asJava)
        .addAllLedger(ledger.map { case (id, amt) => LedgerEntry.newBuilder.setId(id.b).setAmount(amt).build }.asJava)
        .build
    } } }

    responseObserver.onNext(data)
    responseObserver.onCompleted()
  }

  /**
    * Handle a sync request from another node, listing all blocks since the requested one.
    */
  override def sync(request: SyncRequest, responseObserver: StreamObserver[Block]): Unit = {
    assert(blockchain.nonEmpty)

    if (JLong.compareUnsigned(request.getFromBlock, blockchain.last.getBlockIndex) >= 0) {
      responseObserver.onCompleted()
      return
    }

    blockchain.synchronized {
      val headIndex = blockchain.head.getBlockIndex
      val chainOffset = request.getFromBlock - headIndex

      (chainOffset.toInt until blockchain.length).foreach { idx =>
        responseObserver.onNext(blockchain(idx))
      }
    }
    responseObserver.onCompleted()
  }

  /**
    * Exchange all pending transactions with another node, allowing each to attempt to mine the transactions.
    */
  override def exchangePendingTxns(responseObserver: StreamObserver[Transaction]): StreamObserver[Transaction] = {
    pendingTransactions.synchronized {
      pendingTransactions.values.foreach { responseObserver.onNext }
    }

    new StreamObserver[Transaction] {
      val acc = mutable.Set.empty[Transaction]
      override def onNext(tx: Transaction): Unit = acc += tx
      override def onError(t: Throwable): Unit = logger.error(s"exchanging pending transactions: $t")
      override def onCompleted(): Unit = {
        pendingTransactions.synchronized { acceptedTransactions.synchronized {
          pendingTransactions ++= acc
            .withFilter { elt => !acceptedTransactions.contains(elt.getSignature.key) && elt.validate.isRight }
            .map { tx => tx.getSignature.key -> tx }
        } }

        responseObserver.onCompleted()
      }
    }
  }

  /**
    * Return a specific block by index. Intended for use in syncback strategies.
   */
  override def getBlock(request: BlockRequest, responseObserver: StreamObserver[Block]): Unit = blockchain.synchronized {
    val targetBlock = request.getBlockIndex - blockchain.head.getBlockIndex

    if (targetBlock > blockchain.last.getBlockIndex) responseObserver.onError(new IllegalArgumentException("block out of range"))
    else {
      responseObserver.onNext(blockchain(targetBlock.toInt))
      responseObserver.onCompleted()
    }
  }
}
