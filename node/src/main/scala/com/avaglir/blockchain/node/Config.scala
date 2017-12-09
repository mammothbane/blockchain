package com.avaglir.blockchain.node

import java.io.File
import java.net.{InetAddress, URL}
import java.nio.ByteBuffer

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated.Node
import scopt.OptionParser

import scala.util.Random

case class Config(
                 port: Int = defaultPort,
                 bind: InetAddress = InetAddress.getByName("localhost"),
                 mine: Boolean = false,
                 fastStart: Boolean = false,
                 nodeSet: Set[URL] = Set.empty,
                 parallelism: Int = Runtime.getRuntime.availableProcessors,
                 nodeCount: Int = 1,
                 name: String = (0 until 8).map { _ => Random.nextPrintableChar() }.mkString,
                 private val _clientFile: Option[File] = None,
                 ) {

  lazy val nodePartial: Node.Builder = {
    val addr = ByteBuffer.wrap(bind.getAddress).getInt

    Node.newBuilder
      .setAddress(addr)
      .setPort(port)
  }

  lazy val clientFile: File = _clientFile.getOrElse {
    val buf = ByteBuffer.allocate(4)
    buf.putInt(nodePartial.build.hash)

    new File(s"${buf.array.hexString}.json")
  }
}

object Config {
  def parse(args: Array[String]): Option[Config] = {
    new OptionParser[Config]("blocknode") {
      opt[Int]('p', "port")
        .action { (x, c) => c.copy(port = x) }
        .text("port to listen on")

      opt[Unit]('m', "mine")
        .action { (_, c) => c.copy(mine = true) }
        .text("enable mining")

      opt[File]("miner_client_data")
        .action { (x, c) => c.copy(_clientFile = Some(x)) }
        .text("client file for use with miner")

      opt[Unit]('f', "fast_start")
        .action { (_, c) => c.copy(fastStart = true) }
        .text("enable fast start (don't copy entire blockchain)")

      opt[String]('b', "bind")
        .action { (x, c) => c.copy(bind = InetAddress.getByName(x)) }
        .text("ip address to bind to")

      opt[String]('n', "name")
        .action { (x, c) => c.copy(name = x) }
        .text("")

      opt[Seq[String]]("nodes")
        .action { (x, c) => c.copy(nodeSet = c.nodeSet ++ x.map { new URL(_) }.toSet) }
        .text("addresses (comma-separated) of nodes to initially connect to")

      opt[Unit]("default_master")
        .abbr("dm")
        .action { (_, c) => c.copy(nodeSet = c.nodeSet + new URL(s"http://localhost:$defaultPort"))}
        .text(s"use default master at http://localhost:$defaultPort")

      opt[Int]("parallelism")
        .action { (x, c) => c.copy(parallelism = x) }
        .text("use the given number of threads")
        .validate {
          case x if x < 0 => failure("invalid parallelism")
          case _ => success
        }

      opt[Int]("node_count")
        .action { (x, c) => c.copy(nodeCount = x)}
        .text("launch n nodes")
        .validate {
          case x if x < 0 => failure("invalid node count")
          case _ => success
        }
    }.parse(args, Config())
  }
}
