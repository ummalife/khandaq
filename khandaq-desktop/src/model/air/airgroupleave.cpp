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

#include "airgroupleave.h"

#include <cstring>

namespace Air {

namespace {
const uint8_t leaveNotice[] = {0x66, 0x77, 0x88, 0x11, 0x34, 0x35, 0x01, 0x44};
} // namespace

QByteArray groupLeaveNoticePacket()
{
    return QByteArray(reinterpret_cast<const char*>(leaveNotice), static_cast<int>(sizeof(leaveNotice)));
}

bool isGroupLeaveNotice(const uint8_t* data, size_t length)
{
    return data != nullptr && length >= sizeof(leaveNotice)
           && std::memcmp(data, leaveNotice, sizeof(leaveNotice)) == 0;
}

} // namespace Air
