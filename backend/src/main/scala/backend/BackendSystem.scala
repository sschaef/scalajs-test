package backend

import java.nio.ByteBuffer

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import nvim.{Selection ⇒ _, _}
import nvim.internal.Notification
import protocol.{Mode ⇒ _, _}

final class NvimAccessor(self: ActorRef)(implicit system: ActorSystem) {
  import system.dispatcher

  private val nvim = new Nvim(new Connection("127.0.0.1", 6666))

  private var windows = Set[Int]()

  /**
   * Whenever this is set to `true`, the next WinEnter event that is receives by
   * `handler` needs to be ignored. This is necessary because in some cases we
   * know that we changed the active window and therefore there may not be a
   * need to handle the sent WinEnter event.
   */
  @volatile
  private var ignoreNextWinEnter = false

  object events {
    val WinEnter = "_WinEnter"
    val WinLeave = "_WinLeave"

    val AllEvents = Seq(WinEnter, WinLeave)
  }

  private val handler: Notification ⇒ Unit = n ⇒ {
    import events._
    n.method match {
      case WinEnter if ignoreNextWinEnter ⇒
        ignoreNextWinEnter = false
      case WinEnter ⇒
        val resp = updateWindows() flatMap (_ ⇒ clientUpdate)

        handle(resp, "Failed to send a broadcast event.") { resp ⇒
          NvimSignal(resp)
        }

      case WinLeave ⇒

      case _ ⇒
        system.log.warning(s"Notification for unknown event type arrived: $n")
    }
  }

  nvim.connection.addNotificationHandler(handler)
  events.AllEvents foreach nvim.subscribe
  updateWindows()

  private def updateWindows(): Future[Unit] = {
    nvim.windows map { ws ⇒
      val winIds = ws.map(_.id).toSet
      val removed = windows diff winIds
      val added = winIds diff windows
      windows --= removed
      windows ++= added
    }
  }

  private def currentBufferContent: Future[Seq[String]] = for {
    b ← nvim.buffer
    c ← bufferContent(b)
  } yield c

  private def bufferContent(b: Buffer): Future[Seq[String]] = for {
    count ← b.lineCount
    s ← b.lineSlice(0, count)
  } yield s

  private def selection = for {
    win ← nvim.window
    buf ← win.buffer
    sel ← nvim.selection
  } yield {
    val List(start, end) = List(
      Pos(sel.start.row-1, sel.start.col-1),
      Pos(sel.end.row-1, sel.end.col-1)
    ).sorted
    Selection(win.id, buf.id, start, end)
  }

  private def winOf(winId: Int): Future[Window] =
    Future.successful(Window(winId, nvim.connection))

  private def winInfo(winId: Int) = for {
    win ← winOf(winId)
    buf ← win.buffer
    content ← bufferContent(buf)
  } yield WindowUpdate(win.id, buf.id,  content)

  private def clientUpdate = for {
    wins ← Future.sequence(windows map winInfo)
    mode ← nvim.activeMode
    sel ← selection
  } yield ClientUpdate(wins.toSeq, Mode.asString(mode), sel)

  def handleClientJoined(sender: String): Unit = {
    val resp = clientUpdate

    handle(resp, s"Failed to send an update to the client '$sender'.") {
      resp ⇒ NvimSignal(sender, resp)
    }
  }

  def handleTextChange(change: TextChange, sender: String): Unit = {
    system.log.info(s"received: $change")
    val resp = for {
      _ ← nvim.sendInput(change.text)
      win ← nvim.window = change.winId
      content ← currentBufferContent
      mode ← nvim.activeMode
      s ← selection
    } yield ClientUpdate(Seq(WindowUpdate(win.id, change.bufferId, content)), Mode.asString(mode), s)

    handle(resp, s"Failed to send response after client request `$change`.") {
      resp ⇒ NvimSignal(sender, resp)
    }
  }

  def handleSelectionChange(change: SelectionChange, sender: String): Unit = {
    system.log.info(s"received: $change")
    val resp = for {
      w ← nvim.window
      _ = if (w.id != change.winId) ignoreNextWinEnter = true
      win ← nvim.window = change.winId
      _ ← win.cursor = Position(change.cursorRow+1, change.cursorColumn)
      s ← selection
    } yield SelectionChangeAnswer(win.id, change.bufferId, s)

    handle(resp, s"Failed to send response after client request `$change`.") {
      resp ⇒ NvimSignal(sender, resp)
    }
  }

  def handleControl(control: Control, sender: String): Unit = {
    system.log.info(s"received: $control")
    val resp = for {
      win ← nvim.window = control.winId
      _ ← nvim.sendInput(control.controlSeq)
      content ← currentBufferContent
      mode ← nvim.activeMode
      s ← selection
    } yield ClientUpdate(Seq(WindowUpdate(win.id, control.bufferId, content)), Mode.asString(mode), s)

    handle(resp, s"Failed to send response after client request `$control`.") {
      resp ⇒ NvimSignal(sender, resp)
    }
  }

  private def handle[A, B](f: Future[A], errMsg: String)(onSuccess: A ⇒ B): Unit = {
    f onComplete {
      case Success(a) ⇒
        val res = onSuccess(a)
        self ! res
        system.log.info(s"sent: $res")

      case Failure(t) ⇒
        system.log.error(t, errMsg)
    }
  }
}

final class BackendSystem(implicit system: ActorSystem) {
  import boopickle.Default._

  private val actor = system.actorOf(Props[MsgActor])

  def authFlow(): Flow[ByteBuffer, ByteBuffer, Unit] = {
    val out = Source
      .actorRef[Response](1, OverflowStrategy.fail)
      .mapMaterializedValue { actor ! NewClient(_) }
      .map(Pickle.intoBytes(_))
    Flow.wrap(Sink.ignore, out)(Keep.none)
  }

  def messageFlow(sender: String): Flow[ByteBuffer, ByteBuffer, Unit] = {
    def sink(sender: String) = Sink.actorRef[Msg](actor, ClientLeft(sender))

    val in = Flow[ByteBuffer]
      .map(b ⇒ ReceivedMessage(sender, Unpickle[Request].fromBytes(b)))
      .to(sink(sender))
    val out = Source
      .actorRef[Response](1, OverflowStrategy.fail)
      .mapMaterializedValue { actor ! ClientReady(sender, _) }
      .map(Pickle.intoBytes(_))
    Flow.wrap(in, out)(Keep.none)
  }
}

final class MsgActor extends Actor {
  import context.system

  private val repl = new Repl
  private var clients = Map.empty[String, ActorRef]
  private val nvim = new NvimAccessor(self)

  override def receive = {
    case NewClient(subject) ⇒
      val sender = "client" + clients.size
      system.log.info(s"New client '$sender' seen")
      subject ! ConnectionSuccessful(sender)

    case ClientReady(sender, subject) ⇒
      if (clients.contains(sender)) {
        system.log.info(s"'$sender' already exists")
        // TODO this can only happen when multiple clients try to join at nearly the same moment
        subject ! ConnectionFailure
      }
      else {
        clients += sender → subject
        system.log.info(s"'$sender' joined")
        subject ! ConnectionSuccessful(sender)
        nvim.handleClientJoined(sender)
      }

    case ReceivedMessage(sender, msg) ⇒
      msg match {
        case Interpret(id, expr) ⇒
          val res = repl.interpret(expr)
          clients(sender) ! InterpretedResult(id, res)

        case change: SelectionChange ⇒
          nvim.handleSelectionChange(change, sender)

        case change: TextChange ⇒
          nvim.handleTextChange(change, sender)

        case control: Control ⇒
          nvim.handleControl(control, sender)
      }

    case NvimSignal(Some(sender), resp) ⇒
      clients(sender) ! resp

    case NvimSignal(None, resp) ⇒
      clients.values foreach (_ ! resp)

    case ClientLeft(sender) ⇒
      clients -= sender
      system.log.info(s"'$sender' left")
  }
}

sealed trait Msg
case class ReceivedMessage(sender: String, req: Request) extends Msg
case class ClientLeft(sender: String) extends Msg
case class NewClient(subject: ActorRef) extends Msg
case class ClientReady(sender: String, subject: ActorRef) extends Msg
case class NvimSignal(sender: Option[String], resp: Response)
object NvimSignal {
  def apply(sender: String, resp: Response): NvimSignal =
    NvimSignal(Some(sender), resp)

  def apply(resp: Response): NvimSignal =
    NvimSignal(None, resp)
}