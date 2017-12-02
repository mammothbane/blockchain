package com.avaglir.blockchain.node

import java.time.Duration

trait BgService extends Runnable {
  val interval: Duration = Duration.ofSeconds(10)
}
