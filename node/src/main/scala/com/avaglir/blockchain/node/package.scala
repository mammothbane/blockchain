package com.avaglir.blockchain

import io.grpc.stub.StreamObserver

import scala.util.{Failure, Success, Try}

package object node {
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
