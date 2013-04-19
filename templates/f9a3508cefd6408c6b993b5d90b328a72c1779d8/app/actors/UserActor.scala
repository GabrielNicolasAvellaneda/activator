package actors

import akka.actor.{Props, ActorRef, Actor}
import play.mvc.WebSocket
import org.codehaus.jackson.JsonNode
import scala.collection.mutable
import play.libs.Json
import akka.pattern.ask
import akka.util.Timeout
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import utils.Global

class UserActor(uuid: String, out: WebSocket.Out[JsonNode]) extends Actor {

  val stocks: mutable.Map[String, ActorRef] = mutable.Map.empty[String, ActorRef]

  def receive = {
    case StockUpdate(symbol, price) => {
      if (stocks.contains(symbol)) {
        val message = Json.newObject()
        message.put("type", "stockupdate")
        message.put("symbol", symbol)
        message.put("price", price.doubleValue)
        out.write(message)
      }
    }
    case WatchStock(uuid: String, symbol: String) => {
      implicit val timeout = Timeout(15 seconds)
      (Global.stockHolderActor ? SetupStock(symbol)).mapTo[ActorRef].map { stockActorRef =>
        stocks.put(symbol, stockActorRef)

        // send the whole history to the client
        (stockActorRef ? FetchHistory).mapTo[Seq[Number]].map { history =>

          val message = Json.newObject()
          message.put("type", "stockhistory")
          message.put("symbol", symbol)

          val historyJson = message.putArray("history")
          history.foreach(price => historyJson.add(price.doubleValue))

          out.write(message)
        }
      }
    }
    case UnwatchStock(uuid: String, symbol: String) => {
      if (stocks.contains(symbol)) {
        stocks.remove(symbol)
      }
    }
  }
}

class UsersActor extends Actor {
  def receive = {
    case StockUpdate(symbol, price) => {
      for(child <- context.children) child ! StockUpdate(symbol, price)
    }
    case Listen(uuid: String, out: WebSocket.Out[JsonNode]) => {
      context.actorOf(Props(new UserActor(uuid, out)), uuid)
    }
    case WatchStock(uuid: String, symbol: String) => {
      context.actorFor(uuid) ! WatchStock(uuid, symbol)
    }
    case UnwatchStock(uuid: String, symbol: String) => {
      context.actorFor(uuid) ! UnwatchStock(uuid, symbol)
    }
  }
}

case class Listen(uuid: String, out: WebSocket.Out[JsonNode])

case class WatchStock(uuid: String, symbol: String)

case class UnwatchStock(uuid: String, symbol: String)