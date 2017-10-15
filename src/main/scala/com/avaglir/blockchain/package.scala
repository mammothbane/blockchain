package com.avaglir


import io.grpc.stub.StreamObserver

import scala.collection.GenTraversableOnce
import scala.collection.generic.CanBuildFrom
import scala.util.{Failure, Success, Try}

package object blockchain {
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

  implicit class multiMethodTransform[T, U, V[_]](f: T => V[U]) {
    def asJava(implicit cbf: CanBuildFrom[V[U], U, GenTraversableOnce[U]]): (T, StreamObserver[U]) => Unit =
      (t: T, s: StreamObserver[U]) => {
        Try(f(t)) match {
          case Success(x) => cbf(x).result().foreach { elt => s.onNext(elt) }
          case Failure(e) => s.onError(e)
        }
        s.onCompleted()
      }
  }

}
