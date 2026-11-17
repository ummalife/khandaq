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

#include "msgv3.h"

#include <QRandomGenerator>

#include <climits>
#include <cstring>

namespace MsgV3 {

QByteArray textOf(const uint8_t* data, size_t length, QByteArray* id, quint32* time)
{
    if (id) {
        id->clear();
    }
    if (time) {
        *time = 0;
    }
    if (data == nullptr || length == 0 || length > static_cast<size_t>(INT_MAX)) {
        return QByteArray();
    }

    const char* const bytes = reinterpret_cast<const char*>(data);
    const size_t trailer = static_cast<size_t>(trailerLength);
    if (length > trailer) {
        const size_t guard = length - trailer;
        if (data[guard] == 0 && data[guard + 1] == 0) {
            const uint8_t* const stamp = data + guard + guardLength + idLength;
            if (id) {
                *id = QByteArray(bytes + guard + guardLength, idLength);
            }
            if (time) {
                *time = (static_cast<quint32>(stamp[0]) << 24) | (static_cast<quint32>(stamp[1]) << 16)
                        | (static_cast<quint32>(stamp[2]) << 8) | static_cast<quint32>(stamp[3]);
            }
            return QByteArray(bytes, static_cast<int>(guard));
        }
    }
    return QByteArray(bytes, static_cast<int>(length));
}

QByteArray withTrailer(const QByteArray& text, const QByteArray& id, quint32 time)
{
    if (id.size() != idLength) {
        return text;
    }
    QByteArray bytes = text;
    bytes.reserve(text.size() + trailerLength);
    bytes.append(QByteArray(guardLength, '\0'));
    bytes.append(id);
    bytes.append(static_cast<char>((time >> 24) & 0xFF));
    bytes.append(static_cast<char>((time >> 16) & 0xFF));
    bytes.append(static_cast<char>((time >> 8) & 0xFF));
    bytes.append(static_cast<char>(time & 0xFF));
    return bytes;
}

QByteArray newId()
{
    quint32 words[idLength / sizeof(quint32)];
    QRandomGenerator::system()->fillRange(words);
    QByteArray id(idLength, '\0');
    std::memcpy(id.data(), words, sizeof(words));
    return id;
}

qint64 sentMsAfter(qint64 lastSentMs, qint64 nowMs)
{
    if (lastSentMs <= 0) {
        return nowMs;
    }
    const qint64 lastSeconds = lastSentMs / 1000;
    const qint64 nowSeconds = nowMs / 1000;
    if (lastSeconds - nowSeconds >= maxLeadSeconds || lastSeconds < nowSeconds) {
        return nowMs;
    }
    return (lastSeconds + 1) * 1000;
}

} // namespace MsgV3
