package com.avaglir.blockchain.node

import java.lang.{Long => JLong}
import java.net.InetAddress
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.{Executors, TimeUnit}

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated._
import com.avaglir.blockchain.node.registry.{RegistryService, RegistrySynchronizer}
import com.typesafe.scalalogging.LazyLogging
import io.grpc.{Server, ServerBuilder}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent._
import scala.util.Random

class SNode(val config: Config) extends Runnable with LazyLogging {
  val registrySynchronizer = new RegistrySynchronizer(this)
  val blockSynchronizer = new BlockSynchronizer(this)
  val blockMiner: Option[BlockMiner] = if (config.mine) Some(new BlockMiner(this)) else None

  val blockchainService = new BlockchainService(this)
  val registryService = new RegistryService(this)
  val clientService = new ClientService(this)

  val services: List[BgService] =
    List(registrySynchronizer, blockSynchronizer) ++ blockMiner

  val server: Server = {
    val builder = ServerBuilder.forPort(config.port)

    builder.addService(blockchainService)
    builder.addService(registryService)
    builder.addService(clientService)

    builder.build()
  }

  val initNodes: Map[Int, Node] = config.nodeSet.par.map { node =>
    val out = Node.newBuilder
    out.setPort(node.getPort)

    val addr = InetAddress.getByName(node.getHost).getAddress
    if (addr.length != 4) throw new IllegalArgumentException(s"address $addr did not resolve to a valid IPv4 address")

    out.setAddress(ByteBuffer.wrap(addr).getInt())
    val ret = out.build
    ret.hash -> ret
  }.seq.toMap

  val liveNodes = mutable.HashMap.empty[Int, Node]

  val pendingTransactions = mutable.Map.empty[Array[Byte], Transaction]
  val acceptedTransactions = mutable.Set.empty[Array[Byte]]

  val blockchain: ListBuffer[Block] = mutable.ListBuffer.empty[Block]
  val ledger = mutable.Map.empty[Array[Byte], Double]

  def pushBlock(b: Block): Either[String, Unit] = {
    blockchain.synchronized {
      b.validate.left.foreach { x => return Left(s"block failed to validate: $x") }

      val last = blockchain.last
      if (last.getBlockIndex != b.getBlockIndex - 1) return Left("block index mismatch")
      if (last.getTimestamp >= b.getTimestamp) return Left("timestamp mismatch")
      if (last.getProof != b.getLastBlock) return Left("block proof mismatch")

      val txs = b.getTxnsList.asScala
      if (txs.count { _.getBlockReward } != 1) return Left("bad block reward format")

      val txSigs = txs.map { _.getSignature.toByteArray }.toSet

      pendingTransactions.synchronized { acceptedTransactions.synchronized { ledger.synchronized {
        if ((txSigs intersect acceptedTransactions).nonEmpty) return Left("some transactions already accepted")

        pendingTransactions --= txSigs
        acceptedTransactions ++= txSigs
        blockchain += b

        txs.foreach { tx =>
          ledger(tx.getSender) -= tx.getAmount

          val cur = ledger.getOrElseUpdate(tx.getRecipient, 0d)
          ledger(tx.getRecipient) = cur + tx.getAmount
        }
      } } }

    }

    Right()
  }

  def popBlocks(toIdx: Long): List[Block] = {
    blockchain.synchronized {
      val remove = blockchain.reverseIterator
        .takeWhile { x => JLong.compareUnsigned(x.getBlockIndex, toIdx) >= 0 }
        .toList

      blockchain --= remove

      pendingTransactions.synchronized { acceptedTransactions.synchronized { ledger.synchronized {
        remove.foreach { block =>
          val txs = block.getTxnsList.asScala
          txs.foreach { tx =>
            val sig = tx.getSignature.toByteArray
            pendingTransactions += sig -> tx
            acceptedTransactions -= sig
            ledger(tx.getSender.toByteArray) += tx.getAmount
            ledger(tx.getRecipient.toByteArray) -= tx.getAmount
          }
        }
      }}}

      remove
    }
  }

  val startEpochMillis: Long = Instant.now.toEpochMilli

  val selfNode: Node = {
    val info = Node.NodeInfo.newBuilder
      .setName(config.name)
      .setUpSince(startEpochMillis)
      .build

    config.nodePartial
      .setInfo(info)
      .build
  }

  private implicit val execContext: ExecutionContextExecutor = ExecutionContext.global

  override def run(): Unit = {
    if (!config.fastStart) blockchain += zeroBlock
    else {
      while (blockchain.isEmpty) {
        logger.info("attempting to acquire initial block state")

        initNodes.values.foldLeft[Either[Unit, Unit]](Left()) { (x, node) =>
          x match {
            case x @ Right(_) => x
            case Left(_) =>
              try {
                val initInfo = node.blockchainBlockingStub.retriveInitData(UnitMessage.getDefaultInstance)
                acceptedTransactions ++= initInfo.getAcceptedTransactionsList.asScala.map { _.toByteArray }
                blockchain += initInfo.getLastBlock
                ledger ++= initInfo.getLedgerList.asScala.map { x => x.getId.toByteArray -> x.getAmount }

                logger.info(s"success: init info $initInfo acquired from ${node.pretty}")

                Right()
              } catch {
                case _: Throwable => Left()
              }
          }
        }

        logger.warn("failed to contact init nodes. retrying shortly...")
        Thread.sleep(500)
      }
    }

    server.start()

    logger.info("starting services")

    val exec = Executors.newScheduledThreadPool(config.parallelism)
    services.foreach { svc =>
      // delay randomly so stuff doesn't stack up too much
      val delay = (Random.nextDouble * svc.interval.toMillis).toInt
      exec.scheduleAtFixedRate(svc, delay, svc.interval.toMillis, TimeUnit.MILLISECONDS)
    }
    logger.info("services started")

    server.awaitTermination()

    exec.shutdown()
    exec.awaitTermination(2, TimeUnit.SECONDS)
  }
}
