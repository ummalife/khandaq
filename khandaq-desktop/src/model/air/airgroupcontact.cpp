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

#include "airgroupcontact.h"

#include "src/model/air/airmodel.h"

#include <QRandomGenerator>

#include <algorithm>
#include <cstring>
#include <iterator>

namespace Air {

namespace {
const uint8_t packetMagic[6] = {0x66, 0x77, 0x88, 0x11, 0x34, 0x35};
constexpr uint8_t packetVersion = 0x01;
constexpr size_t headerSize = sizeof(packetMagic) + 2;

const QString markerPrefix = QStringLiteral("[KQ|c|");
constexpr int markerHexLength = 2 * static_cast<int>(groupContactNonceSize);

/** 38 bytes whose last two are the checksum toxcore puts there. */
bool addressHolds(const QByteArray& address)
{
    return address.size() == static_cast<int>(groupContactAddressSize)
           && toxIdCheckMatches(QString::fromLatin1(address.toHex().toUpper()));
}

bool isHexDigit(QChar character)
{
    const ushort code = character.unicode();
    return (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')
           || (code >= 'A' && code <= 'F');
}
} // namespace

constexpr qint64 ContactOfferGate::offerGapMs;
constexpr int ContactOfferGate::memberLimit;

QByteArray groupContactPacket(GroupContactPacket::Kind kind, const QByteArray& address,
                              const QByteArray& nonce)
{
    if (!addressHolds(address) || nonce.size() != static_cast<int>(groupContactNonceSize)) {
        return QByteArray();
    }
    QByteArray packet;
    packet.reserve(static_cast<int>(groupContactPacketSize));
    packet.append(reinterpret_cast<const char*>(packetMagic), sizeof(packetMagic));
    packet.append(static_cast<char>(packetVersion));
    packet.append(static_cast<char>(kind == GroupContactPacket::Kind::Offer ? groupContactOfferId
                                                                            : groupContactAcceptId));
    packet.append(address);
    packet.append(nonce);
    return packet;
}

bool isGroupContactPacket(const uint8_t* data, size_t length)
{
    return data && length >= headerSize && std::memcmp(data, packetMagic, sizeof(packetMagic)) == 0
           && data[6] == packetVersion
           && (data[7] == groupContactOfferId || data[7] == groupContactAcceptId);
}

bool readGroupContactPacket(const uint8_t* data, size_t length, GroupContactPacket* packet)
{
    if (!packet || length != groupContactPacketSize || !isGroupContactPacket(data, length)) {
        return false;
    }
    const char* const bytes = reinterpret_cast<const char*>(data);
    const QByteArray address(bytes + headerSize, static_cast<int>(groupContactAddressSize));
    if (!addressHolds(address)) {
        return false;
    }
    packet->kind = data[7] == groupContactOfferId ? GroupContactPacket::Kind::Offer
                                                  : GroupContactPacket::Kind::Accept;
    packet->address = address;
    packet->nonce = QByteArray(bytes + headerSize + groupContactAddressSize,
                               static_cast<int>(groupContactNonceSize));
    return true;
}

QByteArray newContactOfferNonce()
{
    quint32 words[groupContactNonceSize / sizeof(quint32)];
    QRandomGenerator::system()->fillRange(words);
    return QByteArray(reinterpret_cast<const char*>(words), sizeof(words));
}

QString contactRequestMarker(const QByteArray& nonce)
{
    return markerPrefix + QString::fromLatin1(nonce.toHex().toLower()) + QLatin1Char(']');
}

QString contactRequestMessage(const QByteArray& nonce, const QString& words)
{
    return contactRequestMarker(nonce) + QLatin1Char('\n') + words;
}

bool readContactRequestMarker(const QString& message, QByteArray* nonce, QString* words)
{
    const int markerEnd = markerPrefix.size() + markerHexLength;
    const bool marked = message.startsWith(markerPrefix) && message.size() > markerEnd
                        && message.at(markerEnd) == QLatin1Char(']')
                        && std::all_of(message.cbegin() + markerPrefix.size(),
                                       message.cbegin() + markerEnd, isHexDigit);
    if (!marked) {
        if (words) {
            *words = message;
        }
        return false;
    }
    if (nonce) {
        *nonce = QByteArray::fromHex(message.mid(markerPrefix.size(), markerHexLength).toLatin1());
    }
    if (words) {
        QString rest = message.mid(markerEnd + 1);
        if (rest.startsWith(QLatin1String("\r\n"))) {
            rest.remove(0, 2);
        } else if (rest.startsWith(QLatin1Char('\n'))) {
            rest.remove(0, 1);
        }
        *words = rest.trimmed();
    }
    return true;
}

bool ContactOfferGate::admit(const QString& member, qint64 nowMs)
{
    const auto last = lastTaken.constFind(member);
    if (last != lastTaken.constEnd() && nowMs - last.value() < offerGapMs) {
        return false;
    }
    if (lastTaken.size() >= memberLimit && !lastTaken.contains(member)) {
        // Only the members whose gap still runs need remembering.
        for (auto it = lastTaken.begin(); it != lastTaken.end();) {
            it = nowMs - it.value() >= offerGapMs ? lastTaken.erase(it) : std::next(it);
        }
        if (lastTaken.size() >= memberLimit) {
            return false;
        }
    }
    lastTaken.insert(member, nowMs);
    return true;
}

} // namespace Air
