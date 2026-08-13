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
#include <QtTest/QtTest>

#include <sodium.h>

/**
 * KHANDAQ (external audit #2, finding 1) — proves this client builds the SAME bytes as the frozen
 * reference in ngc_histsync_vectors.py at the repository root.
 *
 * The signature primitive is Ed25519 from libsodium and will not diverge between platforms. The
 * pre-image absolutely can, and a divergence there means signatures silently stop verifying across
 * clients while every implementation's own tests pass. Hence: the digests below are copied from the
 * reference, and any drift fails here.
 */
class TestNgcHistSig : public QObject
{
    Q_OBJECT

private slots:
    void initTestCase();

    // pre-image agreement with the frozen reference vectors
    void asciiBasic();
    void emptyText();
    void utf8Multibyte();
    void timestampAbove32Bit();
    void timestampMaxU64();
    void zeroAuthorKey();
    void announceBasic();
    void announceZeroTs();

    // structural guarantees the format relies on
    void preimageLengthIsIndependentOfMessageSize();
    void domainsAreDistinct();

    // verification behaviour
    void verifiesAGenuineSignature();
    void rejectsATamperedMessage();
    void rejectsAWrongSigner();
    void rejectsMalformedInputs();

private:
    static QByteArray repeated(unsigned char byte, int n)
    {
        return QByteArray(n, static_cast<char>(byte));
    }
    static QByteArray counting32()
    {
        QByteArray b;
        for (int i = 0; i < 32; ++i) {
            b.append(static_cast<char>(i));
        }
        return b;
    }
    static QString sha256Hex(const QByteArray& data)
    {
        return QString::fromLatin1(
            QCryptographicHash::hash(data, QCryptographicHash::Sha256).toHex());
    }

    const QByteArray groupA = counting32();
    const QByteArray groupB = repeated(0xff, 32);
    const QByteArray authorA = repeated(0xAA, 32);
    const QByteArray authorB = repeated(0x00, 32);
    const QByteArray msgId = QByteArray::fromHex("deadbeef");
    static constexpr uint64_t tsBase = 1754870400ULL;
};

void TestNgcHistSig::initTestCase()
{
    QVERIFY2(sodium_init() >= 0, "libsodium failed to initialise");
}

void TestNgcHistSig::asciiBasic()
{
    const auto pre = NgcHistSig::histSyncPreimage(groupA, authorA, msgId, tsBase, "hello");
    QCOMPARE(sha256Hex(pre),
             QStringLiteral("33599061b75b2c487120a845450367ee880c931d6c00095960f8c3828f3457ed"));
}

/** An implementation that skips hashing an empty body, or substitutes null, diverges here. */
void TestNgcHistSig::emptyText()
{
    const auto pre = NgcHistSig::histSyncPreimage(groupA, authorA, msgId, tsBase, QByteArray{});
    QCOMPARE(sha256Hex(pre),
             QStringLiteral("d008aea0521ebdc8ac4488263746b0c63c7819b37aa966edcdf89ff7121b711f"));
}

/** Catches a platform hashing UTF-16 (native Java/Swift strings) instead of UTF-8. */
void TestNgcHistSig::utf8Multibyte()
{
    const auto pre = NgcHistSig::histSyncPreimage(groupA, authorA, msgId, tsBase,
                                                  QString::fromUtf8("Привет, мир 👋").toUtf8());
    QCOMPARE(sha256Hex(pre),
             QStringLiteral("752e856237a501d9bb3c278d97b445b2d428375f54edf0692ffbb80818dafd49"));
}

/**
 * The wire format transmits only the LOW 4 bytes of the timestamp today, while the pre-image signs
 * all 8. An implementation that truncates to 32 bits passes every other vector and fails this one.
 */
void TestNgcHistSig::timestampAbove32Bit()
{
    const auto pre = NgcHistSig::histSyncPreimage(groupA, authorA, msgId, 0x0000000100000001ULL, "x");
    QCOMPARE(sha256Hex(pre),
             QStringLiteral("b16b06ed10ce4bec25661e486350f2b8b4018014b9ddcb8cbcc35ca51779507d"));
}

/** An implementation using a SIGNED 64-bit timestamp wraps negative here. */
void TestNgcHistSig::timestampMaxU64()
{
    const auto pre = NgcHistSig::histSyncPreimage(groupA, authorA, msgId, UINT64_MAX, "x");
    QCOMPARE(sha256Hex(pre),
             QStringLiteral("e867afdab7e3f201da8b48259530733400dce9caf5d1306e22af0989e69db055"));
}

/** An all-zero pubkey must produce a normal pre-image, not be treated as an absent field. */
void TestNgcHistSig::zeroAuthorKey()
{
    const auto pre =
        NgcHistSig::histSyncPreimage(groupB, authorB, QByteArray(4, '\0'), 0, QByteArray{});
    QCOMPARE(sha256Hex(pre),
             QStringLiteral("0c0a9029409cf4a617254a0f5bc2fa78119ca3f4766ce77f377ade1f38defa99"));
}

void TestNgcHistSig::announceBasic()
{
    QByteArray hsk;
    for (int i = 0; i < 16; ++i) {
        hsk.append(static_cast<char>(0x11));
        hsk.append(static_cast<char>(0x22));
    }
    const auto pre = NgcHistSig::announcePreimage(authorA, hsk, tsBase);
    QCOMPARE(sha256Hex(pre),
             QStringLiteral("70b75055b020a79fbc70fe27fb7d48adebf585dcf6fd79f9a1f58d195d11a88b"));
}

/** validFromTs == 0 must not be confused with "field absent". */
void TestNgcHistSig::announceZeroTs()
{
    QByteArray hsk;
    for (int i = 0; i < 16; ++i) {
        hsk.append(static_cast<char>(0x11));
        hsk.append(static_cast<char>(0x22));
    }
    const auto pre = NgcHistSig::announcePreimage(authorB, hsk, 0);
    QCOMPARE(sha256Hex(pre),
             QStringLiteral("b25c730609d8e16646630ff6f8dff11046dfaa4d8f617bda18c948fa843de278"));
}

/**
 * The body is hashed rather than embedded, so signature handling has no size-dependent path. If
 * someone "optimises" that away the pre-image starts growing and this catches it immediately.
 */
void TestNgcHistSig::preimageLengthIsIndependentOfMessageSize()
{
    const auto small = NgcHistSig::histSyncPreimage(groupA, authorA, msgId, tsBase, "x");
    const auto large = NgcHistSig::histSyncPreimage(groupA, authorA, msgId, tsBase,
                                                    QByteArray(1024 * 1024, 'x'));
    QCOMPARE(small.size(), 121);
    QCOMPARE(large.size(), 121);
    QVERIFY(small != large);
    QCOMPARE(NgcHistSig::announcePreimage(authorA, authorB, tsBase).size(), 89);
}

/** A history signature must never validate as an announcement signature, and vice versa. */
void TestNgcHistSig::domainsAreDistinct()
{
    const QByteArray hist(NgcHistSig::HistSyncDomain);
    const QByteArray announce(NgcHistSig::AnnounceDomain);
    QVERIFY(hist != announce);
    QVERIFY(!announce.startsWith(hist));
    QVERIFY(!hist.startsWith(announce));
}

void TestNgcHistSig::verifiesAGenuineSignature()
{
    unsigned char pk[crypto_sign_PUBLICKEYBYTES];
    unsigned char sk[crypto_sign_SECRETKEYBYTES];
    crypto_sign_keypair(pk, sk);

    const auto pre = NgcHistSig::histSyncPreimage(groupA, authorA, msgId, tsBase, "hello");
    unsigned char sig[crypto_sign_BYTES];
    crypto_sign_detached(sig, nullptr, reinterpret_cast<const unsigned char*>(pre.constData()),
                         static_cast<unsigned long long>(pre.size()), sk);

    QVERIFY(NgcHistSig::verifySignature(pre, QByteArray(reinterpret_cast<char*>(sig), sizeof(sig)),
                                        QByteArray(reinterpret_cast<char*>(pk), sizeof(pk))));
}

/** The whole point: changing the message must invalidate the signature. */
void TestNgcHistSig::rejectsATamperedMessage()
{
    unsigned char pk[crypto_sign_PUBLICKEYBYTES];
    unsigned char sk[crypto_sign_SECRETKEYBYTES];
    crypto_sign_keypair(pk, sk);

    const auto genuine = NgcHistSig::histSyncPreimage(groupA, authorA, msgId, tsBase, "send 10");
    unsigned char sig[crypto_sign_BYTES];
    crypto_sign_detached(sig, nullptr, reinterpret_cast<const unsigned char*>(genuine.constData()),
                         static_cast<unsigned long long>(genuine.size()), sk);

    const auto tampered = NgcHistSig::histSyncPreimage(groupA, authorA, msgId, tsBase, "send 100");
    QVERIFY(!NgcHistSig::verifySignature(tampered,
                                         QByteArray(reinterpret_cast<char*>(sig), sizeof(sig)),
                                         QByteArray(reinterpret_cast<char*>(pk), sizeof(pk))));
}

/** Signed by a real key, but not the one we attribute the message to — the impersonation case. */
void TestNgcHistSig::rejectsAWrongSigner()
{
    unsigned char pkGood[crypto_sign_PUBLICKEYBYTES], skGood[crypto_sign_SECRETKEYBYTES];
    unsigned char pkEvil[crypto_sign_PUBLICKEYBYTES], skEvil[crypto_sign_SECRETKEYBYTES];
    crypto_sign_keypair(pkGood, skGood);
    crypto_sign_keypair(pkEvil, skEvil);

    const auto pre = NgcHistSig::histSyncPreimage(groupA, authorA, msgId, tsBase, "hello");
    unsigned char sig[crypto_sign_BYTES];
    crypto_sign_detached(sig, nullptr, reinterpret_cast<const unsigned char*>(pre.constData()),
                         static_cast<unsigned long long>(pre.size()), skEvil);

    QVERIFY(!NgcHistSig::verifySignature(pre, QByteArray(reinterpret_cast<char*>(sig), sizeof(sig)),
                                         QByteArray(reinterpret_cast<char*>(pkGood), sizeof(pkGood))));
}

/** Everything malformed must fail CLOSED, including a wrong-sized field in the pre-image builder. */
void TestNgcHistSig::rejectsMalformedInputs()
{
    const QByteArray sig(64, '\x01');
    const QByteArray key(32, '\x02');
    const QByteArray pre = NgcHistSig::histSyncPreimage(groupA, authorA, msgId, tsBase, "hello");

    QVERIFY(!NgcHistSig::verifySignature(QByteArray{}, sig, key));      // empty pre-image
    QVERIFY(!NgcHistSig::verifySignature(pre, QByteArray(63, '\x01'), key)); // short signature
    QVERIFY(!NgcHistSig::verifySignature(pre, QByteArray(65, '\x01'), key)); // long signature
    QVERIFY(!NgcHistSig::verifySignature(pre, sig, QByteArray(31, '\x02')));  // short key

    // A wrong-sized fixed field yields an EMPTY pre-image rather than a short one, so it can never
    // be silently signed or verified.
    QVERIFY(NgcHistSig::histSyncPreimage(QByteArray(31, '\0'), authorA, msgId, tsBase, "x").isEmpty());
    QVERIFY(NgcHistSig::histSyncPreimage(groupA, QByteArray(33, '\0'), msgId, tsBase, "x").isEmpty());
    QVERIFY(NgcHistSig::histSyncPreimage(groupA, authorA, QByteArray(3, '\0'), tsBase, "x").isEmpty());
    QVERIFY(NgcHistSig::announcePreimage(QByteArray(31, '\0'), authorA, tsBase).isEmpty());
}

QTEST_GUILESS_MAIN(TestNgcHistSig)
#include "ngchistsig_test.moc"
