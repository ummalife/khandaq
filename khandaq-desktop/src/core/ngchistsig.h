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

#include <cstdint>

/**
 * KHANDAQ (external audit #2, finding 1) — authorship verification for NGC history sync.
 *
 * A history-sync packet carries the alleged original author as raw bytes nobody signed. The
 * transport authenticates only the SYNCING peer, so any group member can manufacture a message that
 * displays as another member's. The fix is an original-author signature; the design, the decisions
 * behind it and the rollout are in DESIGN-ngc-signed-history-sync.md.
 *
 * This desktop client is a history-sync CONSUMER only (it never emits a request — see core.cpp), so
 * it needs to VERIFY signatures and never to produce them. That is why this header exposes
 * pre-image construction and verification but no signing.
 *
 * The pre-image is the part that must be identical on Android, iOS and here. The signature itself is
 * Ed25519 from libsodium and will not diverge; the byte string being signed absolutely can — field
 * order, integer width, endianness, whether the text is hashed or embedded. That is frozen by
 * ngc_histsync_vectors.py at the repository root, and ngchistsig_test.cpp checks this implementation
 * against those exact digests.
 */
namespace NgcHistSig
{
/** Sizes the wire format fixes. Anything of a different length is a malformed packet, not a variant. */
static constexpr int GroupIdSize = 32;
static constexpr int PubKeySize = 32;
static constexpr int MsgIdSize = 4;
static constexpr int SignatureSize = 64;
static constexpr int Sha256Size = 32;

/** Domain separators. They keep a history signature from ever validating as an announcement one. */
extern const char HistSyncDomain[];  //!< "KQ-HISTSYNC-1"
extern const char AnnounceDomain[];  //!< "KQ-HSK-ANNOUNCE-1"

/**
 * Builds the exact bytes a history-sync signature covers:
 *
 *   "KQ-HISTSYNC-1" || groupId(32) || authorPub(32) || msgId(4) || ts(8, big-endian) || sha256(text)
 *
 * The text is HASHED rather than embedded, so the pre-image is always 121 bytes whatever the message
 * length. The timestamp is the full 64 bits even though today's wire format transmits only its low
 * 32 — a client that signs the truncated value produces signatures nobody else accepts.
 *
 * @param text the message body as UTF-8. Not as the platform's native string encoding: hashing
 *             UTF-16 here is the single most likely way for an implementation to diverge.
 * @return the pre-image, or an empty QByteArray if any fixed-width argument has the wrong size.
 */
QByteArray histSyncPreimage(const QByteArray& groupId, const QByteArray& authorPub,
                            const QByteArray& msgId, uint64_t timestamp, const QByteArray& text);

/**
 * Builds the bytes a history-signing-key announcement covers:
 *
 *   "KQ-HSK-ANNOUNCE-1" || toxPub(32) || hskPub(32) || validFromTs(8, big-endian)
 *
 * @return the pre-image (89 bytes), or empty if a key has the wrong size.
 */
QByteArray announcePreimage(const QByteArray& toxPub, const QByteArray& hskPub,
                            uint64_t validFromTs);

/**
 * Verifies an Ed25519 signature over a pre-image.
 *
 * Fails CLOSED on every malformed input — wrong signature length, wrong key length, empty
 * pre-image. A packet that carries a signature we cannot even parse is an attack or a bug, never an
 * old client: old clients send version 0x01, which never reaches this code at all.
 *
 * @return true only if the signature verifies.
 */
bool verifySignature(const QByteArray& preimage, const QByteArray& signature,
                     const QByteArray& signerPub);
} // namespace NgcHistSig
