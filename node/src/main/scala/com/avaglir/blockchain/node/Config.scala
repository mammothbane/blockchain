package com.avaglir.blockchain.node

import java.net.URL

import com.avaglir.blockchain._
import scopt.OptionParser

case class Config(
                 port: Int = defaultPort,
                 mine: Boolean = false,
                 fastStart: Boolean = false,
                 nodeSet: Set[URL] = Set.empty
                 )

object Config {
  def parse(args: Array[String]): Option[Config] = {
    new OptionParser[Config]("blocknode") {
      opt[Int]('p', "port")
        .action { (x, c) => c.copy(port = x) }
        .text("port to listen on")

      opt[Unit]('m', "mine")
        .action { (_, c) => c.copy(mine = false) }
        .text("enable mining")

      opt[Unit]('f', "fast_start")
        .action{ (_, c) => c.copy(fastStart = true) }
        .text("enable fast start (don't copy entire blockchain)")

      opt[Seq[String]]('n', "nodes")
        .action { (x, c) => c.copy(nodeSet = x.map { new URL(_) }.toSet) }
        .text("addresses (comma-separated) of initial nodes to start with")

    }.parse(args, Config())
  }
}
