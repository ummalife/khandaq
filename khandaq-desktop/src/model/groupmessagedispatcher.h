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

#pragma once

#include "src/core/icoregroupmessagesender.h"
#include "src/core/icoreidhandler.h"
#include "src/model/group.h"
#include "src/model/imessagedispatcher.h"
#include "src/model/message.h"

#include <QElapsedTimer>
#include <QObject>
#include <QString>
#include <QTimer>

#include <cstdint>
#include <deque>

class IGroupSettings;

/**
 * Sends the texts of a group. One the group does not take now (Air::groupTakesText) is shown and
 * waits on this device, and the ones after it wait behind it: they go in order once the group
 * takes them, a while after the peer list changes (joinGraceMs) or with the next text sent. A
 * member who joins in that while has it in full too (joinGraceLimit). The texts written in the
 * grace of the first member who comes into a group we were alone in wait for it too. One that
 * cannot go as it is fails and lets the others go. A message that waits is pending in the
 * history, whose next start sends it again (ChatHistory::dispatchUnsentMessages).
 */
class GroupMessageDispatcher : public IMessageDispatcher
{
    Q_OBJECT
public:
    /**
     * How long the texts that wait give a member who just joined: toxcore drops a text from a
     * peer it has no peer info for, and ours reaches them a moment after they are confirmed here.
     */
    static constexpr int joinGraceMs = 2000;
    /**
     * The members who join in a grace put it off again, by a grace each, until it lasted this many
     * graces in all: a stream of joins does not hold the texts that wait back for good.
     */
    static constexpr int joinGraceLimit = 5;

    GroupMessageDispatcher(Group& g_, MessageProcessor processor, ICoreIdHandler& idHandler,
                           ICoreGroupMessageSender& messageSender,
                           const IGroupSettings& groupSettings, int graceMs = joinGraceMs);

    std::pair<DispatchedMessageId, DispatchedMessageId> sendMessage(bool isAction,
                                                                    QString const& content) override;

    std::pair<DispatchedMessageId, DispatchedMessageId> sendExtendedMessage(const QString& content,
                            ExtensionSet extensions) override;
    void onMessageReceived(ToxPk const& sender, bool isAction, QString const& content,
                           uint32_t wireId = 0);
    /**
     * KHANDAQ (0.2.63): a text a member relayed from the history of the group
     * (Core::groupSyncedMessageReceived): as a received one, but with the time it was written, and
     * told as messageSynced. Its author is told it arrived (a delivery receipt), so that their
     * phone stops relaying it here.
     */
    void onSyncedMessageReceived(const ToxPk& author, const QString& content, uint32_t wireId,
                                 const QDateTime& sentAt);
    /**
     * The group leaves this window (Widget::discardGroup): the texts that wait go no more, and a
     * grace that runs sends nothing. broken: they fail as well, for a group left or deleted whose
     * history stays, so that no later start or join of the group sends them. Otherwise they stay
     * pending in the history, which the next start sends, or go with that history.
     */
    void dropWaiting(bool broken);

private:
    struct WaitingText
    {
        DispatchedMessageId id;
        Message message;
    };

    /** Hands the text to toxcore; one that went gets the id toxcore gave it. */
    ICoreGroupMessageSender::SendResult send(Message& message);
    /** Sends the texts that wait, in order, until the group takes no more. */
    void sendWaiting();
    /** Marks a message whose mention block names the key the group knows us by as a mention. */
    void markMentionOfUs(Message& message) const;

    Group& group;
    MessageProcessor processor;
    ICoreIdHandler& idHandler;
    ICoreGroupMessageSender& messageSender;
    const IGroupSettings& groupSettings;
    DispatchedMessageId nextMessageId{0};
    std::deque<WaitingText> waiting;
    QTimer graceTimer;
    /** When the grace that runs began, before the members who joined in it put it off. */
    QElapsedTimer graceBegan;
    /** Whether the peer list last had someone else in it: the first one to come has the grace. */
    bool someoneElse = false;
};
