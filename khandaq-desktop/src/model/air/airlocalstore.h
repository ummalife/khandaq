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

#pragma once

#include "src/model/air/airmessagemarks.h"

#include <QByteArray>
#include <QDateTime>
#include <QHash>
#include <QSet>
#include <QString>
#include <QStringList>
#include <QVector>

namespace Air {

/**
 * What the conversation remembers that the Tox profile and its history do not: the hours a
 * contact was seen online (the day strip), "mute" and "key verified" from the dossier, the keys
 * blocked on this device, the friend requests and group invitations waiting for an answer (E12),
 * the edits and reactions shown on entries of the transcript and the ids groups gave their messages
 * (stage 3, item 10), the files and the texts of groups that came relayed and, in memory only,
 * when this device saw the contact come online.
 *
 * A port of AirLocalStore in khandaq-ios/Antidote/AirLocalStores.swift. Contacts are filed under a
 * hash of their key, so their marks never name anyone; the block list has to show its keys, and a
 * waiting request or invitation needs the key of who sent it, so these keep them whole. Hours are
 * the starts of local clock hours in seconds since 1970 and are kept for seven days. The stored
 * form goes into the profile's own settings, which are encrypted together with the profile when it
 * has a password, and never into the Tox profile itself, so an exported profile carries none of
 * it. Every change returns whether anything changed, so the owner knows when to save.
 */
class LocalStore
{
public:
    static constexpr qint64 keepSeconds = 7 * 86400;

    /**
     * A friend request waiting for Accept or Decline: toxcore hands it over once. A member of a
     * group may offer their ID over the group as well (airgroupcontact.h): that offer waits here
     * too, with the group it came from (fromGroup).
     */
    struct WaitingRequest
    {
        /** 64 hexadecimal characters in upper case. */
        QString publicKey;
        QString message;
        /** When it came, in milliseconds since 1970. */
        qint64 date = 0;
        /**
         * An offer of a member of a group: their whole Tox ID, 76 hexadecimal characters in upper
         * case, which Accept sends a request to. Empty for a Tox request.
         */
        QString address;
        /** The group the offer came in (its chat id, upper case) and its name then. */
        QString groupKey;
        QString groupName;
        /** The member's key in that group, upper case, and their name in it. */
        QString memberKey;
        QString memberName;
        /** The 8 bytes of the offer, which the accept and the request that answers it repeat. */
        QByteArray nonce;

        bool fromGroup() const;
        bool operator==(const WaitingRequest& other) const;
    };

    /**
     * An offer of ours to a member of a group to become contacts (airgroupcontact.h), waiting for
     * their accept: one per member of a group, kept for Air::contactOfferKeepMs.
     */
    struct ContactOffer
    {
        /** The group's chat id and the member's key in it, upper case. */
        QString groupKey;
        QString memberKey;
        /** Their name in the group, which the contact takes until their own name comes. */
        QString memberName;
        /** The 8 bytes of the offer. */
        QByteArray nonce;
        /** When it went, in milliseconds since 1970. */
        qint64 date = 0;

        bool isNull() const;
        bool operator==(const ContactOffer& other) const;
    };
    /** The most offers kept; the oldest make room. */
    static constexpr int contactOfferLimit = 256;

    /**
     * A member of a group who became a contact through the exchange, on either side: the group
     * and the member's key in it, the contact's key and whole ID, and the member's name, which the
     * contact keeps until their own name comes.
     */
    struct MemberContact
    {
        QString groupKey;
        QString memberKey;
        /** 64 hexadecimal characters in upper case. */
        QString contactKey;
        /** 76 hexadecimal characters in upper case; empty while only the key is known. */
        QString address;
        QString memberName;

        bool isNull() const;
        bool operator==(const MemberContact& other) const;
    };
    /** The most members kept; the oldest make room. */
    static constexpr int memberContactLimit = 4096;

    /**
     * An invitation to a group waiting for Join or Decline. toxcore hands it over once, and Join
     * goes through the contact who sent it.
     */
    struct WaitingInvite
    {
        /** The same for the same invitation from the same person, across launches. */
        QString id;
        /** 64 hexadecimal characters in upper case. */
        QString inviterKey;
        QString groupName;
        /** toxcore's invitation, as it came. */
        QByteArray data;
        /** When it came, in milliseconds since 1970. */
        qint64 date = 0;

        bool isNull() const;
        bool operator==(const WaitingInvite& other) const;
    };

    /**
     * KHANDAQ (0.2.60): an invitation of ours to a group for a contact who was offline. toxcore
     * sends one only over the link with the contact, so it waits here and goes once both are
     * online. It goes away once sent, with the contact or the group, or outgoingInviteKeepMs after
     * it was asked for.
     */
    struct OutgoingInvite
    {
        /** 64 hexadecimal characters in upper case: the contact, and the chat id of the group. */
        QString friendKey;
        QString groupKey;
        /** When it was asked for, in milliseconds since 1970. */
        qint64 date = 0;

        bool operator==(const OutgoingInvite& other) const;
    };
    /** Thirty days: a contact away for longer is invited again by hand. */
    static constexpr qint64 outgoingInviteKeepMs = 30LL * 24 * 60 * 60 * 1000;
    /** The most invitations kept; the oldest make room. */
    static constexpr int outgoingInviteLimit = 256;

    /**
     * A private message with a member of a group (E13): toxcore keeps none, so this device does,
     * the last privateLimit of each conversation.
     */
    struct PrivateLine
    {
        /** The group's key (its chat id), 64 hexadecimal characters in upper case. */
        QString groupKey;
        /** The member's key in that group, 64 hexadecimal characters in upper case. */
        QString peerKey;
        bool ours = false;
        QString text;
        /** When it was written or came, in milliseconds since 1970. */
        qint64 date = 0;

        bool operator==(const PrivateLine& other) const;
    };
    static constexpr int privateLimit = 200;

    /** 16 bytes of SHA-256 over the upper-cased key, in hexadecimal. */
    static QString hashedKey(const QString& publicKey);

    /** Replaces the stored marks with those in data (empty data: none); forgets onlineSince too. */
    void load(const QByteArray& data, const QDateTime& now);
    QByteArray save() const;

    /** Marks the clock hour of at as an hour the contact was online; true only for a new hour. */
    bool markOnline(const QString& publicKey, const QDateTime& at);
    QSet<qint64> hours(const QString& publicKey) const;

    /** The first connect counts: later ones keep the time until a disconnect. */
    bool noteConnected(const QString& publicKey, const QDateTime& at);
    bool noteDisconnected(const QString& publicKey);
    /** Invalid when this device has not seen the contact come online since it started. */
    QDateTime onlineSince(const QString& publicKey) const;

    bool isMuted(const QString& publicKey) const;
    bool setMuted(bool muted, const QString& publicKey);

    /** Invalid when the key is not verified. */
    QDateTime verifiedAt(const QString& publicKey) const;
    bool setVerified(const QDateTime& at, const QString& publicKey);

    /** Forgets the marks of a contact, when the contact itself is removed, and the invitations
     * that came from them: Join goes through the inviter. A block stays: it is about the key, not
     * the contact. The members a contact or a group stood for, and our offers in a group, go too. */
    bool forget(const QString& publicKey);

    /** Drops the hours older than keepSeconds before now; true if any went. */
    bool pruneHours(const QDateTime& now);

    /** A public key as the block list keeps it: without surrounding spaces, in upper case. */
    static QString normalizedKey(const QString& publicKey);
    /** 64 hexadecimal characters in upper case; a whole ID or a shorter string is not a key. */
    static bool isPublicKey(const QString& key);

    /** The blocked keys, 64 hexadecimal characters in upper case, oldest first. Tox has no block:
     * the list stays on this device and the other side is not told. */
    QStringList blockedKeys() const;
    bool isBlocked(const QString& publicKey) const;
    /** False when the text is not a public key or the key is already on the list. A request that
     * waits from the key goes with the block. */
    bool block(const QString& publicKey);
    bool unblock(const QString& publicKey);
    /** Which of the waiting requests to drop: the positions of every listed key, in order. */
    QVector<int> blockedPositions(const QStringList& publicKeys) const;

    /** The friend requests waiting for an answer, oldest first, one per key. */
    QVector<WaitingRequest> requests() const;
    bool hasRequest(const QString& publicKey) const;
    /**
     * KHANDAQ (QA 2026-11-14, F2): the key became a contact while its request waited, so the
     * request is answered: it stops waiting, and the contact keeps the time it came (askedAt).
     * False when no request from the key waits.
     */
    bool acceptRequest(const QString& publicKey);
    /**
     * When the request of a contact made by accepting it came: they were online then, and no
     * request of ours waits for them. Invalid for a contact made by our own request. Kept until the
     * contact is removed. Desktop only so far; the iOS store has no such mark.
     */
    QDateTime askedAt(const QString& publicKey) const;
    /**
     * Keeps a request until it is answered. False, and nothing kept, for a text that is not a
     * public key, a blocked key, or the same words from a key that waits already; other words from
     * that key take the place of the old ones, with the new time.
     */
    bool addRequest(const QString& publicKey, const QString& message, const QDateTime& at);
    /**
     * Keeps the offer of a member of a group as a request (WaitingRequest::fromGroup) until it is
     * answered: under the key of its address, in place of any request from that key. False, and
     * nothing kept, for an address that is not 76 hexadecimal characters, keys that are not keys,
     * a nonce not of 8 bytes, a blocked key, or the same offer again.
     */
    bool addGroupRequest(const WaitingRequest& offer, const QDateTime& at);
    /** The request waiting from the key; one with an empty key when none waits. */
    WaitingRequest request(const QString& publicKey) const;
    bool removeRequest(const QString& publicKey);

    /** Our offers to members of groups that wait for their accept, oldest first. */
    QVector<ContactOffer> contactOffers() const;
    /** The offer with this nonce; a null one when none waits. */
    ContactOffer contactOffer(const QByteArray& nonce) const;
    /**
     * Keeps an offer, in place of an earlier one to the same member of the same group. False for
     * keys that are not keys, a nonce not of 8 bytes, or an offer older than the keep at now.
     */
    bool keepContactOffer(const ContactOffer& offer, const QDateTime& now);
    /**
     * Takes the offer with this nonce: returns it and drops it in one step, so that of the two
     * answers that may come to one offer (the accept over the group, the Tox request with its
     * marker) only the first one adds the contact, and the other finds nothing. With groupKey and
     * memberKey, only an offer that went to that member of that group is taken; any other stays.
     * A null offer when none is taken.
     */
    ContactOffer takeContactOffer(const QByteArray& nonce, const QString& groupKey = QString(),
                                  const QString& memberKey = QString());
    /** Drops the offers older than Air::contactOfferKeepMs at now. */
    bool pruneContactOffers(const QDateTime& now);

    /** The contact a member of a group is; a null one when they are none. */
    MemberContact memberContact(const QString& groupKey, const QString& memberKey) const;
    /** The member behind a contact, the newest kept; a null one for a contact made otherwise. */
    MemberContact memberOfContact(const QString& contactKey) const;
    /**
     * Keeps who a member is, in place of what was kept of the member; an address that comes later
     * fills one kept without it. False for keys that are not keys, an address that is not 76
     * hexadecimal characters of the contact's key, or nothing new.
     */
    bool keepMemberContact(const MemberContact& member);

    /** The group invitations waiting for an answer, oldest first. */
    QVector<WaitingInvite> invites() const;
    /** A null invitation when none has the id. */
    WaitingInvite invite(const QString& id) const;
    /**
     * Keeps an invitation until it is answered and returns its id; an empty id, and nothing kept,
     * when the inviter's key is not a key or the invitation is empty. A second invitation from the
     * same person to a group of the same name takes the place of the first.
     */
    QString addInvite(const QString& inviterKey, const QString& groupName, const QByteArray& data,
                      const QDateTime& at);
    bool removeInvite(const QString& id);

    /** Our invitations waiting for their contacts, oldest first. */
    QVector<OutgoingInvite> outgoingInvites() const;
    /**
     * Keeps an invitation of ours until it can go; false for keys that are not keys, an invitation
     * kept already (it keeps its date) or one older than outgoingInviteKeepMs at now.
     */
    bool keepOutgoingInvite(const QString& friendKey, const QString& groupKey, const QDateTime& at,
                            const QDateTime& now);
    bool dropOutgoingInvite(const QString& friendKey, const QString& groupKey);
    /** Drops the invitations asked for more than outgoingInviteKeepMs before now. */
    bool pruneOutgoingInvites(const QDateTime& now);

    /** The private messages with one member of a group, oldest first. */
    QVector<PrivateLine> privateLines(const QString& groupKey, const QString& peerKey) const;
    /** Keeps a private message; false for keys that are not keys or an empty text. */
    bool addPrivateLine(const PrivateLine& line);
    /** The group was left: its private messages go with it. */
    bool forgetGroup(const QString& groupKey);

    /**
     * The marks of an entry of a conversation (stage 3, item 10): an edit by its author and the
     * reactions on it, under textEntryKey or fileEntryKey. The entryLimit entries changed last are
     * kept; a contact removed or a group left takes the marks of its conversation along (forget).
     */
    static constexpr int entryLimit = 500;
    EntryMarks entryMarks(const QString& entryKey) const;
    /** Keeps the marks of an entry as its newest change; empty marks forget the entry. */
    bool setEntryMarks(const QString& entryKey, const EntryMarks& marks);
    /**
     * The id a group gave one of its messages (groupWireId), which the edits, deletions and
     * reactions of its members point at; the history has no place for it. The ids of the
     * wireLimit group messages kept last are kept.
     */
    static constexpr int wireLimit = 1000;
    QByteArray wireId(const QString& entryKey) const;
    /** Keeps a 4-byte id under an entry key; an empty id forgets it. */
    bool setWireId(const QString& entryKey, const QByteArray& wireId);
    /**
     * KHANDAQ (0.2.57, review): the files of groups a member relayed from the history (0x03), by
     * fileEntryKey, and (0.2.63) the texts (0x02), by textEntryKey. Who sent one is only whom that
     * member names, so a Report on it names nobody, in a later launch too. The relayedLimit
     * marked last in each group are kept; a group left takes its own along (forget), an erased
     * history all of them (forgetEntries).
     */
    static constexpr int relayedLimit = 4096;
    bool isRelayed(const QString& entryKey) const;
    /** Keeps the mark of a relayed entry; false for a text that is no entry key, or a key kept. */
    bool markRelayed(const QString& entryKey);
    /**
     * The private messages of groups that their feeds show (Air::PrivateEntry), by textEntryKey.
     * The privateEntryLimit marked last are kept; a group left takes its own along (forget), an
     * erased history all of them (forgetEntries).
     */
    static constexpr int privateEntryLimit = 4096;
    PrivateEntry privateEntry(const QString& entryKey) const;
    /** False for a text that is no entry key, a member key that is not a key, or nothing new. */
    bool markPrivateEntry(const QString& entryKey, const PrivateEntry& entry);
    /** The history was erased: no entry is left for marks or ids to belong to. */
    bool forgetEntries();

    /**
     * KHANDAQ (0.2.57): read receipts between two people (protocol v1), kept per contact until the
     * contact is removed (forget).
     *
     * The time, by our clock, of the newest of our messages the contact has seen (their read
     * receipts), in milliseconds since 1970; -1 before the first receipt. Every message of ours
     * up to that time counts as seen.
     */
    qint64 seenUpTo(const QString& publicKey) const;
    /** Moves seenUpTo to timeMs; false, and nothing changed, for a time not after it: never back. */
    bool raiseSeenUpTo(const QString& publicKey, qint64 timeMs);
    /**
     * The newest of the contact's messages we have seen and told them about, or still owe them
     * telling (isReadPending); empty before the first.
     */
    SeenAnchor readAnchor(const QString& publicKey) const;
    /** Whether the receipt for readAnchor has yet to go: the contact was offline, say. */
    bool isReadPending(const QString& publicKey) const;
    /**
     * Takes anchor as the newest message seen, its receipt still to go; false, and nothing changed,
     * for an empty anchor or one not newer than readAnchor.
     */
    bool noteRead(const QString& publicKey, const SeenAnchor& anchor);
    /** The receipt for anchor went; false when that is not the pending readAnchor. */
    bool noteReadSent(const QString& publicKey, const SeenAnchor& anchor);

    /**
     * KHANDAQ (0.2.57): Khandaq Community (Air::Community). Whether this profile joined it by
     * itself once, and whether the person left it; after either it is never joined by itself
     * again. Both only ever become true, and leaving the group (forgetGroup) keeps them. Each
     * setter is false when the mark was there already.
     */
    bool communityAutoJoinDone() const;
    bool setCommunityAutoJoinDone();
    bool communityLeft() const;
    bool setCommunityLeft();
    /** KHANDAQ (0.2.60): the mute of an earlier auto-join is lifted (Air::Community::restoreSound). */
    bool communitySoundRestored() const;
    bool setCommunitySoundRestored();

private:
    struct Contact
    {
        QVector<qint64> hours;
        bool muted = false;
        /** Milliseconds since 1970, or -1 when not verified. */
        qint64 verifiedAt = -1;
        /** When their request came, in milliseconds since 1970, or -1 (askedAt). */
        qint64 askedAt = -1;
        /** KHANDAQ (0.2.57): seenUpTo, readAnchor and isReadPending. */
        qint64 seenUpTo = -1;
        SeenAnchor readAnchor;
        bool readPending = false;

        bool isEmpty() const;
    };

    void store(const QString& key, const Contact& contact);
    /** The id of an invitation: a hash of who sent it and what they sent. */
    static QString inviteId(const QString& inviterKey, const QByteArray& data);

    QHash<QString, Contact> contacts;
    QHash<QString, QDateTime> since;
    QStringList blocked;
    QVector<WaitingRequest> waitingRequests;
    QVector<WaitingInvite> waitingInvites;
    QVector<OutgoingInvite> outgoingQueue;
    QVector<PrivateLine> privateMessages;
    /** Marks by entry key, and the keys from the oldest change to the newest. */
    QHash<QString, EntryMarks> entries;
    QStringList entryOrder;
    /** Group message ids by entry key, and the keys from the oldest to the newest. */
    QHash<QString, QByteArray> wires;
    QStringList wireOrder;
    /** The relayed entry keys, and by the hash of their group those keys, oldest first. */
    QSet<QString> relayedKeys;
    QHash<QString, QStringList> relayedOrder;
    QVector<ContactOffer> offers;
    QVector<MemberContact> memberContacts;
    /** The private entries of groups by entry key, and the keys from the oldest to the newest. */
    QHash<QString, PrivateEntry> privateEntries;
    QStringList privateEntryOrder;
    bool communityJoined = false;
    bool communityGone = false;
    bool communitySoundOn = false;
};

} // namespace Air
