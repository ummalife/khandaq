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

#include "src/persistence/profilefile.h"

#include "src/core/toxencrypt.h"

#include <QTemporaryDir>
#include <QtTest/QtTest>

#include <tox/tox.h>

using Problem = ProfileFile::Contents::Problem;

/**
 * Reading a profile file before it is moved in (E20): a plain save and an encrypted one give the
 * name and the ID they hold, an encrypted one asks for its password and refuses a wrong one, and a
 * file that is not a save, or is cut short, says so.
 */
class TestProfileFile : public QObject
{
    Q_OBJECT

private slots:
    void initTestCase();

    void plainSaveGivesItsNameAndId();
    void encryptedSaveTakesItsPassword();
    void otherFilesAreNotProfiles();
    void cutShortSaveIsDamaged();
    void missingFileIsUnreadable();

private:
    /** A save made by toxcore without network, with this name. */
    static QByteArray makeSave(const QString& name, QString* id);

    QByteArray save;
    QString id;
};

QByteArray TestProfileFile::makeSave(const QString& name, QString* id)
{
    Tox_Options* const options = tox_options_new(nullptr);
    tox_options_set_udp_enabled(options, false);
    tox_options_set_local_discovery_enabled(options, false);
    tox_options_set_ipv6_enabled(options, false);
    Tox* const tox = tox_new(options, nullptr);
    tox_options_free(options);
    if (tox == nullptr) {
        return QByteArray();
    }
    const QByteArray bytes = name.toUtf8();
    tox_self_set_name(tox, reinterpret_cast<const uint8_t*>(bytes.constData()),
                      static_cast<size_t>(bytes.size()), nullptr);
    QByteArray address(TOX_ADDRESS_SIZE, '\0');
    tox_self_get_address(tox, reinterpret_cast<uint8_t*>(address.data()));
    *id = QString::fromLatin1(address.toHex()).toUpper();
    QByteArray data(static_cast<int>(tox_get_savedata_size(tox)), '\0');
    tox_get_savedata(tox, reinterpret_cast<uint8_t*>(data.data()));
    tox_kill(tox);
    return data;
}

void TestProfileFile::initTestCase()
{
    save = makeSave(QStringLiteral("Амина Рахимова"), &id);
    QVERIFY(!save.isEmpty());
    QCOMPARE(id.size(), 76);
}

void TestProfileFile::plainSaveGivesItsNameAndId()
{
    const ProfileFile::Contents contents = ProfileFile::read(save, QString());
    QCOMPARE(contents.problem, Problem::None);
    QVERIFY(!contents.encrypted);
    QCOMPARE(contents.name, QStringLiteral("Амина Рахимова"));
    QCOMPARE(contents.id, id);
    // A password typed for a plain file changes nothing.
    QCOMPARE(ProfileFile::read(save, QStringLiteral("Kitab2026")).id, id);

    QString unnamedId;
    const QByteArray unnamed = makeSave(QString(), &unnamedId);
    const ProfileFile::Contents noName = ProfileFile::read(unnamed, QString());
    QCOMPARE(noName.problem, Problem::None);
    QVERIFY(noName.name.isEmpty());
    QCOMPARE(noName.id, unnamedId);
}

void TestProfileFile::encryptedSaveTakesItsPassword()
{
    const QByteArray encrypted = ToxEncrypt::encryptPass(QStringLiteral("Kitab2026"), save);
    QVERIFY(!encrypted.isEmpty());

    const ProfileFile::Contents asked = ProfileFile::read(encrypted, QString());
    QCOMPARE(asked.problem, Problem::NeedsPassword);
    QVERIFY(asked.encrypted);
    QVERIFY(asked.id.isEmpty());

    const ProfileFile::Contents wrong = ProfileFile::read(encrypted, QStringLiteral("Kitab2025"));
    QCOMPARE(wrong.problem, Problem::WrongPassword);
    QVERIFY(!wrong.code.isEmpty());

    const ProfileFile::Contents opened = ProfileFile::read(encrypted, QStringLiteral("Kitab2026"));
    QCOMPARE(opened.problem, Problem::None);
    QVERIFY(opened.encrypted);
    QCOMPARE(opened.name, QStringLiteral("Амина Рахимова"));
    QCOMPARE(opened.id, id);
}

void TestProfileFile::otherFilesAreNotProfiles()
{
    QCOMPARE(ProfileFile::read(QByteArray(), QString()).problem, Problem::NotProfile);
    QCOMPARE(ProfileFile::read(QByteArrayLiteral("Not a profile, just words."), QString()).problem,
             Problem::NotProfile);
    // A picture renamed to .tox.
    QCOMPARE(ProfileFile::read(QByteArray::fromHex("89504e470d0a1a0a0000000d49484452"), QString()).problem,
             Problem::NotProfile);
}

void TestProfileFile::cutShortSaveIsDamaged()
{
    const ProfileFile::Contents cut = ProfileFile::read(save.left(30), QString());
    QCOMPARE(cut.problem, Problem::Damaged);
    QVERIFY(cut.code.startsWith(QStringLiteral("Tox_Err_New")));

    // Encrypted, then cut: the password cannot be told from the damage, and nothing loads.
    const QByteArray encrypted = ToxEncrypt::encryptPass(QStringLiteral("Kitab2026"), save);
    const ProfileFile::Contents cutEncrypted =
        ProfileFile::read(encrypted.left(encrypted.size() - 16), QStringLiteral("Kitab2026"));
    QCOMPARE(cutEncrypted.problem, Problem::WrongPassword);
}

void TestProfileFile::missingFileIsUnreadable()
{
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    QCOMPARE(ProfileFile::readFile(dir.filePath(QStringLiteral("absent.tox")), QString()).problem,
             Problem::Unreadable);

    QFile file(dir.filePath(QStringLiteral("Амина.tox")));
    QVERIFY(file.open(QIODevice::WriteOnly));
    file.write(save);
    file.close();
    const ProfileFile::Contents contents = ProfileFile::readFile(file.fileName(), QString());
    QCOMPARE(contents.problem, Problem::None);
    QCOMPARE(contents.id, id);
}

QTEST_GUILESS_MAIN(TestProfileFile)
#include "profilefile_test.moc"
