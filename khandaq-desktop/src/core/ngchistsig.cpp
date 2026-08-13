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
} // namespace NgcHistSig
