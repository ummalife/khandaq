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

#include "src/persistence/paths.h"
#include "src/persistence/profile.h"
#include "src/persistence/profilelocker.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QLockFile>
#include <QtTest/QtTest>

#include <memory>

/**
 * Profile::removeFiles, behind Delete at sign-in and a profile that did not start: the files of a
 * profile go only while nothing has it open, here or in another instance. The files are made in the
 * portable folder of the test, beside its binary, under a name no profile has.
 */
class TestProfileRemove : public QObject
{
    Q_OBJECT
private slots:
    void initTestCase();
    void init();
    void cleanup();
    void closedProfileGoes();
    void profileOpenElsewhereStays();
    void profileOpenHereStays();

private:
    QStringList files();
    QString lockPath() const;

    std::unique_ptr<Paths> paths;
    const QString name = QStringLiteral("khandaq-test-removed-profile");
};

void TestProfileRemove::initTestCase()
{
    paths.reset(new Paths(Paths::Portable::Portable));
    // Never the folder of a real profile: the one beside the test binary.
    QVERIFY(paths->isPortable());
    QCOMPARE(QDir(paths->getSettingsDirPath()).canonicalPath(),
             QDir(QCoreApplication::applicationDirPath()).canonicalPath());
}

QStringList TestProfileRemove::files()
{
    const QString base = paths->getSettingsDirPath() + name;
    return {base + QStringLiteral(".tox"), base + QStringLiteral(".ini"),
            Profile::getDbPath(name, *paths)};
}

QString TestProfileRemove::lockPath() const
{
    return ProfileLocker::lockPathFromName(name, *paths);
}

void TestProfileRemove::init()
{
    for (const QString& path : files()) {
        QFile file(path);
        QVERIFY(file.open(QIODevice::WriteOnly));
        QVERIFY(file.write("not a real profile") > 0);
    }
}

void TestProfileRemove::cleanup()
{
    ProfileLocker::unlock();
    for (const QString& path : files()) {
        QFile::remove(path);
    }
    QFile::remove(lockPath());
}

void TestProfileRemove::closedProfileGoes()
{
    QVERIFY(Profile::removeFiles(name, *paths).isEmpty());
    for (const QString& path : files()) {
        QVERIFY2(!QFile::exists(path), qPrintable(path));
    }
    // The lock held while the files went goes with them.
    QVERIFY(!QFile::exists(lockPath()));
}

void TestProfileRemove::profileOpenElsewhereStays()
{
    // Another instance holds the lock, as ProfileLocker::lock() takes it.
    QLockFile other(lockPath());
    other.setStaleLockTime(0);
    QVERIFY(other.tryLock());
    QCOMPARE(Profile::removeFiles(name, *paths), files());
    for (const QString& path : files()) {
        QVERIFY2(QFile::exists(path), qPrintable(path));
    }
    other.unlock();
}

void TestProfileRemove::profileOpenHereStays()
{
    // Open in this instance: its lock is ours, and its files still stay.
    QVERIFY(ProfileLocker::lock(name, *paths));
    QCOMPARE(Profile::removeFiles(name, *paths), files());
    for (const QString& path : files()) {
        QVERIFY2(QFile::exists(path), qPrintable(path));
    }
    QVERIFY(ProfileLocker::hasLock());
    QCOMPARE(ProfileLocker::getCurLockName(), name);
}

QTEST_GUILESS_MAIN(TestProfileRemove)
#include "profileremove_test.moc"
