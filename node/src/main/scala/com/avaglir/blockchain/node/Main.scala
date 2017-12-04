package com.avaglir.blockchain.node

import java.util.concurrent.Executors

import com.avaglir.blockchain._
import com.typesafe.scalalogging.LazyLogging

object Main extends LazyLogging {
  def main(args: Array[String]): Unit = {
    configLogger()

    val config = Config.parse(args).getOrElse {
      sys.exit(1)
    }

    val exec = Executors.newSingleThreadExecutor()
    val node = new SNode(config)
    exec.submit(node)
    exec.shutdown()
  }
}
