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

} // namespace Air
