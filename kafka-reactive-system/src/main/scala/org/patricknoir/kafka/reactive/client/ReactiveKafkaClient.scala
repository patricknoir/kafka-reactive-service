package org.patricknoir.kafka.reactive.client

import akka.actor.{ ActorRef, ActorSystem }
import akka.kafka.scaladsl.Producer
import akka.kafka.{ ProducerMessage, ProducerSettings }
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl._
import akka.util.Timeout
import akka.{ Done, NotUsed }
import io.circe.generic.auto._
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.patricknoir.kafka.reactive.client.actors.protocol.{ ResponseInfo, SendMessageComplete, StreamRequest, StreamRequestWithSender }
import org.patricknoir.kafka.reactive.client.actors.{ StreamCoordinatorActor, StreamPublisherActor }
import org.patricknoir.kafka.reactive.client.config.KafkaReactiveClientConfig
import org.patricknoir.kafka.reactive.common._

import scala.concurrent.Future
import scala.reflect.ClassTag
import io.circe.syntax._

/**
 * Concrete implementation of a [[ReactiveClient]] using Reactive Streams
 * @param config configuration for setting up the reactive stream
 * @param system actor system to be used by this instance
 */
class ReactiveKafkaClient(config: KafkaReactiveClientConfig)(implicit system: ActorSystem, materializer: Materializer) extends ReactiveClient {

  import config._
  import system.dispatcher

  val coordinator: ActorRef = system.actorOf(StreamCoordinatorActor.props, "streamCoordinator")

  /** AT MOST ONCE **/

  private val publisher = createStream()

  override def request[In: ReactiveSerializer, Out: ReactiveDeserializer](destination: String, payload: In, headers: Map[String, String])(implicit timeout: Timeout, ct: ClassTag[Out]): Future[Out] =
    destination match {
      case Destination(medium, topic, serviceId) =>
        (publisher ? StreamRequest(Destination(medium, topic, serviceId), new String(implicitly[ReactiveSerializer[In]].serialize(payload)), timeout, Some(ResponseInfo(responseTopic, implicitly[ReactiveDeserializer[Out]])), headers)).mapTo[Out]
      case other =>
        Future.failed[Out](new RuntimeException(s"invalid destination: $destination"))
    }

  override def send[In: ReactiveSerializer](destination: String, payload: In, confirmSend: Boolean, headers: Map[String, String])(implicit timeout: Timeout): Future[Unit] =
    destination match {
      case Destination(medium, topic, serviceId) =>
        (publisher ? StreamRequest(Destination(medium, topic, serviceId), new String(implicitly[ReactiveSerializer[In]].serialize(payload)), timeout, None, headers)).mapTo[Done].map(_ => ())
      case other =>
        Future.failed[Unit](new RuntimeException(s"invalid destination: $destination"))
    }

  /**
   * STREAM SETUP
   */

  private def createStream(): ActorRef = {
    val producerSettings = config.producerConfig

    val requestFlow: Flow[StreamRequestWithSender, ProducerMessage.Message[String, String, KafkaRequestEnvelope], NotUsed] = Flow[StreamRequestWithSender].mapAsync(parallelism) { reqWithSender: StreamRequestWithSender =>
      implicit val timeout = reqWithSender.request.timeout
      (coordinator ? reqWithSender).mapTo[KafkaRequestEnvelope].map(envelope => ProducerMessage.Message(new ProducerRecord[String, String](envelope.destination.topic, envelope.asJson.noSpaces), envelope))
    }

    val responseFlow: Flow[KafkaResponseEnvelope, KafkaResponseEnvelope, NotUsed] = Flow[KafkaResponseEnvelope] // Response go straight to the Sink

    val bidiFlow: BidiFlow[StreamRequestWithSender, ProducerMessage.Message[String, String, KafkaRequestEnvelope], KafkaResponseEnvelope, KafkaResponseEnvelope, NotUsed] =
      BidiFlow.fromFlows(requestFlow, responseFlow)

    val requestKafkaSink: Sink[ProducerMessage.Message[String, String, KafkaRequestEnvelope], NotUsed] = Producer.flow[String, String, KafkaRequestEnvelope](producerSettings).map { result =>
      val requestEnvelope = result.message.passThrough
      if (requestEnvelope.replyTo.isEmpty) //FIXME: replace replyTo: String with Option[String] and update the server logic too
        coordinator ! SendMessageComplete(requestEnvelope.correlationId)
    }.to(Sink.ignore)

    val kafkaFlow: Flow[ProducerMessage.Message[String, String, KafkaRequestEnvelope], KafkaResponseEnvelope, NotUsed] = Flow.fromSinkAndSource(
      sink = requestKafkaSink,
      source = ReactiveKafkaStreamSource.atMostOnce(responseTopic, consumerConfig, parallelism))

    val actorSink: Sink[KafkaResponseEnvelope, Future[Done]] =
      Sink.foreach(respEnv => coordinator ! respEnv)

    //    val streamSink: Sink[KafkaResponseEnvelope, NotUsed] =
    //      MergeHub.source[KafkaResponseEnvelope](perProducerBufferSize = 16).to(actorSink).run() // TODO: Tune perProducerBufferSize appropriately

    val stream: RunnableGraph[ActorRef] = Source.actorPublisher(StreamPublisherActor.props).via(bidiFlow.join(kafkaFlow)).to(actorSink)

    stream.run()
  }

}

