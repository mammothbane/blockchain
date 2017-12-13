package com.avaglir.blockchain.node

import java.time.Duration

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated.Block
import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters._

class BlockMiner(snode: SNode) extends BgService with LazyLogging {
  import snode._

  override val interval: Duration = Duration.ofMillis(500)

  var nonce: Long = 0

  val txClient: TransactionClient = {
    import config.clientFile
    if (clientFile.exists) {
      TransactionClient(config.clientFile)
    } else {
      TransactionClient.apply
    }
  }

  override def run(): Unit = {
    // optimistic approach here: don't bother locking on pendingTransactions or blockchain
    // just allow the block to be rejected if it's invalid

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

      bld.validate.fold(
        err => logger.warn(s"block failed to validate: $err"),
        _ => pushBlock(bld.build).fold(
          err => logger.warn(s"adding block failed with $err"),
          _ => logger.info(s"successfully mined block ${bld.getBlockIndex}")
        )
      )

      nonce += 1
    }
    logger.debug("done mining (no transactions to process)")
  }
}
