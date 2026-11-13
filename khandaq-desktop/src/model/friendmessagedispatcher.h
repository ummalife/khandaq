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

#include "src/core/icorefriendmessagesender.h"
#include "src/model/friend.h"
#include "src/model/imessagedispatcher.h"
#include "src/model/message.h"
#include "src/persistence/offlinemsgengine.h"

#include <QObject>
#include <QSet>
#include <QString>

#include <cstdint>

/**
 * @brief How long a sent message waits for its delivery receipt, and how many times it is sent
 * again while the friend stays online, before it is reported as not sent.
 */
struct FriendMessageRetryPolicy
{
    int receiptTimeoutMs = 20000;
    int maxRetries = 5;
    int retryBaseMs = 2000;
    int retryMaxMs = 30000;
};

class FriendMessageDispatcher : public IMessageDispatcher
{
    Q_OBJECT
public:
    FriendMessageDispatcher(Friend& f, MessageProcessor processor,
                            ICoreFriendMessageSender& messageSender, ICoreExtPacketAllocator& coreExt,
                            FriendMessageRetryPolicy retryPolicy = FriendMessageRetryPolicy());

    std::pair<DispatchedMessageId, DispatchedMessageId> sendMessage(bool isAction,
                                                                    const QString& content) override;

    std::pair<DispatchedMessageId, DispatchedMessageId> sendExtendedMessage(const QString& content, ExtensionSet extensions) override;
    bool cancelMessage(DispatchedMessageId id) override;
    void onMessageReceived(bool isAction, const QString& content);
    void onReceiptReceived(ReceiptNum receipt);
    void onExtMessageReceived(const QString& content);
    void onExtReceiptReceived(uint64_t receiptId);
private slots:
    void onFriendOnlineOfflineChanged(const ToxPk& friendPk, bool isOnline);

private:
    void sendProcessedMessage(Message const& message,
                              OfflineMsgEngine::CompletionFn onOfflineMsgComplete, uint64_t tag,
                              int attempt = 0);
    void sendExtendedProcessedMessage(Message const& message,
                                      OfflineMsgEngine::CompletionFn onOfflineMsgComplete,
                                      uint64_t tag);
    void sendCoreProcessedMessage(Message const& message,
                                  OfflineMsgEngine::CompletionFn onOfflineMsgComplete, uint64_t tag,
                                  int attempt);
    void scheduleMessageRetry(Message const& message,
                              OfflineMsgEngine::CompletionFn onOfflineMsgComplete, uint64_t tag,
                              int attempt);
    OfflineMsgEngine::CompletionFn getCompletionFn(DispatchedMessageId messageId);
    OfflineMsgEngine::CompletionFn wrapWithReceiptTimeout(Message const& message,
                                                          OfflineMsgEngine::CompletionFn inner,
                                                          const char* sendKind, uint64_t receiptId,
                                                          uint64_t tag, int attempt, bool isExtended);
    static uint64_t tagOf(DispatchedMessageId messageId);

    Friend& f;
    ICoreExtPacketAllocator& coreExtPacketAllocator;
    DispatchedMessageId nextMessageId = DispatchedMessageId(0);

    ICoreFriendMessageSender& messageSender;
    OfflineMsgEngine offlineMsgEngine;
    MessageProcessor processor;
    FriendMessageRetryPolicy retryPolicy;
    /** Engine tags of the messages taken back with cancelMessage(). */
    QSet<quint64> cancelledTags;
};
