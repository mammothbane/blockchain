package com.avaglir.blockchain.node

import java.net.URL
import java.util.concurrent.{Executors, TimeUnit}

import com.avaglir.blockchain._

object Main {
  def main(args: Array[String]): Unit = {
    configLogger()

    val config = Config.parse(args).getOrElse {
      sys.exit(1)
    }

    if (config.nodeCount == 1) {
      val exec = Executors.newSingleThreadExecutor()
      val node = new SNode(config)
      exec.submit(node)
      exec.shutdown()
      return
    }

    val nodeNames = List(
      "weir", "shumway", "smick", "hendo", "bonner", "scott-heiser", "baker", "pasterczyk", "halperin",
      "morton", "madondo"
    )

    def nodeAddr(i: Int) = new URL(s"http://${config.bind.getHostAddress}:${config.port + i}")

    val nodeSet = nodeNames.indices.map { nodeAddr }.toSet

    val nodes = nodeNames.zipWithIndex.map { case (name, idx) =>
      val relevantSet = nodeSet - nodeAddr(idx)

      val conf = config.copy(
        port = config.port + idx,
        name = name,
        nodeSet = config.nodeSet ++ relevantSet,
        _clientFile = None
      )

      new SNode(conf)
    }

    val exec = Executors.newCachedThreadPool
    nodes.foreach { exec.submit }
    exec.shutdown()

    exec.awaitTermination(Long.MaxValue, TimeUnit.SECONDS)  // apparently this is the canonical way to do this
  }
}
