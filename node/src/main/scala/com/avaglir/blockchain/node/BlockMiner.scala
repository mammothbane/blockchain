package com.avaglir.blockchain.node

import java.time.Duration

object BlockMiner extends BgService {
  // run continuously
  override val interval: Duration = Duration.ZERO

  var nonce: Int = 0

  override def run(): Unit = {

  }
}
