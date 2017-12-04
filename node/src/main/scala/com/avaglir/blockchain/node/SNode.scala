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
import scala.concurrent.duration.Duration

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

  val nodes = mutable.HashMap.empty[Int, Node]
  val pendingTransactions = mutable.Map.empty[Array[Byte], Transaction]
  val blockchain = mutable.Seq.empty[Block]

  val startEpochMillis: Long = Instant.now.toEpochMilli

  val selfNode: Node = {
    val addr = ByteBuffer.wrap(config.bind.getAddress).getInt

    val info = Node.NodeInfo.newBuilder
      .setName(config.name)
      .setUpSince(startEpochMillis)
      .build

    Node.newBuilder
      .setAddress(addr)
      .setPort(config.port)
      .setInfo(info)
      .build
  }

  private implicit val execContext: ExecutionContextExecutor = ExecutionContext.global

  override def run(): Unit = {
    server.start()

    nodes ++= config.nodeSet.par.map { node =>
      val out = Node.newBuilder
      out.setPort(node.getPort)

      val addr = InetAddress.getByName(node.getHost).getAddress
      if (addr.length != 4) throw new IllegalArgumentException(s"address $addr did not resolve to a valid IPv4 address")

      out.setAddress(ByteBuffer.wrap(addr).getInt())
      val ret = out.build
      ret.hash -> ret
    }.seq

    logger.info("transmitting joins to peers")
    val joins = nodes.values.par.map { node =>
      node.registryFutureStub.join(selfNode).asScala
    }.seq

    Await.ready(Future.sequence(joins), Duration(20, TimeUnit.SECONDS))
    logger.info("peers joined")
    logger.info("starting services")

    val execs = services.map { sync =>
      val exec = Executors.newSingleThreadScheduledExecutor()
      exec.scheduleAtFixedRate(sync, sync.interval.toMillis / 2, sync.interval.toMillis, TimeUnit.MILLISECONDS)
      exec
    }
    logger.info("services started")

    server.awaitTermination()

    execs.par.foreach { exec => exec.shutdown() }
    execs.par.foreach { exec => exec.awaitTermination(2, TimeUnit.SECONDS) }
  }
}
