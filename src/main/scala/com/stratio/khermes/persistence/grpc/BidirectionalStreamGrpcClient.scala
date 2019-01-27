package com.stratio.khermes.persistence.grpc

import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging

import io.grpc._
import io.grpc.netty._
import io.grpc.stub._
import io.netty.handler.ssl._

import java.util.concurrent._

import scala.concurrent.{ExecutionContext, Future}

abstract class BidirectionalStreamGrpcClient[T](
  host: String,
  port: Int,
  ssl: Boolean = false,
  sslContext: Option[SslContext] = None
) extends GrpcClient[T, Unit] {
  implicit val interceptor: ClientInterceptor

  val (originChannel, channel) = connect(host, port, ssl, sslContext)

  def requestObserver: StreamObserver[T]

  def responseObserver: StreamObserver[Unit]

  def shutdown(): Unit = {
    originChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    logger.info("Closed gRPC connection")
  }

  def close(): Unit = {
    requestObserver.onCompleted
    shutdown()
  }

  override def send(topic: Option[String], message: T)(implicit ec: ExecutionContext): Future[Unit] = {
    Future {
      try {
        requestObserver.onNext(message)
      } catch {
        case t: Throwable ⇒
          logger.error("Closed client-side gRPC stream")
          requestObserver.onError(t) // cancel client-side gRPC stream
          throw t
      }
    }
  }
}
