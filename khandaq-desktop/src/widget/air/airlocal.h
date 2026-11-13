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

#include "src/model/air/airlocalstore.h"

#include <QDateTime>
#include <QHash>
#include <QObject>
#include <QPointer>
#include <QSet>
#include <QTimer>

class Friend;
class Settings;
class ToxPk;

/**
 * The running side of Air::LocalStore: it watches friends come and go to mark the hours they were
 * online and when this device saw them come online, and keeps "mute", "key verified" and the block
 * list from the dossier, and the friend requests and group invitations waiting for an answer (E12).
 * The store lives in the profile's settings and is written a moment after it changes; a block, a
 * request or an invitation is written at once, since toxcore hands a request over only once.
 * It also keeps, for the conversations, the edits and reactions shown on entries and the ids
 * groups gave their messages (stage 3, item 10).
 */
class AirLocal : public QObject, public Air::EntryMarksStore
{
    Q_OBJECT
public:
    explicit AirLocal(Settings& settings, QObject* parent = nullptr);
    ~AirLocal() override;

    /** Follows a friend until they are removed. */
    void watchFriend(Friend& frnd);
    /** The friend was removed: nothing about them stays on this device. */
    void forget(const ToxPk& publicKey);

    QSet<qint64> hours(const ToxPk& publicKey) const;
    /** Invalid when this device has not seen the person come online since it started. */
    QDateTime onlineSince(const ToxPk& publicKey) const;

    bool isMuted(const ToxPk& publicKey) const;
    void setMuted(const ToxPk& publicKey, bool muted);

    /** Invalid when the key is not verified. */
    QDateTime verifiedAt(const ToxPk& publicKey) const;
    void setVerified(const ToxPk& publicKey, bool verified);

    /** The keys blocked on this device, oldest first: 64 hexadecimal characters in upper case. */
    QStringList blockedKeys() const;
    bool isBlocked(const ToxPk& publicKey) const;
    /** Puts the key on the block list and writes the list at once, before anything else happens
     * to the contact. */
    void block(const ToxPk& publicKey);
    void unblock(const QString& publicKey);

    /** The friend requests waiting for Accept or Decline, oldest first, one per key. */
    QVector<Air::LocalStore::WaitingRequest> requests() const;
    bool hasRequest(const ToxPk& publicKey) const;
    /**
     * Keeps a request until it is answered; false when nothing new is kept: a blocked key, or the
     * same words again from a key that waits already.
     */
    bool addRequest(const ToxPk& publicKey, const QString& message);
    void removeRequest(const ToxPk& publicKey);

    /** The group invitations waiting for Join or Decline, oldest first. */
    QVector<Air::LocalStore::WaitingInvite> invites() const;
    /** A null invitation when none has the id. */
    Air::LocalStore::WaitingInvite invite(const QString& id) const;
    /** Keeps an invitation until it is answered; its id, or empty when it is not kept. */
    QString addInvite(const ToxPk& inviter, const QString& groupName, const QByteArray& data);
    void removeInvite(const QString& id);

    /** The private messages with a member of a group, oldest first (E13). */
    QVector<Air::LocalStore::PrivateLine> privateLines(const QString& groupKey,
                                                       const QString& peerKey) const;
    /** Keeps a private message, written by us or come from the member, and writes it at once. */
    void addPrivateLine(const QString& groupKey, const QString& peerKey, bool ours,
                        const QString& text);
    /** The group was left: its mute and its private messages go. */
    void forgetGroup(const QString& groupKey);

    Air::EntryMarks entryMarks(const QString& key) const override;
    void setEntryMarks(const QString& key, const Air::EntryMarks& marks) override;
    QByteArray wireId(const QString& key) const override;
    void setWireId(const QString& key, const QByteArray& wireId) override;
    /** The history was erased: the marks and the ids of its entries go too. */
    void forgetEntries();

#ifdef KHANDAQ_SCREENSHOT_HOOK
    /** Test builds only: marks the hour of at as one the person was online in. */
    void markOnlineForShots(const ToxPk& publicKey, const QDateTime& at);
#endif

signals:
    /** The hours, the mute or the verification of this person changed. */
    void changed(const ToxPk& publicKey);
    /** A key was blocked or unblocked. */
    void blockListChanged();
    /** A request or an invitation came, was answered or went with its contact. */
    void requestsChanged();
    /** A private message was kept in the conversation with that member of that group. */
    void privateLinesChanged(const QString& groupKey, const QString& peerKey);

private:
    void onStatus(Friend& frnd);
    void markOnlineFriends();
    void noteChange(const ToxPk& publicKey);
    /** Writes the store at once and says that the requests changed. */
    void saveRequests();
    void save();

    Settings& settings;
    Air::LocalStore store;
    QHash<QString, QPointer<Friend>> friends;
    /** Online friends get the mark of each new hour while they stay online. */
    QTimer ticker;
    /** Changes are written together, a moment after the last one. */
    QTimer saveLater;
};
