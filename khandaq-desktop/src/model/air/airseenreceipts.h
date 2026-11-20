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

#include "src/core/toxpk.h"
#include "src/model/air/airmessagemarks.h"

#include <QByteArray>
#include <QElapsedTimer>
#include <QHash>
#include <QObject>
#include <QSet>

#include <functional>

namespace Air {

/**
 * KHANDAQ (0.2.57): what decides whether the person reads a conversation between two people right
 * now (read receipts, protocol v1): it is the one shown in its window, the window is shown, not
 * minimized, the active one and not hidden behind others, and the app is in front. Nothing that
 * comes while any of that is missing counts as seen: not a row of the list, a notification, the
 * app behind others or a locked screen.
 */
struct ReadingFacts
{
    bool appActive = false;
    bool windowShown = false;
    bool windowMinimized = false;
    bool windowActive = false;
    bool windowExposed = false;
    bool chatShown = false;
};

bool isReadingNow(const ReadingFacts& facts);

/**
 * KHANDAQ (0.2.57): where the read receipts this device owes and sent are kept between launches
 * (AirLocal, through Air::LocalStore::readAnchor).
 */
class ReadMarkStore
{
public:
    virtual ~ReadMarkStore();
    /** The newest message of the person we have seen; empty before the first. */
    virtual SeenAnchor readAnchor(const ToxPk& friendPk) const = 0;
    /** Whether the receipt for readAnchor has yet to go. */
    virtual bool isReadPending(const ToxPk& friendPk) const = 0;
    /** Takes anchor as the newest seen, its receipt to go; false for one not newer. */
    virtual bool noteRead(const ToxPk& friendPk, const SeenAnchor& anchor) = 0;
    /** The receipt for anchor went. */
    virtual void noteReadSent(const ToxPk& friendPk, const SeenAnchor& anchor) = 0;
};

/**
 * KHANDAQ (0.2.57): the read receipts this device sends a person: the lossless packet 189 naming
 * the newest of their messages seen in the conversation with them (seenPacket), which marks it and
 * every message before it as seen on their side.
 *
 * A receipt goes at once, and at most one each gapMs to each person: one asked for sooner waits
 * for the gap, and a later message seen meanwhile takes its place. While the person is offline, or
 * when toxcore refuses the packet, the newest receipt owed is kept (ReadMarkStore, between launches
 * too) and goes when they come online. Groups, the saved messages and friend requests have none.
 *
 * KHANDAQ (beta ideas 2026-11-20): the person can turn them off (Settings, Privacy, setEnabled).
 */
class SeenReceipts : public QObject
{
    Q_OBJECT
public:
    /** Sends a packet to the person; false when it did not go (Core::sendSeenReceipt). */
    using Send = std::function<bool(const ToxPk& friendPk, const QByteArray& packet)>;
    using IsOnline = std::function<bool(const ToxPk& friendPk)>;
    static constexpr int defaultGapMs = 1000;

    SeenReceipts(ReadMarkStore& store_, Send send_, IsOnline isOnline_, int gapMs_ = defaultGapMs,
                 QObject* parent = nullptr);

    /**
     * The conversation with the person is being read (isReadingNow), and anchor is the newest of
     * their messages a receipt can name. Nothing new for an empty anchor or one not newer than
     * the last; a receipt still owed goes all the same.
     */
    void seen(const ToxPk& friendPk, const SeenAnchor& anchor);
    /** The person came online: the receipt owed them goes. */
    void friendOnline(const ToxPk& friendPk);
    /**
     * Send read receipts, in Settings, Privacy. Turned off, no receipt goes and nothing read
     * meanwhile is owed one later; a receipt owed from before waits until they are turned on
     * again. The receipts of others show either way.
     */
    void setEnabled(bool on);
    bool isEnabled() const;

private:
    void flush(const ToxPk& friendPk);

    ReadMarkStore& store;
    Send send;
    IsOnline isOnline;
    int gapMs;
    bool enabled = true;
    QElapsedTimer clock;
    /** When the last receipt went to each person (by the key's bytes), by clock. */
    QHash<QByteArray, qint64> lastSent;
    /** The people whose next receipt waits for the gap to pass. */
    QSet<QByteArray> waiting;
};

} // namespace Air
