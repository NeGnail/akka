/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor

import akka.config.Supervision.Permanent
import akka.util.ByteString

import java.net.InetSocketAddress
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.nio.ByteBuffer
import java.nio.channels.{
  SelectableChannel,
  ReadableByteChannel,
  WritableByteChannel,
  SocketChannel,
  ServerSocketChannel,
  Selector,
  SelectionKey,
  CancelledKeyException
}

import scala.collection.immutable.Queue
import scala.annotation.tailrec

import com.eaio.uuid.UUID

object IO {

  case class Token(owner: ActorRef, ioManager: ActorRef, uuid: UUID = new UUID()) {
    override lazy val hashCode = scala.runtime.ScalaRunTime._hashCode(this)
  }

  trait IOMessage { def token: Token }
  case class Listen(token: Token, address: InetSocketAddress) extends IOMessage
  case class NewConnection(token: Token) extends IOMessage
  case class Accept(token: Token, source: Token) extends IOMessage
  case class Connect(token: Token, address: InetSocketAddress) extends IOMessage
  case class Connected(token: Token) extends IOMessage
  case class Close(token: Token) extends IOMessage
  case class Closed(token: Token) extends IOMessage
  case class Read(token: Token, bytes: ByteString) extends IOMessage
  case class Write(token: Token, bytes: ByteString) extends IOMessage

}

trait IO {
  this: Actor ⇒

  def listen(ioManager: ActorRef, host: String, port: Int): IO.Token =
    listen(ioManager, new InetSocketAddress(host, port))

  def listen(ioManager: ActorRef, address: InetSocketAddress): IO.Token = {
    val token = IO.Token(self, ioManager)
    ioManager ! IO.Listen(token, address)
    token
  }

  def connect(ioManager: ActorRef, host: String, port: Int): IO.Token =
    connect(ioManager, new InetSocketAddress(host, port))

  def connect(ioManager: ActorRef, address: InetSocketAddress): IO.Token = {
    val token = IO.Token(self, ioManager)
    ioManager ! IO.Connect(token, address)
    token
  }

  def accept(source: IO.Token, owner: ActorRef): IO.Token = {
    val ioManager = source.ioManager
    val token = IO.Token(owner, ioManager)
    ioManager ! IO.Accept(token, source)
    token
  }

  def write(token: IO.Token, bytes: ByteString): Unit =
    token.ioManager ! IO.Write(token, bytes)

  def close(token: IO.Token): Unit =
    token.ioManager ! IO.Close(token)

}

class IOManager extends Actor {

  var worker: IOWorker = _
  var workerThread: Thread = _

  override def preStart: Unit = {
    worker = new IOWorker(self)
    workerThread = new Thread(worker)
    workerThread.start
  }

  def receive = {
    case IO.Listen(token, address)  ⇒ worker.createServer(token, address)
    case IO.Connect(token, address) ⇒ worker.createClient(token, address)
    case IO.Accept(token, source)   ⇒ worker.acceptConnection(token, source)
    case IO.Write(token, data)      ⇒ worker.write(token, data)
    case IO.Close(token)            ⇒ worker.close(token)
  }

}

private[akka] object IOWorker {
  sealed trait ChangeRequest { def token: IO.Token }
  case class Register(token: IO.Token, channel: SelectableChannel, ops: Int) extends ChangeRequest
  case class Accepted(token: IO.Token, serverToken: IO.Token) extends ChangeRequest
  case class QueueWrite(token: IO.Token, data: ByteBuffer) extends ChangeRequest
  case class Close(token: IO.Token) extends ChangeRequest
}

private[akka] class IOWorker(ioManager: ActorRef) extends Runnable {
  import SelectionKey.{ OP_READ, OP_WRITE, OP_ACCEPT, OP_CONNECT }
  import IOWorker._

  type ReadChannel = ReadableByteChannel with SelectableChannel
  type WriteChannel = WritableByteChannel with SelectableChannel

  implicit val optionIOManager: Some[ActorRef] = Some(ioManager)

  val bufferSize = 8192 // make configurable

  def createServer(token: IO.Token, address: InetSocketAddress): Unit = {
    val server = ServerSocketChannel open ()
    server configureBlocking false
    server.socket bind address
    addChangeRequest(Register(token, server, OP_ACCEPT))
  }

  def createClient(token: IO.Token, address: InetSocketAddress): Unit = {
    val client = SocketChannel open ()
    client configureBlocking false
    client connect address
    addChangeRequest(Register(token, client, OP_CONNECT | OP_READ))
  }

  def acceptConnection(token: IO.Token, source: IO.Token): Unit =
    addChangeRequest(Accepted(token, source))

  def write(token: IO.Token, data: ByteString): Unit =
    addChangeRequest(QueueWrite(token, data.asByteBuffer))

  def close(token: IO.Token): Unit =
    addChangeRequest(Close(token))

  // private

  private val selector: Selector = Selector open ()

  private val _changeRequests = new AtomicReference(List.empty[ChangeRequest])

  private var acceptedChannels = Map.empty[IO.Token, Queue[SelectableChannel]].withDefaultValue(Queue.empty)

  private var channels = Map.empty[IO.Token, SelectableChannel]

  private var writeQueues = Map.empty[IO.Token, Queue[ByteBuffer]].withDefaultValue(Queue.empty)

  private val buffer = ByteBuffer.allocate(bufferSize)

  def run(): Unit = {
    while (selector.isOpen) {
      val keys = selector.selectedKeys.iterator
      while (keys.hasNext) {
        val key = keys next ()
        keys remove ()
        if (key.isValid) { process(key) }
      }
      _changeRequests.getAndSet(Nil).reverse foreach {
        case Register(token, channel, ops) ⇒
          channels += (token -> channel)
          channel register (selector, ops, token)
        case Accepted(token, serverToken) ⇒
          val (channel, rest) = acceptedChannels(serverToken).dequeue
          if (rest.isEmpty) acceptedChannels -= serverToken
          else acceptedChannels += (serverToken -> rest)
          channels += (token -> channel)
          channel register (selector, OP_READ, token)
        case QueueWrite(token, data) ⇒
          if (channels contains token) {
            val queue = writeQueues(token)
            if (queue.isEmpty) addOps(token, OP_WRITE)
            writeQueues += (token -> queue.enqueue(data))
          }
        case Close(token) ⇒
          channels(token).close
          channels -= token
          token.owner ! IO.Closed(token)
      }
      selector select ()
    }
  }

  private def process(key: SelectionKey): Unit = {
    val token = key.attachment.asInstanceOf[IO.Token]
    try {
      if (key.isConnectable) key.channel match {
        case client: SocketChannel ⇒ connected(token, client)
      }
      if (key.isAcceptable) key.channel match {
        case server: ServerSocketChannel ⇒ accept(token, server)
      }
      if (key.isReadable) key.channel match {
        case channel: ReadChannel ⇒ read(token, channel)
      }
      if (key.isWritable) key.channel match {
        case channel: WriteChannel ⇒ write(token, channel)
      }
    } catch {
      // TODO: Perform cleanup due to closed connection
      case e: CancelledKeyException ⇒
    }
  }

  private def setOps(token: IO.Token, ops: Int): Unit =
    channels(token) keyFor selector interestOps ops

  private def addOps(token: IO.Token, ops: Int): Unit = {
    val key = channels(token) keyFor selector
    val cur = key.interestOps
    key interestOps (cur | ops)
  }

  private def removeOps(token: IO.Token, ops: Int): Unit = {
    val key = channels(token) keyFor selector
    val cur = key.interestOps
    key interestOps (cur - (cur & ops))
  }

  private def connected(token: IO.Token, client: SocketChannel): Unit = {
    client.finishConnect
    removeOps(token, OP_CONNECT)
    token.owner ! IO.Connected(token)
  }

  @tailrec
  private def accept(token: IO.Token, server: ServerSocketChannel): Unit = {
    val client = server.accept
    if (client ne null) {
      client configureBlocking false
      acceptedChannels += (token -> (acceptedChannels(token) enqueue client))
      token.owner ! IO.NewConnection(token)
      accept(token, server)
    }
  }

  @tailrec
  private def read(token: IO.Token, channel: ReadChannel): Unit = {
    buffer.clear
    val readLen = channel read buffer
    if (readLen == -1) {
      channel.close
      channels -= token
      token.owner ! IO.Closed(token)
    } else if (readLen > 0) {
      buffer.flip
      token.owner ! IO.Read(token, ByteString(buffer))
      if (readLen == buffer.capacity) read(token, channel)
    }
  }

  @tailrec
  private def write(token: IO.Token, channel: WriteChannel): Unit = {
    val queue = writeQueues(token)
    if (queue.nonEmpty) {
      val (buf, bufs) = queue.dequeue
      val writeLen = channel write buf
      if (buf.remaining == 0) {
        if (bufs.isEmpty) {
          writeQueues -= token
          removeOps(token, OP_WRITE)
        } else {
          writeQueues += (token -> bufs)
          write(token, channel)
        }
      }
    }
  }

  @tailrec
  private def addChangeRequest(req: ChangeRequest): Unit = {
    val changeRequests = _changeRequests.get
    if (_changeRequests compareAndSet (changeRequests, req :: changeRequests))
      selector wakeup ()
    else
      addChangeRequest(req)
  }
}
