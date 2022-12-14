package ai.ipavlov.communication.rest

import java.time.{Clock, Instant}

import ai.ipavlov.communication.Endpoint
import ai.ipavlov.communication.user.Bot
import ai.ipavlov.dialog.Dialog
import ai.ipavlov.dialog.DialogFather.UserAvailable
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.typesafe.config.Config
import info.mukel.telegrambot4s.models.{Chat, ChatType, Message, Update}
import spray.json._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration.{Deadline, _}
import scala.util.control.NonFatal
import scala.util.{Failure, Random, Success, Try}

/**
  * @author vadim
  * @since 10.07.17
  */
class BotEndpoint(daddy: ActorRef, clock: Clock) extends Actor with ActorLogging with ai.ipavlov.Implicits {
  import BotEndpoint._

  private val rnd: Random = Random

  private val delay_mean_k: Double = Try(context.system.settings.config.getDouble("talk.bot.delay.mean_k")).getOrElse(0.5)
  private val delay_variance: Double = Try(context.system.settings.config.getDouble("talk.bot.delay.variance")).getOrElse(5)

  import context.dispatcher

  private val delayOn: mutable.Set[String] = mutable.Set.empty[String]

  private val botsQueues: mutable.Map[String, mutable.Queue[Update]] = mutable.Map.empty[String, mutable.Queue[Update]]

  private def loadBots(): Unit = {
    val newBots = context.system.settings.config.getConfigList("bot.registered").asScala.toSet.map { botCfg: Config =>
      Try {
        (botCfg.getString("token"), botCfg.getInt("max_connections"), Try(botCfg.getBoolean("delayOn")).getOrElse(false))
      }.recoverWith {
        case NonFatal(e) =>
          log.warning("bad settings for bot {}, not used! error: {}", botCfg, e)
          Failure(e)
      }
    }.collect {
      case Success(cfg) => cfg
    }

    val newBotsIds = newBots.map(_._1)
    val botsToRemove = botsQueues.keySet.diff(newBotsIds)
    val botsToAdd = newBotsIds.diff(botsQueues.keySet)

    botsToRemove.foreach { id =>
      botsQueues.remove(id)
    }

    newBots.filter(id => botsToAdd.contains(id._1)).map { case (token, maxConn, isDelaed) =>
      log.info("bot {} registred", token)
      daddy ! UserAvailable(Bot(token), maxConn)
      if (isDelaed) delayOn.add(token)
      botsQueues += token -> mutable.Queue.empty[Update]
    }
  }

  private val activeChats: mutable.Map[(Bot, Long), ActorRef] = mutable.Map.empty[(Bot, Long), ActorRef]

  private val waitedMessages = mutable.Set.empty[(ActorRef, Dialog.PushMessageToTalk, Deadline)]

  context.system.scheduler.schedule(1.second, 1.second) { self ! SendMessages }

  self ! Endpoint.Configure

  override def receive: Receive = {
    case GetMessages(token) =>
      sender ! botsQueues.get(token).fold[Any] {
        log.warning("bot {} not registered", token)
        akka.actor.Status.Failure(new IllegalArgumentException("bot not registered"))
      } { mq =>
        val res = mq.toList
        mq.clear()
        res
      }

    case SendMessage(token, chat, m: TalkEvaluationMessage) =>
      activeChats.get(Bot(token) -> chat).foreach(_ ! Dialog.EndDialog)
      sender ! Message(rnd.nextInt(), None, Instant.now(clock).getNano, Chat(chat, ChatType.Private), text = Some(m.toJson(talkEvaluationFormat).toString))

    case SendMessage(token, chat, m: BotMessage) =>
      activeChats.get(Bot(token) -> chat).foreach { to =>
        if (delayOn.contains(token))
          waitedMessages.add((to, Dialog.PushMessageToTalk(Bot(token), m.text), messageDeadline(m.text)))
        else
          to ! Dialog.PushMessageToTalk(Bot(token), m.text)
      }
      sender ! Message(rnd.nextInt(), None, Instant.now(clock).getNano, Chat(chat, ChatType.Private), text = Some(m.toJson(botMessageFormat).toString))

    case SendMessages =>
      waitedMessages.retain {
        case (to, message, timeout) if timeout.isOverdue() =>
          to ! message
          false
        case _ => true
      }

    case Endpoint.ChatMessageToUser(Bot(token), text, dialogId, _) =>
      botsQueues.get(token).fold[Any] {
        log.warning("bot {} not registered", token)
        akka.actor.Status.Failure(new IllegalArgumentException("bot not registered"))
      }(_ += Update(0, Some(Message(0, None, Instant.now(clock).getNano, Chat(dialogId, ChatType.Private), text = Some(text)))) )

    case Endpoint.ActivateTalkForUser(user: Bot, talk) => activeChats.put(user -> talk.chatId, talk)

    case Endpoint.FinishTalkForUser(user: Bot, talk) => activeChats.remove(user -> talk.chatId)

    case Endpoint.Configure => loadBots()
  }

  private def messageDeadline(m: String): Deadline = {
    val typeTime = Math.abs(util.Random.nextGaussian() * delay_variance) + m.length * delay_mean_k
    val typeTimeTrunc = if (typeTime > 40) 40 else if (typeTime < 5) 5 else typeTime
    log.debug("slowdown message delivery from bot on {} seconds", typeTimeTrunc)
    Deadline.now + typeTimeTrunc.seconds
  }
}

object BotEndpoint extends SprayJsonSupport with DefaultJsonProtocol  {
  def props(talkConstructor: ActorRef, clock: Clock = Clock.systemDefaultZone()): Props = Props(new BotEndpoint(talkConstructor, clock))

  sealed trait BotMessage {
    val text: String
  }
  case class TextMessage(text: String) extends BotMessage
  case class TextWithEvaluationMessage(text: String, evaluation: Int) extends BotMessage
  case class SummaryEvaluation(quality: Int, breadth: Int, engagement: Int)
  case class TalkEvaluationMessage(evaluation: SummaryEvaluation) extends BotMessage {
    val text = ""
  }

  case class SendMessage(token: String, chat_id: Long, text: BotMessage)
  case class GetMessages(token: String)

  implicit val normalMessageFormat: JsonFormat[TextWithEvaluationMessage] = jsonFormat2(TextWithEvaluationMessage)
  implicit val summaryEvaluationFormat: JsonFormat[SummaryEvaluation] = jsonFormat3(SummaryEvaluation)
  implicit val talkEvaluationFormat: JsonFormat[TalkEvaluationMessage] = jsonFormat(TalkEvaluationMessage.apply _, "evaluation")
  implicit val firstMessageFormat: JsonFormat[TextMessage] = jsonFormat1(TextMessage)
  implicit val botMessageFormat = new JsonFormat[BotMessage] {
    override def write(obj: BotMessage): JsValue = obj match {
      case TextMessage(text: String) => JsObject("text" -> text.toJson)
      case TextWithEvaluationMessage(text: String, evaluation: Int) => JsObject("text" -> text.toJson, "evaluation" -> evaluation.toJson)
      case TalkEvaluationMessage(SummaryEvaluation(quality, breadth, engagment)) => JsObject("quality" -> quality.toJson, "breadth" -> breadth.toJson, "engagement" -> engagment.toJson)
    }

    override def read(json: JsValue): BotMessage = json.asJsObject.getFields("text") match {
      case Seq(JsString("/end")) => json.convertTo[TalkEvaluationMessage]
      case _ if json.asJsObject.fields.contains("evaluation") => json.convertTo[TextWithEvaluationMessage]
      case _ if !json.asJsObject.fields.contains("evaluation") => json.convertTo[TextMessage]
      case _ => serializationError(s"Invalid json format: $json")
    }
  }

  implicit val sendMessageFormat: JsonFormat[SendMessage] = jsonFormat3(SendMessage)

  private case object SendMessages
}
