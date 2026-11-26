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
#include "src/model/air/airgroupcontact.h"
#include "src/model/air/airmodel.h"

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
const QString askedField = QStringLiteral("askedAt");
const QString blockedField = QStringLiteral("blocked");
const QString requestsField = QStringLiteral("requests");
const QString invitesField = QStringLiteral("invites");
// KHANDAQ (0.2.60): our invitations waiting for an offline contact. Optional: a store without them
// reads as before, and a desktop of 0.2.59 skips them.
const QString outgoingInvitesField = QStringLiteral("outgoingInvites");
const QString friendField = QStringLiteral("friend");
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
// KHANDAQ (0.2.57): read receipts. Optional fields of a contact: a store without them reads as
// before, and a desktop of 0.2.56 skips them.
const QString seenUpToField = QStringLiteral("seenUpTo");
const QString readField = QStringLiteral("read");
const QString anchorField = QStringLiteral("anchor");
const QString pendingField = QStringLiteral("pending");
constexpr int anchorText = 1;
constexpr int anchorFile = 2;
// KHANDAQ (0.2.57): Khandaq Community. Optional, like the fields of read receipts.
const QString communityField = QStringLiteral("community");
const QString autoJoinDoneField = QStringLiteral("autoJoinDone");
const QString leftField = QStringLiteral("left");
const QString soundRestoredField = QStringLiteral("soundRestored");
// KHANDAQ (0.2.57, review): relayed files, optional too: the hash of each group, then its entries.
const QString relayedField = QStringLiteral("relayed");
// KHANDAQ (26.11.2026): a member of a group to contacts and the private messages in the feed of a
// group (DESIGN-group-contacts-private-mentions.md). Optional: a store without them reads as
// before, and an older desktop skips them (and the offers of members among its requests).
const QString addressField = QStringLiteral("address");
const QString groupKeyField = QStringLiteral("groupKey");
const QString groupNameField = QStringLiteral("groupName");
const QString memberKeyField = QStringLiteral("memberKey");
const QString memberNameField = QStringLiteral("memberName");
const QString nonceField = QStringLiteral("nonce");
const QString contactField = QStringLiteral("contact");
const QString contactOffersField = QStringLiteral("contactOffers");
const QString memberContactsField = QStringLiteral("memberContacts");
const QString privateEntriesField = QStringLiteral("privateEntries");

/** A whole Tox ID: 76 hexadecimal characters in upper case whose checksum holds. */
bool isAddress(const QString& address)
{
    return address.size() == toxAddressHexLength && address == address.toUpper()
           && toxIdCheckMatches(address);
}

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

/** The hash of the conversation an entry key belongs to: what comes before its "/". */
QString conversationOf(const QString& entryKey)
{
    return entryKey.left(entryKey.indexOf(QLatin1Char('/')));
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
constexpr int LocalStore::relayedLimit;
constexpr qint64 LocalStore::outgoingInviteKeepMs;
constexpr int LocalStore::outgoingInviteLimit;
constexpr int LocalStore::contactOfferLimit;
constexpr int LocalStore::memberContactLimit;
constexpr int LocalStore::privateEntryLimit;

bool LocalStore::PrivateLine::operator==(const PrivateLine& other) const
{
    return groupKey == other.groupKey && peerKey == other.peerKey && ours == other.ours
           && text == other.text && date == other.date;
}

bool LocalStore::WaitingRequest::fromGroup() const
{
    return !address.isEmpty();
}

bool LocalStore::WaitingRequest::operator==(const WaitingRequest& other) const
{
    return publicKey == other.publicKey && message == other.message && date == other.date
           && address == other.address && groupKey == other.groupKey
           && groupName == other.groupName && memberKey == other.memberKey
           && memberName == other.memberName && nonce == other.nonce;
}

bool LocalStore::ContactOffer::isNull() const
{
    return nonce.isEmpty();
}

bool LocalStore::ContactOffer::operator==(const ContactOffer& other) const
{
    return groupKey == other.groupKey && memberKey == other.memberKey
           && memberName == other.memberName && nonce == other.nonce && date == other.date;
}

bool LocalStore::MemberContact::isNull() const
{
    return contactKey.isEmpty();
}

bool LocalStore::MemberContact::operator==(const MemberContact& other) const
{
    return groupKey == other.groupKey && memberKey == other.memberKey
           && contactKey == other.contactKey && address == other.address
           && memberName == other.memberName;
}

bool LocalStore::WaitingInvite::isNull() const
{
    return id.isEmpty();
}

bool LocalStore::OutgoingInvite::operator==(const OutgoingInvite& other) const
{
    return friendKey == other.friendKey && groupKey == other.groupKey && date == other.date;
}

bool LocalStore::WaitingInvite::operator==(const WaitingInvite& other) const
{
    return id == other.id && inviterKey == other.inviterKey && groupName == other.groupName
           && data == other.data && date == other.date;
}

bool LocalStore::Contact::isEmpty() const
{
    return hours.isEmpty() && !muted && verifiedAt < 0 && askedAt < 0 && seenUpTo < 0
           && readAnchor.isEmpty();
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
    outgoingQueue.clear();
    privateMessages.clear();
    entries.clear();
    entryOrder.clear();
    wires.clear();
    wireOrder.clear();
    relayedKeys.clear();
    relayedOrder.clear();
    offers.clear();
    memberContacts.clear();
    privateEntries.clear();
    privateEntryOrder.clear();
    communityJoined = false;
    communityGone = false;
    communitySoundOn = false;
    if (data.isEmpty()) {
        return;
    }
    const QJsonObject root = QJsonDocument::fromJson(data).object();
    if (root.value(versionField).toInt() != storeVersion) {
        return;
    }
    const QJsonObject community = root.value(communityField).toObject();
    communityJoined = community.value(autoJoinDoneField).toBool();
    communityGone = community.value(leftField).toBool();
    communitySoundOn = community.value(soundRestoredField).toBool();
    const QJsonObject stored = root.value(contactsField).toObject();
    for (auto it = stored.constBegin(); it != stored.constEnd(); ++it) {
        const QJsonObject fields = it.value().toObject();
        Contact contact;
        for (const QJsonValue hour : fields.value(hoursField).toArray()) {
            contact.hours.append(static_cast<qint64>(hour.toDouble()));
        }
        contact.muted = fields.value(mutedField).toBool();
        contact.verifiedAt = static_cast<qint64>(fields.value(verifiedField).toDouble(-1));
        contact.askedAt = static_cast<qint64>(fields.value(askedField).toDouble(-1));
        contact.seenUpTo = static_cast<qint64>(fields.value(seenUpToField).toDouble(-1));
        const QJsonObject read = fields.value(readField).toObject();
        const int anchor = read.value(anchorField).toInt();
        const QByteArray readId = QByteArray::fromHex(read.value(idField).toString().toLatin1());
        if ((anchor == anchorText || anchor == anchorFile) && !readId.isEmpty()) {
            contact.readAnchor.anchor =
                anchor == anchorFile ? MessageEffect::Anchor::File : MessageEffect::Anchor::Text;
            contact.readAnchor.id = readId;
            contact.readAnchor.timeMs = static_cast<qint64>(read.value(dateField).toDouble());
            contact.readPending = read.value(pendingField).toBool();
        }
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
        const QDateTime at = QDateTime::fromMSecsSinceEpoch(
            static_cast<qint64>(fields.value(dateField).toDouble()));
        if (fields.contains(addressField)) {
            WaitingRequest offer;
            offer.address = fields.value(addressField).toString();
            offer.groupKey = fields.value(groupKeyField).toString();
            offer.groupName = fields.value(groupNameField).toString();
            offer.memberKey = fields.value(memberKeyField).toString();
            offer.memberName = fields.value(memberNameField).toString();
            offer.nonce = QByteArray::fromHex(fields.value(nonceField).toString().toLatin1());
            addGroupRequest(offer, at);
            continue;
        }
        addRequest(fields.value(keyField).toString(), fields.value(messageField).toString(), at);
    }
    for (const QJsonValue value : root.value(invitesField).toArray()) {
        const QJsonObject fields = value.toObject();
        addInvite(fields.value(inviterField).toString(), fields.value(groupField).toString(),
                  QByteArray::fromBase64(fields.value(dataField).toString().toLatin1()),
                  QDateTime::fromMSecsSinceEpoch(
                      static_cast<qint64>(fields.value(dateField).toDouble())));
    }
    for (const QJsonValue value : root.value(outgoingInvitesField).toArray()) {
        const QJsonObject fields = value.toObject();
        keepOutgoingInvite(fields.value(friendField).toString(), fields.value(groupField).toString(),
                           QDateTime::fromMSecsSinceEpoch(
                               static_cast<qint64>(fields.value(dateField).toDouble())),
                           now);
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
    const QJsonObject relayed = root.value(relayedField).toObject();
    for (auto it = relayed.constBegin(); it != relayed.constEnd(); ++it) {
        for (const QJsonValue entry : it.value().toArray()) {
            markRelayed(it.key() + QLatin1Char('/') + entry.toString());
        }
    }
    for (const QJsonValue value : root.value(contactOffersField).toArray()) {
        const QJsonObject fields = value.toObject();
        ContactOffer offer;
        offer.groupKey = fields.value(groupKeyField).toString();
        offer.memberKey = fields.value(memberKeyField).toString();
        offer.memberName = fields.value(memberNameField).toString();
        offer.nonce = QByteArray::fromHex(fields.value(nonceField).toString().toLatin1());
        offer.date = static_cast<qint64>(fields.value(dateField).toDouble());
        keepContactOffer(offer, now);
    }
    for (const QJsonValue value : root.value(memberContactsField).toArray()) {
        const QJsonObject fields = value.toObject();
        MemberContact member;
        member.groupKey = fields.value(groupKeyField).toString();
        member.memberKey = fields.value(memberKeyField).toString();
        member.contactKey = fields.value(contactField).toString();
        member.address = fields.value(addressField).toString();
        member.memberName = fields.value(memberNameField).toString();
        keepMemberContact(member);
    }
    for (const QJsonValue value : root.value(privateEntriesField).toArray()) {
        const QJsonObject fields = value.toObject();
        PrivateEntry entry;
        entry.peerKey = fields.value(peerField).toString();
        entry.peerName = fields.value(memberNameField).toString();
        entry.ours = fields.value(oursField).toBool();
        markPrivateEntry(fields.value(keyField).toString(), entry);
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
        if (it.value().askedAt >= 0) {
            fields.insert(askedField, static_cast<double>(it.value().askedAt));
        }
        if (it.value().seenUpTo >= 0) {
            fields.insert(seenUpToField, static_cast<double>(it.value().seenUpTo));
        }
        const SeenAnchor& anchor = it.value().readAnchor;
        if (!anchor.isEmpty()) {
            QJsonObject read;
            read.insert(anchorField,
                        anchor.anchor == MessageEffect::Anchor::File ? anchorFile : anchorText);
            read.insert(idField, QString::fromLatin1(anchor.id.toHex()));
            read.insert(dateField, static_cast<double>(anchor.timeMs));
            read.insert(pendingField, it.value().readPending);
            fields.insert(readField, read);
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
            if (request.fromGroup()) {
                fields.insert(addressField, request.address);
                fields.insert(groupKeyField, request.groupKey);
                fields.insert(groupNameField, request.groupName);
                fields.insert(memberKeyField, request.memberKey);
                fields.insert(memberNameField, request.memberName);
                fields.insert(nonceField, QString::fromLatin1(request.nonce.toHex()));
            }
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
    if (!outgoingQueue.isEmpty()) {
        QJsonArray outgoing;
        for (const OutgoingInvite& invite : outgoingQueue) {
            QJsonObject fields;
            fields.insert(friendField, invite.friendKey);
            fields.insert(groupField, invite.groupKey);
            fields.insert(dateField, static_cast<double>(invite.date));
            outgoing.append(fields);
        }
        root.insert(outgoingInvitesField, outgoing);
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
    if (!relayedOrder.isEmpty()) {
        QJsonObject relayed;
        for (auto it = relayedOrder.constBegin(); it != relayedOrder.constEnd(); ++it) {
            QJsonArray entryList;
            for (const QString& entryKey : it.value()) {
                entryList.append(entryKey.mid(it.key().size() + 1));
            }
            relayed.insert(it.key(), entryList);
        }
        root.insert(relayedField, relayed);
    }
    if (!offers.isEmpty()) {
        QJsonArray offerList;
        for (const ContactOffer& offer : offers) {
            QJsonObject fields;
            fields.insert(groupKeyField, offer.groupKey);
            fields.insert(memberKeyField, offer.memberKey);
            fields.insert(memberNameField, offer.memberName);
            fields.insert(nonceField, QString::fromLatin1(offer.nonce.toHex()));
            fields.insert(dateField, static_cast<double>(offer.date));
            offerList.append(fields);
        }
        root.insert(contactOffersField, offerList);
    }
    if (!memberContacts.isEmpty()) {
        QJsonArray memberList;
        for (const MemberContact& member : memberContacts) {
            QJsonObject fields;
            fields.insert(groupKeyField, member.groupKey);
            fields.insert(memberKeyField, member.memberKey);
            fields.insert(contactField, member.contactKey);
            if (!member.address.isEmpty()) {
                fields.insert(addressField, member.address);
            }
            fields.insert(memberNameField, member.memberName);
            memberList.append(fields);
        }
        root.insert(memberContactsField, memberList);
    }
    if (!privateEntryOrder.isEmpty()) {
        QJsonArray entryList;
        for (const QString& key : privateEntryOrder) {
            const PrivateEntry entry = privateEntries.value(key);
            QJsonObject fields;
            fields.insert(keyField, key);
            fields.insert(peerField, entry.peerKey);
            fields.insert(memberNameField, entry.peerName);
            fields.insert(oursField, entry.ours);
            entryList.append(fields);
        }
        root.insert(privateEntriesField, entryList);
    }
    if (communityJoined || communityGone || communitySoundOn) {
        QJsonObject community;
        community.insert(autoJoinDoneField, communityJoined);
        community.insert(leftField, communityGone);
        if (communitySoundOn) {
            community.insert(soundRestoredField, true);
        }
        root.insert(communityField, community);
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
    // Our invitations for the contact, or to the group, go too (forgetGroup comes here).
    const int outgoingBefore = outgoingQueue.size();
    outgoingQueue.erase(std::remove_if(outgoingQueue.begin(), outgoingQueue.end(),
                                       [&inviter](const OutgoingInvite& invite) {
                                           return invite.friendKey == inviter
                                                  || invite.groupKey == inviter;
                                       }),
                        outgoingQueue.end());
    changed = changed || outgoingQueue.size() != outgoingBefore;
    // Our offers in a group left, and the members a removed contact or a left group stood for.
    const int offersBefore = offers.size();
    offers.erase(std::remove_if(offers.begin(), offers.end(),
                                [&inviter](const ContactOffer& offer) {
                                    return offer.groupKey == inviter;
                                }),
                 offers.end());
    changed = changed || offers.size() != offersBefore;
    const int membersBefore = memberContacts.size();
    memberContacts.erase(std::remove_if(memberContacts.begin(), memberContacts.end(),
                                        [&inviter](const MemberContact& member) {
                                            return member.groupKey == inviter
                                                   || member.contactKey == inviter;
                                        }),
                         memberContacts.end());
    changed = changed || memberContacts.size() != membersBefore;
    // The marks of the conversation go with it; dropPrefix runs for every list in any case.
    const QString conversation = entryKeyPrefix(publicKey);
    changed = dropPrefix(entries, entryOrder, conversation) || changed;
    changed = dropPrefix(wires, wireOrder, conversation) || changed;
    changed = dropPrefix(privateEntries, privateEntryOrder, conversation) || changed;
    for (const QString& entryKey : relayedOrder.take(key)) {
        changed = relayedKeys.remove(entryKey) || changed;
    }
    return removeRequest(publicKey) || changed;
}

qint64 LocalStore::seenUpTo(const QString& publicKey) const
{
    return contacts.value(hashedKey(publicKey)).seenUpTo;
}

bool LocalStore::raiseSeenUpTo(const QString& publicKey, qint64 timeMs)
{
    const QString key = hashedKey(publicKey);
    Contact contact = contacts.value(key);
    if (timeMs < 0 || timeMs <= contact.seenUpTo) {
        return false;
    }
    contact.seenUpTo = timeMs;
    store(key, contact);
    return true;
}

SeenAnchor LocalStore::readAnchor(const QString& publicKey) const
{
    return contacts.value(hashedKey(publicKey)).readAnchor;
}

bool LocalStore::isReadPending(const QString& publicKey) const
{
    return contacts.value(hashedKey(publicKey)).readPending;
}

bool LocalStore::noteRead(const QString& publicKey, const SeenAnchor& anchor)
{
    const QString key = hashedKey(publicKey);
    Contact contact = contacts.value(key);
    const SeenAnchor& last = contact.readAnchor;
    // Newer: another message, at a later time or at the same millisecond.
    const bool newer = !anchor.isEmpty()
                       && (last.isEmpty() || (anchor.id != last.id && anchor.timeMs >= last.timeMs));
    if (!newer) {
        return false;
    }
    contact.readAnchor = anchor;
    contact.readPending = true;
    store(key, contact);
    return true;
}

bool LocalStore::noteReadSent(const QString& publicKey, const SeenAnchor& anchor)
{
    const QString key = hashedKey(publicKey);
    Contact contact = contacts.value(key);
    if (!contact.readPending || contact.readAnchor.id != anchor.id
        || contact.readAnchor.anchor != anchor.anchor) {
        return false;
    }
    contact.readPending = false;
    store(key, contact);
    return true;
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

bool LocalStore::acceptRequest(const QString& publicKey)
{
    const QString key = normalizedKey(publicKey);
    const auto waiting =
        std::find_if(waitingRequests.cbegin(), waitingRequests.cend(),
                     [&key](const WaitingRequest& request) { return request.publicKey == key; });
    if (waiting == waitingRequests.cend()) {
        return false;
    }
    const QString hashed = hashedKey(key);
    Contact contact = contacts.value(hashed);
    contact.askedAt = waiting->date;
    store(hashed, contact);
    return removeRequest(key);
}

QDateTime LocalStore::askedAt(const QString& publicKey) const
{
    const qint64 at = contacts.value(hashedKey(publicKey)).askedAt;
    return at < 0 ? QDateTime() : QDateTime::fromMSecsSinceEpoch(at);
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

bool LocalStore::addGroupRequest(const WaitingRequest& offer, const QDateTime& at)
{
    WaitingRequest request;
    request.address = normalizedKey(offer.address);
    request.publicKey = request.address.left(64);
    request.groupKey = normalizedKey(offer.groupKey);
    request.groupName = offer.groupName;
    request.memberKey = normalizedKey(offer.memberKey);
    request.memberName = offer.memberName;
    request.nonce = offer.nonce;
    request.date = at.toMSecsSinceEpoch();
    if (!isAddress(request.address) || !isPublicKey(request.groupKey)
        || !isPublicKey(request.memberKey)
        || request.nonce.size() != static_cast<int>(groupContactNonceSize)
        || blocked.contains(request.publicKey)) {
        return false;
    }
    const auto same = std::find_if(waitingRequests.begin(), waitingRequests.end(),
                                   [&request](const WaitingRequest& waiting) {
                                       return waiting.publicKey == request.publicKey;
                                   });
    if (same != waitingRequests.end()) {
        if (same->fromGroup() && same->nonce == request.nonce
            && same->groupKey == request.groupKey && same->memberKey == request.memberKey) {
            return false;
        }
        waitingRequests.erase(same);
    }
    waitingRequests.append(request);
    return true;
}

LocalStore::WaitingRequest LocalStore::request(const QString& publicKey) const
{
    const QString key = normalizedKey(publicKey);
    for (const WaitingRequest& waiting : waitingRequests) {
        if (waiting.publicKey == key) {
            return waiting;
        }
    }
    return WaitingRequest();
}

QVector<LocalStore::ContactOffer> LocalStore::contactOffers() const
{
    return offers;
}

LocalStore::ContactOffer LocalStore::contactOffer(const QByteArray& nonce) const
{
    if (nonce.isEmpty()) {
        return ContactOffer();
    }
    for (const ContactOffer& offer : offers) {
        if (offer.nonce == nonce) {
            return offer;
        }
    }
    return ContactOffer();
}

bool LocalStore::keepContactOffer(const ContactOffer& offer, const QDateTime& now)
{
    ContactOffer kept = offer;
    kept.groupKey = normalizedKey(offer.groupKey);
    kept.memberKey = normalizedKey(offer.memberKey);
    if (!isPublicKey(kept.groupKey) || !isPublicKey(kept.memberKey)
        || kept.nonce.size() != static_cast<int>(groupContactNonceSize)
        || now.toMSecsSinceEpoch() - kept.date > contactOfferKeepMs) {
        return false;
    }
    offers.erase(std::remove_if(offers.begin(), offers.end(),
                                [&kept](const ContactOffer& earlier) {
                                    return (earlier.groupKey == kept.groupKey
                                            && earlier.memberKey == kept.memberKey)
                                           || earlier.nonce == kept.nonce;
                                }),
                 offers.end());
    offers.append(kept);
    while (offers.size() > contactOfferLimit) {
        offers.removeFirst();
    }
    return true;
}

LocalStore::ContactOffer LocalStore::takeContactOffer(const QByteArray& nonce,
                                                     const QString& groupKey,
                                                     const QString& memberKey)
{
    if (nonce.size() != static_cast<int>(groupContactNonceSize)) {
        return ContactOffer();
    }
    for (int i = 0; i < offers.size(); ++i) {
        const ContactOffer& offer = offers.at(i);
        if (offer.nonce != nonce) {
            continue;
        }
        // No two offers share a nonce (keepContactOffer): this one went to someone else.
        if ((!groupKey.isEmpty() && offer.groupKey != normalizedKey(groupKey))
            || (!memberKey.isEmpty() && offer.memberKey != normalizedKey(memberKey))) {
            return ContactOffer();
        }
        return offers.takeAt(i);
    }
    return ContactOffer();
}

bool LocalStore::pruneContactOffers(const QDateTime& now)
{
    const qint64 oldest = now.toMSecsSinceEpoch() - contactOfferKeepMs;
    const int before = offers.size();
    offers.erase(std::remove_if(offers.begin(), offers.end(),
                                [oldest](const ContactOffer& offer) { return offer.date < oldest; }),
                 offers.end());
    return offers.size() != before;
}

LocalStore::MemberContact LocalStore::memberContact(const QString& groupKey,
                                                    const QString& memberKey) const
{
    const QString group = normalizedKey(groupKey);
    const QString member = normalizedKey(memberKey);
    for (const MemberContact& kept : memberContacts) {
        if (kept.groupKey == group && kept.memberKey == member) {
            return kept;
        }
    }
    return MemberContact();
}

LocalStore::MemberContact LocalStore::memberOfContact(const QString& contactKey) const
{
    const QString key = normalizedKey(contactKey);
    for (auto it = memberContacts.crbegin(); it != memberContacts.crend(); ++it) {
        if (it->contactKey == key) {
            return *it;
        }
    }
    return MemberContact();
}

bool LocalStore::keepMemberContact(const MemberContact& member)
{
    MemberContact kept = member;
    kept.groupKey = normalizedKey(member.groupKey);
    kept.memberKey = normalizedKey(member.memberKey);
    kept.contactKey = normalizedKey(member.contactKey);
    kept.address = normalizedKey(member.address);
    if (!isPublicKey(kept.groupKey) || !isPublicKey(kept.memberKey)
        || !isPublicKey(kept.contactKey)
        || (!kept.address.isEmpty()
            && (!isAddress(kept.address) || !kept.address.startsWith(kept.contactKey)))) {
        return false;
    }
    const auto same = std::find_if(memberContacts.begin(), memberContacts.end(),
                                   [&kept](const MemberContact& earlier) {
                                       return earlier.groupKey == kept.groupKey
                                              && earlier.memberKey == kept.memberKey;
                                   });
    if (same != memberContacts.end()) {
        if (kept.address.isEmpty() && same->contactKey == kept.contactKey) {
            kept.address = same->address;
        }
        if (kept.memberName.isEmpty()) {
            kept.memberName = same->memberName;
        }
        if (*same == kept) {
            return false;
        }
        memberContacts.erase(same);
    }
    memberContacts.append(kept);
    while (memberContacts.size() > memberContactLimit) {
        memberContacts.removeFirst();
    }
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

QVector<LocalStore::OutgoingInvite> LocalStore::outgoingInvites() const
{
    return outgoingQueue;
}

bool LocalStore::keepOutgoingInvite(const QString& friendKey, const QString& groupKey,
                                    const QDateTime& at, const QDateTime& now)
{
    OutgoingInvite invite;
    invite.friendKey = normalizedKey(friendKey);
    invite.groupKey = normalizedKey(groupKey);
    invite.date = at.toMSecsSinceEpoch();
    if (!isPublicKey(invite.friendKey) || !isPublicKey(invite.groupKey) || !at.isValid()
        || now.toMSecsSinceEpoch() - invite.date > outgoingInviteKeepMs) {
        return false;
    }
    for (const OutgoingInvite& kept : outgoingQueue) {
        if (kept.friendKey == invite.friendKey && kept.groupKey == invite.groupKey) {
            return false;
        }
    }
    outgoingQueue.append(invite);
    while (outgoingQueue.size() > outgoingInviteLimit) {
        outgoingQueue.removeFirst();
    }
    return true;
}

bool LocalStore::dropOutgoingInvite(const QString& friendKey, const QString& groupKey)
{
    const QString contact = normalizedKey(friendKey);
    const QString group = normalizedKey(groupKey);
    const int before = outgoingQueue.size();
    outgoingQueue.erase(std::remove_if(outgoingQueue.begin(), outgoingQueue.end(),
                                       [&contact, &group](const OutgoingInvite& invite) {
                                           return invite.friendKey == contact
                                                  && invite.groupKey == group;
                                       }),
                        outgoingQueue.end());
    return outgoingQueue.size() != before;
}

bool LocalStore::pruneOutgoingInvites(const QDateTime& now)
{
    const qint64 oldest = now.toMSecsSinceEpoch() - outgoingInviteKeepMs;
    const int before = outgoingQueue.size();
    outgoingQueue.erase(std::remove_if(outgoingQueue.begin(), outgoingQueue.end(),
                                       [oldest](const OutgoingInvite& invite) {
                                           return invite.date < oldest;
                                       }),
                        outgoingQueue.end());
    return outgoingQueue.size() != before;
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

bool LocalStore::isRelayed(const QString& entryKey) const
{
    return relayedKeys.contains(entryKey);
}

bool LocalStore::markRelayed(const QString& entryKey)
{
    if (!isEntryKey(entryKey) || relayedKeys.contains(entryKey)) {
        return false;
    }
    QStringList& order = relayedOrder[conversationOf(entryKey)];
    order.append(entryKey);
    relayedKeys.insert(entryKey);
    while (order.size() > relayedLimit) {
        relayedKeys.remove(order.takeFirst());
    }
    return true;
}

PrivateEntry LocalStore::privateEntry(const QString& entryKey) const
{
    return privateEntries.value(entryKey);
}

bool LocalStore::markPrivateEntry(const QString& entryKey, const PrivateEntry& entry)
{
    PrivateEntry kept = entry;
    kept.peerKey = normalizedKey(entry.peerKey);
    if (!isEntryKey(entryKey) || !isPublicKey(kept.peerKey)) {
        return false;
    }
    if (privateEntries.contains(entryKey) && privateEntries.value(entryKey) == kept) {
        return false;
    }
    privateEntryOrder.removeOne(entryKey);
    privateEntryOrder.append(entryKey);
    privateEntries.insert(entryKey, kept);
    while (privateEntryOrder.size() > privateEntryLimit) {
        privateEntries.remove(privateEntryOrder.takeFirst());
    }
    return true;
}

bool LocalStore::forgetEntries()
{
    const bool changed = !entryOrder.isEmpty() || !wireOrder.isEmpty() || !relayedKeys.isEmpty()
                         || !privateEntryOrder.isEmpty();
    entries.clear();
    entryOrder.clear();
    wires.clear();
    wireOrder.clear();
    relayedKeys.clear();
    relayedOrder.clear();
    privateEntries.clear();
    privateEntryOrder.clear();
    return changed;
}

bool LocalStore::communityAutoJoinDone() const
{
    return communityJoined;
}

bool LocalStore::setCommunityAutoJoinDone()
{
    const bool changed = !communityJoined;
    communityJoined = true;
    return changed;
}

bool LocalStore::communityLeft() const
{
    return communityGone;
}

bool LocalStore::setCommunityLeft()
{
    const bool changed = !communityGone;
    communityGone = true;
    return changed;
}

bool LocalStore::communitySoundRestored() const
{
    return communitySoundOn;
}

bool LocalStore::setCommunitySoundRestored()
{
    const bool changed = !communitySoundOn;
    communitySoundOn = true;
    return changed;
}

} // namespace Air
