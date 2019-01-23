/**
 * © 2017 Stratio Big Data Inc., Sucursal en España.
 *
 * This software is licensed under the Apache 2.0.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the terms of the License for more details.
 *
 * SPDX-License-Identifier:  Apache-2.0.
 */
package com.stratio.khermes

import java.io.File
import java.net.InetAddress
import java.util.Date

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import com.stratio.khermes.clients.http.flows.WSFlow
import com.stratio.khermes.cluster.collector.CommandCollectorActor
import com.stratio.khermes.cluster.supervisor.{KhermesClientActor, NodeSupervisorActor}
import com.stratio.khermes.commons.constants.AppConstants
import com.stratio.khermes.commons.implicits.AppImplicits
import com.stratio.khermes.metrics.MetricsReporter
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success, Try}

/**
 * Entry point of the application.
 */
object Khermes extends App with LazyLogging {

  import AppImplicits._
  welcome
  createPaths
  MetricsReporter.start

  // Create a NodeSupervisorActor as 'khermesSupervisor'
  val khermesSupervisor: ActorRef = workerSupervisor

  if(config.getString("khermes.client") == "true") {
    clientActor(khermesSupervisor) // 'khermesSupervisor' is a redundant argument here
  }

  if(config.getString("khermes.ws") == "true") {
    wsHttp()
  }

  /**
   * Prints a welcome message with some information about the system.
   * @param system
   */
  def welcome(implicit system: ActorSystem, config: Config): Unit = {
    logger.info(
      s"""
         |╦╔═┬ ┬┌─┐┬─┐┌┬┐┌─┐┌─┐
         |╠╩╗├─┤├┤ ├┬┘│││├┤ └─┐
         |╩ ╩┴ ┴└─┘┴└─┴ ┴└─┘└─┘ Powered by Stratio (www.stratio.com)
         |
         |> System Name   : ${system.name}
         |> Start time    : ${new Date(system.startTime)}
         |> Number of CPUs: ${Runtime.getRuntime.availableProcessors}
         |> Total memory  : ${Runtime.getRuntime.totalMemory}
         |> Free memory   : ${Runtime.getRuntime.freeMemory}
    """.stripMargin)
  }

  /**
   * Creates necessary paths used mainly to generate and compile Twirl templates.
   * @param config with all necessary configuration.
   */
  def createPaths(implicit config: Config): Unit = {
    val templatesFile = new File(config.getString("khermes.templates-path"))
    if(!templatesFile.exists()) {
      logger.info(s"Creating templates path: ${templatesFile.getAbsolutePath}")
      templatesFile.mkdirs()
    }
  }


  def workerSupervisor(implicit config: Config,
                       system: ActorSystem,
                       executionContext: ExecutionContextExecutor): ActorRef =
    system.actorOf(Props(new NodeSupervisorActor()), "khermes-supervisor")

  // Creates a 'KhermesClientActor' actor and sends 'Start' message to it
  //
  // 'config', 'system' and 'executionContext' are all implicit parameters
  // and 'khermesSupervisor' is a redundant arugment
  def clientActor(khermesSupervisor: ActorRef)(implicit config: Config,
                                               system: ActorSystem,
                                               executionContext: ExecutionContextExecutor): Unit = {

    val clientActor = system.actorOf(Props(new KhermesClientActor()), "khermes-client")
    clientActor ! KhermesClientActor.Start // sending a 'Start' message to 'clientActor'
  }

  def wsHttp()(implicit config: Config,
               system: ActorSystem,
               executionContext: ExecutionContextExecutor): Unit = {
    val commandCollector = system.actorOf(CommandCollectorActor.props)

    val routes =
      get {
        pathPrefix("css") {
          getFromResourceDirectory("web/css")
        } ~
        pathPrefix("js") {
          getFromResourceDirectory("web/js")
        } ~
        pathSingleSlash {
            getFromResource("web/index.html")
        } ~
        path("console") {
          getFromResource("web/console.html")
        } ~
        path("input") {
          handleWebSocketMessages(WSFlow.inputFlow(commandCollector))
        } ~
        path("output") {
          handleWebSocketMessages(WSFlow.outputFlow)
        }
      }

    // 'Try(config.getString("khermes.ws.host"))' returns 'Try[String]' on success
    // 'getOrElse' expects a by name argument
    val host: String = Try(config.getString("khermes.ws.host")).getOrElse({
      logger.info("khermes.ws.host is not defined. Setting default: localhost")
      AppConstants.DefaultWSHost
    })

    val port = Try(config.getInt("khermes.ws.port")).getOrElse({
      logger.info("khermes.ws.port is not defined. Setting default: 8080")
      AppConstants.DefaultWSPort
    })

    logger.info("Binding routes......")
    val binding = Http().bindAndHandle(routes, host, port)

    binding.onComplete {
      case Success(b) ⇒ logger.info(s"Started WebSocket Command Server online at ${b.localAddress}")
      case Failure(t) ⇒ logger.error("Failed to start HTTP server")
    }

    while(true){}
    binding.flatMap(_.unbind()).onComplete(_ ⇒ system.terminate())
  }
}
