/*
    Copyright © 2019 by The qTox Project Contributors

    This file is part of qTox, a Qt-based graphical interface for Tox.

    qTox is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    qTox is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with qTox.  If not, see <http://www.gnu.org/licenses/>.
*/

#include "groupmessagedispatcher.h"
#include "src/model/air/airmessagemarks.h"
#include "src/persistence/igroupsettings.h"

#include <QtCore>

constexpr int GroupMessageDispatcher::joinGraceMs;

GroupMessageDispatcher::GroupMessageDispatcher(Group& g_, MessageProcessor processor_,
                                               ICoreIdHandler& idHandler_,
                                               ICoreGroupMessageSender& messageSender_,
                                               const IGroupSettings& groupSettings_, int graceMs)
    : group(g_)
    , processor(processor_)
    , idHandler(idHandler_)
    , messageSender(messageSender_)
    , groupSettings(groupSettings_)
{
    processor.enableMentions();
    graceTimer.setSingleShot(true);
    graceTimer.setInterval(graceMs);
    connect(&graceTimer, &QTimer::timeout, this, &GroupMessageDispatcher::sendWaiting);
    // A member who joins, the group that connects: what waits may go now. The texts give the
    // member the grace first; members who join meanwhile do not put them off again.
    connect(&group, &Group::peerListRefreshed, this, [this] {
        if (!waiting.empty() && !graceTimer.isActive()) {
            graceTimer.start();
        }
    });
}

std::pair<DispatchedMessageId, DispatchedMessageId>
GroupMessageDispatcher::sendMessage(bool isAction, QString const& content)
{
    const auto firstMessageId = nextMessageId;
    auto lastMessageId = firstMessageId;

    // Nothing tells the texts that wait when toxcore takes them again but a change of the peer
    // list, so a new text tries them first, unless a member who just joined has the grace.
    if (!waiting.empty() && !graceTimer.isActive()) {
        sendWaiting();
    }

    using SendResult = ICoreGroupMessageSender::SendResult;
    for (auto message : processor.processOutgoingMessage(isAction, content, ExtensionSet())) {
        auto messageId = nextMessageId++;
        lastMessageId = messageId;
        // Behind a text that waits, the next one waits too: they go in the order written, as
        // the parts of a long text do.
        const SendResult result = waiting.empty() ? send(message) : SendResult::NotReady;

        // A group has no receipts: a text is complete once toxcore took it.
        //
        // NOTE: We could in theory keep track of our sent message and wait for
        // toxcore to send it back to us to indicate a completed message, but
        // this isn't necessarily the design of toxcore and associating the
        // received message back would be difficult.
        emit messageSent(messageId, message);
        switch (result) {
        case SendResult::Sent:
            emit messageComplete(messageId);
            break;
        case SendResult::NotReady:
            waiting.push_back({messageId, message});
            break;
        case SendResult::Failed:
            emit messageBroken(messageId, BrokenMessageReason::unknown);
            break;
        }
    }

    return std::make_pair(firstMessageId, lastMessageId);
}

ICoreGroupMessageSender::SendResult GroupMessageDispatcher::send(Message& message)
{
    const int groupId = static_cast<int>(group.getId());
    uint32_t wireId = 0;
    const ICoreGroupMessageSender::SendResult result =
        message.isAction ? messageSender.sendGroupAction(groupId, message.content, wireId)
                         : messageSender.sendGroupMessage(groupId, message.content, wireId);
    // What the members' reactions to it will point at.
    if (result == ICoreGroupMessageSender::SendResult::Sent && wireId != 0) {
        message.wireId = Air::groupWireId(wireId);
    }
    return result;
}

void GroupMessageDispatcher::sendWaiting()
{
    using SendResult = ICoreGroupMessageSender::SendResult;
    while (!waiting.empty()) {
        WaitingText text = waiting.front();
        const SendResult result = send(text.message);
        if (result == SendResult::NotReady) {
            return;
        }
        // Out of the queue before anyone hears of it.
        waiting.pop_front();
        if (result == SendResult::Failed) {
            // It will not go as it is; the ones behind it do not wait for it.
            emit messageBroken(text.id, BrokenMessageReason::unknown);
            continue;
        }
        if (!text.message.wireId.isEmpty()) {
            emit messageWireIdAssigned(text.id, text.message.wireId);
        }
        emit messageComplete(text.id);
    }
}

void GroupMessageDispatcher::dropWaiting(bool broken)
{
    graceTimer.stop();
    // Out of the queue before anyone hears of it.
    std::deque<WaitingText> dropped;
    dropped.swap(waiting);
    if (!broken) {
        return;
    }
    for (const WaitingText& text : dropped) {
        emit messageBroken(text.id, BrokenMessageReason::unknown);
    }
}

std::pair<DispatchedMessageId, DispatchedMessageId>
GroupMessageDispatcher::sendExtendedMessage(const QString& content, ExtensionSet extensions)
{
    std::ignore = extensions;
    // Stub this api to immediately fail
    auto messageId = nextMessageId++;
    auto messages = processor.processOutgoingMessage(false, content, ExtensionSet());
    emit messageSent(messageId, messages[0]);
    emit messageBroken(messageId, BrokenMessageReason::unsupportedExtensions);
    return {messageId, messageId};
}

/**
 * @brief Processes and dispatches received message from toxcore
 * @param[in] sender
 * @param[in] isAction True if is action
 * @param[in] content Message content
 * @param[in] wireId The id toxcore gave the message, which the edits, deletions and reactions of
 * its author and of the others point at; 0 for none
 */
void GroupMessageDispatcher::onMessageReceived(const ToxPk& sender, bool isAction,
                                               QString const& content, uint32_t wireId)
{
    bool isSelf = sender == idHandler.getSelfPublicKey();

    if (isSelf) {
        return;
    }

    if (groupSettings.getBlackList().contains(sender.toString())) {
        qDebug() << "onGroupMessageReceived: Filtered:" << sender.toString();
        return;
    }

    Message message = processor.processIncomingCoreMessage(isAction, content);
    if (wireId != 0) {
        message.wireId = Air::groupWireId(wireId);
        // Only a message that is shown here is confirmed: none from ourselves or a blocked peer.
        messageSender.sendGroupDeliveryReceipt(static_cast<int>(group.getId()), sender, wireId);
    }
    emit messageReceived(sender, message);
}
