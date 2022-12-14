package ai.ipavlov.communication.fbmessager

import ai.ipavlov.communication.user.Client
import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest}
import akka.stream.ActorMaterializer

import scala.concurrent.Future
import scala.util.Failure

/**
  * @author vadim
  * @since 26.09.17
  */
class FBClient(pageAccessToken: String) extends Actor with ActorLogging {
  import context.dispatcher

  private implicit val system: ActorSystem = context.system
  private implicit val mat: ActorMaterializer = ActorMaterializer()

  override def receive: Receive = {
    case Client.ShowChatMessage(receiverId, messageId, text) =>
      sendMessage("`(peer msg):` " + text, receiverId, pageAccessToken, txt => FBMessage(
        text = None,
        metadata = None,
        attachment = Some(FBAttachment("template", FBButtonsPayload(txt, List(
          FBButton("postback", cutStr(text) + " \uD83D\uDC4D", "like " + messageId),
          FBButton("postback", cutStr(text) + " \uD83D\uDC4E", "dislike " + messageId)
        )))))
      )

    case Client.ShowSystemNotification(receiverId, text) =>
      sendMessage("`(system msg):` " + text, receiverId, pageAccessToken, txt => FBMessage(
        text = Some(txt),
        metadata = None
      )
      )
    case Client.ShowEvaluationMessage(receiverId, text) =>
      sendMessage("`(system msg):` " + text, receiverId, pageAccessToken, txt => FBMessage(
        text = Some(txt),
        metadata = None
      )
      )
    case Client.ShowLastNotificationInDialog(receiverId, text) =>
      sendMessage("`(system msg):` " + text, receiverId, pageAccessToken, txt => FBMessage(
        text = None,
        metadata = None,
        attachment = Some(FBAttachment("template", FBButtonsPayload(txt, List(
          FBButton("postback", "begin", "/begin"),
          FBButton("postback", "help", "/help")
        )))))
      )

    case Client.ShowHelpMessage(address) =>
      val text =
        """
          |1. To start a dialog type or choose a /begin command .
          |2. You will be connected to a peer or, if no peer is available at the moment, you???ll receive the message from @ConvaiBot `Please wait for you partner.`
          |3. Peer might be a bot or another human evaluator.
          |4. After you were connected with your peer you will receive a starting message - a passage or two from a Wikipedia article.
          |5. Your task is to discuss the content of a presented passage with the peer and score her/his replies.
          |6. Please score every utterance of your peer with a ???thumb UP??? button if you like it, and ???thumb DOWN??? button in the opposite case.
          |7. To finish the conversation type or choose a command /end.
          |8. When the conversation is finished, you will receive a request from @ConvaiBot to score the overall quality of the dialog along three dimensions:
          |    - quality - how much are you satisfied with the whole conversation?
          |    - breadth - in your opinion was a topic discussed thoroughly or just from one side?
          |    - engagement - was it interesting to participate in this conversation?
          |9. If your peer ends the dialog before you, you will also receive a scoring request from @ConvaiBot.
          |10. Your conversations with a peer will be recorded for further use. By starting a chat you give permission for your anonymised conversation data to be released publicly under [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
        """.stripMargin

      sendMessage("`(system msg):` " + text, address, pageAccessToken, txt => FBMessage(
        text = None,
        metadata = None,
        attachment = Some(FBAttachment("template", FBButtonsPayload(txt, List(
          FBButton("postback", "begin", "/begin"),
          FBButton("postback", "help", "/help")
        )))))
      )

  }

  private def sendMessage(text: String, receiverId: String, pageAccessToken: String, f: (String) => FBMessage): Unit = {

    def splitText(txt: String): Seq[(String, Boolean)] = {
      val folds = txt.split(" ").foldLeft(List("")) { case (acc, c) =>
        if (acc.head.length <= 600) (acc.head + " " + c) :: acc.tail
        else c :: acc
      }.reverse

      folds.dropRight(1).map { f =>
        (f, false)
      } :+ (folds.last, true)
    }

    def post(txt: String, isLast: Boolean): Future[Unit] = {
      val responseUri = "https://graph.facebook.com/v2.6/me/messages"

      import spray.json._

      val message = if (isLast)
        FBMessageEventOut(
          recipient = FBRecipient(receiverId.toString),
          message = f(txt)
        ).toJson
      else
        FBMessageEventOut(
          recipient = FBRecipient(receiverId.toString),
          message = FBMessage(
            text = Some(txt),
            metadata = None,
            attachment = None)
        ).toJson

      Http().singleRequest(HttpRequest(
        HttpMethods.POST,
        uri = s"$responseUri?access_token=$pageAccessToken",
        entity = HttpEntity(ContentTypes.`application/json`, message.toString))
      ).andThen {
        case Failure(err) => log.error("message did not send: {}", err)
      }.map(_ => ())
    }

    splitText(text).foldLeft(Future.successful(())) { case (ft, (mes, isLast)) => ft.flatMap( _ => post(mes, isLast) ) }
  }

  private def cutStr(str: String): String = if (str.length > 15) str.substring(0, 12) + "..." else str
}

object FBClient {
  def props(pageAccessToken: String) = Props(new FBClient(pageAccessToken))
}
