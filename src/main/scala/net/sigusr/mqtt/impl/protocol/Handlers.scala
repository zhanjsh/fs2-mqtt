/*
 * Copyright 2014 Frédéric Cabestre
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.sigusr.mqtt.impl.protocol

import net.sigusr.mqtt.api._
import net.sigusr.mqtt.impl.frames._
import net.sigusr.mqtt.impl.protocol.Registers.RegistersState
import scodec.bits.ByteVector

import scalaz.State._

trait Handlers {

  private val zeroId = MessageId(0)

  private[protocol] def handleApiMessages(apiCommand: APICommand): Action = apiCommand match {
    case Connect(clientId, keepAlive, cleanSession, will, user, password) ⇒
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val retain = will.fold(false)(_.retain)
      val qos = will.fold(AtMostOnce.enum)(_.qos.enum)
      val topic = will.map(_.topic)
      val message = will.map(_.message)
      val variableHeader = ConnectVariableHeader(user.isDefined, password.isDefined, willRetain = retain, qos, willFlag = will.isDefined, cleanSession, keepAlive)
      Sequence(
        Seq(SetKeepAlive(keepAlive.toLong * 1000),
          SendToNetwork(ConnectFrame(header, variableHeader, clientId, topic, message, user, password))))
    case Disconnect ⇒
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      SendToNetwork(DisconnectFrame(header))
    case Publish(topic, payload, qos, messageId, retain) if qos == AtMostOnce ⇒
      val header = Header(dup = false, qos.enum, retain = retain)
      SendToNetwork(PublishFrame(header, topic, messageId.getOrElse(zeroId).identifier, ByteVector(payload)))
    case Publish(topic, payload, qos, Some(messageId), retain) ⇒
      val header = Header(dup = false, qos.enum, retain = retain)
      // TODO handle storage and timeouts
      SendToNetwork(PublishFrame(header, topic, messageId.identifier, ByteVector(payload)))
    case Subscribe(topics, messageId) ⇒
      val header = Header(dup = false, AtLeastOnce.enum, retain = false)
      SendToNetwork(SubscribeFrame(header, messageId.identifier, topics.map((v: (String, QualityOfService)) ⇒ (v._1, v._2.enum))))
    case Status ⇒
      SendToClient(Connected)
  }

  private[protocol] def handleNetworkFrames(frame: Frame): RegistersState[Action] = gets { registers ⇒
    frame match {
      case ConnackFrame(header, 0) ⇒
        if (registers.keepAlive == 0) SendToClient(Connected)
        else Sequence(Seq(StartPingRespTimer(registers.keepAlive), SendToClient(Connected)))
      case ConnackFrame(header, returnCode) ⇒ SendToClient(ConnectionFailure(ConnectionFailureReason.fromEnum(returnCode)))
      case PingRespFrame(header) ⇒
        SetPendingPingResponse(isPending = false)
      case PublishFrame(header, topic, messageIdentifier, payload) ⇒
        val toClient = SendToClient(Message(topic, payload.toArray.to[Vector]))
        header.qos match {
          case AtMostOnce.enum ⇒
            toClient
          case AtLeastOnce.enum ⇒
            Sequence(Seq(
              toClient,
              SendToNetwork(PubackFrame(Header(), messageIdentifier))))
          case ExactlyOnce.enum ⇒
            Sequence(Seq(
              toClient,
              SendToNetwork(PubrecFrame(Header(), messageIdentifier))))
        }
      case PubackFrame(header, messageId) ⇒
        // TODO handle storage (and timeouts ?)
        SendToClient(Published(messageId))
      case PubrecFrame(header, messageIdentifier) ⇒
        // TODO handle storage (and timeouts ?)
        SendToNetwork(PubrelFrame(header.copy(qos = 1), messageIdentifier))
      case PubcompFrame(header, messageId) ⇒
        // TODO handle storage (and timeouts ?)
        SendToClient(Published(messageId))
      case SubackFrame(header, messageIdentifier, topicResults) ⇒
        SendToClient(Subscribed(topicResults.map(QualityOfService.fromEnum), messageIdentifier.identifier))
      case _ ⇒
        // TODO do more than ignoring unexpected frames!
        Sequence()
    }
  }

  private[protocol] def timerSignal(currentTime: Long): RegistersState[Action] = gets { registers ⇒
    if (registers.isPingResponsePending)
      ForciblyCloseTransport
    else {
      val timeout = registers.keepAlive - currentTime + registers.lastSentMessageTimestamp
      if (timeout < 1000)
        Sequence(Seq(
          SetPendingPingResponse(isPending = true),
          StartPingRespTimer(registers.keepAlive),
          SendToNetwork(PingReqFrame(Header(dup = false, AtMostOnce.enum, retain = false)))))
      else
        StartPingRespTimer(timeout)
    }
  }

  private[protocol] def connectionClosed(): Action = SendToClient(Disconnected)

  private[protocol] def transportNotReady(): Action = SendToClient(Disconnected)
}
