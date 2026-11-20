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

#include "src/net/sshsig.h"
#include "src/net/updatemanifest.h"

#include <QFile>
#include <QtTest/QtTest>

/**
 * KHANDAQ (0.2.65): the app checks the manifest signature `ssh-keygen -Y sign` makes, and nothing
 * that only looks like one. The fixtures in test/net/update were signed with a throwaway key whose
 * private half was never kept: the manifest in the update namespace with SHA-512 (the genuine one),
 * with SHA-256, and in the namespace of the release artifacts.
 */
class TestSshSig : public QObject
{
    Q_OBJECT

private slots:
    void initTestCase();
    void readsTheTestKey();
    void readsTheReleaseKeyAsTheAppTrustsIt();
    void refusesKeysThatAreNotOneEd25519Key();
    void verifiesTheGenuineSignature();
    void acceptsCrlfArmour();
    void refusesAnotherMessage();
    void refusesAnotherKey();
    void refusesAnotherNameSpace();
    void refusesAnotherHash();
    void refusesATruncatedSignature();
    void refusesTrailingJunk();
    void refusesNonCanonicalBase64();
    void refusesBytesAfterTheBlob();
    void refusesAChangedByteAnywhere();
    void refusesOversizedArmour();

private:
    static QByteArray fixture(const QString& name);
    static QByteArray testKey();
    static QByteArray reArmor(const QByteArray& blob);
    static QByteArray blobOf(const QByteArray& armored);

    QByteArray manifest;
    QByteArray genuine;
};

namespace {
constexpr char updateNameSpace[] = "khandaq-desktop-update";
} // namespace

QByteArray TestSshSig::fixture(const QString& name)
{
    QFile file(QStringLiteral(":/update/") + name);
    if (!file.open(QIODevice::ReadOnly)) {
        return {};
    }
    return file.readAll();
}

QByteArray TestSshSig::testKey()
{
    const QList<QByteArray> parts = fixture(QStringLiteral("update-test.pub")).split(' ');
    return parts.size() >= 2 ? SshSig::ed25519Key(parts.at(1)) : QByteArray();
}

QByteArray TestSshSig::blobOf(const QByteArray& armored)
{
    QByteArray base64;
    for (const QByteArray& line : armored.split('\n')) {
        if (!line.startsWith("-----")) {
            base64 += line.trimmed();
        }
    }
    return QByteArray::fromBase64(base64);
}

QByteArray TestSshSig::reArmor(const QByteArray& blob)
{
    const QByteArray base64 = blob.toBase64();
    QByteArray armored("-----BEGIN SSH SIGNATURE-----\n");
    for (int at = 0; at < base64.size(); at += 70) {
        armored += base64.mid(at, 70) + '\n';
    }
    return armored + "-----END SSH SIGNATURE-----\n";
}

void TestSshSig::initTestCase()
{
    Q_INIT_RESOURCE(fixtures);
    manifest = fixture(QStringLiteral("khandaq-desktop-update.json"));
    genuine = fixture(QStringLiteral("khandaq-desktop-update.json.sig"));
    QVERIFY(!manifest.isEmpty());
    QVERIFY(!genuine.isEmpty());
    // Armouring the decoded blob again gives the file back: the helpers below change only what each
    // test says.
    QCOMPARE(reArmor(blobOf(genuine)), genuine);
}

void TestSshSig::readsTheTestKey()
{
    QCOMPARE(testKey().size(), 32);
}

void TestSshSig::readsTheReleaseKeyAsTheAppTrustsIt()
{
    const QVector<QByteArray> keys = UpdateEndpoint::trustedKeys();
    QVERIFY(!keys.isEmpty());
    QCOMPARE(keys.first().size(), 32);
    // The release key never verifies what the test key signed.
    QVERIFY(!SshSig::verify(manifest, genuine, keys.first(), updateNameSpace));
}

void TestSshSig::refusesKeysThatAreNotOneEd25519Key()
{
    const QByteArray blob = fixture(QStringLiteral("update-test.pub")).split(' ').at(1);
    QVERIFY(SshSig::ed25519Key(QByteArray()).isEmpty());
    QVERIFY(SshSig::ed25519Key("not base64!").isEmpty());
    // Lenient base64 would read these; the key is only read from its exact text.
    QVERIFY(SshSig::ed25519Key(blob + "AAAA").isEmpty());
    QVERIFY(SshSig::ed25519Key(blob.left(blob.size() - 4)).isEmpty());
    QVERIFY(SshSig::ed25519Key(" " + blob).isEmpty());
    // Another key type with the same length.
    QByteArray decoded = QByteArray::fromBase64(blob);
    decoded.replace("ssh-ed25519", "ssh-ed25518");
    QVERIFY(SshSig::ed25519Key(decoded.toBase64()).isEmpty());
}

void TestSshSig::verifiesTheGenuineSignature()
{
    QVERIFY(SshSig::verify(manifest, genuine, testKey(), updateNameSpace));
}

void TestSshSig::acceptsCrlfArmour()
{
    QByteArray crlf = genuine;
    crlf.replace("\n", "\r\n");
    QVERIFY(SshSig::verify(manifest, crlf, testKey(), updateNameSpace));
}

void TestSshSig::refusesAnotherMessage()
{
    QByteArray changed = manifest;
    changed.replace("0.2.65", "0.2.66");
    QVERIFY(changed != manifest);
    QVERIFY(!SshSig::verify(changed, genuine, testKey(), updateNameSpace));
    QVERIFY(!SshSig::verify(manifest + "\n", genuine, testKey(), updateNameSpace));
    QVERIFY(!SshSig::verify(QByteArray(), genuine, testKey(), updateNameSpace));
}

void TestSshSig::refusesAnotherKey()
{
    QByteArray other = testKey();
    other[0] = static_cast<char>(other.at(0) ^ 0x01);
    QVERIFY(!SshSig::verify(manifest, genuine, other, updateNameSpace));
    QVERIFY(!SshSig::verify(manifest, genuine, QByteArray(), updateNameSpace));
    QVERIFY(!SshSig::verify(manifest, genuine, testKey().left(31), updateNameSpace));
}

void TestSshSig::refusesAnotherNameSpace()
{
    // Signed for the release artifacts: valid there, never for the manifest.
    const QByteArray releaseSignature = fixture(QStringLiteral("khandaq-desktop-update.json.release.sig"));
    QVERIFY(SshSig::verify(manifest, releaseSignature, testKey(), "khandaq-release"));
    QVERIFY(!SshSig::verify(manifest, releaseSignature, testKey(), updateNameSpace));
    QVERIFY(!SshSig::verify(manifest, genuine, testKey(), "khandaq-release"));
    QVERIFY(!SshSig::verify(manifest, genuine, testKey(), QByteArray()));
}

void TestSshSig::refusesAnotherHash()
{
    // A genuine signature by the right key in the right namespace, over SHA-256: refused all the
    // same, as only SHA-512 is taken.
    const QByteArray sha256 = fixture(QStringLiteral("khandaq-desktop-update.json.sha256.sig"));
    QVERIFY(!sha256.isEmpty());
    QVERIFY(!SshSig::verify(manifest, sha256, testKey(), updateNameSpace));
}

void TestSshSig::refusesATruncatedSignature()
{
    const QByteArray blob = blobOf(genuine);
    for (int cut = 1; cut < blob.size(); cut += 7) {
        QVERIFY2(!SshSig::verify(manifest, reArmor(blob.left(blob.size() - cut)), testKey(),
                                 updateNameSpace),
                 qPrintable(QString::number(cut)));
    }
    QVERIFY(!SshSig::verify(manifest, genuine.left(genuine.size() / 2), testKey(), updateNameSpace));
    QVERIFY(!SshSig::verify(manifest, QByteArray(), testKey(), updateNameSpace));
}

void TestSshSig::refusesTrailingJunk()
{
    QVERIFY(!SshSig::verify(manifest, genuine + "junk\n", testKey(), updateNameSpace));
    QVERIFY(!SshSig::verify(manifest, "junk\n" + genuine, testKey(), updateNameSpace));
    QByteArray inside = genuine;
    inside.replace("-----END", "AAAA\n-----END");
    QVERIFY(!SshSig::verify(manifest, inside, testKey(), updateNameSpace));
    // The armour lines stand on lines of their own.
    QByteArray joined = genuine;
    joined.replace("-----\n", "-----");
    QVERIFY(!SshSig::verify(manifest, joined, testKey(), updateNameSpace));
}

void TestSshSig::refusesNonCanonicalBase64()
{
    // A character base64 does not have, which a lenient decoder would skip.
    QByteArray skipped = genuine;
    skipped.insert(genuine.indexOf('\n') + 5, '*');
    QVERIFY(!SshSig::verify(manifest, skipped, testKey(), updateNameSpace));
    QByteArray spaced = genuine;
    spaced.insert(genuine.indexOf('\n') + 5, ' ');
    QVERIFY(!SshSig::verify(manifest, spaced, testKey(), updateNameSpace));
    // The same bytes in other text: padding the blob does not need, and padding inside it. A lenient
    // decoder reads both as the genuine signature.
    const QByteArray base64 = blobOf(genuine).toBase64();
    QVERIFY(!base64.endsWith('='));
    for (const QByteArray& text : {base64 + "====", base64.left(128) + "====" + base64.mid(128)}) {
        QByteArray armored("-----BEGIN SSH SIGNATURE-----\n");
        for (int at = 0; at < text.size(); at += 70) {
            armored += text.mid(at, 70) + '\n';
        }
        armored += "-----END SSH SIGNATURE-----\n";
        QVERIFY(!SshSig::verify(manifest, armored, testKey(), updateNameSpace));
    }
    // A key blob whose last character carries bits the bytes do not have.
    const QByteArray blob = fixture(QStringLiteral("update-test.pub")).split(' ').at(1);
    QByteArray padded = QByteArray::fromBase64(blob);
    padded.append('\0');
    QByteArray loose = padded.toBase64();
    QVERIFY(loose.endsWith("=="));
    const QByteArray alphabet("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/");
    const int last = loose.indexOf('=') - 1;
    loose[last] = alphabet.at(alphabet.indexOf(loose.at(last)) | 0x01);
    QCOMPARE(QByteArray::fromBase64(loose), padded);
    QVERIFY(SshSig::ed25519Key(loose).isEmpty());
}

void TestSshSig::refusesBytesAfterTheBlob()
{
    QVERIFY(!SshSig::verify(manifest, reArmor(blobOf(genuine) + QByteArray(1, '\0')), testKey(),
                            updateNameSpace));
    QVERIFY(!SshSig::verify(manifest, reArmor(blobOf(genuine) + QByteArray(4, '\0')), testKey(),
                            updateNameSpace));
}

void TestSshSig::refusesAChangedByteAnywhere()
{
    const QByteArray blob = blobOf(genuine);
    for (int at = 0; at < blob.size(); ++at) {
        QByteArray changed = blob;
        changed[at] = static_cast<char>(changed.at(at) ^ 0x20);
        QVERIFY2(!SshSig::verify(manifest, reArmor(changed), testKey(), updateNameSpace),
                 qPrintable(QString::number(at)));
    }
}

void TestSshSig::refusesOversizedArmour()
{
    QByteArray padded = genuine;
    padded.prepend(QByteArray(17 * 1024, '\n'));
    QVERIFY(!SshSig::verify(manifest, padded, testKey(), updateNameSpace));
}

QTEST_GUILESS_MAIN(TestSshSig)
#include "sshsig_test.moc"
