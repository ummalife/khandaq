/*
    Copyright © 2026 by The Khandaq Project

    This file is part of Khandaq, a Qt-based graphical interface for Tox.

    Khandaq is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Khandaq is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Khandaq.  If not, see <http://www.gnu.org/licenses/>.
*/

#include "aircommunity.h"

#include "src/model/air/airlocalstore.h"

#include <QString>

namespace Air {
namespace Community {

QByteArray chatId()
{
    return QByteArray::fromHex(QByteArray(chatIdHex));
}

bool isCommunity(const QByteArray& groupChatId)
{
    return groupChatId == chatId();
}

bool shouldAutoJoin(bool connected, bool autoJoinDone, bool left, bool alreadyInGroup)
{
    return connected && !autoJoinDone && !left && !alreadyInGroup;
}

bool autoJoin(LocalStore& store, bool connected, bool alreadyInGroup,
              const std::function<bool()>& join)
{
    if (!shouldAutoJoin(connected, store.communityAutoJoinDone(), store.communityLeft(),
                        alreadyInGroup)) {
        return false;
    }
    if (!join()) {
        return false;
    }
    // The group mutes as it comes: a person added without asking hears nothing of it until they
    // turn it on. The key of a group's mute is its chat id, as the dossier's switch keeps it.
    store.setMuted(true, QString::fromLatin1(chatIdHex));
    store.setCommunityAutoJoinDone();
    return true;
}

bool noteGroupLeft(LocalStore& store, const QByteArray& groupChatId)
{
    return isCommunity(groupChatId) && store.setCommunityLeft();
}

} // namespace Community
} // namespace Air
