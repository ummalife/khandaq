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

bool GroupLeaveNotices::waits(const Notice& notice, qint64 nowMs)
{
    return !notice.exited && nowMs >= notice.atMs && nowMs - notice.atMs <= windowMs;
}

void GroupLeaveNotices::note(int group, const QByteArray& publicKey, uint32_t peerId, qint64 nowMs)
{
    if (publicKey.isEmpty()) {
        return;
    }
    QHash<QByteArray, Notice>& notices = groups[group];
    // Notices that no exit followed have lapsed.
    for (auto it = notices.begin(); it != notices.end();) {
        if (!it->exited && !waits(*it, nowMs)) {
            it = notices.erase(it);
        } else {
            ++it;
        }
    }
    Notice notice;
    notice.peerId = peerId;
    notice.atMs = nowMs;
    notices.insert(publicKey, notice);
}

void GroupLeaveNotices::joined(int group, const QByteArray& publicKey)
{
    const auto found = groups.find(group);
    if (found == groups.end()) {
        return;
    }
    found->remove(publicKey);
    if (found->isEmpty()) {
        groups.erase(found);
    }
}

bool GroupLeaveNotices::exited(int group, uint32_t peerId, qint64 nowMs)
{
    const auto found = groups.find(group);
    if (found == groups.end()) {
        return false;
    }
    for (auto it = found->begin(); it != found->end(); ++it) {
        if (it->exited || it->peerId != peerId) {
            continue;
        }
        if (waits(*it, nowMs)) {
            it->exited = true;
            return true;
        }
        // The notice had lapsed: this is an exit like any other.
        found->erase(it);
        if (found->isEmpty()) {
            groups.erase(found);
        }
        return false;
    }
    return false;
}

bool GroupLeaveNotices::hides(int group, const QByteArray& publicKey, qint64 nowMs) const
{
    const auto found = groups.constFind(group);
    if (found == groups.constEnd()) {
        return false;
    }
    const auto notice = found->constFind(publicKey);
    return notice != found->constEnd() && (notice->exited || waits(*notice, nowMs));
}

bool GroupLeaveNotices::hidesAny(int group) const
{
    return groups.contains(group);
}

void GroupLeaveNotices::forgetGroup(int group)
{
    groups.remove(group);
}

} // namespace Air
