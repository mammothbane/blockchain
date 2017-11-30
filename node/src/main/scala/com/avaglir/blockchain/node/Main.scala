package com.avaglir.blockchain.node

import com.avaglir.blockchain._
import io.grpc.ServerBuilder

object Main {
  def main(args: Array[String]): Unit = {
    val builder = ServerBuilder.forPort(port)

    builder.addService(new BlockchainService)
    builder.addService(new RegistryService)

    val server = builder.build()

    server
      .start()
      .awaitTermination()
  }

}
