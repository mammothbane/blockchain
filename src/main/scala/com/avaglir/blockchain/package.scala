package com.avaglir

import java.nio.ByteBuffer
import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, MessageDigest, PrivateKey}
import java.util
import javax.crypto.Cipher

import com.avaglir.blockchain.generated.Transaction
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}

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
    def hexString: String = f"$b%02X"
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
}
