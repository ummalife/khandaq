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

#include <QStringList>

#include <cstring>

namespace Air {

namespace {
const uint8_t leaveNotice[] = {0x66, 0x77, 0x88, 0x11, 0x34, 0x35, 0x01, 0x44};
// TOX_GROUP_CHAT_ID_SIZE.
constexpr int chatIdSize = 32;
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

void PendingGroupLeaves::record(int group, const QByteArray& chatId, const QByteArray& partMessage,
                                qint64 nowMs)
{
    for (auto it = leaves.begin(); it != leaves.end();) {
        if (it->group == group || it->chatId == chatId) {
            it = leaves.erase(it);
        } else {
            ++it;
        }
    }
    Leave leave;
    leave.group = group;
    leave.chatId = chatId;
    leave.partMessage = partMessage;
    leave.dueMs = nowMs + delayMs;
    leaves.append(leave);
}

QVector<PendingGroupLeaves::Leave> PendingGroupLeaves::takeDue(qint64 nowMs)
{
    QVector<Leave> due;
    for (auto it = leaves.begin(); it != leaves.end();) {
        if (nowMs >= it->dueMs) {
            due.append(*it);
            it = leaves.erase(it);
        } else {
            ++it;
        }
    }
    return due;
}

QVector<PendingGroupLeaves::Leave> PendingGroupLeaves::takeAll()
{
    QVector<Leave> all;
    all.swap(leaves);
    return all;
}

bool PendingGroupLeaves::cancel(const QByteArray& chatId, Leave* cancelled)
{
    for (auto it = leaves.begin(); it != leaves.end(); ++it) {
        if (it->chatId != chatId) {
            continue;
        }
        if (cancelled) {
            *cancelled = *it;
        }
        leaves.erase(it);
        return true;
    }
    return false;
}

bool PendingGroupLeaves::containsGroup(int group) const
{
    for (const Leave& leave : leaves) {
        if (leave.group == group) {
            return true;
        }
    }
    return false;
}

int PendingGroupLeaves::groupOf(const QByteArray& chatId) const
{
    for (const Leave& leave : leaves) {
        if (leave.chatId == chatId) {
            return leave.group;
        }
    }
    return -1;
}

QList<QByteArray> PendingGroupLeaves::chatIds() const
{
    QList<QByteArray> ids;
    for (const Leave& leave : leaves) {
        ids.append(leave.chatId);
    }
    return ids;
}

QString PendingGroupLeaves::store(const QList<QByteArray>& chatIds)
{
    QStringList lines;
    for (const QByteArray& chatId : chatIds) {
        lines.append(QString::fromLatin1(chatId.toHex()).toUpper());
    }
    return lines.join(QLatin1Char('\n'));
}

QList<QByteArray> PendingGroupLeaves::parseStore(const QString& stored)
{
    QList<QByteArray> chatIds;
    for (const QString& line : stored.split(QLatin1Char('\n'))) {
        const QString hex = line.trimmed();
        const QByteArray chatId = QByteArray::fromHex(hex.toLatin1());
        // fromHex() skips what is no hex digit: only a line that comes back the same is a chat id.
        if (chatId.size() != chatIdSize
            || QString::fromLatin1(chatId.toHex()).compare(hex, Qt::CaseInsensitive) != 0
            || chatIds.contains(chatId)) {
            continue;
        }
        chatIds.append(chatId);
    }
    return chatIds;
}

} // namespace Air
