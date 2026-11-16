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

#include "airgroupfilerepair.h"

namespace Air {

// C++11 wants a definition of what qMin and qBound take by reference.
constexpr int FileArrival::maxRequests;
constexpr qint64 FileArrival::requestGapMs;
constexpr qint64 FileArrival::stallMs;
constexpr qint64 FileArrival::longestStallMs;
constexpr qint64 FileArrival::silenceLimitMs;
constexpr qint64 FileArrival::awayLimitMs;
constexpr int ResendBudget::maxAnswers;
constexpr qint64 ResendBudget::cooldownMs;
constexpr int ResendBudget::chunkShare;
constexpr int ResendBudget::maxKept;

namespace {
uint32_t readU32(const uint8_t* p)
{
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16)
           | (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}
} // namespace

namespace FileRequest {
Wanted parse(const uint8_t* data, size_t length, int totalChunks)
{
    Wanted wanted;
    const size_t header = static_cast<size_t>(headerBytes);
    if (data == nullptr || length < header || totalChunks <= 0) {
        return wanted;
    }
    if (length == header) {
        wanted.kind = Wanted::Kind::Whole;
        return wanted;
    }
    const uint32_t count = readU32(data + 40);
    const size_t body = length - header;
    const uint32_t chunks = static_cast<uint32_t>(totalChunks);
    if (count >= 1 && count <= static_cast<uint32_t>(maxListed) && body == 4 * size_t{count}) {
        QVector<bool> named(totalChunks, false);
        for (uint32_t k = 0; k < count; ++k) {
            const uint32_t index = readU32(data + header + 4 * size_t{k});
            if (index < chunks) {
                named[static_cast<int>(index)] = true;
            }
        }
        for (int i = 0; i < totalChunks; ++i) {
            if (named.at(i)) {
                wanted.chunks.append(i);
            }
        }
    } else if (count == chunks && body >= (size_t{count} + 7) / 8) {
        for (int i = 0; i < totalChunks; ++i) {
            if ((data[header + static_cast<size_t>(i >> 3)] & (1u << (i & 7))) != 0) {
                wanted.chunks.append(i);
            }
        }
    } else {
        return wanted;
    }
    wanted.kind = wanted.chunks.isEmpty() ? Wanted::Kind::Invalid : Wanted::Kind::Chunks;
    return wanted;
}

QVector<int> missing(const QVector<bool>& received)
{
    QVector<int> chunks;
    for (int i = 0; i < received.size() && chunks.size() < maxListed; ++i) {
        if (!received.at(i)) {
            chunks.append(i);
        }
    }
    return chunks;
}
} // namespace FileRequest

FileArrival::FileArrival(qint64 nowMs)
    : heardMs(nowMs)
    , progressMs(nowMs)
{
}

void FileArrival::heard(qint64 nowMs, bool newChunk)
{
    heardMs = qMax(heardMs, nowMs);
    if (!newChunk) {
        return;
    }
    // The gap since the chunk before tells the sender's pace, unless the file asked meanwhile:
    // then it was a stall, which says nothing of the pace.
    if (anyChunk && askedMs < progressMs) {
        gapMs = qMax(qint64{0}, nowMs - progressMs);
    }
    anyChunk = true;
    progressMs = qMax(progressMs, nowMs);
}

FileArrival::Step FileArrival::look(qint64 nowMs, bool senderHere)
{
    if (givenUp) {
        return Step::GiveUp;
    }
    if (!senderHere) {
        awayMs = nowMs;
        if (nowMs - heardMs >= awayLimitMs) {
            givenUp = true;
            return Step::GiveUp;
        }
        return Step::Wait;
    }
    // Its silence runs from when it was last heard, or from when its sender was last away.
    if (nowMs - qMax(heardMs, awayMs) >= silenceLimitMs) {
        givenUp = true;
        return Step::GiveUp;
    }
    if (nowMs - progressMs < askAfterMs() || asked >= maxRequests
        || (askedMs >= 0 && nowMs - askedMs < requestGapMs)) {
        return Step::Wait;
    }
    ++asked;
    askedMs = nowMs;
    return Step::Ask;
}

int FileArrival::requests() const
{
    return asked;
}

bool FileArrival::gaveUp() const
{
    return givenUp;
}

qint64 FileArrival::askAfterMs() const
{
    return qBound(stallMs, 2 * gapMs, longestStallMs);
}

int ResendBudget::take(const QByteArray& fileId, const QByteArray& member, int chunks,
                       int totalChunks, qint64 nowMs)
{
    if (chunks <= 0 || totalChunks <= 0) {
        return 0;
    }
    const QByteArray key = fileId + member;
    auto it = entries.find(key);
    if (it == entries.end()) {
        while (entries.size() >= maxKept) {
            auto oldest = entries.begin();
            for (auto entry = entries.begin(); entry != entries.end(); ++entry) {
                if (entry->order < oldest->order) {
                    oldest = entry;
                }
            }
            entries.erase(oldest);
        }
        Entry entry;
        entry.fileId = fileId;
        entry.order = ++made;
        it = entries.insert(key, entry);
    }
    Entry& entry = it.value();
    if (entry.answers >= maxAnswers || (entry.answers > 0 && nowMs - entry.lastMs < cooldownMs)) {
        return 0;
    }
    const qint64 left = static_cast<qint64>(chunkShare) * totalChunks - entry.chunks;
    const int granted = static_cast<int>(qMin<qint64>(chunks, qMax(qint64{0}, left)));
    if (granted <= 0) {
        return 0;
    }
    ++entry.answers;
    entry.lastMs = nowMs;
    entry.chunks += granted;
    return granted;
}

void ResendBudget::forget(const QByteArray& fileId)
{
    for (auto it = entries.begin(); it != entries.end();) {
        if (it->fileId == fileId) {
            it = entries.erase(it);
        } else {
            ++it;
        }
    }
}

int ResendBudget::kept() const
{
    return entries.size();
}

} // namespace Air
