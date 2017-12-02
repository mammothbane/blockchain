package com.avaglir.blockchain.node

import java.net.InetAddress
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.{Executors, TimeUnit}

import ch.qos.logback.classic.{Level, Logger => CLogger}
import com.avaglir.blockchain.generated.Node
import io.grpc.ServerBuilder
import org.slf4j.{Logger, LoggerFactory}

object Main {
  val startEpochMillis: Long = Instant.now.toEpochMilli
  var config: Config = _

  def main(args: Array[String]): Unit = {
    LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME).asInstanceOf[CLogger].setLevel(Level.DEBUG)
    LoggerFactory.getLogger("io.netty").asInstanceOf[CLogger].setLevel(Level.INFO)

    config = Config.parse(args).getOrElse {
      sys.exit(1)
    }

    val builder = ServerBuilder.forPort(config.port)

    builder.addService(BlockchainService)
    builder.addService(RegistryService)
    builder.addService(ClientService)

    val server = builder.build()
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

    val services =
      List(RegistrySynchronizer, BlockSynchronizer) ++ (if (config.mine) BlockMiner :: Nil else Nil)

    val execs = services.map { sync =>
      val exec = Executors.newSingleThreadScheduledExecutor()
      exec.scheduleAtFixedRate(sync, 0, sync.interval.toMillis, TimeUnit.MILLISECONDS)
      exec
    }


    server.awaitTermination()

    execs.par.foreach { exec => exec.shutdown() }
    execs.par.foreach { exec => exec.awaitTermination(2, TimeUnit.SECONDS) }
  }
}
