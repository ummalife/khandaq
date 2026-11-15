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
constexpr qint64 GroupFileQueue::urgentMs;
constexpr qint64 GroupFileQueue::windowMs;
constexpr qint64 GroupFileQueue::restartMs;
constexpr qint64 GroupFileQueue::lostLookbackMs;
constexpr int GroupFileQueue::wholeResendChunks;
constexpr qint64 UploadPace::bytesPerSecond;
constexpr qint64 UploadPace::burstMs;
constexpr qint64 UploadPace::burstBytes;

namespace {
constexpr qint64 payload = static_cast<qint64>(NGC_CHUNK_PAYLOAD_MAX);
// Longer pauses fill the credit all the same; the bound keeps the product in range.
constexpr qint64 longestPauseMs = 60 * 60 * 1000;
// What was next of a file to go, kept once a second for twice the look back of a time-out.
constexpr qint64 trailStepMs = 1000;
constexpr int trailLength = static_cast<int>(2 * GroupFileQueue::lostLookbackMs / trailStepMs);
// A member that timed out this many times in a row without getting further gets no more of a file.
constexpr int fruitlessLimit = 2;
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
    if (indexOf(fileId) >= 0) {
        return;
    }
    // One whose last chunk went is sent anew.
    remove(fileId);
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

QList<QByteArray> GroupFileQueue::fitGoingFiles()
{
    const qint64 limit = UploadPace::bytesPerSecond * restartMs / 1000;
    QList<QByteArray> restarted;
    for (;;) {
        qint64 going = 0;
        int last = -1;
        int count = 0;
        for (const int group : turns) {
            if (!goes(group)) {
                continue;
            }
            const int index = firstOf(group);
            going += chunkCost(group);
            ++count;
            if (last < 0 || files.at(index).begun > files.at(last).begun) {
                last = index;
            }
        }
        if (going <= limit || count <= 1) {
            return restarted;
        }
        // As for a group whose member is back (setMembers): it goes again from its BEGIN.
        files[last].next = -1;
        restarted.append(files.at(last).fileId);
    }
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

QList<QByteArray> GroupFileQueue::memberTimedOut(int group, const QByteArray& member, int others,
                                                 qint64 nowMs)
{
    QList<QByteArray> hopeless;
    for (QList<File>* list : {&files, &lingering}) {
        for (File& file : *list) {
            if (file.group != group || file.begun == 0 || file.latecomers.contains(member)) {
                // Nothing of it went yet, or nothing to the member.
                continue;
            }
            // Counted from its last packet, as toxcore counts from the oldest one the member did
            // not acknowledge: a file that paused meanwhile went no further.
            const int from = firstLacked(file, file.lastMs - lostLookbackMs);
            auto it = file.absences.find(member);
            if (it == file.absences.end()) {
                it = file.absences.insert(member, Absence());
                it->from = from;
            } else if (it->resumed) {
                // It went to the member again from where the member was: the member kept that.
                it->fruitless = from > it->from ? 0 : it->fruitless + 1;
                it->from = qMax(it->from, from);
            }
            it->awayMs = nowMs;
            it->resumed = false;
            file.leftAlone = others <= 0;
            if (it->fruitless >= fruitlessLimit && others <= 0) {
                hopeless.append(file.fileId);
            }
        }
    }
    return hopeless;
}

QList<QByteArray> GroupFileQueue::memberJoined(int group, const QByteArray& member)
{
    QList<QByteArray> again;
    for (File& file : files) {
        if (file.group != group) {
            continue;
        }
        if (takeBack(file, member)) {
            again.append(file.fileId);
        } else if (file.begun != 0 && !file.absences.contains(member)
                   && !file.latecomers.contains(member)) {
            file.latecomers.append(member);
        }
    }
    // A file whose last chunk went goes again behind the files of its group that wait.
    for (int i = 0; i < lingering.size();) {
        if (lingering.at(i).group != group || !takeBack(lingering[i], member)) {
            ++i;
            continue;
        }
        again.append(lingering.at(i).fileId);
        files.append(lingering.takeAt(i));
        if (!turns.contains(group)) {
            costs.insert(group, leastCost(group));
            turns.append(group);
        }
    }
    return again;
}

QList<QByteArray> GroupFileQueue::delivered(qint64 nowMs, QList<QByteArray>* failed)
{
    QList<QByteArray> done;
    for (int i = 0; i < lingering.size();) {
        const File& file = lingering.at(i);
        bool waitsForMember = false;
        for (const Absence& absence : file.absences) {
            waitsForMember = waitsForMember
                             || (absence.awayMs >= 0 && absence.fruitless < fruitlessLimit
                                 && nowMs - absence.awayMs < aloneLimitMs);
        }
        if (waitsForMember || nowMs - file.lastMs < lostLookbackMs) {
            ++i;
            continue;
        }
        if (file.leftAlone) {
            failed->append(file.fileId);
        } else {
            done.append(file.fileId);
        }
        lingering.removeAt(i);
    }
    return done;
}

bool GroupFileQueue::next(Packet* packet, qint64 nowMs) const
{
    // A file whose last packet went urgentMs ago goes first, the one that waited the longest.
    int chosen = -1;
    for (const int group : turns) {
        if (!goes(group)) {
            continue;
        }
        const qint64 lastMs = files.at(firstOf(group)).lastMs;
        if (nowMs - lastMs >= urgentMs
            && (chosen < 0 || lastMs < files.at(firstOf(chosen)).lastMs)) {
            chosen = group;
        }
    }
    if (chosen < 0) {
        // Else the group that has cost the least, of those whose file goes or may begin: one
        // begins while the files that go leave room for its chunks, or when none goes.
        qint64 going = 0;
        for (const int group : turns) {
            going += goes(group) ? chunkCost(group) : 0;
        }
        const qint64 room = UploadPace::bytesPerSecond * windowMs / 1000;
        for (const int group : turns) {
            if (waits(group) || (chosen >= 0 && costs.value(group) >= costs.value(chosen))) {
                continue;
            }
            if (!goes(group) && going > 0 && going + chunkCost(group) > room) {
                continue;
            }
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

bool GroupFileQueue::sent(const Packet& packet, qint64 nowMs)
{
    const int index = firstOf(packet.group);
    if (index < 0) {
        return false;
    }
    File& file = files[index];
    if (file.fileId != packet.fileId || file.next != packet.chunk) {
        return false;
    }
    if (packet.chunk < 0) {
        // A file begins level with the files that go, not behind them by the time it waited for
        // room among them (next) or for a member (setMembers): it would take that time from them.
        costs[packet.group] = qMax(costs.value(packet.group), leastGoingCost(packet.group));
        file.begun = ++begins;
    }
    costs[packet.group] += cost(packet);
    file.lastMs = nowMs;
    if (packet.chunk < 0) {
        // Chunk 0, or where a member back from a time-out was (memberJoined).
        file.next = qMax(0, file.resumeAt);
        file.resumeAt = -1;
        file.trail.clear();
        file.trailBase = file.next;
        file.latecomers.clear();
        for (Absence& absence : file.absences) {
            absence.resumed = absence.awayMs < 0 && absence.fruitless < fruitlessLimit;
        }
    } else {
        ++file.next;
    }
    if (file.trail.isEmpty() || nowMs - file.trail.constLast().first >= trailStepMs) {
        file.trail.append(qMakePair(nowMs, file.next));
        if (file.trail.size() > trailLength) {
            // Its time is longer ago than any look back.
            file.trailBase = file.trail.takeFirst().second;
        }
    }
    const bool done = file.next >= file.chunks;
    if (done) {
        file.leftAlone = false;
        lingering.append(files.takeAt(index));
    }
    // Of two groups that cost the same, the other one goes first.
    turns.removeAll(packet.group);
    turns.append(packet.group);
    dropTurnIfDone(packet.group);
    return done;
}

void GroupFileQueue::remove(const QByteArray& fileId)
{
    for (int i = 0; i < lingering.size(); ++i) {
        if (lingering.at(i).fileId == fileId) {
            lingering.removeAt(i);
            return;
        }
    }
    const int index = indexOf(fileId);
    if (index < 0) {
        return;
    }
    const int group = files.at(index).group;
    files.removeAt(index);
    dropTurnIfDone(group);
}

qint64 GroupFileQueue::bytesSent(const QByteArray& fileId) const
{
    for (const File& file : lingering) {
        if (file.fileId == fileId) {
            return file.size;
        }
    }
    const int index = indexOf(fileId);
    if (index < 0) {
        return 0;
    }
    const File& file = files.at(index);
    // A file that goes again from its BEGIN goes on from where its BEGIN leads.
    const int from = file.next >= 0 ? file.next : qMax(0, file.resumeAt);
    return qMin(file.size, chunkOffset(from));
}

bool GroupFileQueue::contains(const QByteArray& fileId) const
{
    return lingers(fileId) || indexOf(fileId) >= 0;
}

bool GroupFileQueue::lingers(const QByteArray& fileId) const
{
    for (const File& file : lingering) {
        if (file.fileId == fileId) {
            return true;
        }
    }
    return false;
}

bool GroupFileQueue::sendsInto(int group) const
{
    if (firstOf(group) >= 0) {
        return true;
    }
    for (const File& file : lingering) {
        if (file.group == group) {
            return true;
        }
    }
    return false;
}

bool GroupFileQueue::isEmpty() const
{
    return files.isEmpty();
}

int GroupFileQueue::firstLacked(const File& file, qint64 atMs)
{
    // What was next to go then had not gone yet.
    int from = file.trailBase;
    for (const auto& mark : file.trail) {
        if (mark.first > atMs) {
            break;
        }
        from = mark.second;
    }
    return from;
}

bool GroupFileQueue::takeBack(File& file, const QByteArray& member)
{
    const auto it = file.absences.find(member);
    if (it == file.absences.end() || it->awayMs < 0) {
        return false;
    }
    it->awayMs = -1;
    if (it->fruitless >= fruitlessLimit) {
        // It goes on for the others.
        return false;
    }
    int from = file.chunks <= wholeResendChunks ? 0 : it->from;
    if (file.next >= 0 && file.next < file.chunks) {
        // The members that stayed lack what is next.
        from = qMin(from, file.next);
    } else if (file.next < 0 && file.resumeAt >= 0) {
        // Another member is back before the BEGIN went: from the first chunk either lacks.
        from = qMin(from, file.resumeAt);
    }
    file.resumeAt = from;
    file.next = -1;
    return true;
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

bool GroupFileQueue::goes(int group) const
{
    const int index = firstOf(group);
    return index >= 0 && files.at(index).next >= 0 && !waits(group);
}

qint64 GroupFileQueue::chunkCost(int group) const
{
    return UploadPace::cost(static_cast<qint64>(NGC_MAX_PACKET_SIZE), members.value(group, 1));
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

/** The least cost of the other groups whose file goes; 0 while none does. */
qint64 GroupFileQueue::leastGoingCost(int except) const
{
    bool found = false;
    qint64 least = 0;
    for (const int group : turns) {
        if (group == except || !goes(group)) {
            continue;
        }
        const qint64 spent = costs.value(group);
        least = found ? qMin(least, spent) : spent;
        found = true;
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

qint64 UploadPace::msUntilReady(qint64 nowMs) const
{
    if (lastMs < 0 || credit > 0) {
        return 0;
    }
    // The shortest pause whose share of the rate, rounded down as ready() rounds it, makes the
    // credit positive.
    const qint64 pauseMs = ((1 - credit) * 1000 + bytesPerSecond - 1) / bytesPerSecond;
    return qMax(qint64{0}, lastMs + pauseMs - nowMs);
}

} // namespace Air
