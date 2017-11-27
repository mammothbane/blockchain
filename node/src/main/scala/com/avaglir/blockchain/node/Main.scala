package com.avaglir.blockchain.node

import io.grpc.ServerBuilder

object Main {
  val port = 9148


  def main(args: Array[String]): Unit = {
    val builder = ServerBuilder.forPort(port)

    builder.addService(new BlockchainService)
    builder.addService(new RegistryService)

    val server = builder.build()

    server.start()




    server.awaitTermination()
  }

}
