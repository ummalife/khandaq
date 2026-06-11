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

#include "friendmessagedispatcher.h"
#include "src/persistence/settings.h"
#include "src/model/status.h"

#include <QTimer>

#include <atomic>
#include <cstdint>
#include <memory>

namespace {
constexpr int RECEIPT_TIMEOUT_MS = 20000;
constexpr int MAX_RECEIPT_RETRIES = 5;
constexpr int RECEIPT_RETRY_BASE_MS = 2000;
constexpr int RECEIPT_RETRY_MAX_MS = 30000;

/** Outgoing friend messages use the Tox core path for reliable cross-client delivery. */
ExtensionSet coreOnlyExtensions(ExtensionSet extensions)
{
    extensions[ExtensionType::messages] = false;
    return extensions;
}
}

FriendMessageDispatcher::FriendMessageDispatcher(Friend& f_, MessageProcessor processor_,
                                                 ICoreFriendMessageSender& messageSender_,
                                                 ICoreExtPacketAllocator& coreExtPacketAllocator_)
    : f(f_)
    , coreExtPacketAllocator(coreExtPacketAllocator_)
    , messageSender(messageSender_)
    , processor(std::move(processor_))
{
    connect(&f, &Friend::onlineOfflineChanged, this, &FriendMessageDispatcher::onFriendOnlineOfflineChanged);
}

/**
 * @see IMessageDispatcher::sendMessage
 */
std::pair<DispatchedMessageId, DispatchedMessageId>
FriendMessageDispatcher::sendMessage(bool isAction, const QString& content)
{
    const auto firstId = nextMessageId;
    auto lastId = nextMessageId;
    for (const auto& message : processor.processOutgoingMessage(isAction, content,
                                                               coreOnlyExtensions(f.getSupportedExtensions()))) {
        auto messageId = nextMessageId++;
        lastId = messageId;

        auto onOfflineMsgComplete = getCompletionFn(messageId);
        sendProcessedMessage(message, onOfflineMsgComplete);

        emit messageSent(messageId, message);
    }
    return std::make_pair(firstId, lastId);
}

/**
 * @see IMessageDispatcher::sendExtendedMessage
 */
std::pair<DispatchedMessageId, DispatchedMessageId>
FriendMessageDispatcher::sendExtendedMessage(const QString& content, ExtensionSet extensions)
{
    const auto firstId = nextMessageId;
    auto lastId = nextMessageId;

    for (const auto& message : processor.processOutgoingMessage(false, content, coreOnlyExtensions(extensions))) {
        auto messageId = nextMessageId++;
        lastId = messageId;

        auto onOfflineMsgComplete = getCompletionFn(messageId);
        sendProcessedMessage(message, onOfflineMsgComplete);

        emit messageSent(messageId, message);
    }
    return std::make_pair(firstId, lastId);
}

/**
 * @brief Handles received message from toxcore
 * @param[in] isAction True if action message
 * @param[in] content Unprocessed toxcore message
 */
void FriendMessageDispatcher::onMessageReceived(bool isAction, const QString& content)
{
    emit messageReceived(f.getPublicKey(), processor.processIncomingCoreMessage(isAction, content));
}

/**
 * @brief Handles received receipt from toxcore
 * @param[in] receipt receipt id
 */
void FriendMessageDispatcher::onReceiptReceived(ReceiptNum receipt)
{
    qDebug() << "FriendMessageDispatcher: core read receipt" << receipt.get()
             << "friend" << f.getPublicKey().toString();
    offlineMsgEngine.onReceiptReceived(receipt);
}

void FriendMessageDispatcher::onExtMessageReceived(const QString& content)
{
    auto message = processor.processIncomingExtMessage(content);
    emit messageReceived(f.getPublicKey(), message);
}

void FriendMessageDispatcher::onExtReceiptReceived(uint64_t receiptId)
{
    qDebug() << "FriendMessageDispatcher: extended read receipt" << receiptId
             << "friend" << f.getPublicKey().toString();
    offlineMsgEngine.onExtendedReceiptReceived(ExtendedReceiptNum(receiptId));
}

/**
 * @brief Handles status change for friend
 * @note Parameters just to fit slot api
 */
void FriendMessageDispatcher::onFriendOnlineOfflineChanged(const ToxPk& friendPk, bool isOnline)
{
    std::ignore = friendPk;
    if (isOnline) {
        auto messagesToResend = offlineMsgEngine.removeAllMessages();
        for (auto const& message : messagesToResend) {
            sendProcessedMessage(message.message, message.callback);
        }
    }
}

/**
 * @brief Clears all currently outgoing messages
 */
void FriendMessageDispatcher::clearOutgoingMessages()
{
    offlineMsgEngine.removeAllMessages();
}


void FriendMessageDispatcher::sendProcessedMessage(Message const& message, OfflineMsgEngine::CompletionFn onOfflineMsgComplete)
{
    if (!Status::isOnline(f.getStatus())) {
        offlineMsgEngine.addUnsentMessage(message, onOfflineMsgComplete);
        return;
    }

    Q_UNUSED(coreExtPacketAllocator);
    sendCoreProcessedMessage(message, onOfflineMsgComplete);
}



void FriendMessageDispatcher::sendExtendedProcessedMessage(Message const& message, OfflineMsgEngine::CompletionFn onOfflineMsgComplete)
{
    assert(!message.isAction); // Actions not supported with extensions

    if ((f.getSupportedExtensions() & message.extensionSet) != message.extensionSet) {
        onOfflineMsgComplete(false);
        return;
    }

    const auto friendId = f.getId();
    auto packet = coreExtPacketAllocator.getPacket(friendId);

    const uint64_t rawReceipt = packet->addExtendedMessage(message.content);
    if (rawReceipt == UINT64_MAX) {
        offlineMsgEngine.addUnsentMessage(message, onOfflineMsgComplete);
        return;
    }

    const ExtendedReceiptNum receipt(rawReceipt);
    const auto messageSent = packet->send();

    if (messageSent) {
        offlineMsgEngine.addSentExtendedMessage(
            receipt, message, wrapWithReceiptTimeout(message, onOfflineMsgComplete, "extended",
                                                     receipt.get(), 0, true));
    } else {
        offlineMsgEngine.addUnsentMessage(message, onOfflineMsgComplete);
    }
}

void FriendMessageDispatcher::scheduleMessageRetry(Message const& message,
                                                   OfflineMsgEngine::CompletionFn onOfflineMsgComplete,
                                                   int attempt)
{
    if (attempt >= MAX_RECEIPT_RETRIES) {
        qWarning() << "FriendMessageDispatcher: giving up after" << MAX_RECEIPT_RETRIES
                   << "retries for friend" << f.getPublicKey().toString();
        offlineMsgEngine.addUnsentMessage(message, onOfflineMsgComplete);
        onOfflineMsgComplete(false);
        return;
    }

    const int delayMs = qMin(RECEIPT_RETRY_BASE_MS * (1 << attempt), RECEIPT_RETRY_MAX_MS);
    QTimer::singleShot(delayMs, this, [this, message, onOfflineMsgComplete, attempt]() {
        sendProcessedMessage(message, onOfflineMsgComplete);
    });
}

void FriendMessageDispatcher::sendCoreProcessedMessage(Message const& message, OfflineMsgEngine::CompletionFn onOfflineMsgComplete)
{
    auto receipt = ReceiptNum();

    uint32_t friendId = f.getId();

    auto sendFn = message.isAction ? std::mem_fn(&ICoreFriendMessageSender::sendAction)
                                   : std::mem_fn(&ICoreFriendMessageSender::sendMessage);

    const auto messageSent = sendFn(messageSender, friendId, message.content, receipt);

    if (messageSent) {
        offlineMsgEngine.addSentCoreMessage(
            receipt, message, wrapWithReceiptTimeout(message, onOfflineMsgComplete, "core",
                                                     receipt.get(), 0, false));
    } else {
        offlineMsgEngine.addUnsentMessage(message, onOfflineMsgComplete);
    }
}

OfflineMsgEngine::CompletionFn FriendMessageDispatcher::wrapWithReceiptTimeout(
    Message const& message, OfflineMsgEngine::CompletionFn inner, const char* sendKind,
    uint64_t receiptId, int attempt, bool isExtended)
{
    const QString friendPk = f.getPublicKey().toString();
    auto finished = std::make_shared<std::atomic<bool>>(false);

    const auto completeOnce = [inner, finished, friendPk, sendKind, receiptId](bool success) {
        if (finished->exchange(true)) {
            return;
        }

        if (success) {
            qDebug() << "FriendMessageDispatcher: delivery confirmed" << sendKind
                     << "receipt" << receiptId << "friend" << friendPk;
        }

        inner(success);
    };

    QTimer::singleShot(RECEIPT_TIMEOUT_MS, this,
                       [this, message, inner, completeOnce, finished, friendPk, sendKind, receiptId,
                        attempt, isExtended]() {
                           if (finished->load()) {
                               return;
                           }

                           qInfo() << "FriendMessageDispatcher: receipt timeout, retrying" << sendKind
                                   << "receipt" << receiptId << "friend" << friendPk << "attempt"
                                   << attempt;

                           if (isExtended) {
                               offlineMsgEngine.abandonExtendedMessage(ExtendedReceiptNum(receiptId));
                           } else {
                               offlineMsgEngine.abandonCoreMessage(ReceiptNum(receiptId));
                           }

                           scheduleMessageRetry(message, inner, attempt + 1);
                       });

    return [completeOnce, finished](bool success) {
        if (!success) {
            finished->store(true);
        }

        completeOnce(success);
    };
}

OfflineMsgEngine::CompletionFn FriendMessageDispatcher::getCompletionFn(DispatchedMessageId messageId)
{
    return [this, messageId] (bool success) {
        if (success) {
            emit messageComplete(messageId);
        } else {
            // For now we know the only reason we can fail after giving to the
            // offline message engine is due to a reduced extension set
            emit messageBroken(messageId, BrokenMessageReason::unsupportedExtensions);
        }
    };
}
