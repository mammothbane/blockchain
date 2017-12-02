package com.avaglir.blockchain.client

import java.security.{KeyPair, KeyPairGenerator}

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated.TransactionResponse.Data
import com.avaglir.blockchain.generated.{ClientGrpc, RegistryGrpc, Transaction, UnitMessage}
import com.google.protobuf.ByteString
import io.grpc.ManagedChannelBuilder

object Main {
  val keyGen: KeyPairGenerator = {
    val out = KeyPairGenerator.getInstance("RSA")
    out.initialize(keylen)
    out
  }

  val keyPair: KeyPair = keyGen.generateKeyPair()

  val publicKey: Array[Byte] = keyPair.getPublic.getEncoded

  def main(args: Array[String]): Unit = {
    val amount = 1.2f

    val recipient = Array.fill[Byte](0)(0)

    val txn = Transaction.newBuilder()
      .setAmount(amount)
      .setSender(ByteString.copyFrom(publicKey))
      .setRecipient(ByteString.copyFrom(recipient))
      .setSignature(ByteString.copyFrom(transactionSignature(amount, publicKey, recipient, keyPair.getPrivate)))
      .build()

    val channel = ManagedChannelBuilder
      .forAddress("localhost", defaultPort)
      .usePlaintext(true)
      .build()

    val clientStub = ClientGrpc.newBlockingStub(channel)

    val resp = clientStub.submitTransaction(txn)
    resp.getData match {
      case Data.OK =>
        println("got ok!")
      case _ =>
        println("help")
        sys.exit(1)
    }

    val regStub = RegistryGrpc.newBlockingStub(channel)

    regStub.heartbeat(UnitMessage.getDefaultInstance)
    val info = regStub.info(UnitMessage.getDefaultInstance)
    println(s"got info $info")
  }
}
