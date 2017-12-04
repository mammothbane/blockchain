package com.avaglir.blockchain.node

import java.net.InetAddress
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.{Executors, TimeUnit}

import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger => CLogger}
import ch.qos.logback.core.ConsoleAppender
import com.avaglir.blockchain._
import com.avaglir.blockchain.generated.Node
import com.typesafe.scalalogging.LazyLogging
import io.grpc._
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}

object Main extends LazyLogging {
  val startEpochMillis: Long = Instant.now.toEpochMilli
  var config: Config = _

  private implicit val execContext: ExecutionContextExecutor = ExecutionContext.global

  def main(args: Array[String]): Unit = {
    val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME).asInstanceOf[CLogger]
    rootLogger.detachAndStopAllAppenders()
    rootLogger.setLevel(Level.INFO)

    LoggerFactory.getLogger("com.avaglir").asInstanceOf[CLogger].setLevel(Level.DEBUG)

    val enc = new PatternLayoutEncoder
    enc.setContext(rootLogger.getLoggerContext)
    enc.setPattern("%-5level %class{0} %message%n")
    enc.start()

    val appender = new ConsoleAppender[ILoggingEvent]
    appender.setContext(rootLogger.getLoggerContext)
    appender.setEncoder(enc)
    appender.start()

    rootLogger.addAppender(appender)

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

    logger.info("transmitting joins to peers")
    val joins = nodes.values.par.map { node =>
      node.registryFutureStub.join(selfNode).asScala
    }.seq

    Await.ready(Future.sequence(joins), Duration(20, TimeUnit.SECONDS))
    logger.info("peers joined")

    logger.info("starting services")
    val services =
      List(RegistrySynchronizer, BlockSynchronizer) ++ (if (config.mine) BlockMiner :: Nil else Nil)

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
