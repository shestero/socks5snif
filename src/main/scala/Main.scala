import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.scaladsl.{BidiFlow, BroadcastHub, Flow, Keep, MergeHub, Sink, Source, Tcp}
import akka.stream.scaladsl.Tcp.{IncomingConnection, ServerBinding}
import akka.util.ByteString
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.io.StdIn
import scala.util.{Failure, Success, Try}
import akka.http.scaladsl.coding._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.HttpEntity.ChunkStreamPart
import akka.http.scaladsl.model.ws.TextMessage

import java.net.InetSocketAddress
import scala.concurrent.ExecutionContext.Implicits.global

object Main extends App {
  val app = "socks5snif"

  println(s"HELLO\t$app [listen_host [listen_port [remote_host [remote_port [control_host [ control_port]]]]]]")

  val def_listen_host = "127.0.0.1"
  val def_listen_port = 9090
  val def_remote_host = "127.0.0.1"
  val def_remote_port = 9099
  val def_http_host = "127.0.0.1"
  val def_http_port = 9080

  println("Where defaults are: "+
    s"$def_listen_host, $def_listen_port;  $def_remote_host, $def_remote_port;  $def_http_host, $def_http_port")

  val argmap = ((Stream from 1) zip args).toMap
  Try {
    (
      argmap.getOrElse(1, def_listen_host),
      argmap.get(2).map(_.toInt).getOrElse(def_listen_port),
      argmap.getOrElse(3, def_remote_host),
      argmap.get(4).map(_.toInt).getOrElse(def_remote_port),
      argmap.getOrElse(3, def_http_host),
      argmap.get(4).map(_.toInt).getOrElse(def_http_port)
    )
  }.map{ params =>
    println(s"So using: $params")
    run _ tupled params
  }.failed.map { e =>
    println(e.getMessage)
  }

  def run(
           listen_host: String, listen_port: Int,
           remote_host: String, remote_port: Int,
           http_host: String, http_port: Int
         ): Unit =
  {
    val wspath = "ws"
    val ws = s"ws://$http_host:$http_port/$wspath"

    val config = ConfigFactory.parseString("akka.loglevel = DEBUG")
    implicit val system = ActorSystem(app, config)

    val bufSize = 256
    val (bfSink, bfSource) = MergeHub
      .source[MonitorRecord](bufSize)
      .toMat(BroadcastHub.sink[MonitorRecord](bufSize))(Keep.both)
      .run()

    val listen: Source[IncomingConnection, Future[ServerBinding]] = Tcp().bind(listen_host, listen_port)

    val sink = Sink.foreach { incomingConnection: IncomingConnection =>
      val id = java.util.UUID.randomUUID().toString
      val connInfo = s"New incomingConnection from ID=$id : ${incomingConnection.remoteAddress}"
      println(connInfo)

      val outgoing = Tcp().outgoingConnection(remote_host, remote_port)

      val (futAdr, parser) = Flow[ByteString].drop(1).statefulMapConcat[InetSocketAddress] { () =>
        var host = ""
        var port = 0
        var atyp = 0
        var len = 0
        var stage = 0

        def next(ok: Boolean = true): Unit = {
          if (ok) stage = stage + 1 else stage = 0
        }

        // ByteString => Iterable[InetSocketAddress]
        _.zipWithIndex.flatMap(
          (_, atyp, stage) match {
            case ((c, 0), _, 0) => // VER
              next(c == 5) // Socks5
              None

            case ((c, _), _, 1) => // CMD
              next(c == 1) // CONNECT
              None

            case ((_, _), _, 2) => // RSV
              next()
              None

            case ((c, _), _, 3) => // ATYP
              // o   IP v4 addr: X'01'
              // o  domain name: X'03'
              // o   IP v6 addr: X'04'
              next(c == 1 || c == 3 || c == 4)
              atyp = c
              None

            case ((c, _), 1, s) if s >= 4 && s <= 7 => // DST.HOST IPv4
              if (!host.isEmpty) host += "."
              host += "%03d".format(c & 0xFF)
              if (s == 7)
                stage = 300
              else
                next()
              None

            case ((c, _), 3, 4) => // DST.HOST domain (len)
              len = c;
              next()
              None

            case ((c, _), 3, s) if s > 4 && len > 0 => // DST.HOST domain
              host += c.toChar
              len = len - 1
              if (len == 0)
                stage = 300
              else
                next()
              None

            case ((c, _), 4, s) if s >= 4 && s <= 19 => // DST.HOST IPv6
              host += "%02x".format(c)
              if (s == 19)
                stage = 300
              else {
                if (s % 2 > 0)
                  host += ":"
                next()
              }
              None

            case ((c, _), _, 300) =>
              port = c;
              next()
              None

            case ((c, _), _, 301) =>
              port = port * 256 + c
              next()
              Some(new InetSocketAddress(host, port))

            // case _ => List.empty
          }
        )
      }.toMat(Sink.head)(Keep.right).preMaterialize()

      // -----
      val (toConnectionInformer, connectionInformer) = MergeHub.source[(Int, Int)](bufSize).preMaterialize() // since 2.5.27
      // ^ (Mat, S) = (Sink that can be materialized any number of times, Source (common)

      connectionInformer.groupedWithin(Int.MaxValue, 1.seconds)
        // .map(  combineAll(_) ) // cats
        .map(_.reduce((a, b) => (a._1 + b._1, a._2 + b._2)))
        .map {
          var acc = (0L, 0L)
          p =>
            //import cats.implicits._
            //acc = acc combine p // cats
            acc = (acc._1 + p._1, acc._2 + p._2)
            MonitorRecord(id, incomingConnection.remoteAddress, futAdr, p, acc)
        }
        .runWith(bfSink)

      val counterBidiFlow = BidiFlow.fromFlows(
        Flow[ByteString].alsoTo(Flow[ByteString].map(bs => (bs.size, 0)).to(toConnectionInformer)),
        Flow[ByteString].alsoTo(Flow[ByteString].map(bs => (0, bs.size)).to(toConnectionInformer))
      )
      // -----

      val workflow = Flow[ByteString].wireTap(parser).via(counterBidiFlow join outgoing)

      incomingConnection.handleWith(workflow.watchTermination()(Keep.right)).onComplete {
        case Success(_) => println(s"stream completed successfully: $id ${incomingConnection.remoteAddress}")
        case Failure(e) => println(e.getMessage)
      }
    }

    val binding = listen.to(sink).run()
    binding.onComplete { tb =>
      println("binding ...")
      tb.foreach(_.whenUnbound.onComplete(_ => println("unbound")))
    }
    binding.failed.map(_.getMessage).foreach(println)

    // HTTP/WS control service
    val route = {
      pathSingleSlash {
        complete(HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          "<ul>\n" +
            "\t<li><a href='/monitor'>/monitor</a> - Monitor connections as TSV (AJAX endless stream)</li>\n" +
            s"\t<li><a href='$ws'>$ws</a> - WebSocket connection point. Tested like:<br/>" +
            s"\t<tt>wscat -c $ws</tt><br/>" +
            "\t(<i>npm install -g wscat</i>)</li>\n" +
            "</ul>\n"
        ))
      } ~
        path("monitor") {
          get {
            withRequestTimeoutResponse(request => HttpResponse(entity = "status timeout")) {
              encodeResponseWith(Coders.NoCoding) {
                complete {
                  val chunked =
                    Source.fromIterator(() =>
                      (Seq.fill(1000)(ChunkStreamPart(" ")) :+ ChunkStreamPart("CONNECTION's INFORMATION:\n")).iterator) ++
                      bfSource.map(_.toString()).map { s => println("chunk " + s); ChunkStreamPart(s + "\n") }

                  HttpResponse(entity = HttpEntity.Chunked(ContentTypes.`text/plain(UTF-8)`, chunked))
                }
              }
            }
          }
        } ~
        path(wspath) {
          /*
        handle( r=>
        Future( r.attribute(AttributeKeys.webSocketUpgrade) match {
          case Some(upgrade) => upgrade.handleMessages(Flow.fromSinkAndSource(Sink.ignore,bfSource.map(_.toString).map(TextMessage(_))))
          case None          => HttpResponse(400, entity = "Not a valid websocket request!")
        }))
        */
          handleWebSocketMessages(Flow.fromSinkAndSource(Sink.ignore, bfSource.map(_.toString).map(TextMessage(_))))
        }
    }

    val bindingFuture = Http().newServerAt(http_host, http_port).bind(route)
    // .bindAndHandle(route, http_host, http_port) // depricated
    bindingFuture.onComplete(_ match {
      case Success(_) => println(s"HTTP service is accepting connections at $http_host:$http_port")
      case Failure(e) => println("ERROR: Cannot start HTTP service: " + e.getMessage)
    })


    println(s"Server now online.\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return

    binding.flatMap(_.unbind()).onComplete(_ => system.terminate()) // and shutdown when done
    system.terminate()
  }

  println(s"END\t$app")
/}
