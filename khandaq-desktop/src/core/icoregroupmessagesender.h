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

class ICoreGroupMessageSender
{
public:
    ICoreGroupMessageSender() = default;
    virtual ~ICoreGroupMessageSender();
    ICoreGroupMessageSender(const ICoreGroupMessageSender&) = default;
    ICoreGroupMessageSender& operator=(const ICoreGroupMessageSender&) = default;
    ICoreGroupMessageSender(ICoreGroupMessageSender&&) = default;
    ICoreGroupMessageSender& operator=(ICoreGroupMessageSender&&) = default;

    /**
     * Sends a message or an action to a group. Returns the id toxcore gave it, which the edits,
     * deletions and reactions of the members point at, or 0 when it did not go.
     */
    virtual uint32_t sendGroupAction(int groupId, const QString& message) = 0;
    virtual uint32_t sendGroupMessage(int groupId, const QString& message) = 0;
};
