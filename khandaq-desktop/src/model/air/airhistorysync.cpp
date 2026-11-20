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

#include "airhistorysync.h"

#include <QCryptographicHash>

#include <cstring>
#include <iterator>

namespace Air {

namespace {
const uint8_t ngcMagic[] = {0x66, 0x77, 0x88, 0x11, 0x34, 0x35};
constexpr uint8_t historyVersion = 0x01;
constexpr uint8_t historyRequestId = 0x01;
constexpr uint8_t syncTextId = 0x02;
constexpr int ngcHeader = 8;
// 0x02: the header, the message id, the author, the time and the name, then the words.
constexpr int idAt = ngcHeader;
constexpr int authorAt = idAt + 4;
constexpr int keyLength = 32;
constexpr int timeAt = authorAt + keyLength;
constexpr int nameLength = 25;
constexpr int syncTextHeader = timeAt + 4 + nameLength;
// The longest words the senders put in, and the receivers take (the 0x03 files have theirs).
constexpr int maxSyncTextBytes = 37000;
constexpr qint64 acceptedPastSecs = 200 * 60;
constexpr qint64 acceptedFutureSecs = 5 * 60;
// Above so many members asked, those that may be asked again are forgotten.
constexpr int pacedKept = 4096;

quint32 bigEndian(const uint8_t* bytes)
{
    return (static_cast<quint32>(bytes[0]) << 24) | (static_cast<quint32>(bytes[1]) << 16)
           | (static_cast<quint32>(bytes[2]) << 8) | static_cast<quint32>(bytes[3]);
}

QByteArray bigEndianBytes(quint32 value)
{
    QByteArray bytes(4, '\0');
    bytes[0] = static_cast<char>((value >> 24) & 0xFF);
    bytes[1] = static_cast<char>((value >> 16) & 0xFF);
    bytes[2] = static_cast<char>((value >> 8) & 0xFF);
    bytes[3] = static_cast<char>(value & 0xFF);
    return bytes;
}
} // namespace

bool isSyncTextPacket(const uint8_t* data, size_t length)
{
    return length >= static_cast<size_t>(ngcHeader)
           && std::memcmp(data, ngcMagic, sizeof(ngcMagic)) == 0 && data[6] == historyVersion
           && data[7] == syncTextId;
}

bool readSyncText(const QByteArray& packet, SyncedText* text)
{
    const int length = packet.size();
    const uint8_t* const bytes = reinterpret_cast<const uint8_t*>(packet.constData());
    if (length <= syncTextHeader || length > syncTextHeader + maxSyncTextBytes
        || !isSyncTextPacket(bytes, static_cast<size_t>(length))) {
        return false;
    }
    SyncedText read;
    read.messageId = bigEndian(bytes + idAt);
    read.author = packet.mid(authorAt, keyLength);
    read.time = bigEndian(bytes + timeAt);
    // The name at timeAt + 4 is not read: who wrote it is told by the key, and the name it shows
    // by the members of the group as this device knows them.
    read.text = QString::fromUtf8(packet.constData() + syncTextHeader, length - syncTextHeader);
    if (read.messageId == 0 || read.author.count('\0') == keyLength || read.text.isEmpty()) {
        return false;
    }
    *text = read;
    return true;
}

bool syncTimeAccepted(quint32 time, qint64 nowSecs)
{
    const qint64 at = static_cast<qint64>(time);
    return at >= nowSecs - acceptedPastSecs && at <= nowSecs + acceptedFutureSecs;
}

qint64 syncedTextTimeMs(const SyncedText& text)
{
    return static_cast<qint64>(text.time) * 1000 + static_cast<qint64>(text.messageId % 1000);
}

bool groupEntryNotifies(bool relayed, bool mentionsUs, bool alwaysNotify)
{
    return !relayed && (mentionsUs || alwaysNotify);
}

QByteArray historyRequestPacket()
{
    QByteArray packet(reinterpret_cast<const char*>(ngcMagic), static_cast<int>(sizeof(ngcMagic)));
    packet.append(static_cast<char>(historyVersion));
    packet.append(static_cast<char>(historyRequestId));
    return packet;
}

constexpr int SyncedTextLedger::limit;

bool SyncedTextLedger::seen(const QByteArray& groupChatId, const QByteArray& author,
                            quint32 messageId, const QString& text)
{
    const QByteArray key =
        groupChatId + author + bigEndianBytes(messageId)
        + QCryptographicHash::hash(text.toUtf8(), QCryptographicHash::Sha256).left(16);
    if (keys.contains(key)) {
        return true;
    }
    keys.insert(key);
    order.append(key);
    while (order.size() > limit) {
        keys.remove(order.takeFirst());
    }
    return false;
}

constexpr int SyncedTextBudget::maxTexts;
constexpr qint64 SyncedTextBudget::maxBytes;

bool SyncedTextBudget::take(const QByteArray& groupChatId, int textBytes)
{
    Spent& group = spent[groupChatId];
    if (textBytes < 0 || group.texts >= maxTexts || group.bytes + textBytes > maxBytes) {
        ++group.refused;
        return false;
    }
    ++group.texts;
    group.bytes += textBytes;
    return true;
}

int SyncedTextBudget::refused(const QByteArray& groupChatId) const
{
    return spent.value(groupChatId).refused;
}

constexpr int HistoryRequestPace::minDelayMs;
constexpr int HistoryRequestPace::maxDelayMs;
constexpr qint64 HistoryRequestPace::gapMs;

bool HistoryRequestPace::mayAsk(const QByteArray& groupChatId, const QByteArray& peerKey,
                                qint64 nowMs) const
{
    const auto it = lastAsked.constFind(groupChatId + peerKey);
    return it == lastAsked.constEnd() || nowMs - it.value() >= gapMs;
}

void HistoryRequestPace::asked(const QByteArray& groupChatId, const QByteArray& peerKey,
                               qint64 nowMs)
{
    if (lastAsked.size() >= pacedKept) {
        for (auto it = lastAsked.begin(); it != lastAsked.end();) {
            it = nowMs - it.value() >= gapMs ? lastAsked.erase(it) : std::next(it);
        }
    }
    lastAsked.insert(groupChatId + peerKey, nowMs);
}

} // namespace Air
