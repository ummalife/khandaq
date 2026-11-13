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

#include "ichatlog.h"
#include "sessionchatlog.h"
#include "src/model/air/airmessagemarks.h"
#include "src/model/brokenmessagereason.h"
#include "src/persistence/history.h"

#include <QSet>

class Settings;
class FriendList;
class GroupList;

class ChatHistory : public IChatLog
{
    Q_OBJECT
public:
    /** One of our messages that has not reached the person yet. */
    struct QueuedMessage
    {
        qint64 outboxId = -1;
        /** The text, without the action prefix. */
        QString text;
        bool isAction = false;
        QDateTime timestamp;
        /** The delivery gave up on it: it waits for "Retry" instead of the person. */
        bool failed = false;
    };

    /**
     * @param entryStore_ Keeps the edits and reactions shown on entries and the ids a group gave
     * its messages between launches (stage 3, item 10); without it they last until the app quits.
     */
    ChatHistory(Chat& chat_, History* history_, const ICoreIdHandler& coreIdHandler_,
                const Settings& settings_, IMessageDispatcher& messageDispatcher,
                FriendList& friendList, GroupList& groupList,
                Air::EntryMarksStore* entryStore_ = nullptr);
    const ChatLogItem& at(ChatLogIdx idx) const override;
    SearchResult searchForward(SearchPos startIdx, const QString& phrase,
                               const ParameterSearch& parameter) const override;
    SearchResult searchBackward(SearchPos startIdx, const QString& phrase,
                                const ParameterSearch& parameter) const override;
    ChatLogIdx getFirstIdx() const override;
    ChatLogIdx getNextIdx() const override;
    std::vector<DateChatLogIdxPair> getDateIdxs(const QDate& startDate, size_t maxDates) const override;
    void addSystemMessage(const SystemMessage& message) override;
    bool isShown(ChatLogIdx idx) const override;
    bool isCancelled(ChatLogIdx idx) const override;

    /** The messages to one person that have not reached them, oldest first; none for a group. */
    QVector<QueuedMessage> queuedMessages() const;
    /** Takes a waiting or failed message back: it is not sent again and leaves the conversation. */
    void cancelQueuedMessage(qint64 outboxId);

    /**
     * Applies an edit, a deletion or a reaction of sender (SessionChatLog::applyEffect) and keeps
     * it: the new words or the erased message in the history, the marks in the entry store.
     */
    SessionChatLog::EffectResult applyEffect(const ToxPk& sender, const Air::MessageEffect& effect,
                                             const ToxPk& selfKey);

signals:
    /** A message joined the queue, left it, or failed. */
    void queueChanged();

public slots:
    void onFileUpdated(const ToxPk& sender, const ToxFile& file);
    void onFileTransferRemotePausedUnpaused(const ToxPk& sender, const ToxFile& file, bool paused);
    void onFileTransferBrokenUnbroken(const ToxPk& sender, const ToxFile& file, bool broken);


private slots:
    void onMessageReceived(const ToxPk& sender, const Message& message);
    void onMessageSent(DispatchedMessageId id, const Message& message);
    void onMessageComplete(DispatchedMessageId id);
    void onMessageBroken(DispatchedMessageId id, BrokenMessageReason reason);

private:
    void ensureIdxInSessionChatLog(ChatLogIdx idx) const;
    void loadHistoryIntoSessionChatLog(ChatLogIdx start) const;
    void dispatchUnsentMessages(IMessageDispatcher& messageDispatcher);
    void handleDispatchedMessage(DispatchedMessageId dispatchId, RowId historyId);
    void completeMessage(DispatchedMessageId id);
    void breakMessage(DispatchedMessageId id, BrokenMessageReason reason);
    bool canUseHistory() const;
    ChatLogIdx getInitialChatLogIdx() const;
    /** The key of this conversation, under which its entries keep their marks. */
    QString chatKey() const;
    /** Keeps the id a group gave a message of ours or of a member, when the history keeps it. */
    void keepWireId(const ToxPk& author, const Message& message);

    struct QueueEntry
    {
        QueuedMessage message;
        /** False for a failed message: the dispatcher no longer holds it. */
        bool dispatched = false;
        DispatchedMessageId dispatchId{0};
        /** The same message in the history, or -1. */
        RowId historyId{-1};
        /** Whether the message has an index in this log yet, and which. */
        bool hasIdx = false;
        size_t idx = 0;
    };

    void dispatchQueue(IMessageDispatcher& messageDispatcher);
    void queueSentMessage(DispatchedMessageId id, const Message& message);
    int queueIndexOf(DispatchedMessageId id) const;
    void noteLoadedQueueRow(RowId historyId, ChatLogIdx idx) const;

    Chat& chat;
    History* history;
    const Settings& settings;
    const ICoreIdHandler& coreIdHandler;
    mutable SessionChatLog sessionChatLog;

    // If a message completes before it's inserted into history it will end up
    // in this set
    QSet<DispatchedMessageId> completedMessages;
    // If a message breaks before it's inserted into history it will end up
    // in this set
    QMap<DispatchedMessageId, BrokenMessageReason> brokenMessages;

    // If a message is inserted into history before it gets a completion
    // callback it will end up in this map
    QMap<DispatchedMessageId, RowId> dispatchedMessageRowIdMap;

    IMessageDispatcher& dispatcher;
    // The outgoing queue of a friend chat; null for a group, or without a database. A chat with a
    // queue keeps its history rows in step through it, instead of the maps above.
    Outbox* outbox = nullptr;
    // Indexes of queued messages are only known once the history row is loaded, hence mutable.
    mutable QVector<QueueEntry> queue;
    // Queued messages whose history rows are not loaded yet, by row: the dispatch id to show them with
    QMap<RowId, DispatchedMessageId> queuedHistoryRows;
    QSet<int64_t> cancelledHistoryRows;
    mutable QSet<size_t> cancelledIdx;
    Air::EntryMarksStore* entryStore = nullptr;
};
