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

#include "airlocalstore.h"

#include "src/model/air/airconversation.h"

#include <QCryptographicHash>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>

#include <algorithm>

namespace Air {

namespace {
constexpr int storeVersion = 1;
const QString versionField = QStringLiteral("version");
const QString contactsField = QStringLiteral("contacts");
const QString hoursField = QStringLiteral("hours");
const QString mutedField = QStringLiteral("muted");
const QString verifiedField = QStringLiteral("verifiedAt");
const QString blockedField = QStringLiteral("blocked");
const QString requestsField = QStringLiteral("requests");
const QString invitesField = QStringLiteral("invites");
const QString keyField = QStringLiteral("key");
const QString messageField = QStringLiteral("message");
const QString dateField = QStringLiteral("date");
const QString inviterField = QStringLiteral("inviter");
const QString groupField = QStringLiteral("group");
const QString dataField = QStringLiteral("data");
const QString privateField = QStringLiteral("private");
const QString peerField = QStringLiteral("peer");
const QString oursField = QStringLiteral("ours");
const QString textField = QStringLiteral("text");
const QString entriesField = QStringLiteral("entries");
const QString editedField = QStringLiteral("edited");
const QString reactionsField = QStringLiteral("reactions");
const QString emojiField = QStringLiteral("emoji");
const QString actorsField = QStringLiteral("actors");
const QString wiresField = QStringLiteral("wires");
const QString idField = QStringLiteral("id");

/** An entry key: the hash of a conversation's key, "/", the hash of the entry. */
bool isEntryKey(const QString& key)
{
    const int hashLength = 32;
    if (key.size() != 2 * hashLength + 1 || key.at(hashLength) != QLatin1Char('/')) {
        return false;
    }
    for (int i = 0; i < key.size(); ++i) {
        const QChar character = key.at(i);
        const bool hex = (character >= QLatin1Char('0') && character <= QLatin1Char('9'))
                         || (character >= QLatin1Char('a') && character <= QLatin1Char('f'));
        if (i != hashLength && !hex) {
            return false;
        }
    }
    return true;
}

/** Drops the keys of one conversation, by the prefix entryKeyPrefix gives it. */
template <typename Value>
bool dropPrefix(QHash<QString, Value>& values, QStringList& order, const QString& prefix)
{
    const int before = order.size();
    order.erase(std::remove_if(order.begin(), order.end(),
                               [&prefix](const QString& key) { return key.startsWith(prefix); }),
                order.end());
    for (auto it = values.begin(); it != values.end();) {
        it = it.key().startsWith(prefix) ? values.erase(it) : std::next(it);
    }
    return order.size() != before;
}
} // namespace

// The initialisers in the class declare these; C++11 also wants a definition once a reference binds
// to one, as QCOMPARE's does, or a build without inlining fails to link.
constexpr qint64 LocalStore::keepSeconds;
constexpr int LocalStore::privateLimit;
constexpr int LocalStore::entryLimit;
constexpr int LocalStore::wireLimit;

bool LocalStore::PrivateLine::operator==(const PrivateLine& other) const
{
    return groupKey == other.groupKey && peerKey == other.peerKey && ours == other.ours
           && text == other.text && date == other.date;
}

bool LocalStore::WaitingRequest::operator==(const WaitingRequest& other) const
{
    return publicKey == other.publicKey && message == other.message && date == other.date;
}

bool LocalStore::WaitingInvite::isNull() const
{
    return id.isEmpty();
}

bool LocalStore::WaitingInvite::operator==(const WaitingInvite& other) const
{
    return id == other.id && inviterKey == other.inviterKey && groupName == other.groupName
           && data == other.data && date == other.date;
}

bool LocalStore::Contact::isEmpty() const
{
    return hours.isEmpty() && !muted && verifiedAt < 0;
}

QString LocalStore::hashedKey(const QString& publicKey)
{
    const QByteArray digest =
        QCryptographicHash::hash(publicKey.toUpper().toUtf8(), QCryptographicHash::Sha256);
    return QString::fromLatin1(digest.left(16).toHex());
}

void LocalStore::load(const QByteArray& data, const QDateTime& now)
{
    contacts.clear();
    since.clear();
    blocked.clear();
    waitingRequests.clear();
    waitingInvites.clear();
    privateMessages.clear();
    entries.clear();
    entryOrder.clear();
    wires.clear();
    wireOrder.clear();
    if (data.isEmpty()) {
        return;
    }
    const QJsonObject root = QJsonDocument::fromJson(data).object();
    if (root.value(versionField).toInt() != storeVersion) {
        return;
    }
    const QJsonObject stored = root.value(contactsField).toObject();
    for (auto it = stored.constBegin(); it != stored.constEnd(); ++it) {
        const QJsonObject fields = it.value().toObject();
        Contact contact;
        for (const QJsonValue hour : fields.value(hoursField).toArray()) {
            contact.hours.append(static_cast<qint64>(hour.toDouble()));
        }
        contact.muted = fields.value(mutedField).toBool();
        contact.verifiedAt = static_cast<qint64>(fields.value(verifiedField).toDouble(-1));
        if (!contact.isEmpty()) {
            contacts.insert(it.key(), contact);
        }
    }
    for (const QJsonValue key : root.value(blockedField).toArray()) {
        block(key.toString());
    }
    // Through the same doors as when they came: a key that is not one, or is blocked, stays out.
    for (const QJsonValue value : root.value(requestsField).toArray()) {
        const QJsonObject fields = value.toObject();
        addRequest(fields.value(keyField).toString(), fields.value(messageField).toString(),
                   QDateTime::fromMSecsSinceEpoch(
                       static_cast<qint64>(fields.value(dateField).toDouble())));
    }
    for (const QJsonValue value : root.value(invitesField).toArray()) {
        const QJsonObject fields = value.toObject();
        addInvite(fields.value(inviterField).toString(), fields.value(groupField).toString(),
                  QByteArray::fromBase64(fields.value(dataField).toString().toLatin1()),
                  QDateTime::fromMSecsSinceEpoch(
                      static_cast<qint64>(fields.value(dateField).toDouble())));
    }
    for (const QJsonValue value : root.value(privateField).toArray()) {
        const QJsonObject fields = value.toObject();
        PrivateLine line;
        line.groupKey = fields.value(groupField).toString();
        line.peerKey = fields.value(peerField).toString();
        line.ours = fields.value(oursField).toBool();
        line.text = fields.value(textField).toString();
        line.date = static_cast<qint64>(fields.value(dateField).toDouble());
        addPrivateLine(line);
    }
    // Oldest first, through setEntryMarks: what is not a key or not marks stays out, and so does
    // anything past the limits.
    for (const QJsonValue value : root.value(entriesField).toArray()) {
        const QJsonObject fields = value.toObject();
        EntryMarks marks;
        const QJsonValue edited = fields.value(editedField);
        if (edited.isDouble()) {
            marks.edited = true;
            marks.editedAt = static_cast<quint32>(edited.toDouble());
        }
        for (const QJsonValue reaction : fields.value(reactionsField).toArray()) {
            const QJsonObject parts = reaction.toObject();
            const QString emoji = parts.value(emojiField).toString();
            for (const QJsonValue actor : parts.value(actorsField).toArray()) {
                if (!emoji.isEmpty() && !actor.toString().isEmpty()) {
                    marks.react(actor.toString(), emoji, true);
                }
            }
        }
        setEntryMarks(fields.value(keyField).toString(), marks);
    }
    for (const QJsonValue value : root.value(wiresField).toArray()) {
        const QJsonObject fields = value.toObject();
        setWireId(fields.value(keyField).toString(),
                  QByteArray::fromHex(fields.value(idField).toString().toLatin1()));
    }
    pruneHours(now);
}

QByteArray LocalStore::save() const
{
    QJsonObject stored;
    for (auto it = contacts.constBegin(); it != contacts.constEnd(); ++it) {
        QJsonObject fields;
        QJsonArray hours;
        for (const qint64 hour : it.value().hours) {
            hours.append(static_cast<double>(hour));
        }
        fields.insert(hoursField, hours);
        fields.insert(mutedField, it.value().muted);
        if (it.value().verifiedAt >= 0) {
            fields.insert(verifiedField, static_cast<double>(it.value().verifiedAt));
        }
        stored.insert(it.key(), fields);
    }
    QJsonObject root;
    root.insert(versionField, storeVersion);
    root.insert(contactsField, stored);
    if (!blocked.isEmpty()) {
        root.insert(blockedField, QJsonArray::fromStringList(blocked));
    }
    if (!waitingRequests.isEmpty()) {
        QJsonArray requests;
        for (const WaitingRequest& request : waitingRequests) {
            QJsonObject fields;
            fields.insert(keyField, request.publicKey);
            fields.insert(messageField, request.message);
            fields.insert(dateField, static_cast<double>(request.date));
            requests.append(fields);
        }
        root.insert(requestsField, requests);
    }
    if (!waitingInvites.isEmpty()) {
        QJsonArray invites;
        for (const WaitingInvite& invite : waitingInvites) {
            QJsonObject fields;
            fields.insert(inviterField, invite.inviterKey);
            fields.insert(groupField, invite.groupName);
            fields.insert(dataField, QString::fromLatin1(invite.data.toBase64()));
            fields.insert(dateField, static_cast<double>(invite.date));
            invites.append(fields);
        }
        root.insert(invitesField, invites);
    }
    if (!privateMessages.isEmpty()) {
        QJsonArray lines;
        for (const PrivateLine& line : privateMessages) {
            QJsonObject fields;
            fields.insert(groupField, line.groupKey);
            fields.insert(peerField, line.peerKey);
            fields.insert(oursField, line.ours);
            fields.insert(textField, line.text);
            fields.insert(dateField, static_cast<double>(line.date));
            lines.append(fields);
        }
        root.insert(privateField, lines);
    }
    if (!entryOrder.isEmpty()) {
        QJsonArray entryList;
        for (const QString& key : entryOrder) {
            const EntryMarks marks = entries.value(key);
            QJsonObject fields;
            fields.insert(keyField, key);
            if (marks.edited) {
                fields.insert(editedField, static_cast<double>(marks.editedAt));
            }
            if (!marks.reactions.isEmpty()) {
                QJsonArray reactions;
                for (const Reaction& reaction : marks.reactions) {
                    QJsonObject parts;
                    parts.insert(emojiField, reaction.emoji);
                    parts.insert(actorsField, QJsonArray::fromStringList(reaction.actors));
                    reactions.append(parts);
                }
                fields.insert(reactionsField, reactions);
            }
            entryList.append(fields);
        }
        root.insert(entriesField, entryList);
    }
    if (!wireOrder.isEmpty()) {
        QJsonArray wireList;
        for (const QString& key : wireOrder) {
            QJsonObject fields;
            fields.insert(keyField, key);
            fields.insert(idField, QString::fromLatin1(wires.value(key).toHex()));
            wireList.append(fields);
        }
        root.insert(wiresField, wireList);
    }
    return QJsonDocument(root).toJson(QJsonDocument::Compact);
}

void LocalStore::store(const QString& key, const Contact& contact)
{
    if (contact.isEmpty()) {
        contacts.remove(key);
    } else {
        contacts.insert(key, contact);
    }
}

bool LocalStore::markOnline(const QString& publicKey, const QDateTime& at)
{
    const QString key = hashedKey(publicKey);
    const qint64 start = hourStart(at);
    Contact contact = contacts.value(key);
    if (contact.hours.contains(start)) {
        return false;
    }
    const qint64 oldest = at.toSecsSinceEpoch() - keepSeconds;
    contact.hours.erase(std::remove_if(contact.hours.begin(), contact.hours.end(),
                                       [oldest](qint64 hour) { return hour < oldest; }),
                        contact.hours.end());
    contact.hours.append(start);
    store(key, contact);
    return true;
}

QSet<qint64> LocalStore::hours(const QString& publicKey) const
{
    const QVector<qint64> list = contacts.value(hashedKey(publicKey)).hours;
    // A loop rather than the range constructor, which Qt 5.12 lacks.
    QSet<qint64> set;
    set.reserve(list.size());
    for (const qint64 hour : list) {
        set.insert(hour);
    }
    return set;
}

bool LocalStore::noteConnected(const QString& publicKey, const QDateTime& at)
{
    const QString key = hashedKey(publicKey);
    if (since.contains(key)) {
        return false;
    }
    since.insert(key, at);
    return true;
}

bool LocalStore::noteDisconnected(const QString& publicKey)
{
    return since.remove(hashedKey(publicKey)) > 0;
}

QDateTime LocalStore::onlineSince(const QString& publicKey) const
{
    return since.value(hashedKey(publicKey));
}

bool LocalStore::isMuted(const QString& publicKey) const
{
    return contacts.value(hashedKey(publicKey)).muted;
}

bool LocalStore::setMuted(bool muted, const QString& publicKey)
{
    const QString key = hashedKey(publicKey);
    Contact contact = contacts.value(key);
    if (contact.muted == muted) {
        return false;
    }
    contact.muted = muted;
    store(key, contact);
    return true;
}

QDateTime LocalStore::verifiedAt(const QString& publicKey) const
{
    const qint64 at = contacts.value(hashedKey(publicKey)).verifiedAt;
    return at < 0 ? QDateTime() : QDateTime::fromMSecsSinceEpoch(at);
}

bool LocalStore::setVerified(const QDateTime& at, const QString& publicKey)
{
    const QString key = hashedKey(publicKey);
    Contact contact = contacts.value(key);
    const qint64 value = at.isValid() ? at.toMSecsSinceEpoch() : -1;
    if (contact.verifiedAt == value) {
        return false;
    }
    contact.verifiedAt = value;
    store(key, contact);
    return true;
}

bool LocalStore::forget(const QString& publicKey)
{
    const QString key = hashedKey(publicKey);
    since.remove(key);
    bool changed = contacts.remove(key) > 0;
    const QString inviter = normalizedKey(publicKey);
    const int before = waitingInvites.size();
    waitingInvites.erase(std::remove_if(waitingInvites.begin(), waitingInvites.end(),
                                        [&inviter](const WaitingInvite& invite) {
                                            return invite.inviterKey == inviter;
                                        }),
                         waitingInvites.end());
    changed = changed || waitingInvites.size() != before;
    // The marks of the conversation go with it; dropPrefix runs for both lists in any case.
    const QString conversation = entryKeyPrefix(publicKey);
    changed = dropPrefix(entries, entryOrder, conversation) || changed;
    changed = dropPrefix(wires, wireOrder, conversation) || changed;
    return removeRequest(publicKey) || changed;
}

bool LocalStore::pruneHours(const QDateTime& now)
{
    const qint64 oldest = now.toSecsSinceEpoch() - keepSeconds;
    bool changed = false;
    for (auto it = contacts.begin(); it != contacts.end();) {
        QVector<qint64>& hours = it.value().hours;
        const int before = hours.size();
        hours.erase(std::remove_if(hours.begin(), hours.end(),
                                   [oldest](qint64 hour) { return hour < oldest; }),
                    hours.end());
        changed = changed || hours.size() != before;
        if (it.value().isEmpty()) {
            it = contacts.erase(it);
        } else {
            ++it;
        }
    }
    return changed;
}

QString LocalStore::normalizedKey(const QString& publicKey)
{
    return publicKey.trimmed().toUpper();
}

bool LocalStore::isPublicKey(const QString& key)
{
    if (key.size() != 64) {
        return false;
    }
    for (const QChar c : key) {
        if (!((c >= QLatin1Char('0') && c <= QLatin1Char('9'))
              || (c >= QLatin1Char('A') && c <= QLatin1Char('F')))) {
            return false;
        }
    }
    return true;
}

QStringList LocalStore::blockedKeys() const
{
    return blocked;
}

bool LocalStore::isBlocked(const QString& publicKey) const
{
    return blocked.contains(normalizedKey(publicKey));
}

bool LocalStore::block(const QString& publicKey)
{
    const QString key = normalizedKey(publicKey);
    if (!isPublicKey(key) || blocked.contains(key)) {
        return false;
    }
    blocked.append(key);
    removeRequest(key);
    return true;
}

bool LocalStore::unblock(const QString& publicKey)
{
    return blocked.removeOne(normalizedKey(publicKey));
}

QVector<int> LocalStore::blockedPositions(const QStringList& publicKeys) const
{
    QVector<int> positions;
    for (int i = 0; i < publicKeys.size(); ++i) {
        if (isBlocked(publicKeys.at(i))) {
            positions.append(i);
        }
    }
    return positions;
}

QVector<LocalStore::WaitingRequest> LocalStore::requests() const
{
    return waitingRequests;
}

bool LocalStore::hasRequest(const QString& publicKey) const
{
    const QString key = normalizedKey(publicKey);
    return std::any_of(waitingRequests.cbegin(), waitingRequests.cend(),
                       [&key](const WaitingRequest& request) { return request.publicKey == key; });
}

bool LocalStore::addRequest(const QString& publicKey, const QString& message, const QDateTime& at)
{
    const QString key = normalizedKey(publicKey);
    if (!isPublicKey(key) || blocked.contains(key)) {
        return false;
    }
    const auto same =
        std::find_if(waitingRequests.begin(), waitingRequests.end(),
                     [&key](const WaitingRequest& request) { return request.publicKey == key; });
    if (same != waitingRequests.end()) {
        if (same->message == message) {
            return false;
        }
        waitingRequests.erase(same);
    }
    WaitingRequest request;
    request.publicKey = key;
    request.message = message;
    request.date = at.toMSecsSinceEpoch();
    waitingRequests.append(request);
    return true;
}

bool LocalStore::removeRequest(const QString& publicKey)
{
    const QString key = normalizedKey(publicKey);
    const int before = waitingRequests.size();
    waitingRequests.erase(std::remove_if(waitingRequests.begin(), waitingRequests.end(),
                                         [&key](const WaitingRequest& request) {
                                             return request.publicKey == key;
                                         }),
                          waitingRequests.end());
    return waitingRequests.size() != before;
}

QVector<LocalStore::WaitingInvite> LocalStore::invites() const
{
    return waitingInvites;
}

LocalStore::WaitingInvite LocalStore::invite(const QString& id) const
{
    for (const WaitingInvite& invite : waitingInvites) {
        if (invite.id == id) {
            return invite;
        }
    }
    return WaitingInvite();
}

QString LocalStore::inviteId(const QString& inviterKey, const QByteArray& data)
{
    QCryptographicHash hash(QCryptographicHash::Sha256);
    hash.addData(inviterKey.toLatin1());
    hash.addData(data);
    return QString::fromLatin1(hash.result().left(16).toHex());
}

QString LocalStore::addInvite(const QString& inviterKey, const QString& groupName,
                              const QByteArray& data, const QDateTime& at)
{
    const QString key = normalizedKey(inviterKey);
    if (!isPublicKey(key) || data.isEmpty()) {
        return QString();
    }
    waitingInvites.erase(std::remove_if(waitingInvites.begin(), waitingInvites.end(),
                                        [&key, &groupName](const WaitingInvite& invite) {
                                            return invite.inviterKey == key
                                                   && invite.groupName == groupName;
                                        }),
                         waitingInvites.end());
    WaitingInvite invite;
    invite.id = inviteId(key, data);
    invite.inviterKey = key;
    invite.groupName = groupName;
    invite.data = data;
    invite.date = at.toMSecsSinceEpoch();
    // The same invitation again replaces itself too.
    removeInvite(invite.id);
    waitingInvites.append(invite);
    return invite.id;
}

bool LocalStore::removeInvite(const QString& id)
{
    const int before = waitingInvites.size();
    waitingInvites.erase(std::remove_if(waitingInvites.begin(), waitingInvites.end(),
                                        [&id](const WaitingInvite& invite) {
                                            return invite.id == id;
                                        }),
                         waitingInvites.end());
    return waitingInvites.size() != before;
}

QVector<LocalStore::PrivateLine> LocalStore::privateLines(const QString& groupKey,
                                                          const QString& peerKey) const
{
    const QString group = normalizedKey(groupKey);
    const QString peer = normalizedKey(peerKey);
    QVector<PrivateLine> lines;
    for (const PrivateLine& line : privateMessages) {
        if (line.groupKey == group && line.peerKey == peer) {
            lines.append(line);
        }
    }
    return lines;
}

bool LocalStore::addPrivateLine(const PrivateLine& line)
{
    PrivateLine kept = line;
    kept.groupKey = normalizedKey(line.groupKey);
    kept.peerKey = normalizedKey(line.peerKey);
    if (!isPublicKey(kept.groupKey) || !isPublicKey(kept.peerKey) || kept.text.isEmpty()) {
        return false;
    }
    privateMessages.append(kept);
    // The oldest of this conversation go once it holds more than the limit.
    int count = 0;
    for (const PrivateLine& existing : privateMessages) {
        count += existing.groupKey == kept.groupKey && existing.peerKey == kept.peerKey;
    }
    for (auto it = privateMessages.begin(); count > privateLimit && it != privateMessages.end();) {
        if (it->groupKey == kept.groupKey && it->peerKey == kept.peerKey) {
            it = privateMessages.erase(it);
            --count;
        } else {
            ++it;
        }
    }
    return true;
}

bool LocalStore::forgetGroup(const QString& groupKey)
{
    const QString group = normalizedKey(groupKey);
    const int before = privateMessages.size();
    privateMessages.erase(std::remove_if(privateMessages.begin(), privateMessages.end(),
                                         [&group](const PrivateLine& line) {
                                             return line.groupKey == group;
                                         }),
                          privateMessages.end());
    const bool changed = privateMessages.size() != before;
    return forget(group) || changed;
}

EntryMarks LocalStore::entryMarks(const QString& entryKey) const
{
    return entries.value(entryKey);
}

bool LocalStore::setEntryMarks(const QString& entryKey, const EntryMarks& marks)
{
    if (!isEntryKey(entryKey)) {
        return false;
    }
    if (marks.isEmpty()) {
        entryOrder.removeOne(entryKey);
        return entries.remove(entryKey) > 0;
    }
    if (entries.contains(entryKey) && entries.value(entryKey) == marks
        && entryOrder.last() == entryKey) {
        return false;
    }
    entryOrder.removeOne(entryKey);
    entryOrder.append(entryKey);
    entries.insert(entryKey, marks);
    while (entryOrder.size() > entryLimit) {
        entries.remove(entryOrder.takeFirst());
    }
    return true;
}

QByteArray LocalStore::wireId(const QString& entryKey) const
{
    return wires.value(entryKey);
}

bool LocalStore::setWireId(const QString& entryKey, const QByteArray& wireId)
{
    if (!isEntryKey(entryKey)) {
        return false;
    }
    if (wireId.isEmpty()) {
        wireOrder.removeOne(entryKey);
        return wires.remove(entryKey) > 0;
    }
    if (wireId.size() != 4 || wires.value(entryKey) == wireId) {
        return false;
    }
    wireOrder.removeOne(entryKey);
    wireOrder.append(entryKey);
    wires.insert(entryKey, wireId);
    while (wireOrder.size() > wireLimit) {
        wires.remove(wireOrder.takeFirst());
    }
    return true;
}

bool LocalStore::forgetEntries()
{
    const bool changed = !entryOrder.isEmpty() || !wireOrder.isEmpty();
    entries.clear();
    entryOrder.clear();
    wires.clear();
    wireOrder.clear();
    return changed;
}

} // namespace Air
