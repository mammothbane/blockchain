package com.avaglir.blockchain

import java.io.File
import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, KeyPair, KeyPairGenerator, SecureRandom}

import com.avaglir.blockchain.generated.Transaction
import com.typesafe.scalalogging.LazyLogging

import scala.io.Source

case class TransactionClient(keyPair: KeyPair) {
  import TransactionClient._

  val publicKey: Array[Byte] = keyPair.getPublic.getEncoded
  val privateKey: Array[Byte] = keyPair.getPrivate.getEncoded

  val random = new SecureRandom()

  private lazy val serRepr: SerializedRepr = SerializedRepr(publicKey, privateKey)

  lazy val json: String = org.json4s.native.Serialization.write(serRepr)

  def transaction(recipient: Array[Byte], amount: Long, isBlockReward: Boolean = false): Transaction = Transaction.newBuilder
    .setAmount(if (!isBlockReward) amount else blockReward)
    .setSender(publicKey)
    .setRecipient(recipient)
    .setNonce(random.nextLong)
    .setTimestamp(nowEpochMillis)
    .setBlockReward(isBlockReward)
    .sign(keyPair.getPrivate)
    .build()
}

object TransactionClient extends LazyLogging {
  import org.json4s.DefaultFormats

  val keyFactory: KeyFactory = KeyFactory.getInstance("RSA")

  val keyGen: KeyPairGenerator = {
    val out = KeyPairGenerator.getInstance("RSA")
    out.initialize(keylen)
    out
  }

  def apply: TransactionClient = TransactionClient(keyGen.generateKeyPair())

  private case class SerializedRepr(publicKey: Array[Byte], privateKey: Array[Byte])
  private implicit val dFormats: DefaultFormats.type = DefaultFormats

  def apply(f: File): TransactionClient = {
    import org.json4s._
    import org.json4s.native.JsonMethods._

    val serRepr = parse(Source.fromFile(f).mkString).extractOrElse[SerializedRepr] {
      throw new IllegalArgumentException(s"file '${f.getAbsolutePath}' was not in a recognized format")
    }

    val public = keyFactory.generatePublic(new X509EncodedKeySpec(serRepr.publicKey))
    val priv = keyFactory.generatePrivate(new X509EncodedKeySpec(serRepr.privateKey))

    val kp = new KeyPair(public, priv)
    TransactionClient(kp)
  }
}