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

#pragma once

#include "src/chatlog/chatmessage.h"
#include "src/core/core.h"
#include "src/model/message.h"
#include "src/persistence/db/rawdatabase.h"

#include "util/compatiblerecursivemutex.h"

#include <QDateTime>
#include <QMap>
#include <QMutex>
#include <QObject>
#include <QSet>
#include <chrono>


class OfflineMsgEngine : public QObject
{
    Q_OBJECT
public:
    using CompletionFn = std::function<void(bool)>;
    OfflineMsgEngine() = default;
    /** A tag of 0 marks no message in particular; removeMessage() finds the others by their tag. */
    void addUnsentMessage(Message const& message, CompletionFn completionCallback, uint64_t tag = 0);
    void addSentCoreMessage(ReceiptNum receipt, Message const& message,
                            CompletionFn completionCallback, uint64_t tag = 0);
    void addSentExtendedMessage(ExtendedReceiptNum receipt, Message const& message,
                                CompletionFn completionCallback, uint64_t tag = 0);
    bool abandonCoreMessage(ReceiptNum receipt);
    bool abandonExtendedMessage(ExtendedReceiptNum receipt);
    bool removeMessage(uint64_t tag);

    struct RemovedMessage
    {
        Message message;
        CompletionFn callback;
        uint64_t tag;
    };
    std::vector<RemovedMessage> removeAllMessages();

public slots:
    void onReceiptReceived(ReceiptNum receipt);
    void onExtendedReceiptReceived(ExtendedReceiptNum receipt);

private:
    struct OfflineMessage
    {
        Message message;
        std::chrono::time_point<std::chrono::steady_clock> authorshipTime;
        CompletionFn completionFn;
        uint64_t tag;
    };

    CompatibleRecursiveMutex mutex;

    template <class ReceiptT>
    class ReceiptResolver
    {
    public:
        void notifyMessageSent(ReceiptT receipt, OfflineMessage const& message)
        {
            auto receivedReceiptIt = std::find(
                    receivedReceipts.begin(), receivedReceipts.end(), receipt);

            if (receivedReceiptIt != receivedReceipts.end()) {
                receivedReceipts.erase(receivedReceiptIt);
                message.completionFn(true);
                return;
            }

            unAckedMessages[receipt] = message;
        }

        void notifyReceiptReceived(ReceiptT receipt)
        {
            auto unackedMessageIt = unAckedMessages.find(receipt);
            if (unackedMessageIt != unAckedMessages.end()) {
                unackedMessageIt->second.completionFn(true);
                unAckedMessages.erase(unackedMessageIt);
                return;
            }

            receivedReceipts.push_back(receipt);
        }

        bool removeTagged(uint64_t tag)
        {
            bool removed = false;
            for (auto it = unAckedMessages.begin(); it != unAckedMessages.end();) {
                if (it->second.tag == tag) {
                    it = unAckedMessages.erase(it);
                    removed = true;
                } else {
                    ++it;
                }
            }
            return removed;
        }

        std::vector<OfflineMessage> clear()
        {
            auto ret = std::vector<OfflineMessage>();
            std::transform(
                std::make_move_iterator(unAckedMessages.begin()), std::make_move_iterator(unAckedMessages.end()),
                std::back_inserter(ret),
                [] (const std::pair<ReceiptT, OfflineMessage>& pair) {
                    return std::move(pair.second);
                });

            receivedReceipts.clear();
            unAckedMessages.clear();
            return ret;
        }

        std::vector<ReceiptT> receivedReceipts;
        std::map<ReceiptT, OfflineMessage> unAckedMessages;
    };

    ReceiptResolver<ReceiptNum> receiptResolver;
    ReceiptResolver<ExtendedReceiptNum> extendedReceiptResolver;
    std::vector<OfflineMessage> unsentMessages;
};
