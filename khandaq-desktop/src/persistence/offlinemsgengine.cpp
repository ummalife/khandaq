/*
    Copyright © 2014-2019 by The qTox Project Contributors

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

#include "offlinemsgengine.h"
#include "src/core/core.h"
#include "src/model/friend.h"
#include "src/nexus.h"
#include "src/persistence/profile.h"
#include "src/persistence/settings.h"
#include "src/model/status.h"
#include <QMutexLocker>
#include <QTimer>
#include <QCoreApplication>
#include <algorithm>
#include <chrono>

/**
* @brief Notification that the message is now delivered.
*
* @param[in] receipt   Toxcore message ID which the receipt is for.
*/
void OfflineMsgEngine::onReceiptReceived(ReceiptNum receipt)
{
    QMutexLocker ml(&mutex);
    receiptResolver.notifyReceiptReceived(receipt);
}

void OfflineMsgEngine::onExtendedReceiptReceived(ExtendedReceiptNum receipt)
{
    QMutexLocker ml(&mutex);
    extendedReceiptResolver.notifyReceiptReceived(receipt);
}

/**
 * @brief Stops waiting for the receipt of a sent message, without completing it.
 * @return True if the message was still waiting for this receipt; false if it was completed or
 * taken back for resending meanwhile.
 */
bool OfflineMsgEngine::abandonCoreMessage(ReceiptNum receipt)
{
    QMutexLocker ml(&mutex);
    return receiptResolver.unAckedMessages.erase(receipt) > 0;
}

bool OfflineMsgEngine::abandonExtendedMessage(ExtendedReceiptNum receipt)
{
    QMutexLocker ml(&mutex);
    return extendedReceiptResolver.unAckedMessages.erase(receipt) > 0;
}

/**
* @brief Add a message which has been saved to history, but not sent yet to the peer.
*
* OfflineMsgEngine will send this message once the friend becomes online again, then track its
* receipt, updating history and chatlog once received.
*
* @param[in] messageID   database RowId of the message, used to eventually mark messages as received in history
* @param[in] msg         chat message line in the chatlog, used to eventually set the message's receieved timestamp
*/
void OfflineMsgEngine::addUnsentMessage(Message const& message, CompletionFn completionCallback,
                                        uint64_t tag)
{
    QMutexLocker ml(&mutex);
    unsentMessages.push_back(
        OfflineMessage{message, std::chrono::steady_clock::now(), completionCallback, tag});
}

/**
* @brief Add a message which has been saved to history, and which has been sent to the peer.
*
* OfflineMsgEngine will track this message's receipt. If the friend goes offline then comes back before the receipt
* is received, OfflineMsgEngine will also resend the message, updating history and chatlog once received.
*
* @param[in] receipt     the toxcore message ID, corresponding to expected receipt ID
* @param[in] messageID   database RowId of the message, used to eventually mark messages as received in history
* @param[in] msg         chat message line in the chatlog, used to eventually set the message's receieved timestamp
*/
void OfflineMsgEngine::addSentCoreMessage(ReceiptNum receipt, Message const& message,
                                          CompletionFn completionCallback, uint64_t tag)
{
    QMutexLocker ml(&mutex);
    receiptResolver.notifyMessageSent(receipt, {message, std::chrono::steady_clock::now(),
                                                completionCallback, tag});
}

void OfflineMsgEngine::addSentExtendedMessage(ExtendedReceiptNum receipt, Message const& message,
                                              CompletionFn completionCallback, uint64_t tag)
{
    QMutexLocker ml(&mutex);
    extendedReceiptResolver.notifyMessageSent(receipt, {message, std::chrono::steady_clock::now(),
                                                        completionCallback, tag});
}

/**
 * @brief Forgets the messages added with tag, whether they wait to be sent or wait for their
 * receipt. Their completion is never called.
 * @param[in] tag A tag given when the messages were added; 0 matches nothing.
 * @return True if a message was removed.
 */
bool OfflineMsgEngine::removeMessage(uint64_t tag)
{
    if (tag == 0) {
        return false;
    }

    QMutexLocker ml(&mutex);
    const auto unsentBefore = unsentMessages.size();
    unsentMessages.erase(std::remove_if(unsentMessages.begin(), unsentMessages.end(),
                                        [tag](const OfflineMessage& message) {
                                            return message.tag == tag;
                                        }),
                         unsentMessages.end());
    bool removed = unsentMessages.size() != unsentBefore;
    removed = receiptResolver.removeTagged(tag) || removed;
    removed = extendedReceiptResolver.removeTagged(tag) || removed;
    return removed;
}

/**
* @brief Removes all messages which are being tracked.
*/
std::vector<OfflineMsgEngine::RemovedMessage> OfflineMsgEngine::removeAllMessages()
{
    QMutexLocker ml(&mutex);
    auto messages = receiptResolver.clear();
    auto extendedMessages = extendedReceiptResolver.clear();

    messages.insert(
        messages.end(),
        std::make_move_iterator(extendedMessages.begin()),
        std::make_move_iterator(extendedMessages.end()));

    messages.insert(
        messages.end(),
        std::make_move_iterator(unsentMessages.begin()),
        std::make_move_iterator(unsentMessages.end()));

    unsentMessages.clear();

    std::sort(messages.begin(), messages.end(), [] (const OfflineMessage& a, const OfflineMessage& b) {
        return a.authorshipTime < b.authorshipTime;
    });

    auto ret = std::vector<RemovedMessage>();
    ret.reserve(messages.size());

    std::transform(messages.begin(), messages.end(), std::back_inserter(ret),
                   [](const OfflineMessage& msg) {
                       return RemovedMessage{msg.message, msg.completionFn, msg.tag};
                   });

    return ret;
}
