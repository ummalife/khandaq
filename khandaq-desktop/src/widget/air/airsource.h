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

#include "src/model/air/airnetwork.h"

#include <QDateTime>
#include <QElapsedTimer>
#include <QHash>
#include <QObject>
#include <QPixmap>
#include <QPointer>
#include <QSet>

#include <memory>

class AirLocal;
class Chat;
class Core;
class Friend;
class FriendList;
class Group;
class GroupId;
class GroupList;
class IChatLog;
class Profile;
class Settings;
class ToxPk;

/**
 * What the Air list shows, read from the running client: friends and groups with their
 * conversations, links, last-seen times, unread counts and photos, turned into Air::Entry values.
 * It decides nothing about order, sections or filters, which are the model's, and emits changed()
 * whenever anything it reads may have moved.
 *
 * Only the part of each conversation that ChatHistory already holds in memory (its last 100
 * messages) is read, so building the list never touches the database.
 */
class AirSource : public QObject
{
    Q_OBJECT
public:
    AirSource(Core& core, Profile& profile, Settings& settings, FriendList& friendList,
              GroupList& groupList, QObject* parent = nullptr);

    static QString friendEntryId(const ToxPk& publicKey);
    static QString groupEntryId(const GroupId& groupId);
    /** Saved Messages: the notes to yourself. */
    static QString savedEntryId();

    /** A conversation joined the list; changes in it update its row until it is destroyed. */
    void watchFriend(Friend& frnd, std::shared_ptr<IChatLog> chatLog);
    void watchGroup(Group& group, std::shared_ptr<IChatLog> chatLog);
    /** The notes to yourself, whose newest one the Saved Messages row shows. */
    void watchNotes(const IChatLog& notesLog);

    /** How far one friend is right now, as their row and their conversation say it. */
    Air::Reach reachOf(const Friend& frnd) const;

    /** Where the requests and invitations waiting for an answer are kept (E12). */
    void setLocal(AirLocal* local);
    QVector<Air::Entry> entries();
    /**
     * The friend requests and group invitations waiting for an answer, as their cards: everything
     * the local store keeps but the ones being answered right now.
     */
    QVector<Air::Request> requests() const;
    /**
     * Takes a card out of the list while its answer is on the way, a Decline that can still be
     * undone or an Accept that Core has not confirmed, and puts it back.
     */
    void setRequestHidden(const QString& requestId, bool hidden);
    /** Messages matching the query, newest first, at most 50. */
    QVector<Air::MessageHit> search(const QString& query) const;
    Air::NetworkState networkState() const;
    /** The relays our node holds a connection to and the contacts it reaches directly. */
    Air::Nodes nodes() const;
    /** The network part of the log for support (NetworkDiagnostics::supportReport). */
    QString networkReport() const;
#ifdef KHANDAQ_SCREENSHOT_HOOK
    /**
     * The walk runs where the computer always has a network: this makes the node read it as gone,
     * so the line and Settings show No network.
     */
    void forgetNetworkForWalk();
#endif
    /** Our node came online less than a minute ago: friends seen today may still be linking. */
    bool selfLinkSettling() const;
    /** How long our link still counts as settling, in milliseconds; 0 when it does not. */
    int settlingLeft() const;
    QPixmap avatar(const QString& photoKey) const;
    QPixmap selfAvatar() const;

    /** A message or a file arrived in a conversation that is not in front of the user. */
    void noteUnread(const QString& entryId);
    void setTyping(const ToxPk& publicKey, bool typing);

signals:
    void changed();
    /** The state of the node, the computer's network or the count of nodes moved. */
    void networkChanged();
    void avatarChanged(const QString& photoKey);
    void selfAvatarChanged();

private:
    struct Watched
    {
        QPointer<Chat> chat;
        std::shared_ptr<IChatLog> log;
        bool group = false;
        uint32_t friendNumber = 0;
    };

    /** The newest part of one conversation, as far as the row needs it. */
    struct Conversation
    {
        bool hasItem = false;
        QDateTime lastTime;
        QString preview;
        Air::Delivery delivery = Air::Delivery::None;
        /** The last item is our own message still waiting for its receipt. */
        bool lastPending = false;
        /** Our own messages still waiting for their receipt. */
        int undelivered = 0;
    };

    void forget(const QString& entryId);
    Conversation read(const IChatLog& log, bool group) const;
    Air::Entry friendEntry(Friend& frnd, const IChatLog* log, qint64 nowSeconds, bool settling,
                           const QSet<QString>& favorites);
    Air::Entry groupEntry(Group& group, const IChatLog* log, const QSet<QString>& favorites);
    Air::Entry savedEntry(const QSet<QString>& favorites) const;
    int unreadFor(const QString& entryId, bool hasEvent);
    QString timeText(const QDateTime& when) const;
    bool networkReachable() const;
    void reloadAvatar(const ToxPk& publicKey);
    void reloadSelfAvatar();
    void onFriendLink(uint32_t friendNumber, int toxConnection);
    void onFriendLastSeen(uint32_t friendNumber, const QDateTime& when);
    void onSelfLink(int toxConnection);
    void onRelays(int count);
    /** While the node is not linked, looks again whether the computer has a network. */
    void watchReachability();

    Core& core;
    Profile& profile;
    Settings& settings;
    FriendList& friendList;
    GroupList& groupList;
    QPointer<AirLocal> local;
    /** The cards being answered: request ids. */
    QSet<QString> hiddenRequests;

    QHash<QString, Watched> watched;
    QPointer<const IChatLog> notes;
    QHash<uint32_t, int> friendLinks;
    QHash<uint32_t, qint64> lastSeen;
    QHash<QString, int> unread;
    QSet<QString> typing;
    QHash<QString, QPixmap> avatars;
    QPixmap ownAvatar;

    int selfLink = 0;
    int relays = 0;
    QElapsedTimer linkedFor;

    mutable QElapsedTimer reachabilityAge;
    mutable bool reachable = true;
#ifdef KHANDAQ_SCREENSHOT_HOOK
    bool walkWithoutNetwork = false;
#endif
};
