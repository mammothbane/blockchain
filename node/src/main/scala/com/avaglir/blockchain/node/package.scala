package com.avaglir.blockchain

import com.avaglir.blockchain.generated.{Block, Transaction}
import io.grpc.stub.StreamObserver

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

package object node {
  val pendingTransactions = mutable.Map.empty[Array[Byte], Transaction]
  val blockchain = mutable.Seq.empty[Block]

  implicit class methodTransform[T, U](f: T => U) {
    def asJava: (T, StreamObserver[U]) => Unit =
      (t: T, s: StreamObserver[U]) => {
        Try(f(t)) match {
          case Success(x) => s.onNext(x)
          case Failure(e) => s.onError(e)
        }
        s.onCompleted()
    }
  }
}
