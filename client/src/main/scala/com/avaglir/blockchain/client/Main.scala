package com.avaglir.blockchain.client

import java.security.{KeyPair, KeyPairGenerator}

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated.TransactionResponse.Data
import com.avaglir.blockchain.generated.{ClientGrpc, RegistryGrpc, Transaction, UnitMessage}
import com.google.protobuf.ByteString
import com.typesafe.scalalogging.LazyLogging
import io.grpc.ManagedChannelBuilder

object Main extends LazyLogging {
  val keyGen: KeyPairGenerator = {
    val out = KeyPairGenerator.getInstance("RSA")
    out.initialize(keylen)
    out
  }

  val keyPair: KeyPair = keyGen.generateKeyPair()

  val publicKey: Array[Byte] = keyPair.getPublic.getEncoded

  def main(args: Array[String]): Unit = {
    configLogger()

    val config = Config.parse(args).getOrElse {
      sys.exit(1)
    }

    val amount = 1.2f

    val recipient = Array.fill[Byte](0)(0)

    val txn = Transaction.newBuilder()
      .setAmount(amount)
      .setSender(ByteString.copyFrom(publicKey))
      .setRecipient(ByteString.copyFrom(recipient))
      .setSignature(ByteString.copyFrom(transactionSignature(amount, publicKey, recipient, keyPair.getPrivate)))
      .build()

    val channel = ManagedChannelBuilder
      .forAddress(config.host, config.port)
      .usePlaintext(true)
      .build()

    val clientStub = ClientGrpc.newBlockingStub(channel)

    val resp = clientStub.submitTransaction(txn)
    resp.getData match {
      case Data.OK =>
        logger.info("got ok!")
      case _ =>
        logger.error("help")
        sys.exit(1)
    }

    val regStub = RegistryGrpc.newBlockingStub(channel)

    regStub.heartbeat(UnitMessage.getDefaultInstance)
    val info = regStub.info(UnitMessage.getDefaultInstance)
    logger.info(s"got node info $info")
  }
}
