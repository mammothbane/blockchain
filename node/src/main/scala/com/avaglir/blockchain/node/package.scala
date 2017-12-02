package com.avaglir.blockchain

import java.nio.ByteBuffer

import com.avaglir.blockchain.generated._
import io.grpc.stub.StreamObserver
import io.grpc.{Channel, ManagedChannelBuilder}

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

package object node {
  val pendingTransactions = mutable.Map.empty[Array[Byte], Transaction]
  val blockchain = mutable.Seq.empty[Block]
  val nodes = mutable.Set.empty[Node]

  implicit class nodeExt(t: Node) {
    // TODO: look at caching this
    def channel: Channel = {
      val addr = ByteBuffer.allocate(4).putInt(t.getAddress).array()
      val addrString = addr.map { x => s"${ x.toInt & 0xff }" }.mkString(".")

      ManagedChannelBuilder
        .forAddress(addrString, t.getPort)
        .usePlaintext(true)
        .build
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
