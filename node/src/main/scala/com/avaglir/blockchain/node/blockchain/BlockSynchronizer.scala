package com.avaglir.blockchain.node.blockchain

import java.time
import java.util.concurrent.TimeUnit

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated._
import com.avaglir.blockchain.node._
import com.typesafe.scalalogging.LazyLogging
import io.grpc.stub.StreamObserver

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent._
import scala.concurrent.duration.Duration

class BlockSynchronizer(snode: SNode) extends BgService with LazyLogging {
  import snode._

  private implicit val execContext: ExecutionContextExecutor = ExecutionContext.global

  override val interval: time.Duration = time.Duration.ofSeconds(5)

  override def run(): Unit = {
    if (liveNodes.isEmpty) {
      if (initNodes.nonEmpty) logger.warn("no live nodes for block sync")
      return
    }

    logger.debug(s"-> exch (${liveNodes.size} target(s))")

    val exchanges = liveNodes.values.par.map { node =>
      val p = Promise[Unit]

      val obs = node.blockchainStub.exchangePendingTxns(new StreamObserver[Transaction] {
        override def onNext(tx: Transaction): Unit = {
          if (pendingTransactions.contains(tx.getSignature.key)) return

          pendingTransactions.synchronized { acceptedTransactions.synchronized {
            if (!acceptedTransactions.contains(tx.getSignature.key)) {
              pendingTransactions(tx.getSignature.key) = tx
            }
          } }
        }

        override def onError(t: Throwable): Unit = p.failure(t)
        override def onCompleted(): Unit = p.success()
      })

      pendingTransactions.values.foreach { obs.onNext }
      obs.onCompleted()

      p.future
    }.seq

    val fromBlockIdx = blockchain.last.getBlockIndex
    val blocks = liveNodes.values.par.map { node =>
      val p = Promise[(Node, List[Block])]

      node.blockchainStub.sync(SyncRequest.newBuilder.setFromBlock(fromBlockIdx).build, new StreamObserver[Block] {
        val acc: ListBuffer[Block] = mutable.ListBuffer.empty[Block]
        var ok = true

        override def onNext(block: Block): Unit = block.validate.fold(
          err => {
            ok = false
            logger.error(s"received invalid block during sync from ${node.pretty}: $err")
          },
          _ => acc += block
        )

        override def onError(t: Throwable): Unit = p.failure(t)
        override def onCompleted(): Unit = p.success(node -> acc.toList)
      })

      p.future.recover { case e =>
        logger.warn(s"failed to sync with ${node.pretty}: ${e.getMessage}")
        node -> List()
      }
    }.seq

    val nonEmpty = try {
      Await.result(Future.sequence(blocks), Duration(5, TimeUnit.SECONDS))
        .filter { case (_, list) => list.nonEmpty }
    } catch {
      case t: TimeoutException =>
        logger.error(s"awaiting sync failed: ${t.getClass} :: ${t.getMessage}")
        List.empty
    }

    nonEmpty.headOption.foreach {
      case (node, newBlocks) => handleNew(node, newBlocks)
    }

    try {
      Await.ready(Future.sequence(exchanges), Duration(5, TimeUnit.SECONDS))
    } catch {
      case t: TimeoutException => logger.error(s"tx exchange failed: ${t.getClass} :: ${t.getMessage} ")
    }
  }

  def handleNew(node: Node, newBlocks: List[Block]): Either[String, Unit] = {
    blockchain.synchronized {
      if (newBlocks.last.getBlockIndex <= blockchain.last.getBlockIndex) return Left("new blocks invalidated by blockchain progress")

      if (blockchain.last.getBlockIndex >= newBlocks.head.getBlockIndex) { // we overlap to some degree
        def newBlock(i: Long): Block = newBlocks((i - newBlocks.head.getBlockIndex).toInt)
        def ourBlock(i: Long): Block = blockchain((i - blockchain.head.getBlockIndex).toInt)

        (blockchain.last.getBlockIndex to newBlocks.head.getBlockIndex by -1).find { idx =>
          newBlock(idx) == ourBlock(idx)
        }.foreach { idx =>
          return pendingTransactions.synchronized { acceptedTransactions.synchronized {
            val popped = popBlocks(idx + 1)

            val result = (idx + 1 to newBlocks.last.getBlockIndex)
              .foldLeft[Either[String, Unit]](Right()) { (acc, blockIdx) => acc.flatMap { _ => pushBlock(newBlock(blockIdx)) }}

            result.left.flatMap { err =>
                logger.error(s"applying block: $err, reverting to old blockchain")

                popBlocks(idx + 1)
                popped
                  .foldLeft[Either[String, Unit]](Right()) { (acc, block) => acc.flatMap { _ => pushBlock(block) } }
            }
          }}
        }

        doSyncback(node)

      } else { // no overlap
        if (newBlocks.head.getBlockIndex != blockchain.last.getBlockIndex + 1) return Left("new blocks index out of range")
        if (newBlocks.head.getLastBlock != blockchain.last.getProof) return doSyncback(node)

        val oldHeadBlock = blockchain.last.getBlockIndex

        pendingTransactions.synchronized { acceptedTransactions.synchronized {

          // new block follows our last block properly
          val result = newBlocks
            .foldLeft[Either[String, Unit]](Right()) { (acc, block) => acc.flatMap { _ => pushBlock(block) } }

          result.left.foreach { err =>
            logger.error(s"applying block: $err. reverting to old blockchain state.")
            popBlocks(oldHeadBlock + 1)
          }

          result
        }}
      }
    }
  }

  // pre: blockchain locked
  private def doSyncback(node: Node): Either[String, Unit] = {
    pendingTransactions.synchronized { acceptedTransactions.synchronized {
      val stub = node.blockchainBlockingStub
      val bld = BlockRequest.newBuilder

      val acc = mutable.ListBuffer.empty[Block]
      var idx = blockchain.last.getBlockIndex

      while (idx > blockchain.head.getBlockIndex) {
        val block = stub.getBlock(bld.setBlockIndex(idx).build)
        if (block == blockchain((idx - blockchain.head.getBlockIndex).toInt)) {
          val oldBlocks = popBlocks(idx + 1)
          val result = acc.reverseIterator.foldLeft[Either[String, Unit]](Right()) { (acc, block) => acc.flatMap { _ => pushBlock(block) } }

          result.left.foreach { _ =>
            popBlocks(idx + 1)
            oldBlocks.foldLeft[Either[String, Unit]](Right()) { (acc, block) => acc.flatMap { _ => pushBlock(block) }}
              .left.foreach { err => logger.error(s"restoring from failed syncback: $err") }
          }

          return result
        }
        idx -= 1
      }

      assert(idx == blockchain.head.getBlockIndex)


      val initInfo = stub.retriveInitData(UnitMessage.getDefaultInstance)

      logger.warn(s"divergence at initial block during sync with ${node.pretty}. resetting all blockchain data.")
      popBlocks(idx)

      acceptedTransactions.clear()
      initInfo.getAcceptedTransactionsList.asScala.foreach { elt => acceptedTransactions.add(elt.key) }

      ledger.synchronized {
        ledger.clear()
        initInfo.getLedgerList.asScala.foreach { entry => ledger(entry.getId.key) = entry.getAmount }
      }

      blockchain += initInfo.getLastBlock

      acc.reverseIterator.foldLeft[Either[String, Unit]](Right()) { (acc, block) => acc.flatMap { _ => pushBlock(block) } }
    } }
  }

}
