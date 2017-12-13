package com.avaglir.blockchain.node

import java.lang.{Long => JLong}
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.{Executors, TimeUnit}

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated._
import com.avaglir.blockchain.node.blockchain.{BlockMiner, BlockSynchronizer, BlockchainService}
import com.avaglir.blockchain.node.registry.{RegistryService, RegistrySynchronizer}
import com.typesafe.scalalogging.LazyLogging
import io.grpc.{Server, ServerBuilder}
import org.http4s.server.blaze.BlazeBuilder

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

  val sv: Server = {
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

  val pendingTransactions = mutable.Map.empty[ByteArrayKey, Transaction]
  val acceptedTransactions = mutable.Set.empty[ByteArrayKey]

  val blockchain: ListBuffer[Block] = mutable.ListBuffer.empty[Block]
  val ledger = mutable.Map.empty[ByteArrayKey, Long]

  def pushBlock(b: Block): Either[String, Unit] = {
    blockchain.synchronized {
      b.validate.left.foreach { x => return Left(s"block failed to validate: $x") }

      val last = blockchain.last
      if (last.getBlockIndex != b.getBlockIndex - 1) return Left("block index mismatch")
      if (last.getTimestamp >= b.getTimestamp) return Left("timestamp mismatch")
      if (last.getProof != b.getLastBlock) return Left("block proof mismatch")

      val txs = b.getTxnsList.asScala
      val txSigs = txs.map { _.getSignature.key }.toSet

      pendingTransactions.synchronized { acceptedTransactions.synchronized { ledger.synchronized {
        if ((txSigs intersect acceptedTransactions).nonEmpty) return Left("some transactions already accepted")

        pendingTransactions --= txSigs
        acceptedTransactions ++= txSigs
        blockchain += b

        txs.foreach { tx =>
          val senderKey = tx.getSender.key
          val sendCur = ledger.getOrElseUpdate(senderKey, 0) // TODO: disable this behavior -- all senders should already exist in the ledger
          if (!tx.getBlockReward) ledger(senderKey) = sendCur - tx.getAmount

          val recipKey = tx.getRecipient.key
          val recipCur = ledger.getOrElseUpdate(recipKey, 0)
          ledger(recipKey) = recipCur + tx.getAmount
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
            val sig = tx.getSignature.key
            if (!tx.getBlockReward) {
              ledger(tx.getSender.key) += tx.getAmount
              pendingTransactions += sig -> tx
            }
            acceptedTransactions -= sig
            ledger(tx.getRecipient.key) -= tx.getAmount
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
                acceptedTransactions ++= initInfo.getAcceptedTransactionsList.asScala.map { _.key }
                blockchain += initInfo.getLastBlock
                ledger ++= initInfo.getLedgerList.asScala.map { x => x.getId.key -> x.getAmount }

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

    sv.start()

    logger.info("starting services")

    val exec = Executors.newScheduledThreadPool(config.parallelism)
    services.foreach { svc =>
      // delay randomly so stuff doesn't stack up too much
      val delay = (Random.nextDouble * svc.interval.toMillis).toInt
      exec.scheduleAtFixedRate(svc, delay, svc.interval.toMillis, TimeUnit.MILLISECONDS)
    }
    logger.info("services started")

    import fs2.interop.cats._
    import org.http4s._
    import org.http4s.dsl._

    import scalatags.Text.all._

    implicit def tagEnc[T <: BaseTagType]: EntityEncoder[T] = EntityEncoder[String]
      .contramap { (x: T) => "<!DOCTYPE html>\n" + x.render}
      .withContentType(MediaType.`text/html`)

    def hash(ary: Array[Byte]): String = {
      val md = MessageDigest.getInstance("MD5")
      md.digest(ary).hexString
    }

    val svc = HttpService {
      case GET -> Root => Ok(html(
        head(
          tag("title")(config.name),
          link(rel := "stylesheet", `type` := "text/css", href := "main.css")
        ),
        body(
          div(id := "main", style := "flex-direction: column",
            h1(style := "font-size: 48px;", "BLOCKS"),
            blockchain.map { block =>
              div(
                `class` := "cell",
                div(
                  `class` := "row",
                  span(`class` := "field", b("index "), block.getBlockIndex),
                  span(`class` := "field", b("proof "), ByteBuffer.allocate(8).putLong(block.getProof).array().hexString)
                ),
                div(
                  `class` := "row",
                  span(`class` := "field", b("nonce "), block.getNonce),
                  span(`class` := "field", b("timestamp "), block.getTimestamp)
                )
              )
            },
            h1(style := "font-size: 48px", "LEDGER"),
            ledger.map { case (id, amt) =>
              div(
                `class` := "cell",
                div(
                  `class` := "row",
                  span(`class` := "field", b("id "), hash(id.b))
                ),
                div(
                  `class` := "row",
                  span(`class` := "field", b("amount "), amt)
                )
              )
            }.toSeq
        )
      )))
      case req @ GET -> Root / path if List(".css").exists(path.endsWith) => StaticFile.fromResource(s"/$path", Some(req)).getOrElseF(NotFound())
    }

    val httpServer = BlazeBuilder
      .bindHttp(config.port + 1000)
      .mountService(svc, "/")
      .run

    logger.info(s"http server started on port ${config.port + 1000}")

    sv.awaitTermination()

    httpServer.shutdownNow()

    exec.shutdown()
    exec.awaitTermination(2, TimeUnit.SECONDS)
  }
}
