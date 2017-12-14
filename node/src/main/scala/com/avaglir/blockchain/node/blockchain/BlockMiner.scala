package com.avaglir.blockchain.node.blockchain

import java.nio.ByteBuffer
import java.time.Duration

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated.Block
import com.avaglir.blockchain.node.{BgService, SNode}
import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters._

class BlockMiner(snode: SNode) extends BgService with LazyLogging {
  import snode._

  override val interval: Duration = Duration.ofMillis(500)

  var nonce: Long = 0

  val txClient: TransactionClient = {
    if (config.clientFile.exists) {
      TransactionClient(config.clientFile)
    } else {
      TransactionClient.apply
    }
  }

  /**
    * Mine blocks until the queue is empty.
    *
    * This is executed optimistically: blocks are mined without locking and the queue push is tried blindly. If it
    * fails, we just keep mining with whatever transactions are left.
    */
  override def run(): Unit = {
    while (pendingTransactions.nonEmpty) {
      val selfTx = txClient.transaction(txClient.publicKey, blockReward, isBlockReward = true)
      val txs = pendingTransactions.take(9).values.toList :+ selfTx

      val bld = Block.newBuilder
        .addAllTxns(txs.asJava)
        .setBlockIndex(blockchain.last.getBlockIndex + 1)
        .setNonce(nonce)
        .setTimestamp(nowEpochMillis)
        .setLastBlock(blockchain.last.getProof)

      bld.setProof(bld.calcProof)
      logger.trace({
        val hex = ByteBuffer.allocate(8).putLong(bld.getProof).array().hexString
        s"trying proof $hex"
      })

      bld.validate.fold(
        err => logger.trace(s"block failed to validate: $err"),
        _ => pushBlock(bld.build).fold(
          err => logger.warn(s"adding block failed with $err"),
          _ => logger.info(s"successfully mined block ${bld.getBlockIndex}")
        )
      )

      nonce += 1
    }
    logger.trace("done mining (no transactions to process)")
  }
}
