package akka.stream.io

import java.security.{ KeyStore, SecureRandom }
import javax.net.ssl.{ TrustManagerFactory, KeyManagerFactory, SSLContext }
import akka.stream.{ Graph, BidiShape, ActorFlowMaterializer }
import akka.stream.scaladsl._
import akka.stream.io._
import akka.stream.testkit.{ TestUtils, AkkaSpec }
import akka.util.ByteString
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.collection.immutable
import scala.util.Random
import akka.stream.stage.AsyncStage
import akka.stream.stage.AsyncContext
import java.util.concurrent.TimeoutException
import akka.actor.ActorSystem
import javax.net.ssl.SSLSession
import akka.pattern.{ after ⇒ later }
import scala.concurrent.Future
import java.net.InetSocketAddress
import akka.testkit.EventFilter
import akka.stream.stage.PushStage
import akka.stream.stage.Context

object TlsSpec {

  val rnd = new Random

  def initSslContext(): SSLContext = {

    val password = "changeme"

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    keyStore.load(getClass.getResourceAsStream("/keystore"), password.toCharArray)

    val trustStore = KeyStore.getInstance(KeyStore.getDefaultType)
    trustStore.load(getClass.getResourceAsStream("/truststore"), password.toCharArray)

    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(keyStore, password.toCharArray)

    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(trustStore)

    val context = SSLContext.getInstance("TLS")
    context.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom)
    context
  }

  /**
   * This is a stage that fires a TimeoutException failure 2 seconds after it was started,
   * independent of the traffic going through. The purpose is to include the last seen
   * element in the exception message to help in figuring out what went wrong.
   */
  class Timeout(duration: FiniteDuration)(implicit system: ActorSystem) extends AsyncStage[ByteString, ByteString, Unit] {
    private var last: ByteString = _

    override def initAsyncInput(ctx: AsyncContext[ByteString, Unit]) = {
      val cb = ctx.getAsyncCallback()
      system.scheduler.scheduleOnce(duration)(cb.invoke(()))(system.dispatcher)
    }

    override def onAsyncInput(u: Unit, ctx: AsyncContext[ByteString, Unit]) =
      ctx.fail(new TimeoutException(s"timeout expired, last element was $last"))

    override def onPush(elem: ByteString, ctx: AsyncContext[ByteString, Unit]) = {
      last = elem
      if (ctx.isHoldingDownstream) ctx.pushAndPull(elem)
      else ctx.holdUpstream()
    }

    override def onPull(ctx: AsyncContext[ByteString, Unit]) =
      if (ctx.isFinishing) ctx.pushAndFinish(last)
      else if (ctx.isHoldingUpstream) ctx.pushAndPull(last)
      else ctx.holdDownstream()

    override def onUpstreamFinish(ctx: AsyncContext[ByteString, Unit]) =
      if (ctx.isHoldingUpstream) ctx.absorbTermination()
      else ctx.finish()

    override def onDownstreamFinish(ctx: AsyncContext[ByteString, Unit]) = {
      system.log.debug("cancelled")
      ctx.finish()
    }
  }

  // FIXME #17226 replace by .dropWhile when implemented
  class DropWhile[T](p: T ⇒ Boolean) extends PushStage[T, T] {
    private var open = false
    override def onPush(elem: T, ctx: Context[T]) =
      if (open) ctx.push(elem)
      else if (p(elem)) ctx.pull()
      else {
        open = true
        ctx.push(elem)
      }
  }

}

class TlsSpec extends AkkaSpec("akka.loglevel=INFO\nakka.actor.debug.receive=off") {
  import TlsSpec._

  import system.dispatcher
  implicit val materializer = ActorFlowMaterializer()

  import FlowGraph.Implicits._

  "StreamTLS" must {

    val sslContext = initSslContext()

    val debug = Flow[SslTlsInbound].map { x ⇒
      x match {
        case SessionTruncated   ⇒ system.log.debug(s" ----------- truncated ")
        case SessionBytes(_, b) ⇒ system.log.debug(s" ----------- (${b.size}) ${b.take(32).utf8String}")
      }
      x
    }

    val cipherSuites = NegotiateNewSession.withCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_128_CBC_SHA")
    def clientTls(closing: Closing) = SslTls(sslContext, cipherSuites, Client, closing)
    def serverTls(closing: Closing) = SslTls(sslContext, cipherSuites, Server, closing)

    trait Named {
      def name: String =
        getClass.getName
          .reverse
          .dropWhile(c ⇒ "$0123456789".indexOf(c) != -1)
          .takeWhile(_ != '$')
          .reverse
    }

    trait CommunicationSetup extends Named {
      def decorateFlow(leftClosing: Closing, rightClosing: Closing,
                       rhs: Flow[SslTlsInbound, SslTlsOutbound, Any]): Flow[SslTlsOutbound, SslTlsInbound, Unit]
      def cleanup(): Unit = ()
    }

    object ClientInitiates extends CommunicationSetup {
      def decorateFlow(leftClosing: Closing, rightClosing: Closing,
                       rhs: Flow[SslTlsInbound, SslTlsOutbound, Any]) =
        clientTls(leftClosing) atop serverTls(rightClosing).reversed join rhs
    }

    object ServerInitiates extends CommunicationSetup {
      def decorateFlow(leftClosing: Closing, rightClosing: Closing,
                       rhs: Flow[SslTlsInbound, SslTlsOutbound, Any]) =
        serverTls(leftClosing) atop clientTls(rightClosing).reversed join rhs
    }

    def server(flow: Flow[ByteString, ByteString, Any]) = {
      val server = StreamTcp()
        .bind(new InetSocketAddress("localhost", 0))
        .to(Sink.foreach(c ⇒ c.flow.join(flow).run()))
        .run()
      Await.result(server, 2.seconds)
    }

    object ClientInitiatesViaTcp extends CommunicationSetup {
      var binding: StreamTcp.ServerBinding = null
      def decorateFlow(leftClosing: Closing, rightClosing: Closing,
                       rhs: Flow[SslTlsInbound, SslTlsOutbound, Any]) = {
        binding = server(serverTls(rightClosing).reversed join rhs)
        clientTls(leftClosing) join StreamTcp().outgoingConnection(binding.localAddress)
      }
      override def cleanup(): Unit = binding.unbind()
    }

    object ServerInitiatesViaTcp extends CommunicationSetup {
      var binding: StreamTcp.ServerBinding = null
      def decorateFlow(leftClosing: Closing, rightClosing: Closing,
                       rhs: Flow[SslTlsInbound, SslTlsOutbound, Any]) = {
        binding = server(clientTls(rightClosing).reversed join rhs)
        serverTls(leftClosing) join StreamTcp().outgoingConnection(binding.localAddress)
      }
      override def cleanup(): Unit = binding.unbind()
    }

    val communicationPatterns =
      Seq(
        ClientInitiates,
        ServerInitiates,
        ClientInitiatesViaTcp,
        ServerInitiatesViaTcp)

    trait PayloadScenario extends Named {
      def flow: Flow[SslTlsInbound, SslTlsOutbound, Any] =
        Flow[SslTlsInbound]
          .map {
            var session: SSLSession = null
            def setSession(s: SSLSession) = {
              session = s
              system.log.debug(s"new session: $session (${session.getId mkString ","})")
            }

            {
              case SessionTruncated ⇒ SendBytes(ByteString("TRUNCATED"))
              case SessionBytes(s, b) if session == null ⇒
                setSession(s)
                SendBytes(b)
              case SessionBytes(s, b) if s != session ⇒
                setSession(s)
                SendBytes(ByteString("NEWSESSION") ++ b)
              case SessionBytes(s, b) ⇒ SendBytes(b)
            }
          }
      def leftClosing: Closing = IgnoreComplete
      def rightClosing: Closing = IgnoreComplete

      def inputs: immutable.Seq[SslTlsOutbound]
      def output: ByteString

      protected def send(str: String) = SendBytes(ByteString(str))
      protected def send(ch: Char) = SendBytes(ByteString(ch.toByte))
    }

    object SingleBytes extends PayloadScenario {
      val str = "0123456789"
      def inputs = str.map(ch ⇒ SendBytes(ByteString(ch.toByte)))
      def output = ByteString(str)
    }

    object MediumMessages extends PayloadScenario {
      val strs = "0123456789" map (d ⇒ d.toString * (rnd.nextInt(9000) + 1000))
      def inputs = strs map (s ⇒ SendBytes(ByteString(s)))
      def output = ByteString((strs :\ "")(_ ++ _))
    }

    object LargeMessages extends PayloadScenario {
      // TLS max packet size is 16384 bytes
      val strs = "0123456789" map (d ⇒ d.toString * (rnd.nextInt(9000) + 17000))
      def inputs = strs map (s ⇒ SendBytes(ByteString(s)))
      def output = ByteString((strs :\ "")(_ ++ _))
    }

    object EmptyBytesFirst extends PayloadScenario {
      def inputs = List(ByteString.empty, ByteString("hello")).map(SendBytes)
      def output = ByteString("hello")
    }

    object EmptyBytesInTheMiddle extends PayloadScenario {
      def inputs = List(ByteString("hello"), ByteString.empty, ByteString(" world")).map(SendBytes)
      def output = ByteString("hello world")
    }

    object EmptyBytesLast extends PayloadScenario {
      def inputs = List(ByteString("hello"), ByteString.empty).map(SendBytes)
      def output = ByteString("hello")
    }

    // this demonstrates that cancellation is ignored so that the five results make it back
    object CancellingRHS extends PayloadScenario {
      override def flow =
        Flow[SslTlsInbound]
          .mapConcat {
            case SessionTruncated       ⇒ SessionTruncated :: Nil
            case SessionBytes(s, bytes) ⇒ bytes.map(b ⇒ SessionBytes(s, ByteString(b)))
          }
          .take(5)
          .mapAsync(5, x ⇒ later(500.millis, system.scheduler)(Future.successful(x)))
          .via(super.flow)
      override def rightClosing = IgnoreCancel

      val str = "abcdef" * 100
      def inputs = str.map(send)
      def output = ByteString(str.take(5))
    }

    object CancellingRHSIgnoresBoth extends PayloadScenario {
      override def flow =
        Flow[SslTlsInbound]
          .mapConcat {
            case SessionTruncated       ⇒ SessionTruncated :: Nil
            case SessionBytes(s, bytes) ⇒ bytes.map(b ⇒ SessionBytes(s, ByteString(b)))
          }
          .take(5)
          .mapAsync(5, x ⇒ later(500.millis, system.scheduler)(Future.successful(x)))
          .via(super.flow)
      override def rightClosing = IgnoreBoth

      val str = "abcdef" * 100
      def inputs = str.map(send)
      def output = ByteString(str.take(5))
    }

    object LHSIgnoresBoth extends PayloadScenario {
      override def leftClosing = IgnoreBoth
      val str = "0123456789"
      def inputs = str.map(ch ⇒ SendBytes(ByteString(ch.toByte)))
      def output = ByteString(str)
    }

    object BothSidesIgnoreBoth extends PayloadScenario {
      override def leftClosing = IgnoreBoth
      override def rightClosing = IgnoreBoth
      val str = "0123456789"
      def inputs = str.map(ch ⇒ SendBytes(ByteString(ch.toByte)))
      def output = ByteString(str)
    }

    object SessionRenegotiationBySender extends PayloadScenario {
      def inputs = List(send("hello"), NegotiateNewSession, send("world"))
      def output = ByteString("helloNEWSESSIONworld")
    }

    // difference is that the RHS engine will now receive the handshake while trying to send
    object SessionRenegotiationByReceiver extends PayloadScenario {
      val str = "abcdef" * 100
      def inputs = str.map(send) ++ Seq(NegotiateNewSession) ++ "hello world".map(send)
      def output = ByteString(str + "NEWSESSIONhello world")
    }

    val logCipherSuite = Flow[SslTlsInbound]
      .map {
        var session: SSLSession = null
        def setSession(s: SSLSession) = {
          session = s
          system.log.debug(s"new session: $session (${session.getId mkString ","})")
        }

        {
          case SessionTruncated ⇒ SendBytes(ByteString("TRUNCATED"))
          case SessionBytes(s, b) if s != session ⇒
            setSession(s)
            SendBytes(ByteString(s.getCipherSuite) ++ b)
          case SessionBytes(s, b) ⇒ SendBytes(b)
        }
      }

    object SessionRenegotiationFirstOne extends PayloadScenario {
      override def flow = logCipherSuite
      def inputs = NegotiateNewSession.withCipherSuites("TLS_RSA_WITH_AES_128_CBC_SHA") :: send("hello") :: Nil
      def output = ByteString("TLS_RSA_WITH_AES_128_CBC_SHAhello")
    }

    object SessionRenegotiationFirstTwo extends PayloadScenario {
      override def flow = logCipherSuite
      def inputs = NegotiateNewSession.withCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA") :: send("hello") :: Nil
      def output = ByteString("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHAhello")
    }

    val scenarios =
      Seq(
        SingleBytes,
        MediumMessages,
        LargeMessages,
        EmptyBytesFirst,
        EmptyBytesInTheMiddle,
        EmptyBytesLast,
        CancellingRHS,
        SessionRenegotiationBySender,
        SessionRenegotiationByReceiver,
        SessionRenegotiationFirstOne,
        SessionRenegotiationFirstTwo)

    for {
      commPattern ← communicationPatterns
      scenario ← scenarios
    } {
      s"work in mode ${commPattern.name} while sending ${scenario.name}" in {
        val onRHS = debug.via(scenario.flow)
        val f =
          Source(scenario.inputs)
            .via(commPattern.decorateFlow(scenario.leftClosing, scenario.rightClosing, onRHS))
            .transform(() ⇒ new PushStage[SslTlsInbound, SslTlsInbound] {
              override def onPush(elem: SslTlsInbound, ctx: Context[SslTlsInbound]) =
                ctx.push(elem)
              override def onDownstreamFinish(ctx: Context[SslTlsInbound]) = {
                system.log.debug("me cancelled")
                ctx.finish()
              }
            })
            .via(debug)
            .collect { case SessionBytes(_, b) ⇒ b }
            .scan(ByteString.empty)(_ ++ _)
            .transform(() ⇒ new Timeout(6.seconds))
            .transform(() ⇒ new DropWhile(_.size < scenario.output.size))
            .runWith(Sink.head)

        Await.result(f, 8.seconds).utf8String should be(scenario.output.utf8String)

        commPattern.cleanup()

        // flush log so as to not mix up logs of different test cases
        if (log.isDebugEnabled)
          EventFilter.debug("stopgap", occurrences = 1) intercept {
            log.debug("stopgap")
          }
      }
    }

  }

  "A SslTlsPlacebo" must {

    "pass through data" in {
      val f = Source(1 to 3)
        .map(b ⇒ SendBytes(ByteString(b.toByte)))
        .via(SslTlsPlacebo.forScala join Flow.apply)
        .grouped(10)
        .runWith(Sink.head)
      val result = Await.result(f, 3.seconds)
      result.map(_.bytes) should be((1 to 3).map(b ⇒ ByteString(b.toByte)))
      result.map(_.session).foreach(s ⇒ s.getCipherSuite should be("SSL_NULL_WITH_NULL_NULL"))
    }

  }

}
