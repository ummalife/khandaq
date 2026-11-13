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
#include "imessagedispatcher.h"
#include "src/model/air/airmessagemarks.h"

#include <QList>
#include <QObject>

#include <set>

struct SessionChatLogMetadata;
class FriendList;
class GroupList;

class SessionChatLog : public IChatLog
{
    Q_OBJECT
public:
    SessionChatLog(const ICoreIdHandler& coreIdHandler_, FriendList& friendList,
        GroupList& groupList);
    SessionChatLog(ChatLogIdx initialIdx, const ICoreIdHandler& coreIdHandler_,
        FriendList& friendList, GroupList& groupList);

    ~SessionChatLog();
    const ChatLogItem& at(ChatLogIdx idx) const override;
    SearchResult searchForward(SearchPos startPos, const QString& phrase,
                               const ParameterSearch& parameter) const override;
    SearchResult searchBackward(SearchPos startPos, const QString& phrase,
                                const ParameterSearch& parameter) const override;
    ChatLogIdx getFirstIdx() const override;
    ChatLogIdx getNextIdx() const override;
    std::vector<DateChatLogIdxPair> getDateIdxs(const QDate& startDate, size_t maxDates) const override;
    void addSystemMessage(const SystemMessage& message) override;
    bool isShown(ChatLogIdx idx) const override;
    bool isCancelled(ChatLogIdx idx) const override;

    void hideItem(ChatLogIdx idx);
    /** Leaves out an item as it is loaded, one the history keeps erased; nothing is signalled. */
    void markHidden(ChatLogIdx idx);

    /** What an edit, a deletion or a reaction did to the log (stage 3, item 10). */
    struct EffectResult
    {
        enum class Change
        {
            None,
            Edited,
            Deleted,
            Reacted,
        };
        Change change = Change::None;
        ChatLogIdx idx{0};
        /** Who wrote the item, and when. */
        ToxPk author;
        QDateTime timestamp;
        /** A text as it was before an edit or a deletion. */
        Message before{};
        /** A file as it was when it was deleted or got a reaction. */
        ToxFile file;
        /** The marks of the item after the change. */
        Air::EntryMarks marks;
    };

    /**
     * Applies an edit, a deletion or a reaction of sender to the item it points at, by the rules
     * Android keeps: only the author edits or deletes, and only what they wrote here; anyone in the
     * conversation reacts, once per person, to anything; an older edit than the one shown, or one
     * of ours coming back, changes nothing. Of two items that answer, the newer takes it.
     * @param selfKey Our key as the others here know it: the profile's key between two people, our
     * key in the group in a group, which a reaction on one of our messages names.
     */
    EffectResult applyEffect(const ToxPk& sender, const Air::MessageEffect& effect,
                             const ToxPk& selfKey);

    void insertCompleteMessageAtIdx(ChatLogIdx idx, const ToxPk& sender, QString senderName,
                                    const ChatLogMessage& message);
    void insertIncompleteMessageAtIdx(ChatLogIdx idx, const ToxPk& sender, QString senderName,
                                      const ChatLogMessage& message, DispatchedMessageId dispatchId);
    void insertBrokenMessageAtIdx(ChatLogIdx idx, const ToxPk& sender, QString senderName,
                                  const ChatLogMessage& message);
    void insertFileAtIdx(ChatLogIdx idx, const ToxPk& sender, QString senderName, const ChatLogFile& file);
    void insertSystemMessageAtIdx(ChatLogIdx idx, SystemMessage message);

public slots:
    void onMessageReceived(const ToxPk& sender, const Message& message);
    void onMessageSent(DispatchedMessageId id, const Message& message);
    void onMessageComplete(DispatchedMessageId id);
    void onMessageBroken(DispatchedMessageId id, BrokenMessageReason reason);

    void onFileUpdated(const ToxPk& sender, const ToxFile& file);
    void onFileTransferRemotePausedUnpaused(const ToxPk& sender, const ToxFile& file, bool paused);
    void onFileTransferBrokenUnbroken(const ToxPk& sender, const ToxFile& file, bool broken);


private:
    QString resolveSenderNameFromSender(const ToxPk &sender);


private:
    const ICoreIdHandler& coreIdHandler;

    ChatLogIdx nextIdx = ChatLogIdx(0);

    std::map<ChatLogIdx, ChatLogItem> items;
    /** Items taken out by hideItem: gone, like a cancelled message. */
    std::set<ChatLogIdx> hiddenItems;

    struct CurrentFileTransfer
    {
        ChatLogIdx idx;
        ToxFile file;
    };

    /**
     * Short list of active file transfers in given log. This is to make it
     * so we don't have to search through all files that have ever been transferred
     * in order to find our existing transfers
     */
    std::vector<CurrentFileTransfer> currentFileTransfers;

    /**
     * Maps DispatchedMessageIds back to ChatLogIdxs. Messages are removed when the message
     * is marked as completed
     */
    QMap<DispatchedMessageId, ChatLogIdx> outgoingMessages;
    FriendList& friendList;
    GroupList& groupList;
};
