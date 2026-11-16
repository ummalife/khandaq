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

#include "src/model/air/airmodel.h"

// ---- Network state (E4): the line under your name and the Network section of Settings ----------

namespace Air {

/**
 * The nodes of the Tox network our node is linked with now: the relays it holds a connection to and
 * the contacts it reaches directly. Toxcore gives no count of the DHT nodes behind a direct link,
 * so those are not in it.
 */
struct Nodes
{
    int relays = 0;
    int directContacts = 0;

    int total() const;
    bool operator==(const Nodes& other) const;
    bool operator!=(const Nodes& other) const;
};

/** What the Network section says for one state of our node (DESIGN.md, "Network state on the phone"). */
struct NetworkReport
{
    enum class Link
    {
        Direct,
        Relay,
        /** Not linked yet. */
        None,
    };

    NetworkState state = NetworkState::Connecting;
    /** This computer has internet: in every state but Offline. */
    bool internet = true;
    Link link = Link::None;
    /** Messages leave at once: the dot is the live colour. */
    bool live = false;
    /** Connect again helps only while the node looks for the network with internet at hand. */
    bool offersReconnect = false;
    /** Red only when there is no network at all, as the line under your name. */
    bool alarm = false;

    static NetworkReport make(NetworkState state);
    bool operator==(const NetworkReport& other) const;
};

/**
 * KHANDAQ (0.2.57): the band at the top of the window while the computer has no network at all.
 * It comes once there has been none for 3 s, so a quick change of network shows nothing. With a
 * network back it says Connecting until our node is online, then Back online for 1.5 s, then it
 * goes; still not online after 30 s, it goes all the same, and the line under the name keeps the
 * state. Connecting with a network at hand is the line's to say, never the band's.
 * It is fed what the computer and the node say, with a monotonic clock in milliseconds.
 */
class NoNetworkNotice
{
public:
    enum class Phase
    {
        Hidden,
        /** No network for 3 s or more: messages and files wait. */
        NoNetwork,
        /** A network is back; our node is not online yet. */
        Connecting,
        /** Our node is online again: said for a moment. */
        BackOnline,
    };

    static constexpr qint64 noNetworkAfterMs = 3000;
    static constexpr qint64 backOnlineMs = 1500;
    static constexpr qint64 connectingAtMostMs = 30000;

    /** What the computer and our node say at nowMs; true when the phase changed. */
    bool update(bool network, bool nodeOnline, qint64 nowMs);
    Phase phase() const;
    /** How long until the time alone can change the phase, in milliseconds; -1 when it cannot. */
    qint64 msToNextStep(qint64 nowMs) const;

private:
    Phase current = Phase::Hidden;
    /** Since when the computer has had no network; -1 while it has one. */
    qint64 withoutSince = -1;
    /** Since when the phase is what it is. */
    qint64 phaseSince = 0;
};

} // namespace Air
