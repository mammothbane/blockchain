package com.avaglir.blockchain.node

import java.time.Duration

class BlockMiner(node: SNode) extends BgService {
  // run continuously
  override val interval: Duration = Duration.ZERO

  var nonce: Int = 0

  override def run(): Unit = {

  }
}
