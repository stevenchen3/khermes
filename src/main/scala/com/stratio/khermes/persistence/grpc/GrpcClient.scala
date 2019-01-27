package com.stratio.khermes.persistence.grpc

import com.stratio.khermes.persistence.KhermesSink
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging

import io.grpc._
import io.grpc.netty._
import io.netty.handler.ssl._
import io.netty.handler.ssl.util.InsecureTrustManagerFactory.{INSTANCE ⇒ InsecureInstance}

import scala.concurrent.{ExecutionContext, Future}

trait GrpcClient[T, U] extends KhermesSink[T, U] {
  def connect(host: String, port: Int, ssl: Boolean = false, sslContext: Option[SslContext] = None
    )(implicit interceptor: ClientInterceptor): (ManagedChannel, Channel) = {
      val channel = if (ssl) {
        val context = sslContext match {
          case Some(context) ⇒ context
          case None ⇒ GrpcSslContexts.forClient().trustManager(InsecureInstance).build
        }
        NettyChannelBuilder.forAddress(host, port).sslContext(context).build()
      } else {
        ManagedChannelBuilder.forAddress(host, port).usePlaintext().build()
      }
      (channel, ClientInterceptors.intercept(channel, interceptor))
  }

  def send(topic: Option[String], message: T)(implicit ec: ExecutionContext): Future[U]

  def close(): Unit
}
