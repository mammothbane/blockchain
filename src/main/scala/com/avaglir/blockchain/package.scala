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
import com.typesafe.scalalogging.LazyLogging
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{Future, Promise}

/**
  * A whole lot of utilities and constants.
  */
package object blockchain {
  val keylen = 512
  val defaultPort = 9148
  val maxTxPerBlock = 10

  val workFactor = 16  // number of leading zero bits for valid block
  val validMask: Long = -1L ^ ((1L << (64 - workFactor) + 1) - 1)  // top `workFactor` bits are 1, all else 0

  val blockReward: Long = 1000*1000

  val zeroBlock: Block = Block.newBuilder
      .setNonce(0)
      .setTimestamp(0)
      .setBlockIndex(0)
      .setLastBlock(0)
      .setProof(0)
      .clearTxns()
      .build

  def nowEpochMillis: Long = {
    val inst = Instant.now
    inst.getEpochSecond * 1000L + inst.getNano.toLong / 1000000L
  }

  case class ByteArrayKey(b: Array[Byte]) {
    override def equals(obj: scala.Any): Boolean = obj.isInstanceOf[ByteArrayKey] && util.Arrays.equals(obj.asInstanceOf[ByteArrayKey].b, b)
    override def hashCode(): Int = util.Arrays.hashCode(b)
    override def toString: String = b.hexString
  }

  implicit def byteString2Ary(b: ByteString): Array[Byte] = b.toByteArray
  implicit def ary2ByteString(b: Array[Byte]): ByteString = ByteString.copyFrom(b)
  implicit def key2ByteAry(b: ByteArrayKey): Array[Byte] = b.b

  implicit class byteStringExt(b: ByteString) {
    def key: ByteArrayKey = ByteArrayKey(b.toByteArray)
  }

  implicit class txnSig(t: TransactionOrBuilder) {
    private def preSignedHash: Array[Byte] = {
      val recip = t.getRecipient
      val sender = t.getSender

      val ret = ByteBuffer.allocate(8 + sender.size + recip.size + 8 + 8 + 1)

      ret
        .putDouble(t.getAmount)
        .put(sender)
        .put(recip)
        .putLong(t.getNonce)
        .putLong(t.getTimestamp)
        .put(if (t.getBlockReward) 1.toByte else 0.toByte)

      val digest = MessageDigest.getInstance("SHA-256")
      digest.digest(ret.array)
    }

    def signature(privateKey: PrivateKey): Array[Byte] = {
      require({
        val rsaPub = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(t.getSender)).asInstanceOf[RSAPublicKey]
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
    def validate: Either[String, Unit] = {
      if (t.getTimestamp - nowEpochMillis > skewToleranceMs) return Left("too far in future")

      if (t.getBlockReward) {
        if (t.getAmount != blockReward) return Left("block reward amount mismatch")
        if (t.getSender != t.getRecipient) return Left("reward tx had mismatched recipient")
      } else {
        if (t.getSender == t.getRecipient) return Left("sender cannot be recipient in non-reward tx")
      }

      val cipher = Cipher.getInstance("RSA")
      val publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(t.getSender))
      cipher.init(Cipher.DECRYPT_MODE, publicKey)

      val decryptedHash = cipher.doFinal(t.getSignature)
      val recalcHash = preSignedHash

      if (util.Arrays.equals(decryptedHash, recalcHash)) Right()
      else Left("signature invalid")
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
    def key = ByteArrayKey(b)
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

  implicit class blockExt(b: BlockOrBuilder) extends LazyLogging {
    import scala.collection.JavaConverters._

    private val allowableSkewMillis = 500

    def validate: Either[String, Unit] = {
      if (b.getBlockIndex == 0) {
        return if (b == zeroBlock) Right() else Left("block claimed index 0 but didn't match the zero block")
      }

      if (b.calcProof != b.getProof) return Left("bad block proof")
      if ((b.getProof & validMask) != 0) return Left("proof invalid")

      if (b.getTimestamp - nowEpochMillis > allowableSkewMillis) return Left("block too far in future")

      val txs = b.getTxnsList.asScala
      if (txs.lengthCompare(2) < 0) return Left("too few transactions")
      if (txs.lengthCompare(maxTxPerBlock) > 0) return Left("too many transactions")

      txs.find { _.validate.isLeft }.foreach { x =>
        return Left(s"transaction $x failed to validate: ${x.validate.left.get}")
      }

      if (txs.count { _.getBlockReward } != 1) return Left("block reward unclaimed or claimed multiple times")

      Right()
    }

    def calcProof: Long = {
      val txs = b.getTxnsList.asScala

      val buf = ByteBuffer.allocate(4*8 + txs.foldLeft(0) { (acc, x) => acc + x.getSignature.size() })
      buf.putLong(b.getBlockIndex)
      buf.putLong(b.getLastBlock)
      buf.putLong(b.getNonce)
      buf.putLong(b.getTimestamp)
      txs.foreach { tx => buf.put(tx.getSignature) }

      val digest = MessageDigest.getInstance("SHA-256")
      val result = digest.digest(buf.array())
      ByteBuffer.wrap(result).getLong()
    }
  }

  implicit class nodeExt(t: NodeOrBuilder) {
    private var chanInitialized = false

    lazy val channel: ManagedChannel = {
      chanInitialized = true
      ManagedChannelBuilder
        .forAddress(addrString, t.getPort)
        .usePlaintext(true)
        .build
    }

    // SUPER gross and hacky
    override def finalize(): Unit = {
      if (chanInitialized) channel.shutdown()
      super.finalize()
    }

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
