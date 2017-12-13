package com.avaglir.blockchain.node.blockchain

import java.time
import java.util.concurrent.TimeUnit

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated.{Block, Node, SyncRequest, Transaction}
import com.avaglir.blockchain.node._
import com.typesafe.scalalogging.LazyLogging
import io.grpc.stub.StreamObserver

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
      logger.warn("no live nodes for block sync")
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
        logger.warn(s"failed to sync with ${node.pretty}: $e")
        node -> List()
      }
    }.seq

    val all = Await.ready(Future.sequence(blocks), Duration(5, TimeUnit.SECONDS))
      .recover { case t =>
        logger.error(s"awaiting sync failed: ${t.getClass} :: ${t.getMessage}")
        List.empty
      }

    val nonEmpty = Await.result(all, Duration(-1, TimeUnit.MILLISECONDS))
      .filter { case (_, list) => list.nonEmpty }

    nonEmpty.headOption.foreach {
      case (node, newBlocks) =>
        blockchain.synchronized {
          if (newBlocks.last.getBlockIndex > blockchain.last.getBlockIndex) {
            logger.warn("STOP THE WORLD -- acquiring better blockchain")
            pendingTransactions.synchronized { acceptedTransactions.synchronized {
              node.blockchainStub.syncback()
            } }
          }
        }
    }


    Await.ready(Future.sequence(exchanges), Duration(5, TimeUnit.SECONDS))
      .recover { case t => logger.error(s"tx exchange failed: ${t.getClass} :: ${t.getMessage} ") }
  }

}
