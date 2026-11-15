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

#include "airgroupfilequeue.h"

#include "src/core/khandaqlimits.h"

namespace Air {

// C++11 wants a definition of what qMin takes by reference.
constexpr qint64 GroupFileQueue::aloneLimitMs;
constexpr qint64 UploadPace::bytesPerSecond;
constexpr qint64 UploadPace::burstMs;
constexpr qint64 UploadPace::burstBytes;

namespace {
constexpr qint64 payload = static_cast<qint64>(NGC_CHUNK_PAYLOAD_MAX);
// Longer pauses fill the credit all the same; the bound keeps the product in range.
constexpr qint64 longestPauseMs = 60 * 60 * 1000;
} // namespace

int GroupFileQueue::chunkCount(qint64 size)
{
    if (size <= 0) {
        return 0;
    }
    return static_cast<int>((size + payload - 1) / payload);
}

qint64 GroupFileQueue::chunkOffset(int chunk)
{
    return static_cast<qint64>(chunk) * payload;
}

qint64 GroupFileQueue::chunkBytes(qint64 size, int chunk)
{
    if (chunk < 0) {
        return 0;
    }
    return qBound(qint64{0}, size - chunkOffset(chunk), payload);
}

void GroupFileQueue::add(int group, const QByteArray& fileId, qint64 size)
{
    if (contains(fileId)) {
        return;
    }
    File file;
    file.fileId = fileId;
    file.group = group;
    file.size = size;
    file.chunks = chunkCount(size);
    files.append(file);
    if (!turns.contains(group)) {
        costs.insert(group, leastCost(group));
        turns.append(group);
    }
}

void GroupFileQueue::setMembers(int group, int members_, qint64 nowMs)
{
    if (!turns.contains(group)) {
        return;
    }
    members.insert(group, members_);
    if (members_ <= 0) {
        if (!aloneSince.contains(group)) {
            aloneSince.insert(group, nowMs);
        }
        return;
    }
    if (aloneSince.remove(group) == 0) {
        return;
    }
    const int index = firstOf(group);
    if (index >= 0) {
        files[index].next = -1;
    }
    // Level with the groups that went on meanwhile, not ahead of them by what they sent.
    costs[group] = qMax(costs.value(group), leastCost(group));
}

bool GroupFileQueue::waits(int group) const
{
    return aloneSince.contains(group);
}

QList<QByteArray> GroupFileQueue::waitedTooLong(qint64 nowMs) const
{
    QList<QByteArray> fileIds;
    for (const File& file : files) {
        const auto since = aloneSince.constFind(file.group);
        if (since != aloneSince.constEnd() && nowMs - since.value() >= aloneLimitMs) {
            fileIds.append(file.fileId);
        }
    }
    return fileIds;
}

QList<int> GroupFileQueue::groups() const
{
    return turns;
}

bool GroupFileQueue::next(Packet* packet) const
{
    int chosen = -1;
    for (const int group : turns) {
        if (!waits(group) && (chosen < 0 || costs.value(group) < costs.value(chosen))) {
            chosen = group;
        }
    }
    const int index = chosen < 0 ? -1 : firstOf(chosen);
    if (index < 0) {
        return false;
    }
    const File& file = files.at(index);
    packet->fileId = file.fileId;
    packet->group = file.group;
    packet->chunk = file.next;
    return true;
}

qint64 GroupFileQueue::cost(const Packet& packet) const
{
    const int index = indexOf(packet.fileId);
    const qint64 size = index >= 0 ? files.at(index).size : 0;
    const qint64 bytes = packet.chunk < 0
                             ? static_cast<qint64>(NGC_FILE_BEGIN_SIZE)
                             : static_cast<qint64>(NGC_FILE_CHUNK_HEADER_SIZE)
                                   + chunkBytes(size, packet.chunk);
    return UploadPace::cost(bytes, members.value(packet.group, 1));
}

bool GroupFileQueue::sent(const Packet& packet)
{
    const int index = firstOf(packet.group);
    if (index < 0) {
        return false;
    }
    File& file = files[index];
    if (file.fileId != packet.fileId || file.next != packet.chunk) {
        return false;
    }
    costs[packet.group] += cost(packet);
    ++file.next;
    const bool done = file.next >= file.chunks;
    if (done) {
        files.removeAt(index);
    }
    // Of two groups that cost the same, the other one goes first.
    turns.removeAll(packet.group);
    turns.append(packet.group);
    dropTurnIfDone(packet.group);
    return done;
}

void GroupFileQueue::remove(const QByteArray& fileId)
{
    const int index = indexOf(fileId);
    if (index < 0) {
        return;
    }
    const int group = files.at(index).group;
    files.removeAt(index);
    dropTurnIfDone(group);
}

bool GroupFileQueue::isFirst(const QByteArray& fileId) const
{
    const int index = indexOf(fileId);
    return index >= 0 && firstOf(files.at(index).group) == index;
}

qint64 GroupFileQueue::bytesSent(const QByteArray& fileId) const
{
    const int index = indexOf(fileId);
    if (index < 0) {
        return 0;
    }
    const File& file = files.at(index);
    return qMin(file.size, chunkOffset(qMax(0, file.next)));
}

bool GroupFileQueue::contains(const QByteArray& fileId) const
{
    return indexOf(fileId) >= 0;
}

bool GroupFileQueue::isEmpty() const
{
    return files.isEmpty();
}

int GroupFileQueue::indexOf(const QByteArray& fileId) const
{
    for (int i = 0; i < files.size(); ++i) {
        if (files.at(i).fileId == fileId) {
            return i;
        }
    }
    return -1;
}

int GroupFileQueue::firstOf(int group) const
{
    for (int i = 0; i < files.size(); ++i) {
        if (files.at(i).group == group) {
            return i;
        }
    }
    return -1;
}

void GroupFileQueue::dropTurnIfDone(int group)
{
    if (firstOf(group) < 0) {
        turns.removeAll(group);
        costs.remove(group);
        members.remove(group);
        aloneSince.remove(group);
    }
}

/** The least cost of the other groups that send now; while all of them wait, of those. */
qint64 GroupFileQueue::leastCost(int except) const
{
    bool found = false;
    qint64 least = 0;
    for (const bool waiting : {false, true}) {
        for (const int group : turns) {
            if (group == except || waits(group) != waiting) {
                continue;
            }
            const qint64 spent = costs.value(group);
            least = found ? qMin(least, spent) : spent;
            found = true;
        }
        if (found) {
            break;
        }
    }
    return least;
}

qint64 UploadPace::cost(qint64 packetBytes, int members)
{
    return packetBytes * qMax(1, members);
}

bool UploadPace::ready(qint64 nowMs)
{
    if (lastMs < 0) {
        credit = burstBytes;
    } else if (nowMs > lastMs) {
        const qint64 pauseMs = qMin(nowMs - lastMs, longestPauseMs);
        credit = qMin(burstBytes, credit + pauseMs * bytesPerSecond / 1000);
    }
    lastMs = qMax(lastMs, nowMs);
    return credit > 0;
}

void UploadPace::spend(qint64 bytes)
{
    credit -= bytes;
}

} // namespace Air
