package gr.ml.analytics.online

import akka.actor.Actor
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.Await
import scala.concurrent.duration._

class OnlineLearningActor(val itemItemRecommender: ItemItemRecommender) extends Actor with LazyLogging {

  override def receive: Receive = {
    case interaction: Interaction =>

//      Thread.sleep(1000)
      Await.ready(itemItemRecommender.learn(interaction), 10.seconds)

      logger.info(s"Received interaction $interaction")
    case _ =>
      throw new RuntimeException("Unknown message")
  }
}
