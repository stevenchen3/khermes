package com.stratio.khermes.persistence.grpc

import io.grpc._
import io.grpc.ClientCall.Listener
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener

class CustomHeaderInterceptor(val metadata: Map[String, String]) extends ClientInterceptor {
  override def interceptCall[ReqT, RespT](method: MethodDescriptor[ReqT, RespT],
    callOptions: CallOptions, next: Channel): ClientCall[ReqT, RespT] = {
    return new SimpleForwardingClientCall[ReqT, RespT](next.newCall(method, callOptions)) {
      override def start(responseListener: Listener[RespT], headers: Metadata): Unit = {
        // Add custom headers
        metadata.foreach { x ⇒
          headers.put(Metadata.Key.of(x._1, Metadata.ASCII_STRING_MARSHALLER), x._2)
        }
        // Ignore server headers
        super.start(new SimpleForwardingClientCallListener[RespT](responseListener){}, headers)
      }
    }
  }
}

object CustomHeaderInterceptor {
  def apply(metadata: Map[String, String]) = new CustomHeaderInterceptor(metadata)
}
