package com.stratio.khermes.persistence.grpc

import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging

import io.grpc._
import io.grpc.netty._
import io.grpc.stub._
import io.netty.handler.ssl._
import io.netty.handler.ssl.util.InsecureTrustManagerFactory.{INSTANCE ⇒ InsecureInstance}

import java.util.concurrent._

import scala.concurrent.{ExecutionContextExecutor, Future}

abstract class BidirectionalStreamGrpcClient[T, U](
  host: String,
  port: Int,
  ssl: Boolean = false,
  sslContext: Option[SslContext] = None
) extends GrpcClient[T, U] {
  implicit val interceptor: ClientInterceptor

  val (originChannel, channel) = connect(host, port, ssl, sslContext)

  def requestObserver: StreamObserver[T]

  def responseObserver: StreamObserver[U]

  override def shutdown(): Unit = {
    originChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
  }

  override def done(): Unit = requestObserver.onCompleted

  override def send(message: T)(implicit ec: ExecutionContextExecutor): Future[Unit] = {
    Future {
      try {
        requestObserver.onNext(message)
      } catch {
        case e: RuntimeException ⇒
          requestObserver.onError(e) // cancel RPC
          throw e
      }
    }
  }
}
