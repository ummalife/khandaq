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

#include <QHash>
#include <QPair>
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
        /** KHANDAQ (0.2.57): the msgV3 id it went with, which "Retry" sends it with again. */
        QByteArray msgV3Id;
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
    /** KHANDAQ (0.2.63): and keeps our reaction in the entry store, as the others' are kept. */
    bool toggleOwnReaction(ChatLogIdx idx, const QString& emoji, QString* ownAfter) override;

    /** The messages to one person that have not reached them, oldest first; none for a group. */
    QVector<QueuedMessage> queuedMessages() const;
    /** Takes a waiting or failed message back: it is not sent again and leaves the conversation. */
    void cancelQueuedMessage(qint64 outboxId);

    /**
     * Applies an edit, a deletion or a reaction of sender (SessionChatLog::applyEffect) and keeps
     * it: the new words or the erased message in the history, the marks in the entry store.
     *
     * KHANDAQ (beta feedback 2026-11-22): in a group a deletion is kept as well, whatever the
     * conversation holds of it (History::addDeletion), and one of a message the conversation has
     * not loaded erases it in the history: a text by the id the entry store keeps for it, among
     * the last texts of its author, a file by its id, if the author sent it.
     */
    SessionChatLog::EffectResult applyEffect(const ToxPk& sender, const Air::MessageEffect& effect,
                                             const ToxPk& selfKey);
    /**
     * KHANDAQ (beta feedback 2026-11-22): whether author deleted that text or file of this group
     * (History::addDeletion): a copy of it that comes again, live or relayed, is not shown. False
     * between two people and without the history.
     */
    bool isDeleted(const ToxPk& author, Air::MessageEffect::Anchor anchor,
                   const QByteArray& anchorId) const;

    /**
     * KHANDAQ (0.2.57): read receipts between two people. The time, by our clock, of our own
     * message that a read receipt names; -1 when this conversation has none.
     */
    qint64 ownItemTime(const Air::SeenAnchor& anchor) const;
    /**
     * The newest message of the person that a read receipt can name
     * (History::newestIncomingAnchor); empty when there is none.
     */
    Air::SeenAnchor newestIncomingAnchor() const;
    /** Whether the person's text with this msgV3 id is here already: it came again. */
    bool hasIncomingText(const ToxPk& sender, const QByteArray& msgV3Id) const;
    /**
     * KHANDAQ (26.11.2026): a private message with a member of the group, which the feed shows
     * among the others (DESIGN-group-contacts-private-mentions.md, part 2): ours to them, author
     * our own key, or theirs to us, author their key. Its mark (Air::PrivateEntry) is kept before
     * it shows, and it is kept delivered and never handed to the dispatcher: it never goes to the
     * group, no receipt goes for it, and this client serves no history of a group (Core).
     */
    void addPrivateEntry(const ToxPk& author, const QString& text, const QDateTime& time,
                         const Air::PrivateEntry& entry);
    /** KHANDAQ (26.11.2026): by the mark addPrivateEntry kept, for a text of a group. */
    Air::PrivateEntry privateEntry(ChatLogIdx idx) const override;
    /**
     * KHANDAQ (0.2.57): the newest entry loaded of the file with this Tox file id, in this
     * direction (SessionChatLog::findFileEntry): what an offer of it goes on with.
     */
    bool findFileEntry(const QByteArray& fileId, ToxFile::FileDirection direction,
                       ToxFile* found) const;
    /**
     * KHANDAQ (0.2.57): whether a file of a person that came after this one of theirs goes to its
     * path too (History::laterFileGoesTo): what is there may be that one.
     */
    bool laterFileGoesTo(const ToxFile& file) const;

signals:
    /** A message joined the queue, left it, or failed. */
    void queueChanged();
    /**
     * KHANDAQ (group notify parity, 2026-11-20): a text a member relayed from the history of the
     * group joined the conversation (onMessageSynced); one the history kept already does not. The
     * window counts it as unread as it counts a live one, with no sound and no notification
     * (Widget::createGroup).
     */
    void syncedMessageAdded(const ToxPk& sender, const Message& message);

public slots:
    void onFileUpdated(const ToxPk& sender, const ToxFile& file);
    void onFileTransferRemotePausedUnpaused(const ToxPk& sender, const ToxFile& file, bool paused);
    void onFileTransferBrokenUnbroken(const ToxPk& sender, const ToxFile& file, bool broken);


private slots:
    void onMessageReceived(const ToxPk& sender, const Message& message);
    /**
     * KHANDAQ (0.2.63): a text a member relayed from the history of the group
     * (IMessageDispatcher::messageSynced): kept under its own time and marked relayed, unless the
     * history has the author's words from that while already.
     */
    void onMessageSynced(const ToxPk& sender, const Message& message);
    void onMessageSent(DispatchedMessageId id, const Message& message);
    void onMessageComplete(DispatchedMessageId id);
    void onMessageBroken(DispatchedMessageId id, BrokenMessageReason reason);
    void onMessageWireIdAssigned(DispatchedMessageId id, const QByteArray& wireId);
    /**
     * KHANDAQ (0.2.63): the authors of entries kept without a name (Air::authorUnnamed) that the
     * group knows by now: their entries take the name, here and in the history.
     */
    void nameUnnamedAuthors();

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
    /**
     * KHANDAQ (beta feedback 2026-11-22): erases in the history the message of this group that a
     * deletion by author points at, which the conversation has not loaded (applyEffect).
     */
    void eraseUnloaded(const ToxPk& author, const Air::MessageEffect& effect);
    /** KHANDAQ (0.2.57): the msgV3 id the history keeps for a message: one between two people. */
    QByteArray msgV3IdOf(const Message& message) const;
    /** The time of our message a read receipt names, among those the log holds; -1 for none. */
    qint64 loggedItemTime(const ToxPk& selfPk, const Air::SeenAnchor& anchor) const;
    /**
     * KHANDAQ (0.2.63): the name of the author of a text relayed from the history of the group: as
     * the group knows them, else as the history last kept them; none when neither knows them.
     */
    QString relayedAuthorName(const ToxPk& author) const;
    /**
     * KHANDAQ (0.2.63): the name an entry loaded from the history is shown under: the one it was
     * kept with, or, kept without one, the author's name in the group now; an author the group
     * does not know yet is remembered for nameUnnamedAuthors.
     */
    QString loadedAuthorName(const ToxPk& author, const QString& keptName) const;

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
    /** A conversation between two people, not a group. */
    bool friendChat = false;
    /**
     * KHANDAQ (0.2.63): authors of entries here kept without a name, which the group may come to
     * know (nameUnnamedAuthors). Filled by the loading of the history too, hence mutable.
     */
    mutable QSet<ToxPk> unnamedAuthors;
    /**
     * KHANDAQ (0.2.57): files of the person accepted in this run, whose row says so already, with
     * the path and the stamp it has.
     */
    QHash<QByteArray, QPair<QString, ToxFile::Stamp>> underwayFiles;
};
