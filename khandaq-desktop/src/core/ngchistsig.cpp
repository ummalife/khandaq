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

#include "src/core/ngchistsig.h"

#include <QCryptographicHash>

#include <cstring>

#include <sodium.h>

namespace NgcHistSig
{
const char HistSyncDomain[] = "KQ-HISTSYNC-1";
const char AnnounceDomain[] = "KQ-HSK-ANNOUNCE-1";

namespace
{
/**
 * Appends a 64-bit value big-endian.
 *
 * Written out by hand rather than memcpy'ing a uint64_t: the desktop builds for both little- and
 * big-endian targets, and a host-order copy would make signatures architecture-dependent — a bug
 * that would only ever surface on the one platform nobody tests on.
 */
void appendBigEndian64(QByteArray& out, uint64_t value)
{
    for (int shift = 56; shift >= 0; shift -= 8) {
        out.append(static_cast<char>((value >> shift) & 0xff));
    }
}
} // namespace

QByteArray histSyncPreimage(const QByteArray& groupId, const QByteArray& authorPub,
                            const QByteArray& msgId, uint64_t timestamp, const QByteArray& text)
{
    if (groupId.size() != GroupIdSize || authorPub.size() != PubKeySize
        || msgId.size() != MsgIdSize) {
        return {};
    }

    QByteArray out;
    out.reserve(static_cast<int>(sizeof(HistSyncDomain) - 1) + GroupIdSize + PubKeySize + MsgIdSize
                + 8 + Sha256Size);
    out.append(HistSyncDomain, static_cast<int>(sizeof(HistSyncDomain) - 1));
    out.append(groupId);
    out.append(authorPub);
    out.append(msgId);
    appendBigEndian64(out, timestamp);
    // The body is hashed, never embedded: a 1 MiB message and an empty one produce the same
    // pre-image length, so signature handling has no size-dependent path to get wrong.
    out.append(QCryptographicHash::hash(text, QCryptographicHash::Sha256));
    return out;
}

QByteArray announcePreimage(const QByteArray& toxPub, const QByteArray& hskPub,
                            uint64_t validFromTs)
{
    if (toxPub.size() != PubKeySize || hskPub.size() != PubKeySize) {
        return {};
    }

    QByteArray out;
    out.reserve(static_cast<int>(sizeof(AnnounceDomain) - 1) + PubKeySize + PubKeySize + 8);
    out.append(AnnounceDomain, static_cast<int>(sizeof(AnnounceDomain) - 1));
    out.append(toxPub);
    out.append(hskPub);
    appendBigEndian64(out, validFromTs);
    return out;
}

bool verifySignature(const QByteArray& preimage, const QByteArray& signature,
                     const QByteArray& signerPub)
{
    // Fail closed on anything malformed. An old client cannot reach here - it sends version 0x01,
    // which the dispatcher drops before this point - so a malformed signature is never "legacy".
    if (preimage.isEmpty() || signature.size() != SignatureSize
        || signerPub.size() != PubKeySize) {
        return false;
    }

    return crypto_sign_verify_detached(reinterpret_cast<const unsigned char*>(signature.constData()),
                                       reinterpret_cast<const unsigned char*>(preimage.constData()),
                                       static_cast<unsigned long long>(preimage.size()),
                                       reinterpret_cast<const unsigned char*>(signerPub.constData()))
        == 0;
}

namespace
{
const unsigned char kMagic[6] = {0x66, 0x77, 0x88, 0x11, 0x34, 0x35};

/** Reads 8 bytes big-endian. Never memcpy'd: host order must not leak into a wire format. */
uint64_t readBigEndian64(const unsigned char* p)
{
    uint64_t v = 0;
    for (int i = 0; i < 8; i++) {
        v = (v << 8) | static_cast<uint64_t>(p[i]);
    }
    return v;
}

/** Reads 4 bytes big-endian as UNSIGNED - a top-bit-set field must not become negative. */
uint64_t readBigEndian32Unsigned(const unsigned char* p)
{
    uint64_t v = 0;
    for (int i = 0; i < 4; i++) {
        v = (v << 8) | static_cast<uint64_t>(p[i]);
    }
    return v;
}

bool isSignedPacket(const unsigned char* data, int length, unsigned char pktId)
{
    if (data == nullptr || length < NgcHistSig::HeaderSize) {
        return false;
    }
    if (memcmp(data, kMagic, sizeof(kMagic)) != 0) {
        return false;
    }
    return data[6] == NgcHistSig::VersionSigned && data[7] == pktId;
}
} // namespace

bool parseAnnouncement(const unsigned char* data, int length, Announcement& out)
{
    // Exact length, not a minimum: this packet has no variable part, so anything longer is either a
    // different packet or an attempt to hide bytes after the signature.
    if (!isSignedPacket(data, length, PktHskAnnounce) || length != AnnouncePacketSize) {
        return false;
    }

    int pos = HeaderSize;
    out.hskPub = QByteArray(reinterpret_cast<const char*>(data + pos), PubKeySize);
    pos += PubKeySize;
    out.validFromTs = readBigEndian64(data + pos);
    pos += 8;
    out.signature = QByteArray(reinterpret_cast<const char*>(data + pos), SignatureSize);
    return true;
}

bool parseSignedText(const unsigned char* data, int length, SignedText& out)
{
    const int fixedBefore = HeaderSize + MsgIdSize + PubKeySize + 8 + PeerNameSize + 4;
    if (!isSignedPacket(data, length, PktSignedText) || length < fixedBefore + SignatureSize) {
        return false;
    }

    int pos = HeaderSize;
    out.msgId = QByteArray(reinterpret_cast<const char*>(data + pos), MsgIdSize);
    pos += MsgIdSize;
    out.authorPub = QByteArray(reinterpret_cast<const char*>(data + pos), PubKeySize);
    pos += PubKeySize;
    out.timestamp = readBigEndian64(data + pos);
    pos += 8;
    out.peerNameRaw = QByteArray(reinterpret_cast<const char*>(data + pos), PeerNameSize);
    pos += PeerNameSize;

    const uint64_t declaredTextLen = readBigEndian32Unsigned(data + pos);
    pos += 4;

    if (declaredTextLen > static_cast<uint64_t>(MaxTextBytes)) {
        return false;
    }
    // The bytes must be present AND the signature must follow them exactly - no trailing slack in
    // which to hide anything the signature does not cover.
    if (static_cast<uint64_t>(pos) + declaredTextLen + SignatureSize
        != static_cast<uint64_t>(length)) {
        return false;
    }

    out.textUtf8 = QByteArray(reinterpret_cast<const char*>(data + pos),
                              static_cast<int>(declaredTextLen));
    pos += static_cast<int>(declaredTextLen);
    out.signature = QByteArray(reinterpret_cast<const char*>(data + pos), SignatureSize);
    return true;
}

} // namespace NgcHistSig
