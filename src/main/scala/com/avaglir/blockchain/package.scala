package com.avaglir

import java.nio.ByteBuffer
import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, MessageDigest, PrivateKey}
import java.util
import javax.crypto.Cipher

import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger => CLogger}
import ch.qos.logback.core.ConsoleAppender
import com.avaglir.blockchain.generated._
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import io.grpc.{Channel, ManagedChannelBuilder}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{Future, Promise}

package object blockchain {
  val keylen = 512
  val defaultPort = 9148

  private def preSigned(amount: Double, sender: Array[Byte], recipient: Array[Byte]): Array[Byte] = {
    val ret = Array.fill[Byte](8 + sender.length + recipient.length)(0)

    ByteBuffer.wrap(ret)
      .putDouble(amount)
      .put(sender)
      .put(recipient)

    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(ret)
  }

  def transactionSignature(amount: Double, sender: Array[Byte], recipient: Array[Byte], privateKey: PrivateKey): Array[Byte] = {
    val cipher = Cipher.getInstance("RSA")
    cipher.init(Cipher.ENCRYPT_MODE, privateKey)
    cipher.doFinal(preSigned(amount, sender, recipient))
  }

  implicit class txnSig(t: Transaction) {
    def validate: Boolean = {
      val cipher = Cipher.getInstance("RSA")
      val publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(t.getSender.toByteArray))
      cipher.init(Cipher.DECRYPT_MODE, publicKey)

      val decryptedHash = cipher.doFinal(t.getSignature.toByteArray)
      val recalcHash = preSigned(t.getAmount, t.getSender.toByteArray, t.getRecipient.toByteArray)

      util.Arrays.equals(decryptedHash, recalcHash)
    }

    override def toString = s"Txn(sender: ${t.getSender.toByteArray.hexString}, recipient: ${t.getRecipient.toByteArray.hexString}, amount: ${t.getAmount}, signature: ${t.getSignature.toByteArray.hexString})"
  }

  implicit class byteHexString(b: Byte) {
    def hexString: String = f"$b%02x"
  }

  implicit class byteArrayHexString(b: Array[Byte]) {
    def hexString: String = b.map { _.hexString }.mkString
    override def toString: String = hexString
  }

  // borrowed from https://stackoverflow.com/questions/18026601/listenablefuture-to-scala-future
  implicit class RichListenableFuture[T](lf: ListenableFuture[T]) {
    def asScala: Future[T] = {
      val p = Promise[T]()
      Futures.addCallback(lf, new FutureCallback[T] {
        def onFailure(t: Throwable): Unit = p failure t
        def onSuccess(result: T): Unit    = p success result
      })
      p.future
    }
  }

  def configLogger(): Unit = {
    val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME).asInstanceOf[CLogger]
    rootLogger.detachAndStopAllAppenders()
    rootLogger.setLevel(Level.INFO)

    LoggerFactory.getLogger("com.avaglir").asInstanceOf[CLogger].setLevel(Level.DEBUG)

    val enc = new PatternLayoutEncoder
    enc.setContext(rootLogger.getLoggerContext)
    enc.setPattern("%highlight(%-5level) %white(%-23class{0}) %message%n")
    enc.start()

    val appender = new ConsoleAppender[ILoggingEvent]
    appender.setContext(rootLogger.getLoggerContext)
    appender.setEncoder(enc)
    appender.setWithJansi(true)
    appender.start()

    rootLogger.addAppender(appender)
  }

  implicit class nodeExt(t: Node) {
    // TODO: look at caching this
    def channel: Channel = ManagedChannelBuilder
      .forAddress(addrString, t.getPort)
      .usePlaintext(true)
      .build

    def addrString: String = {
      val addr = ByteBuffer.allocate(4).putInt(t.getAddress).array()
      addr.map { x => s"${ x.toInt & 0xff }" }.mkString(".")
    }

    def pretty: String =
      if (t.hasInfo)  s"${t.getInfo.getName}@${t.addrString}:${t.getPort}"
      else            s"${t.addrString}:${t.getPort}"

    lazy val hash: Int = {
      val buf = ByteBuffer.allocate(8)
      buf
        .putInt(t.getAddress)
        .putInt(t.getPort)

      val ret = MessageDigest.getInstance("SHA-256").digest(buf.array)
      ByteBuffer.wrap(ret).getInt
    }

    def blockchainStub:         BlockchainGrpc.BlockchainStub         = BlockchainGrpc.newStub(channel)
    def blockchainBlockingStub: BlockchainGrpc.BlockchainBlockingStub = BlockchainGrpc.newBlockingStub(channel)
    def blockchainFutureStub:   BlockchainGrpc.BlockchainFutureStub   = BlockchainGrpc.newFutureStub(channel)
    def registryStub:           RegistryGrpc.RegistryStub             = RegistryGrpc.newStub(channel)
    def registryBlockingStub:   RegistryGrpc.RegistryBlockingStub     = RegistryGrpc.newBlockingStub(channel)
    def registryFutureStub:     RegistryGrpc.RegistryFutureStub       = RegistryGrpc.newFutureStub(channel)
    def clientStub:             ClientGrpc.ClientStub                 = ClientGrpc.newStub(channel)
    def clientBlockingStub:     ClientGrpc.ClientBlockingStub         = ClientGrpc.newBlockingStub(channel)
    def clientFutureStub:       ClientGrpc.ClientFutureStub           = ClientGrpc.newFutureStub(channel)
  }
}
