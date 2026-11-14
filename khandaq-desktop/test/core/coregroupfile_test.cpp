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

#include "mock/mockbootstraplistgenerator.h"
#include "mock/mockcoresettings.h"
#include "src/core/core.h"
#include "src/core/khandaqlimits.h"

#include <QSignalSpy>
#include <QTemporaryDir>
#include <QtTest/QtTest>

#include <tox/tox.h>

namespace {
constexpr qint64 payload = static_cast<qint64>(NGC_CHUNK_PAYLOAD_MAX);
} // namespace

// 0.2.56: what the window's thread sees of a file it sends into a group, on cores that are never
// started: a group founded here is all there is, and nothing goes out. A file of more than one
// packet is queued and its chunks are left to the loop of the node; their order and pace are
// Air::GroupFileQueue's and Air::UploadPace's (airgroupfilequeue_test).
class TestCoreGroupFile : public QObject
{
    Q_OBJECT
private slots:
    void initTestCase();
    void aFileOfSeveralChunksIsQueuedForTheLoop();
    void aGroupThatRefusesTheBeginRefusesTheFile();
    void aFileThatWaitsForAnotherSendsItsBeginInItsTurn();
    void aFileOfOnePacketGoesAtOnce();
    void aFileThatCannotBeReadIsRefused();

private:
    ToxCorePtr makeCore();
    QString writeFile(const QString& name, qint64 size);

    QTemporaryDir dir;
    MockSettings settings;
    MockBootstrapListGenerator nodes;
};

ToxCorePtr TestCoreGroupFile::makeCore()
{
    Core::ToxCoreErrors error = Core::ToxCoreErrors::FAILED_TO_START;
    return Core::makeToxCore(QByteArray(), settings, nodes, &error);
}

QString TestCoreGroupFile::writeFile(const QString& name, qint64 size)
{
    const QString path = dir.filePath(name);
    QFile file(path);
    if (!file.open(QIODevice::WriteOnly)) {
        return QString();
    }
    QByteArray bytes(static_cast<int>(size), '\0');
    for (int i = 0; i < bytes.size(); ++i) {
        bytes[i] = static_cast<char>(i % 251);
    }
    file.write(bytes);
    return path;
}

void TestCoreGroupFile::initTestCase()
{
    QVERIFY(dir.isValid());
    settings.setProxyAddr(QString());
    settings.setProxyPort(0);
    settings.setProxyType(MockSettings::ProxyType::ptNone);
}

void TestCoreGroupFile::aFileOfSeveralChunksIsQueuedForTheLoop()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Files"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(group >= 0);
    const qint64 size = 3 * payload + 5;
    const QString path = writeFile(QStringLiteral("video.mp4"), size);
    QVERIFY(!path.isEmpty());
    QSignalSpy started(core.get(), &Core::groupFileSendStarted);
    QSignalSpy sent(core.get(), &Core::groupFileSent);
    QSignalSpy failed(core.get(), &Core::groupFileSendFailed);

    QVERIFY(core->sendGroupFileFromPath(group, QStringLiteral("video.mp4"), path));

    // The window's thread queued the file and sent its BEGIN; the chunks wait for the loop.
    QCOMPARE(started.count(), 1);
    const QList<QVariant> args = started.takeFirst();
    QCOMPARE(args.at(0).toInt(), group);
    QCOMPARE(args.at(1).toString(), QStringLiteral("video.mp4"));
    QCOMPARE(args.at(2).toString(), path);
    QCOMPARE(args.at(3).toLongLong(), size);
    QCOMPARE(args.at(4).toByteArray().size(), 32);
    QCOMPARE(sent.count(), 0);
    QCOMPARE(failed.count(), 0);
}

void TestCoreGroupFile::aGroupThatRefusesTheBeginRefusesTheFile()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Files"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(group >= 0);
    Tox_Err_Group_Disconnect disconnectError;
    QVERIFY(tox_group_disconnect(core->getTox(), static_cast<uint32_t>(group), &disconnectError));
    const QString path = writeFile(QStringLiteral("refused.bin"), 2 * payload);
    QSignalSpy started(core.get(), &Core::groupFileSendStarted);
    QSignalSpy sent(core.get(), &Core::groupFileSent);

    // As before: the person is told at once that the group is not connected.
    QVERIFY(!core->sendGroupFileFromPath(group, QStringLiteral("refused.bin"), path));
    QCOMPARE(started.count(), 0);
    QCOMPARE(sent.count(), 0);

    // Nothing of it waits either: the next file is the first of the group, and refused the same.
    QVERIFY(!core->sendGroupFileFromPath(group, QStringLiteral("refused.bin"), path));
    QCOMPARE(started.count(), 0);
}

void TestCoreGroupFile::aFileThatWaitsForAnotherSendsItsBeginInItsTurn()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Files"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(group >= 0);
    const QString first = writeFile(QStringLiteral("first.bin"), 2 * payload);
    const QString second = writeFile(QStringLiteral("second.bin"), 2 * payload);
    QSignalSpy started(core.get(), &Core::groupFileSendStarted);

    QVERIFY(core->sendGroupFileFromPath(group, QStringLiteral("first.bin"), first));
    Tox_Err_Group_Disconnect disconnectError;
    QVERIFY(tox_group_disconnect(core->getTox(), static_cast<uint32_t>(group), &disconnectError));
    // The second file waits for the chunks of the first: its BEGIN is not sent now, so the group
    // cannot refuse it yet. Its turn will, and the conversation will say it was not sent.
    QVERIFY(core->sendGroupFileFromPath(group, QStringLiteral("second.bin"), second));
    QCOMPARE(started.count(), 2);
    QVERIFY(started.at(0).at(4).toByteArray() != started.at(1).at(4).toByteArray());

    // Another group sends its own first file at once, and refuses it at once.
    const int other = core->createGroup(QStringLiteral("Other"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(other >= 0);
    QVERIFY(tox_group_disconnect(core->getTox(), static_cast<uint32_t>(other), &disconnectError));
    QVERIFY(!core->sendGroupFileFromPath(other, QStringLiteral("second.bin"), second));
    QCOMPARE(started.count(), 2);
}

void TestCoreGroupFile::aFileOfOnePacketGoesAtOnce()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Files"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(group >= 0);
    const QString path =
        writeFile(QStringLiteral("note.txt"), static_cast<qint64>(NGC_SINGLE_PKT_MAX_FILESIZE));
    QSignalSpy started(core.get(), &Core::groupFileSendStarted);
    QSignalSpy sent(core.get(), &Core::groupFileSent);

    QVERIFY(core->sendGroupFileFromPath(group, QStringLiteral("note.txt"), path));
    QCOMPARE(started.count(), 0);
    QCOMPARE(sent.count(), 1);
    QCOMPARE(sent.at(0).at(3).toLongLong(), static_cast<qint64>(NGC_SINGLE_PKT_MAX_FILESIZE));
}

void TestCoreGroupFile::aFileThatCannotBeReadIsRefused()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Files"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(group >= 0);
    QSignalSpy started(core.get(), &Core::groupFileSendStarted);

    QVERIFY(!core->sendGroupFileFromPath(group, QStringLiteral("gone.bin"),
                                         dir.filePath(QStringLiteral("gone.bin"))));
    const QString empty = writeFile(QStringLiteral("empty.bin"), 0);
    QVERIFY(!core->sendGroupFileFromPath(group, QStringLiteral("empty.bin"), empty));
    // A group that is not there takes no file either.
    const QString path = writeFile(QStringLiteral("nowhere.bin"), 2 * payload);
    QVERIFY(!core->sendGroupFileFromPath(group + 1, QStringLiteral("nowhere.bin"), path));
    QCOMPARE(started.count(), 0);
}

QTEST_GUILESS_MAIN(TestCoreGroupFile)
#include "coregroupfile_test.moc"
