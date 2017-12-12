package com.avaglir.blockchain.node

import java.net.InetAddress
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.{Executors, TimeUnit}

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated.{Block, Node, Transaction}
import com.avaglir.blockchain.node.registry.{RegistryService, RegistrySynchronizer}
import com.typesafe.scalalogging.LazyLogging
import io.grpc.{Server, ServerBuilder}

import scala.collection.mutable
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
  val blockchain = mutable.Seq.empty[Block]

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
