package com.avaglir.blockchain.client

import com.avaglir.blockchain._
import com.avaglir.blockchain.generated.TransactionResponse.Data
import com.avaglir.blockchain.generated._
import com.typesafe.scalalogging.LazyLogging
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise}

/**
  * Client that submits simple transactions to the nodes. Also reports other nodes known to the node contacted.
  */
object Main extends LazyLogging {
  def main(args: Array[String]): Unit = {
    configLogger()

    val config = Config.parse(args).getOrElse {
      sys.exit(1)
    }

    val amount = 1200
    val recipient = List(0xde, 0xad, 0xbe, 0xef).map { _.toByte }.toArray

    val tClient = TransactionClient.apply
    val txn = tClient.transaction(recipient, amount)

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

    val regStub = RegistryGrpc.newStub(channel)

    logger.info("reading nodes from target registry")
    val promise = Promise[Unit]
    val ret = regStub.exchange(new StreamObserver[Node] {
      override def onNext(value: Node): Unit = {
        logger.info(value.pretty)
      }
      override def onCompleted(): Unit = promise.success()
      override def onError(t: Throwable): Unit = promise.failure(t)
    })

    ret.onCompleted()

    Await.ready(promise.future, Duration.Inf)
  }
}
