package com.avaglir.blockchain.client


import com.avaglir.blockchain._
import scopt.OptionParser

case class Config(
                   host: String = "localhost",
                   port: Int = defaultPort
                 )

object Config {
  def parse(args: Array[String]): Option[Config] = {
    new OptionParser[Config]("blocknode") {
      opt[Int]('p', "port")
        .action { (x, c) => c.copy(port = x) }
        .text("port to connect to")

      opt[String]('a', "address")
        .action { (x, c) => c.copy(host = x) }
        .text("address to connect to")
    }.parse(args, Config())
  }
}
