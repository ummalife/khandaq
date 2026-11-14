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

#pragma once

#include <QByteArray>
#include <QHash>
#include <QtGlobal>

#include <cstddef>
#include <cstdint>

namespace Air {

// QA live F11: the leave notice. toxcore sends the same QUIT when a member leaves a group and when its
// app simply closes (tox_kill sends it for every group, with no part message), so Android dropped QA
// Windows from a group when its window closed, and iOS said it had left. A Khandaq client that leaves
// says so first: to the whole group, lossless, right before tox_group_leave, and never when the app
// closes, rejoins a group or disconnects from one. The standard NGC header and the next free Khandaq id
// (0x41-0x43 are the edits, deletes and reactions): magic(6) | 0x01 | 0x44, nothing after it.
// The same packet on Android (GroupPeerKeys) and on iOS (OCTSubmanagerGroupsImpl).
QByteArray groupLeaveNoticePacket();

// A later version may add bytes after the header; they are not read.
bool isGroupLeaveNotice(const uint8_t* data, size_t length);

// QA live F11: the members of each group that said they are leaving. Such a member is gone at
// once: over a TCP relay the QUIT that follows is often lost, and toxcore drops the member only
// when it times out, about a minute later. The member stays hidden while its notice waits for the
// exit (windowMs) and after that exit, until the same key joins the group again: toxcore lists a
// member that exited once more for a while, unconfirmed and nameless. A notice that no exit
// follows lapses, and the member shows again if toxcore still has it. Android (GroupPeerKeys) and
// iOS (OCTSubmanagerGroupsImpl) keep the same rule.
class GroupLeaveNotices
{
public:
    static constexpr qint64 windowMs = 5 * 60 * 1000;

    // The member with this key, peer id now, said it is leaving.
    void note(int group, const QByteArray& publicKey, uint32_t peerId, qint64 nowMs);
    // The key joined the group: it is a member again.
    void joined(int group, const QByteArray& publicKey);
    // A peer exited. True when it is the exit a notice waited for.
    bool exited(int group, uint32_t peerId, qint64 nowMs);
    bool hides(int group, const QByteArray& publicKey, qint64 nowMs) const;
    // Whether any member of the group is hidden or waited for: the peer list is filtered only then.
    bool hidesAny(int group) const;
    // We left the group, or it is gone.
    void forgetGroup(int group);

private:
    struct Notice
    {
        uint32_t peerId = 0;
        qint64 atMs = 0;
        bool exited = false;
    };

    static bool waits(const Notice& notice, qint64 nowMs);

    QHash<int, QHash<QByteArray, Notice>> groups;
};

} // namespace Air
