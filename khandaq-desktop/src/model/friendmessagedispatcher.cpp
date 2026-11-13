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
/** Outgoing friend messages use the Tox core path for reliable cross-client delivery. */
ExtensionSet coreOnlyExtensions(ExtensionSet extensions)
{
    extensions[ExtensionType::messages] = false;
    return extensions;
}
}

FriendMessageDispatcher::FriendMessageDispatcher(Friend& f_, MessageProcessor processor_,
                                                 ICoreFriendMessageSender& messageSender_,
                                                 ICoreExtPacketAllocator& coreExtPacketAllocator_,
                                                 FriendMessageRetryPolicy retryPolicy_)
    : f(f_)
    , coreExtPacketAllocator(coreExtPacketAllocator_)
    , messageSender(messageSender_)
    , processor(std::move(processor_))
    , retryPolicy(retryPolicy_)
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
        sendProcessedMessage(message, onOfflineMsgComplete, tagOf(messageId));

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
        sendProcessedMessage(message, onOfflineMsgComplete, tagOf(messageId));

        emit messageSent(messageId, message);
    }
    return std::make_pair(firstId, lastId);
}

/**
 * @see IMessageDispatcher::cancelMessage
 * @note A message already handed to toxcore may still reach the friend; it is only no longer sent
 * again, and its receipt is ignored.
 */
bool FriendMessageDispatcher::cancelMessage(DispatchedMessageId id)
{
    const auto tag = tagOf(id);
    cancelledTags.insert(tag);
    offlineMsgEngine.removeMessage(tag);
    return true;
}

/**
 * @brief The tag of a message in the offline message engine: never 0, which the engine keeps for
 * untagged messages.
 */
uint64_t FriendMessageDispatcher::tagOf(DispatchedMessageId messageId)
{
    return static_cast<uint64_t>(messageId.get()) + 1;
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
            sendProcessedMessage(message.message, message.callback, message.tag);
        }
    }
}


/**
 * @param attempt How many times this message was already sent again after a missing receipt while
 * the friend stayed online; a reconnect starts the count from zero.
 */
void FriendMessageDispatcher::sendProcessedMessage(Message const& message,
                                                   OfflineMsgEngine::CompletionFn onOfflineMsgComplete,
                                                   uint64_t tag, int attempt)
{
    if (cancelledTags.contains(tag)) {
        return;
    }

    if (!Status::isOnline(f.getStatus())) {
        offlineMsgEngine.addUnsentMessage(message, onOfflineMsgComplete, tag);
        return;
    }

    Q_UNUSED(coreExtPacketAllocator);
    sendCoreProcessedMessage(message, onOfflineMsgComplete, tag, attempt);
}


void FriendMessageDispatcher::sendExtendedProcessedMessage(
    Message const& message, OfflineMsgEngine::CompletionFn onOfflineMsgComplete, uint64_t tag)
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
        offlineMsgEngine.addUnsentMessage(message, onOfflineMsgComplete, tag);
        return;
    }

    const ExtendedReceiptNum receipt(rawReceipt);
    const auto messageSent = packet->send();

    if (messageSent) {
        offlineMsgEngine.addSentExtendedMessage(receipt, message,
                                                wrapWithReceiptTimeout(message, onOfflineMsgComplete,
                                                                       "extended", receipt.get(),
                                                                       tag, 0, true),
                                                tag);
    } else {
        offlineMsgEngine.addUnsentMessage(message, onOfflineMsgComplete, tag);
    }
}

/**
 * @brief Sends a message again after its receipt did not come, or reports it as not sent once the
 * retries are used up. A message reported as not sent leaves the queue: sending it again is up to
 * the user.
 * @param attempt Number of the retry to schedule, starting from 1.
 */
void FriendMessageDispatcher::scheduleMessageRetry(Message const& message,
                                                   OfflineMsgEngine::CompletionFn onOfflineMsgComplete,
                                                   uint64_t tag, int attempt)
{
    if (attempt > retryPolicy.maxRetries) {
        qWarning() << "FriendMessageDispatcher: no receipt after" << retryPolicy.maxRetries
                   << "retries, reporting the message as not sent; friend"
                   << f.getPublicKey().toString();
        onOfflineMsgComplete(false);
        return;
    }

    const int shift = qBound(0, attempt - 1, 16);
    const qint64 delayMs = qMin(static_cast<qint64>(retryPolicy.retryBaseMs) << shift,
                                static_cast<qint64>(retryPolicy.retryMaxMs));
    QTimer::singleShot(static_cast<int>(delayMs), this,
                       [this, message, onOfflineMsgComplete, tag, attempt]() {
                           sendProcessedMessage(message, onOfflineMsgComplete, tag, attempt);
                       });
}

void FriendMessageDispatcher::sendCoreProcessedMessage(Message const& message,
                                                       OfflineMsgEngine::CompletionFn onOfflineMsgComplete,
                                                       uint64_t tag, int attempt)
{
    auto receipt = ReceiptNum();

    uint32_t friendId = f.getId();

    auto sendFn = message.isAction ? std::mem_fn(&ICoreFriendMessageSender::sendAction)
                                   : std::mem_fn(&ICoreFriendMessageSender::sendMessage);

    const auto messageSent = sendFn(messageSender, friendId, message.content, receipt);

    if (messageSent) {
        offlineMsgEngine.addSentCoreMessage(receipt, message,
                                            wrapWithReceiptTimeout(message, onOfflineMsgComplete,
                                                                   "core", receipt.get(), tag,
                                                                   attempt, false),
                                            tag);
    } else {
        offlineMsgEngine.addUnsentMessage(message, onOfflineMsgComplete, tag);
    }
}

/**
 * @brief Wraps the completion of a sent message so that it runs once, and starts the wait for the
 * receipt of this send.
 */
OfflineMsgEngine::CompletionFn FriendMessageDispatcher::wrapWithReceiptTimeout(
    Message const& message, OfflineMsgEngine::CompletionFn inner, const char* sendKind,
    uint64_t receiptId, uint64_t tag, int attempt, bool isExtended)
{
    const QString friendPk = f.getPublicKey().toString();
    auto finished = std::make_shared<std::atomic<bool>>(false);

    QTimer::singleShot(retryPolicy.receiptTimeoutMs, this,
                       [this, message, inner, finished, friendPk, sendKind, receiptId, tag, attempt,
                        isExtended]() {
                           if (finished->load() || cancelledTags.contains(tag)) {
                               return;
                           }

                           if (!Status::isOnline(f.getStatus())) {
                               // The engine keeps waiting for this receipt; the reconnect sends
                               // the message again under a new receipt, with a wait of its own.
                               return;
                           }

                           const bool wasWaiting =
                               isExtended
                                   ? offlineMsgEngine.abandonExtendedMessage(
                                         ExtendedReceiptNum(receiptId))
                                   : offlineMsgEngine.abandonCoreMessage(ReceiptNum(receiptId));
                           if (!wasWaiting) {
                               // A reconnect already sent the message again, and that send is
                               // waited for on its own; retrying here would send it twice.
                               return;
                           }

                           qInfo() << "FriendMessageDispatcher: receipt timeout" << sendKind
                                   << "receipt" << receiptId << "friend" << friendPk << "attempt"
                                   << attempt;

                           scheduleMessageRetry(message, inner, tag, attempt + 1);
                       });

    return [inner, finished, friendPk, sendKind, receiptId](bool success) {
        if (finished->exchange(true)) {
            return;
        }

        if (success) {
            qDebug() << "FriendMessageDispatcher: delivery confirmed" << sendKind << "receipt"
                     << receiptId << "friend" << friendPk;
        }

        inner(success);
    };
}

OfflineMsgEngine::CompletionFn FriendMessageDispatcher::getCompletionFn(DispatchedMessageId messageId)
{
    return [this, messageId](bool success) {
        if (cancelledTags.contains(tagOf(messageId))) {
            return;
        }

        if (success) {
            emit messageComplete(messageId);
        } else {
            // Outgoing messages take the core path only, so the one way to fail after the offline
            // message engine took the message is a receipt that never came. The reason is not
            // read back from the history, so the existing generic value is stored.
            emit messageBroken(messageId, BrokenMessageReason::unknown);
        }
    };
}
