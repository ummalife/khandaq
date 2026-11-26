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

#include "airgroupselfname.h"

#include "src/model/air/airyou.h"

#include <QJsonDocument>
#include <QJsonObject>

namespace Air {

namespace {
/** The size of a chat id (TOX_GROUP_CHAT_ID_SIZE), kept here so that the model needs no toxcore. */
constexpr int chatIdBytes = 32;
} // namespace

QString GroupSelfNames::nameFor(const QByteArray& chatId, const QString& profileName) const
{
    return names.value(chatId, profileName);
}

bool GroupSelfNames::hasOwn(const QByteArray& chatId) const
{
    return names.contains(chatId);
}

bool GroupSelfNames::choose(const QByteArray& chatId, const QString& name,
                            const QString& profileName)
{
    if (chatId.size() != chatIdBytes || name.isEmpty()) {
        return false;
    }
    if (name == profileName) {
        return forget(chatId);
    }
    if (names.value(chatId) == name) {
        return false;
    }
    names.insert(chatId, name);
    return true;
}

bool GroupSelfNames::forget(const QByteArray& chatId)
{
    return names.remove(chatId) > 0;
}

bool GroupSelfNames::isEmpty() const
{
    return names.isEmpty();
}

QString GroupSelfNames::store() const
{
    if (names.isEmpty()) {
        return QString();
    }
    QJsonObject object;
    for (auto entry = names.constBegin(); entry != names.constEnd(); ++entry) {
        object.insert(QString::fromLatin1(entry.key().toHex()), entry.value());
    }
    return QString::fromUtf8(QJsonDocument(object).toJson(QJsonDocument::Compact));
}

GroupSelfNames GroupSelfNames::parseStore(const QString& stored)
{
    GroupSelfNames parsed;
    const QJsonObject object = QJsonDocument::fromJson(stored.toUtf8()).object();
    for (auto entry = object.constBegin(); entry != object.constEnd(); ++entry) {
        const QByteArray chatId = QByteArray::fromHex(entry.key().toLatin1());
        const QString name = entry.value().toString();
        if (chatId.size() != chatIdBytes || chatId.toHex() != entry.key().toLatin1().toLower()
            || name.isEmpty()) {
            continue;
        }
        parsed.names.insert(chatId, name);
    }
    return parsed;
}

bool needsGroupSelfName(const QString& current, const QString& wanted)
{
    const int bytes = wanted.toUtf8().size();
    return bytes > 0 && bytes <= nameLimitBytes && current != wanted;
}

} // namespace Air
