package com.avaglir.blockchain.node

import java.util.concurrent.Executors

import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger => CLogger}
import ch.qos.logback.core.ConsoleAppender
import com.typesafe.scalalogging.LazyLogging
import org.slf4j.{Logger, LoggerFactory}

object Main extends LazyLogging {
  def main(args: Array[String]): Unit = {
    val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME).asInstanceOf[CLogger]
    rootLogger.detachAndStopAllAppenders()
    rootLogger.setLevel(Level.INFO)

    LoggerFactory.getLogger("com.avaglir").asInstanceOf[CLogger].setLevel(Level.DEBUG)

    val enc = new PatternLayoutEncoder
    enc.setContext(rootLogger.getLoggerContext)
    enc.setPattern("%highlight(%-5level) %white(%-23class{0}) %message%n")
    enc.start()

    val appender = new ConsoleAppender[ILoggingEvent]
    appender.setContext(rootLogger.getLoggerContext)
    appender.setEncoder(enc)
    appender.setWithJansi(true)
    appender.start()

    rootLogger.addAppender(appender)

    val config = Config.parse(args).getOrElse {
      sys.exit(1)
    }

    val exec = Executors.newSingleThreadExecutor()
    val node = new SNode(config)
    exec.submit(node)
    exec.shutdown()
  }
}
