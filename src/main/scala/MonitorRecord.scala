
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Calendar
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps


case class MonitorRecord(
                      sockId : String,
                      source : InetSocketAddress,
                      destination: Future[InetSocketAddress],
                      current: (Int,Int),
                        total: (Long,Long)
                      )
{
  val timeout = 250 milliseconds
  
  val tstamp = Calendar.getInstance().getTime
  val fmt = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss")
  def datetime = fmt.format(tstamp)

  def dest = Await.ready(destination.map(a=>a.getHostName+"/"+a.getAddress.getHostAddress+":"+a.getPort), timeout)
    //.map(a=>a.getHostName+":"+a.getPort)
    .value.map(_.toOption).flatten.getOrElse("N/A")

  val sep = "\t"
  override def toString: String =
        sockId+sep+
        datetime+sep+
        source+sep+
        dest+sep+
        current+sep+
        total

}
