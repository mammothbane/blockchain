package com.avaglir.blockchain.node

import com.avaglir.blockchain._
import io.grpc.ServerBuilder

object Main {

  def main(args: Array[String]): Unit = {
    val builder = ServerBuilder.forPort(port)

    builder.addService(BlockchainService)
    builder.addService(RegistryService)
    builder.addService(ClientService)

    val server = builder.build()

    server
      .start()
      .awaitTermination()
  }

}
