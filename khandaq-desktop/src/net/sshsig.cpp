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

#include "sshsig.h"

#include "src/core/ngchistsig.h"

#include <QCryptographicHash>

#include <utility>

namespace {
constexpr char beginLine[] = "-----BEGIN SSH SIGNATURE-----";
constexpr char endLine[] = "-----END SSH SIGNATURE-----";
constexpr char keyType[] = "ssh-ed25519";
constexpr char hashName[] = "sha512";
constexpr int keySize = 32;
constexpr int signatureSize = 64;
/** A signature is about 300 bytes; anything far beyond that is not one. */
constexpr int armourLimit = 16 * 1024;

/** The SSH wire format (RFC 4251): big-endian lengths, every read bounds-checked. */
class Reader
{
public:
    explicit Reader(QByteArray data_)
        : data{std::move(data_)}
    {}

    bool raw(int count, QByteArray& out)
    {
        if (count < 0 || count > data.size() - at) {
            return false;
        }
        out = data.mid(at, count);
        at += count;
        return true;
    }

    bool u32(quint32& out)
    {
        if (data.size() - at < 4) {
            return false;
        }
        const auto* bytes = reinterpret_cast<const uchar*>(data.constData() + at);
        out = (quint32{bytes[0]} << 24) | (quint32{bytes[1]} << 16) | (quint32{bytes[2]} << 8)
              | quint32{bytes[3]};
        at += 4;
        return true;
    }

    bool string(QByteArray& out)
    {
        quint32 length = 0;
        if (!u32(length) || length > static_cast<quint32>(data.size() - at)) {
            return false;
        }
        return raw(static_cast<int>(length), out);
    }

    bool atEnd() const
    {
        return at == data.size();
    }

private:
    QByteArray data;
    int at = 0;
};

QByteArray sshString(const QByteArray& value)
{
    const auto length = static_cast<quint32>(value.size());
    QByteArray out;
    out.reserve(4 + value.size());
    out.append(static_cast<char>((length >> 24) & 0xff));
    out.append(static_cast<char>((length >> 16) & 0xff));
    out.append(static_cast<char>((length >> 8) & 0xff));
    out.append(static_cast<char>(length & 0xff));
    out.append(value);
    return out;
}

bool isBase64Character(char c)
{
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+'
           || c == '/' || c == '=';
}

/** Qt 5.12 decodes base64 leniently, skipping what it does not know; only text that the decoded
 * bytes encode back to exactly is taken. */
bool decodeCanonical(const QByteArray& base64, QByteArray& out)
{
    if (base64.isEmpty() || base64.size() % 4 != 0) {
        return false;
    }
    for (const char c : base64) {
        if (!isBase64Character(c)) {
            return false;
        }
    }
    out = QByteArray::fromBase64(base64);
    return out.toBase64() == base64;
}

bool dearmor(const QByteArray& armored, QByteArray& blob)
{
    if (armored.size() > armourLimit) {
        return false;
    }
    const QByteArray text = armored.trimmed();
    const int beginSize = static_cast<int>(sizeof(beginLine)) - 1;
    const int endSize = static_cast<int>(sizeof(endLine)) - 1;
    if (text.size() < beginSize + endSize || !text.startsWith(beginLine)
        || !text.endsWith(endLine)) {
        return false;
    }
    const QByteArray body = text.mid(beginSize, text.size() - beginSize - endSize);
    // The armour lines stand on lines of their own.
    if (!(body.startsWith('\n') || body.startsWith("\r\n")) || !body.endsWith('\n')) {
        return false;
    }
    QByteArray base64;
    base64.reserve(body.size());
    for (const char c : body) {
        if (c == '\n' || c == '\r') {
            continue;
        }
        if (!isBase64Character(c)) {
            return false;
        }
        base64.append(c);
    }
    return decodeCanonical(base64, blob);
}
} // namespace

namespace SshSig {

QByteArray ed25519Key(const QByteArray& base64Blob)
{
    QByteArray blob;
    if (!decodeCanonical(base64Blob, blob)) {
        return {};
    }
    Reader reader(blob);
    QByteArray type;
    QByteArray key;
    if (!reader.string(type) || type != keyType || !reader.string(key) || key.size() != keySize
        || !reader.atEnd()) {
        return {};
    }
    return key;
}

bool verify(const QByteArray& message, const QByteArray& armored, const QByteArray& publicKey,
            const QByteArray& nameSpace)
{
    if (publicKey.size() != keySize || nameSpace.isEmpty()) {
        return false;
    }
    QByteArray blob;
    if (!dearmor(armored, blob)) {
        return false;
    }

    Reader reader(blob);
    QByteArray magic;
    quint32 version = 0;
    QByteArray signer;
    QByteArray signedNameSpace;
    QByteArray reserved;
    QByteArray hashAlgorithm;
    QByteArray signature;
    if (!reader.raw(6, magic) || magic != "SSHSIG" || !reader.u32(version) || version != 1
        || !reader.string(signer) || !reader.string(signedNameSpace) || !reader.string(reserved)
        || !reader.string(hashAlgorithm) || !reader.string(signature) || !reader.atEnd()) {
        return false;
    }
    if (signer != sshString(keyType) + sshString(publicKey) || signedNameSpace != nameSpace
        || !reserved.isEmpty() || hashAlgorithm != hashName) {
        return false;
    }

    Reader inner(signature);
    QByteArray type;
    QByteArray raw;
    if (!inner.string(type) || type != keyType || !inner.string(raw) || raw.size() != signatureSize
        || !inner.atEnd()) {
        return false;
    }

    // What the key signed: the magic, then the namespace, reserved field, hash name and the hash of
    // the message, each as an SSH string.
    const QByteArray digest = QCryptographicHash::hash(message, QCryptographicHash::Sha512);
    const QByteArray signedData = QByteArray("SSHSIG") + sshString(signedNameSpace)
                                  + sshString(reserved) + sshString(hashAlgorithm)
                                  + sshString(digest);
    return NgcHistSig::verifySignature(signedData, raw, publicKey);
}

} // namespace SshSig
