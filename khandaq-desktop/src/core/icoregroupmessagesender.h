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

#include <QString>

#include <cstdint>

class ToxPk;

class ICoreGroupMessageSender
{
public:
    ICoreGroupMessageSender() = default;
    virtual ~ICoreGroupMessageSender();
    ICoreGroupMessageSender(const ICoreGroupMessageSender&) = default;
    ICoreGroupMessageSender& operator=(const ICoreGroupMessageSender&) = default;
    ICoreGroupMessageSender(ICoreGroupMessageSender&&) = default;
    ICoreGroupMessageSender& operator=(ICoreGroupMessageSender&&) = default;

    /** What became of a text handed to a group. */
    enum class SendResult
    {
        /** toxcore took it for the members it confirmed. */
        Sent,
        /** Nobody would get it now (Air::groupTakesText): it may go once the group is ready. */
        NotReady,
        /** It cannot go as it is: too long, not allowed, refused by toxcore. */
        Failed,
    };

    /**
     * Sends a message or an action to a group. wireId is set to the id toxcore gave a text that
     * went, which the edits, deletions and reactions of the members point at, and to 0 otherwise.
     */
    virtual SendResult sendGroupAction(int groupId, const QString& message, uint32_t& wireId) = 0;
    virtual SendResult sendGroupMessage(int groupId, const QString& message,
                                        uint32_t& wireId) = 0;

    /**
     * Tells the author of a group message, privately, that it arrived here. `messageId` is the id
     * toxcore handed the receive callback. The Android and iOS clients send the same receipt: the
     * author's "delivered" mark and its history-sync retries both wait for one.
     */
    virtual void sendGroupDeliveryReceipt(int groupId, const ToxPk& author, uint32_t messageId) = 0;

    /**
     * KHANDAQ (26.11.2026): the key the group knows us by, which a mention of us names
     * (Air::mentionsKey); empty when it is not known. None by default.
     */
    virtual ToxPk getGroupSelfPublicKey(int groupId) const;
};
