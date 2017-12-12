package com.avaglir

import java.lang.{Long => JLong}
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, MessageDigest, PrivateKey}
import java.time.Instant
import java.util
import javax.crypto.Cipher

import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger => CLogger}
import ch.qos.logback.core.ConsoleAppender
import com.avaglir.blockchain.generated._
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import com.google.protobuf.ByteString
import io.grpc.{Channel, ManagedChannelBuilder}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{Future, Promise}

package object blockchain {
  val keylen = 512
  val defaultPort = 9148
  val maxTxPerBlock = 10

  val workFactor = 10  // number of leading zero bits
  val validMask: Long = -1L ^ ((1L << workFactor + 1) - 1)  // top `workFactor` bits are 1, all else 0

  val zeroBlock: Block = {
    val builder = Block.newBuilder
      .setNonce(0)
      .setTimestamp(0)
      .setBlockIndex(0)
      .setLastBlock(0)
      .clearTxns()

    builder
      .setProof(builder.proof)
      .build
  }

  def nowEpochMillis: Long = {
    val inst = Instant.now
    inst.getEpochSecond * 1000L + inst.getNano.toLong / 1000000L
  }

  implicit class txnSig(t: TransactionOrBuilder) {
    private def preSignedHash: Array[Byte] = {
      val ret = ByteBuffer.allocate(8 + t.getSender.size() + t.getRecipient.size() + 8 + 8)
        .putDouble(t.getAmount)
        .put(t.getSender.toByteArray)
        .put(t.getRecipient.toByteArray)
        .putLong(t.getNonce)
        .putLong(t.getTimestamp)

      val digest = MessageDigest.getInstance("SHA-256")
      digest.digest(ret.array)
    }

    def signature(privateKey: PrivateKey): Array[Byte] = {
      require({
        val rsaPub = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(t.getSender.toByteArray)).asInstanceOf[RSAPublicKey]
        val rsaPriv = privateKey.asInstanceOf[RSAPrivateKey]

        // this check borrowed from https://stackoverflow.com/questions/24121801/how-to-verify-if-the-private-key-matches-with-the-certificate
        val prod = rsaPub.getPublicExponent.multiply(rsaPriv.getPrivateExponent).subtract(BigInteger.ONE)

        rsaPub.getModulus == rsaPriv.getModulus &&
          BigInteger.valueOf(2).modPow(prod, rsaPub.getModulus) == BigInteger.ONE
      }, "private and public keys did not match")

      val cipher = Cipher.getInstance("RSA")
      cipher.init(Cipher.ENCRYPT_MODE, privateKey)
      cipher.doFinal(preSignedHash)
    }

    private val skewToleranceMs = 500
    def validate: Boolean = {
      // make sure tx isn't too far in the future
      if (t.getTimestamp - nowEpochMillis > skewToleranceMs) return false

      val cipher = Cipher.getInstance("RSA")
      val publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(t.getSender.toByteArray))
      cipher.init(Cipher.DECRYPT_MODE, publicKey)

      val decryptedHash = cipher.doFinal(t.getSignature.toByteArray)
      val recalcHash = preSignedHash

      util.Arrays.equals(decryptedHash, recalcHash)
    }

    override def toString = s"Txn(sender: ${t.getSender.toByteArray.hexString}, recipient: ${t.getRecipient.toByteArray.hexString}, amount: ${t.getAmount}, signature: ${t.getSignature.toByteArray.hexString})"
  }

  implicit class txnBuilderExt(t: Transaction.Builder) {
    def sign(privateKey: PrivateKey): Transaction.Builder = {
      val signature = t.signature(privateKey)
      t.setSignature(ByteString.copyFrom(signature))
    }
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

  implicit class blockExt(b: BlockOrBuilder) {
    import scala.collection.JavaConverters._

    private val allowableSkewMillis = 500

    def validate: Boolean = {
      b.getBlockIndex match {
        case 0 => return b == zeroBlock
        case x if x < 0 => return false
        case _ =>
      }

      // make sure block isn't too far in the future
      if (b.getTimestamp - nowEpochMillis > allowableSkewMillis) return false

      val txs = b.getTxnsList.asScala
      if (b.getTxnsList.size() == 0) return false
      if (b.getTxnsList.size() > maxTxPerBlock) return false
      if (txs.exists { !_.validate }) return false

      true
    }

    def proof: Long = {
      val txs = b.getTxnsList.asScala

      val buf = ByteBuffer.allocate(4*8 + txs.foldLeft(0) { (acc, x) => acc + x.getSignature.size() })
      buf.putLong(b.getBlockIndex)
      buf.putLong(b.getLastBlock)
      buf.putLong(b.getNonce)
      buf.putLong(b.getTimestamp)
      txs.foreach { tx => buf.put(tx.getSignature.toByteArray) }

      val digest = MessageDigest.getInstance("SHA-256")
      val result = digest.digest(buf.array())
      ByteBuffer.wrap(result).getLong()
    }
  }

  implicit class nodeExt(t: NodeOrBuilder) {
    // TODO: look at caching this
    lazy val channel: Channel = ManagedChannelBuilder
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
