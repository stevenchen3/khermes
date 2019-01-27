package com.stratio.khermes.persistence

import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ExecutionContext, Future}

trait KhermesSink[T, U] extends LazyLogging {
  def send(topic: Option[String], message: T)(implicit ec: ExecutionContext): Future[U]

  def close(): Unit
}
