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

#include <QByteArray>
#include <QHash>
#include <QString>

#include <cstddef>
#include <cstdint>

namespace Air {

/**
 * A member of a group becomes a contact (DESIGN-group-contacts-private-mentions.md, part 1). The
 * key a group gives a member is theirs in that group only (toxcore makes a key pair per group), so
 * no Tox ID can be made of it: the two of them trade their Tox addresses instead, with consent, over
 * the private channel of the group. Add to contacts sends an offer; the member's Accept answers with
 * an accept and a Tox friend request. Both packets are lossless private custom packets with the
 * header of every Khandaq NGC packet, 54 bytes exactly:
 *
 *     magic 66 77 88 11 34 35 | version 01 | 61 offer, 62 accept | address 38 | nonce 8
 *
 * The address is the sender's own (tox_self_get_address): key 32, nospam 4, checksum 2. The nonce
 * is eight random bytes of the offer; the accept repeats them.
 */
struct GroupContactPacket
{
    enum class Kind
    {
        Offer,
        Accept,
    };

    Kind kind = Kind::Offer;
    /** The sender's whole Tox address, 38 bytes. */
    QByteArray address;
    /** The eight bytes of the offer. */
    QByteArray nonce;
};

constexpr uint8_t groupContactOfferId = 0x61;
constexpr uint8_t groupContactAcceptId = 0x62;
constexpr size_t groupContactAddressSize = 38;
constexpr size_t groupContactNonceSize = 8;
constexpr size_t groupContactPacketSize = 6 + 1 + 1 + groupContactAddressSize + groupContactNonceSize;
/** A pending offer of ours waits this long for its accept: thirty days. */
constexpr qint64 contactOfferKeepMs = 30LL * 24 * 60 * 60 * 1000;

/**
 * The packet for kind, or empty bytes when the address is not 38 bytes whose checksum holds or the
 * nonce is not eight bytes.
 */
QByteArray groupContactPacket(GroupContactPacket::Kind kind, const QByteArray& address,
                              const QByteArray& nonce);
/**
 * Whether a private packet of a group opens as an offer or an accept: the magic, version 1 and the
 * id 61 or 62, whatever its length. Such a packet is read here (readGroupContactPacket) and never
 * reaches the files' dispatcher.
 */
bool isGroupContactPacket(const uint8_t* data, size_t length);
/**
 * Reads an offer or an accept. False, and nothing read, for any length but 54, another magic,
 * version or id, or an address whose checksum does not hold.
 */
bool readGroupContactPacket(const uint8_t* data, size_t length, GroupContactPacket* packet);
/** Eight bytes from the system's random source, for a new offer. */
QByteArray newContactOfferNonce();

/**
 * The first line of the Tox friend request that accepts an offer: "[KQ|c|" and the nonce of the
 * offer as 16 lower-case hex digits, then "]". The one who offered takes a request that opens with
 * the nonce of an offer of theirs without asking (DESIGN-group-contacts-private-mentions.md).
 */
QString contactRequestMarker(const QByteArray& nonce);
/** The whole message of that request: the marker, a line break, then words. */
QString contactRequestMessage(const QByteArray& nonce, const QString& words);
/**
 * Reads the marker a request's message opens with: its nonce into nonce and the words after its
 * line into words. False when the message does not open with "[KQ|c|", 16 hex digits and "]";
 * words is then the whole message.
 */
bool readContactRequestMarker(const QString& message, QByteArray* nonce, QString* words);

/**
 * Drops the second offer of one member within offerGapMs of the first it took, so that a member
 * cannot fill the requests with offers.
 */
class ContactOfferGate
{
public:
    static constexpr qint64 offerGapMs = 60 * 1000;

    /**
     * Whether an offer of member (anything that names them for good: the group and their key in
     * it) at nowMs, a steady clock, is taken. A taken offer starts the gap again.
     */
    bool admit(const QString& member, qint64 nowMs);

private:
    /** The most members remembered; the gap of the others is long over by then. */
    static constexpr int memberLimit = 1024;

    QHash<QString, qint64> lastTaken;
};

} // namespace Air
