package org.pavlovai.dialog

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.util.Timeout
import org.pavlovai.communication._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{Random, Try}

/**
  * @author vadim
  * @since 06.07.17
  */
class DialogFather extends Actor with ActorLogging with akka.pattern.AskSupport {
  import DialogFather._
  private implicit val ec = context.dispatcher
  private implicit val timeout: Timeout = 5.seconds
  private val rnd = Random
  private val cooldownPeriod = Try(Duration.fromNanos(context.system.settings.config.getDuration("bot.talk_period_min").toNanos)).getOrElse(1.minutes)

  private val gate = context.actorOf(Endpoint.props(self), name = "communication-gate")

  context.system.scheduler.schedule(1.second, 1.second) {
    self ! AssembleDialogs
    self ! CleanCooldownList
  }

  private val availableUsers: mutable.Set[User] = mutable.Set.empty[User]
  private val busyHumans: mutable.Set[HumanChat] = mutable.Set.empty[HumanChat]
  private val cooldownBots: mutable.Map[Bot, Deadline] = mutable.Map.empty[Bot, Deadline]
  private val usersChatsInTalks = mutable.Map[ActorRef, List[User]]()

  override def receive: Receive = {
    case AssembleDialogs => assembleDialogs()

    case Terminated(t) =>
      usersChatsInTalks.get(t).foreach(_.foreach { u =>
        gate ! Endpoint.RemoveTargetTalkForUserWithChat(u)
        u match {
          case u: HumanChat => busyHumans -= u
          case _ =>
        }
      })
    case CleanCooldownList => cooldownBots.retain { case (_, deadline) => deadline.hasTimeLeft() }

    case UserAvailable(user: User) => availableUsers += user
    case UserUnavailable(user: User) => availableUsers -= user

  }

  private def addToBlockLists(a: User) {
    a match {
      case u: HumanChat => busyHumans += u
      case u: Bot => cooldownBots += u -> cooldownPeriod.fromNow
      case _ =>
    }
  }

  protected def assembleDialogs(): Unit = {
    val users = availableUsers.diff(busyHumans.toSet ++ cooldownBots.keySet).toList
    rnd.shuffle(users).zip(users.reverse).take(users.size / 2).foreach { case (a, b) =>
      ContextQuestions.selectRandom.foreach { txt =>
        val t = context.actorOf(Dialog.props(a, b, txt, gate))
        gate ! Endpoint.AddTargetTalkForUserWithChat(a, t)
        gate ! Endpoint.AddTargetTalkForUserWithChat(b, t)
        usersChatsInTalks += t -> List(a, b)
        context.watch(t)
        addToBlockLists(a)
        addToBlockLists(b)
      }
    }
  }
}

object DialogFather {
  def props = Props(new DialogFather)

  private case object AssembleDialogs
  private case object CleanCooldownList

  case class UserAvailable(user: User)
  case class UserUnavailable(user: User)
}