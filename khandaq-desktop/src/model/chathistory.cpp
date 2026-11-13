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

#include "chathistory.h"
#include "src/persistence/settings.h"
#include "src/core/chatid.h"
#include "src/model/friend.h"
#include "src/widget/form/chatform.h"

#include <algorithm>

namespace {
/**
 * @brief Determines if the given idx needs to be loaded from history
 * @param[in] idx index to check
 * @param[in] sessionChatLog SessionChatLog containing currently loaded items
 * @return True if load is needed
 */
bool needsLoadFromHistory(ChatLogIdx idx, const SessionChatLog& sessionChatLog)
{
    return idx < sessionChatLog.getFirstIdx();
}

/**
 * @brief Finds the first item in sessionChatLog that contains a message
 * @param[in] sessionChatLog
 * @return index of first message
 */
ChatLogIdx findFirstMessage(const SessionChatLog& sessionChatLog)
{
    auto it = sessionChatLog.getFirstIdx();
    while (it < sessionChatLog.getNextIdx()) {
        if (sessionChatLog.at(it).getContentType() == ChatLogItem::ContentType::message) {
            return it;
        }
        it++;
    }
    return ChatLogIdx(-1);
}

/**
 * @brief Handles presence of aciton prefix in content
 * @param[in/out] content
 * @return True if was an action
 */
bool handleActionPrefix(QString& content)
{
    // Unfortunately due to legacy reasons we have to continue
    // inserting and parsing for ACTION_PREFIX in our messages even
    // though we have the ability to something more intelligent now
    // that we aren't owned by chatform logic
    auto isAction = content.startsWith(ChatForm::ACTION_PREFIX, Qt::CaseInsensitive);
    if (isAction) {
        content.remove(0, ChatForm::ACTION_PREFIX.size());
    }

    return isAction;
}

/** A text as the history keeps it: an action with its prefix. */
QString storedText(const Message& message)
{
    return message.isAction ? ChatForm::ACTION_PREFIX + message.content : message.content;
}
} // namespace

ChatHistory::ChatHistory(Chat& chat_, History* history_, const ICoreIdHandler& coreIdHandler_,
                         const Settings& settings_, IMessageDispatcher& messageDispatcher,
                         FriendList& friendList, GroupList& groupList,
                         Air::EntryMarksStore* entryStore_)
    : chat(chat_)
    , history(history_)
    , settings(settings_)
    , coreIdHandler(coreIdHandler_)
    , sessionChatLog(getInitialChatLogIdx(), coreIdHandler_, friendList, groupList)
    , dispatcher(messageDispatcher)
    , outbox(qobject_cast<Friend*>(&chat_) && history_ ? history_->getOutbox() : nullptr)
    , entryStore(entryStore_)
{
    connect(&messageDispatcher, &IMessageDispatcher::messageComplete, this,
            &ChatHistory::onMessageComplete);
    connect(&messageDispatcher, &IMessageDispatcher::messageReceived, this,
            &ChatHistory::onMessageReceived);
    connect(&messageDispatcher, &IMessageDispatcher::messageBroken, this,
            &ChatHistory::onMessageBroken);

    // Defer messageSent callback until we finish firing off all our unsent messages.
    // If it was connected all our unsent messages would be re-added ot history again
    if (outbox) {
        dispatchQueue(messageDispatcher);
    } else if (canUseHistory()) {
        dispatchUnsentMessages(messageDispatcher);
    }

    // Now that we've fired off our unsent messages we can connect the message
    connect(&messageDispatcher, &IMessageDispatcher::messageSent, this, &ChatHistory::onMessageSent);

    // NOTE: this has to be done _after_ sending all sent messages since initial
    // state of the message has to be marked according to our dispatch state
    constexpr auto defaultNumMessagesToLoad = 100;
    auto firstChatLogIdx = sessionChatLog.getFirstIdx().get() < defaultNumMessagesToLoad
                               ? ChatLogIdx(0)
                               : sessionChatLog.getFirstIdx() - defaultNumMessagesToLoad;

    if (canUseHistory()) {
        loadHistoryIntoSessionChatLog(firstChatLogIdx);
    }

    // We don't manage any of the item updates ourselves, we just forward along
    // the underlying sessionChatLog's updates
    connect(&sessionChatLog, &IChatLog::itemUpdated, this, &IChatLog::itemUpdated);
}

const ChatLogItem& ChatHistory::at(ChatLogIdx idx) const
{
    if (canUseHistory()) {
        ensureIdxInSessionChatLog(idx);
    }

    return sessionChatLog.at(idx);
}

SearchResult ChatHistory::searchForward(SearchPos startIdx, const QString& phrase,
                                        const ParameterSearch& parameter) const
{
    if (startIdx.logIdx >= getNextIdx()) {
        SearchResult res;
        res.found = false;
        return res;
    }

    if (canUseHistory()) {
        ensureIdxInSessionChatLog(startIdx.logIdx);
    }

    return sessionChatLog.searchForward(startIdx, phrase, parameter);
}

SearchResult ChatHistory::searchBackward(SearchPos startIdx, const QString& phrase,
                                         const ParameterSearch& parameter) const
{
    auto res = sessionChatLog.searchBackward(startIdx, phrase, parameter);

    if (res.found || !canUseHistory()) {
        return res;
    }

    auto earliestMessage = findFirstMessage(sessionChatLog);

    auto earliestMessageDate =
        (earliestMessage == ChatLogIdx(-1))
            ? QDateTime::currentDateTime()
            : sessionChatLog.at(earliestMessage).getContentAsMessage().message.timestamp;

    // Roundabout way of getting the first idx but I don't want to have to
    // deal with re-implementing so we'll just piece what we want together...
    //
    // If the double disk access is real bad we can optimize this by adding
    // another function to history
    auto dateWherePhraseFound =
        history->getDateWhereFindPhrase(chat.getPersistentId(), earliestMessageDate, phrase,
                                        parameter);

    auto loadIdx = history->getNumMessagesForChatBeforeDate(chat.getPersistentId(), dateWherePhraseFound);
    loadHistoryIntoSessionChatLog(ChatLogIdx(loadIdx));

    // Reset search pos to the message we just loaded to avoid a double search
    startIdx.logIdx = ChatLogIdx(loadIdx);
    startIdx.numMatches = 0;
    return sessionChatLog.searchBackward(startIdx, phrase, parameter);
}

ChatLogIdx ChatHistory::getFirstIdx() const
{
    if (canUseHistory()) {
        return ChatLogIdx(0);
    } else {
        return sessionChatLog.getFirstIdx();
    }
}

ChatLogIdx ChatHistory::getNextIdx() const
{
    return sessionChatLog.getNextIdx();
}

std::vector<IChatLog::DateChatLogIdxPair> ChatHistory::getDateIdxs(const QDate& startDate,
                                                                   size_t maxDates) const
{
    if (canUseHistory()) {
        auto counts = history->getNumMessagesForChatBeforeDateBoundaries(chat.getPersistentId(),
                                                                           startDate, maxDates);

        std::vector<IChatLog::DateChatLogIdxPair> ret;
        std::transform(counts.begin(), counts.end(), std::back_inserter(ret),
                       [&](const History::DateIdx& historyDateIdx) {
                           DateChatLogIdxPair pair;
                           pair.date = historyDateIdx.date;
                           pair.idx.get() = historyDateIdx.numMessagesIn;
                           return pair;
                       });

        // Do not re-search in the session chat log. If we have history the query to the history should have been sufficient
        return ret;
    } else {
        return sessionChatLog.getDateIdxs(startDate, maxDates);
    }
}

void ChatHistory::addSystemMessage(const SystemMessage& message)
{
    if (canUseHistory()) {
        history->addNewSystemMessage(chat.getPersistentId(), message);
    }

    sessionChatLog.addSystemMessage(message);
}


void ChatHistory::onFileUpdated(const ToxPk& sender, const ToxFile& file)
{
    if (canUseHistory()) {
        switch (file.status) {
        case ToxFile::INITIALIZING: {
            auto selfPk = coreIdHandler.getSelfPublicKey();
            QString username(selfPk == sender ? coreIdHandler.getUsername() : chat.getDisplayedName(sender));

            // Note: There is some implcit coupling between history and the current
            // chat log. Both rely on generating a new id based on the state of
            // initializing. If this is changed in the session chat log we'll end up
            // with a different order when loading from history
            history->addNewFileMessage(chat.getPersistentId(), file.resumeFileId, file.fileName,
                                       file.filePath, file.progress.getFileSize(), sender,
                                       QDateTime::currentDateTime(), username);
            break;
        }
        case ToxFile::CANCELED:
        case ToxFile::FINISHED:
        case ToxFile::BROKEN: {
            const bool isSuccess = file.status == ToxFile::FINISHED;
            history->setFileFinished(file.resumeFileId, isSuccess, file.filePath,
                                     file.hashGenerator->result());
            break;
        }
        case ToxFile::PAUSED:
        case ToxFile::TRANSMITTING:
        default:
            break;
        }
    }

    sessionChatLog.onFileUpdated(sender, file);
}

void ChatHistory::onFileTransferRemotePausedUnpaused(const ToxPk& sender, const ToxFile& file,
                                                     bool paused)
{
    sessionChatLog.onFileTransferRemotePausedUnpaused(sender, file, paused);
}

void ChatHistory::onFileTransferBrokenUnbroken(const ToxPk& sender, const ToxFile& file, bool broken)
{
    sessionChatLog.onFileTransferBrokenUnbroken(sender, file, broken);
}

void ChatHistory::onMessageReceived(const ToxPk& sender, const Message& message)
{
    if (canUseHistory()) {
        auto& chatId = chat.getPersistentId();
        auto displayName = chat.getDisplayedName(sender);
        auto content = message.content;
        if (message.isAction) {
            content = ChatForm::ACTION_PREFIX + content;
        }

        history->addNewMessage(chatId, content, sender, message.timestamp, true, message.extensionSet, displayName);
        keepWireId(sender, message);
    }

    sessionChatLog.onMessageReceived(sender, message);
}

void ChatHistory::onMessageSent(DispatchedMessageId id, const Message& message)
{
    if (outbox) {
        queueSentMessage(id, message);
        return;
    }

    if (canUseHistory()) {
        auto selfPk = coreIdHandler.getSelfPublicKey();
        auto& chatId = chat.getPersistentId();

        auto content = message.content;
        if (message.isAction) {
            content = ChatForm::ACTION_PREFIX + content;
        }

        auto username = coreIdHandler.getUsername();

        auto onInsertion = [this, id](RowId historyId) { handleDispatchedMessage(id, historyId); };

        history->addNewMessage(chatId, content, selfPk, message.timestamp, false, message.extensionSet, username,
                               onInsertion);
        keepWireId(selfPk, message);
    }

    sessionChatLog.onMessageSent(id, message);
}

void ChatHistory::onMessageComplete(DispatchedMessageId id)
{
    if (outbox) {
        const int at = queueIndexOf(id);
        if (at >= 0) {
            outbox->remove(queue[at].message.outboxId);
            queue.remove(at);
            emit queueChanged();
        }
    } else if (canUseHistory()) {
        completeMessage(id);
    }

    sessionChatLog.onMessageComplete(id);
}

void ChatHistory::onMessageBroken(DispatchedMessageId id, BrokenMessageReason reason)
{
    bool queued = false;
    if (outbox) {
        // Marked before the log reports the change, so the conversation moves the message from
        // the log to the failed row in one step.
        const int at = queueIndexOf(id);
        if (at >= 0) {
            outbox->markFailed(queue[at].message.outboxId);
            queue[at].message.failed = true;
            queue[at].dispatched = false;
            queued = true;
        }
    } else if (canUseHistory()) {
        breakMessage(id, reason);
    }

    sessionChatLog.onMessageBroken(id, reason);

    if (queued) {
        emit queueChanged();
    }
}

bool ChatHistory::isShown(ChatLogIdx idx) const
{
    // Cancelled here, or deleted by its author.
    if (cancelledIdx.contains(idx.get()) || !sessionChatLog.isShown(idx)) {
        return false;
    }

    for (const auto& entry : queue) {
        if (entry.hasIdx && entry.idx == idx.get()) {
            return !entry.message.failed;
        }
    }
    return true;
}

bool ChatHistory::isCancelled(ChatLogIdx idx) const
{
    return cancelledIdx.contains(idx.get()) || sessionChatLog.isCancelled(idx);
}

SessionChatLog::EffectResult ChatHistory::applyEffect(const ToxPk& sender,
                                                      const Air::MessageEffect& effect,
                                                      const ToxPk& selfKey)
{
    using Change = SessionChatLog::EffectResult::Change;
    const SessionChatLog::EffectResult result = sessionChatLog.applyEffect(sender, effect, selfKey);
    if (result.change == Change::None || !canUseHistory()) {
        return result;
    }

    const ChatId& chatId = chat.getPersistentId();
    const bool isFile = effect.anchor == Air::MessageEffect::Anchor::File;
    const QString key = isFile ? Air::fileEntryKey(chatKey(), result.file.resumeFileId)
                               : Air::textEntryKey(chatKey(), result.author.toString(),
                                                   result.timestamp.toMSecsSinceEpoch());
    switch (result.change) {
    case Change::Edited: {
        Message after = result.before;
        after.content = effect.text;
        history->setMessageText(chatId, result.author, result.timestamp, storedText(result.before),
                                storedText(after));
        break;
    }
    case Change::Deleted:
        if (isFile) {
            history->eraseFileEntry(chatId, result.file.resumeFileId);
        } else {
            history->setMessageText(chatId, result.author, result.timestamp,
                                    storedText(result.before), QString());
        }
        break;
    case Change::Reacted:
    case Change::None:
        break;
    }
    if (entryStore) {
        entryStore->setEntryMarks(key, result.change == Change::Deleted ? Air::EntryMarks()
                                                                        : result.marks);
        if (result.change == Change::Deleted && !isFile) {
            entryStore->setWireId(key, QByteArray());
        }
    }
    return result;
}

QString ChatHistory::chatKey() const
{
    return chat.getPersistentId().toString();
}

void ChatHistory::keepWireId(const ToxPk& author, const Message& message)
{
    if (!entryStore || message.wireId.isEmpty()) {
        return;
    }
    entryStore->setWireId(Air::textEntryKey(chatKey(), author.toString(),
                                            message.timestamp.toMSecsSinceEpoch()),
                          message.wireId);
}

QVector<ChatHistory::QueuedMessage> ChatHistory::queuedMessages() const
{
    QVector<QueuedMessage> result;
    result.reserve(queue.size());
    for (const auto& entry : queue) {
        result << entry.message;
    }
    return result;
}

/**
 * @brief Takes a waiting or failed message back. The dispatcher forgets it, the queue drops it, and
 * its history row, if any, goes at the next start: removing it now would move the indexes of the
 * rows after it.
 * @param[in] outboxId Id of a message of queuedMessages()
 */
void ChatHistory::cancelQueuedMessage(qint64 outboxId)
{
    if (!outbox) {
        return;
    }

    const auto it = std::find_if(queue.begin(), queue.end(), [outboxId](const QueueEntry& entry) {
        return entry.message.outboxId == outboxId;
    });
    if (it == queue.end()) {
        return;
    }

    const QueueEntry entry = *it;
    queue.erase(it);

    if (entry.dispatched) {
        dispatcher.cancelMessage(entry.dispatchId);
    }
    outbox->cancel(outboxId);

    if (entry.historyId.get() >= 0) {
        cancelledHistoryRows.insert(entry.historyId.get());
    }
    if (entry.hasIdx) {
        cancelledIdx.insert(entry.idx);
        emit itemUpdated(ChatLogIdx(entry.idx));
    }
    emit queueChanged();
}

/**
 * @brief Puts a message that just left the dispatcher in the queue: with the history on, its
 * history row and its queue row are written together.
 */
void ChatHistory::queueSentMessage(DispatchedMessageId id, const Message& message)
{
    auto content = message.content;
    if (message.isAction) {
        content = ChatForm::ACTION_PREFIX + content;
    }

    QueueEntry entry;
    if (canUseHistory()) {
        entry.message.outboxId = outbox->reserveId();
        history->addNewOutgoingMessage(chat.getPersistentId(), content,
                                       coreIdHandler.getSelfPublicKey(), message.timestamp,
                                       message.extensionSet, coreIdHandler.getUsername(),
                                       entry.message.outboxId);
    } else {
        entry.message.outboxId =
            outbox->add(chat.getPersistentId(), content, message.timestamp, message.extensionSet);
    }
    entry.message.text = message.content;
    entry.message.isAction = message.isAction;
    entry.message.timestamp = message.timestamp;
    entry.dispatched = true;
    entry.dispatchId = id;
    entry.hasIdx = true;
    entry.idx = sessionChatLog.getNextIdx().get();

    sessionChatLog.onMessageSent(id, message);
    queue.append(entry);
    emit queueChanged();
}

int ChatHistory::queueIndexOf(DispatchedMessageId id) const
{
    for (int i = 0; i < queue.size(); ++i) {
        if (queue[i].dispatched && queue[i].dispatchId == id) {
            return i;
        }
    }
    return -1;
}

/**
 * @brief Gives the queued or cancelled message of a history row its index, once the row is loaded.
 */
void ChatHistory::noteLoadedQueueRow(RowId historyId, ChatLogIdx idx) const
{
    if (cancelledHistoryRows.contains(historyId.get())) {
        cancelledIdx.insert(idx.get());
    }

    for (auto& entry : queue) {
        if (entry.historyId == historyId) {
            entry.hasIdx = true;
            entry.idx = idx.get();
        }
    }
}

/**
 * @brief Sends the queue of this friend again, oldest first, as it was left last time. With the
 * history on, a message that has a history row is shown when that row is loaded; any other is
 * added to the end of the log.
 * @param[in] messageDispatcher
 */
void ChatHistory::dispatchQueue(IMessageDispatcher& messageDispatcher)
{
    const bool withHistory = canUseHistory();
    for (const auto& stored : outbox->entriesForChat(chat.getPersistentId())) {
        QueueEntry entry;
        entry.message.outboxId = stored.id;
        entry.message.text = stored.message;
        entry.message.isAction = handleActionPrefix(entry.message.text);
        entry.message.timestamp = stored.timestamp;
        entry.message.failed = stored.failed;
        entry.historyId = stored.historyId;

        if (!stored.failed) {
            // As in dispatchUnsentMessages: one stored message is one dispatched message, and in
            // the odd case it splits further, the last part stands for it.
            entry.dispatchId =
                stored.extensionSet.none()
                    ? messageDispatcher.sendMessage(entry.message.isAction, entry.message.text).second
                    : messageDispatcher.sendExtendedMessage(entry.message.text, stored.extensionSet).second;
            entry.dispatched = true;

            if (withHistory && stored.historyId.get() >= 0) {
                queuedHistoryRows.insert(stored.historyId, entry.dispatchId);
            } else {
                entry.hasIdx = true;
                entry.idx = sessionChatLog.getNextIdx().get();
                sessionChatLog.onMessageSent(entry.dispatchId, Message{entry.message.isAction,
                                                                       entry.message.text,
                                                                       stored.timestamp,
                                                                       stored.extensionSet,
                                                                       {},
                                                                       {}});
            }
        }
        queue.append(entry);
    }
}

/**
 * @brief Forces the given index and all future indexes to be in the chatlog
 * @param[in] idx
 * @note Marked const since this doesn't change _external_ state of the class. We
     still have all the same items at all the same indexes, we've just stuckem
     in ram
 */
void ChatHistory::ensureIdxInSessionChatLog(ChatLogIdx idx) const
{
    if (needsLoadFromHistory(idx, sessionChatLog)) {
        loadHistoryIntoSessionChatLog(idx);
    }
}
/**
 * @brief Unconditionally loads the given index and all future messages that
 * are not in the session chat log into the session chat log
 * @param[in] idx
 * @note Marked const since this doesn't change _external_ state of the class. We
   still have all the same items at all the same indexes, we've just stuckem
   in ram
 * @note no end idx as we always load from start -> latest. In the future we
 * could have a less contiguous history
 */
void ChatHistory::loadHistoryIntoSessionChatLog(ChatLogIdx start) const
{
    if (!needsLoadFromHistory(start, sessionChatLog)) {
        return;
    }

    auto end = sessionChatLog.getFirstIdx();

    // We know that both history and us have a start index of 0 so the type
    // conversion should be safe
    assert(getFirstIdx() == ChatLogIdx(0));
    auto messages = history->getMessagesForChat(chat.getPersistentId(), start.get(), end.get());

    assert(messages.size() == static_cast<int>(end.get() - start.get()));
    ChatLogIdx nextIdx = start;

    for (const auto& message : messages) {
        // Note that message.id is _not_ a valid conversion here since it is a
        // global id not a per-chat id like the ChatLogIdx
        auto currentIdx = nextIdx++;
        switch (message.content.getType()) {
        case HistMessageContentType::file: {
            const auto date = message.timestamp;
            const auto file = message.content.asFile();
            const Air::EntryMarks marks =
                entryStore ? entryStore->entryMarks(Air::fileEntryKey(chatKey(), file.resumeFileId))
                           : Air::EntryMarks();
            const auto chatLogFile = ChatLogFile{date, file, marks};
            sessionChatLog.insertFileAtIdx(currentIdx, message.sender, message.dispName, chatLogFile);
            // Its sender deleted it: the history keeps neither its name nor its place.
            if (file.fileName.isEmpty() && file.filePath.isEmpty()) {
                sessionChatLog.markHidden(currentIdx);
            }
            break;
        }
        case HistMessageContentType::message: {
            auto messageContent = message.content.asMessage();

            auto isAction = handleActionPrefix(messageContent);

            // It's okay to skip the message processor here. The processor is
            // meant to convert between boundaries of our internal
            // representation. We already had to go through the processor before
            // we hit IMessageDispatcher's signals which history listens for.
            // Items added to history have already been sent so we know they already
            // reflect what was sent/received.
            // The marks of the entry and the id a group gave it live in the entry store (item 10).
            const QString entryKey = Air::textEntryKey(chatKey(), message.sender.toString(),
                                                       message.timestamp.toMSecsSinceEpoch());
            const QByteArray wireId = entryStore ? entryStore->wireId(entryKey) : QByteArray();
            const Air::EntryMarks marks =
                entryStore ? entryStore->entryMarks(entryKey) : Air::EntryMarks();
            auto processedMessage =
                Message{isAction, messageContent, message.timestamp, {}, {}, wireId};

            auto chatLogMessage = ChatLogMessage{message.state, processedMessage, marks};
            // Its author deleted it: the history keeps no words.
            if (messageContent.isEmpty()) {
                sessionChatLog.markHidden(currentIdx);
            }

            if (outbox) {
                noteLoadedQueueRow(message.id, currentIdx);
                if (message.state == MessageState::pending) {
                    const auto queued = queuedHistoryRows.find(message.id);
                    if (queued != queuedHistoryRows.end()) {
                        sessionChatLog.insertIncompleteMessageAtIdx(currentIdx, message.sender,
                                                                    message.dispName,
                                                                    chatLogMessage, queued.value());
                    } else {
                        // Nothing will ever complete a pending row the queue does not hold.
                        sessionChatLog.insertBrokenMessageAtIdx(currentIdx, message.sender,
                                                                message.dispName, chatLogMessage);
                    }
                    break;
                }
            }

            auto dispatchedMessageIt =
                std::find_if(dispatchedMessageRowIdMap.begin(), dispatchedMessageRowIdMap.end(),
                             [&](RowId dispatchedId) { return dispatchedId == message.id; });

            assert(outbox
                   || (message.state != MessageState::pending
                       && dispatchedMessageIt == dispatchedMessageRowIdMap.end())
                   || (message.state == MessageState::pending
                       && dispatchedMessageIt != dispatchedMessageRowIdMap.end()));

            switch (message.state) {
                case MessageState::complete:
                    sessionChatLog.insertCompleteMessageAtIdx(currentIdx, message.sender, message.dispName,
                                                              chatLogMessage);
                    break;
                case MessageState::pending:
                    sessionChatLog.insertIncompleteMessageAtIdx(currentIdx, message.sender, message.dispName,
                                                                chatLogMessage, dispatchedMessageIt.key());
                    break;
                case MessageState::broken:
                    sessionChatLog.insertBrokenMessageAtIdx(currentIdx, message.sender, message.dispName,
                                                            chatLogMessage);
                    break;
            }
            break;
        }
        case HistMessageContentType::system: {
            const auto& systemMessage = message.content.asSystemMessage();
            sessionChatLog.insertSystemMessageAtIdx(currentIdx, systemMessage);
            break;
        }
        }
    }

    assert(nextIdx == end);
}

/**
 * @brief Sends any unsent messages in history to the underlying message dispatcher
 * @param[in] messageDispatcher
 */
void ChatHistory::dispatchUnsentMessages(IMessageDispatcher& messageDispatcher)
{
    auto unsentMessages = history->getUndeliveredMessagesForChat(chat.getPersistentId());

    auto requiredExtensions = std::accumulate(
        unsentMessages.begin(), unsentMessages.end(),
        ExtensionSet(), [] (const ExtensionSet& a, const History::HistMessage& b) {
            return a | b.extensionSet;
        });

    for (auto& message : unsentMessages) {
        // We should only store messages as unsent, if this changes in the
        // future we need to extend this logic
        assert(message.content.getType() == HistMessageContentType::message);

        auto messageContent = message.content.asMessage();
        auto isAction = handleActionPrefix(messageContent);

        // NOTE: timestamp will be generated in messageDispatcher but we haven't
        // hooked up our history callback so it will not be shown in our chatlog
        // with the new timestamp. This is intentional as everywhere else we use
        // attempted send time (which is whenever the it was initially inserted
        // into history
        auto dispatchId = requiredExtensions.none()
            // We should only send a single message, but in the odd case where we end
            // up having to split more than when we added the message to history we'll
            // just associate the last dispatched id with the history message
            ? messageDispatcher.sendMessage(isAction, messageContent).second
            : messageDispatcher.sendExtendedMessage(messageContent, requiredExtensions).second;

        handleDispatchedMessage(dispatchId, message.id);

        // We don't add the messages to the underlying chatlog since
        // 1. We don't even know the ChatLogIdx of this message
        // 2. We only want to display the latest N messages on boot by default,
        //    even if there are more than N messages that haven't been sent
    }
}

void ChatHistory::handleDispatchedMessage(DispatchedMessageId dispatchId, RowId historyId)
{
    auto completedMessageIt = completedMessages.find(dispatchId);
    auto brokenMessageIt = brokenMessages.find(dispatchId);

    const auto isCompleted = completedMessageIt != completedMessages.end();
    const auto isBroken = brokenMessageIt != brokenMessages.end();
    assert(!(isCompleted && isBroken));

    if (isCompleted) {
        history->markAsDelivered(historyId);
        completedMessages.erase(completedMessageIt);
    } else if (isBroken) {
        history->markAsBroken(historyId, brokenMessageIt.value());
        brokenMessages.erase(brokenMessageIt);
    } else {
        dispatchedMessageRowIdMap.insert(dispatchId, historyId);
    }
}

void ChatHistory::completeMessage(DispatchedMessageId id)
{
    auto dispatchedMessageIt = dispatchedMessageRowIdMap.find(id);

    if (dispatchedMessageIt == dispatchedMessageRowIdMap.end()) {
        completedMessages.insert(id);
    } else {
        history->markAsDelivered(*dispatchedMessageIt);
        dispatchedMessageRowIdMap.erase(dispatchedMessageIt);
    }
}

void ChatHistory::breakMessage(DispatchedMessageId id, BrokenMessageReason reason)
{
    auto dispatchedMessageIt = dispatchedMessageRowIdMap.find(id);

    if (dispatchedMessageIt == dispatchedMessageRowIdMap.end()) {
        brokenMessages.insert(id, reason);
    } else {
        history->markAsBroken(*dispatchedMessageIt, reason);
        dispatchedMessageRowIdMap.erase(dispatchedMessageIt);
    }
}

bool ChatHistory::canUseHistory() const
{
    return history && settings.getEnableLogging();
}

/**
 * @brief Gets the initial chat log index for a sessionChatLog with 0 items loaded from history.
 * Needed to keep history indexes in sync with chat log indexes
 * @param[in] history
 * @param[in] f
 * @return Initial chat log index
 */
ChatLogIdx ChatHistory::getInitialChatLogIdx() const
{
    if (canUseHistory()) {
        return ChatLogIdx(history->getNumMessagesForChat(chat.getPersistentId()));
    }
    return ChatLogIdx(0);
}
