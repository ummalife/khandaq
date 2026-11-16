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

#include <QDateTime>
#include <QSignalSpy>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QtEndian>
#include <QtTest/QtTest>

#include <tox/tox.h>

namespace {
constexpr qint64 payload = static_cast<qint64>(NGC_CHUNK_PAYLOAD_MAX);

// The packets of a chunked file as Android lays them out (NgcGroupFileTransfer.buildBeginPacket
// and buildChunkPacket): magic, version and packet id, the msg id, then big-endian numbers.
QByteArray ngcPacket(char packetId, const QByteArray& msgId, int size)
{
    QByteArray packet(size, '\0');
    const char head[] = {'\x66', '\x77', '\x88', '\x11', '\x34', '\x35', '\x01'};
    packet.replace(0, static_cast<int>(sizeof(head)), head, static_cast<int>(sizeof(head)));
    packet[7] = packetId;
    packet.replace(8, msgId.size(), msgId);
    return packet;
}

QByteArray beginPacket(const QByteArray& msgId, const QString& name, qint64 size)
{
    QByteArray packet = ngcPacket('\x12', msgId, static_cast<int>(NGC_FILE_BEGIN_SIZE));
    // A zero create_ts(4) at 40, then the name at 44, padded with zeros to 255 bytes.
    const QByteArray utf8 = name.toUtf8();
    packet.replace(44, utf8.size(), utf8);
    qToBigEndian(static_cast<quint64>(size), packet.data() + 299);
    qToBigEndian(static_cast<quint32>(payload), packet.data() + 307);
    qToBigEndian(static_cast<quint32>((size + payload - 1) / payload), packet.data() + 311);
    return packet;
}

QByteArray chunkPacket(const QByteArray& msgId, const QByteArray& file, int index)
{
    const QByteArray bytes =
        file.mid(static_cast<int>(index * payload), static_cast<int>(payload));
    QByteArray packet =
        ngcPacket('\x13', msgId, static_cast<int>(NGC_FILE_CHUNK_HEADER_SIZE) + bytes.size());
    qToBigEndian(static_cast<quint32>(index), packet.data() + 40);
    qToBigEndian(static_cast<quint32>(bytes.size()), packet.data() + 44);
    packet.replace(static_cast<int>(NGC_FILE_CHUNK_HEADER_SIZE), bytes.size(), bytes);
    return packet;
}

// A file a member relays from the history of the group (0x03, only privately): the msg id, the
// key it names for the author, its time, a name of 25 bytes nobody reads, the file name, then the
// bytes (core.cpp, NGC_SYNC_FILE_HEADER_SIZE).
constexpr int syncHeaderSize = 6 + 1 + 1 + 32 + 32 + 4 + 25 + 255;

QByteArray syncPacket(const QByteArray& msgId, const QByteArray& author, const QString& name,
                      const QByteArray& bytes)
{
    QByteArray packet = ngcPacket('\x03', msgId, syncHeaderSize + bytes.size());
    packet.replace(40, author.size(), author);
    qToBigEndian(static_cast<quint32>(QDateTime::currentSecsSinceEpoch()), packet.data() + 72);
    const QByteArray utf8 = name.toUtf8();
    packet.replace(101, utf8.size(), utf8);
    packet.replace(syncHeaderSize, bytes.size(), bytes);
    return packet;
}

void hand(Core& core, int group, uint32_t peer, const QByteArray& packet, bool isPrivate)
{
    core.handleNgcFileTransferPacket(static_cast<uint32_t>(group), peer,
                                     reinterpret_cast<const uint8_t*>(packet.constData()),
                                     static_cast<size_t>(packet.size()), isPrivate);
}

// Where the receiver assembles a chunked file (core.cpp, the BEGIN of handleNgcFileTransferPacket).
QString scratchPath(const QByteArray& msgId)
{
    return QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)
           + QStringLiteral("/ngc_files/") + QString::fromLatin1(msgId.toHex())
           + QStringLiteral(".part");
}
} // namespace

// 0.2.56: what the window's thread sees of a file it sends into a group, on cores that are never
// started: a group founded here is all there is, and nothing goes out. A file of more than one
// packet is queued and its chunks are left to the loop of the node; their order and pace are
// Air::GroupFileQueue's and Air::UploadPace's (airgroupfilequeue_test). The packets of a file
// that comes are handed to the core as toxcore's callbacks would, from a member that is not there.
class TestCoreGroupFile : public QObject
{
    Q_OBJECT
private slots:
    void initTestCase();
    void cleanupTestCase();
    void aFileOfSeveralChunksIsQueuedForTheLoop();
    void aGroupThatIsNotConnectedRefusesTheFile();
    void aFileThatWaitsForAnotherIsRefusedOnceTheGroupIsNotConnected();
    void aFileOfOnePacketGoesAtOnce();
    void aFileThatCannotBeReadIsRefused();
    void aChunkedFileThatComesPrivatelyArrives();
    void aChunkedFileThatComesBothWaysArrivesOnce();
    void aRelayedFileThatNamesUsIsDroppedWithoutBurningItsId();

private:
    ToxCorePtr makeCore();
    QString writeFile(const QString& name, qint64 size);
    QByteArray fileBytes(qint64 size);

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
    file.write(fileBytes(size));
    return path;
}

QByteArray TestCoreGroupFile::fileBytes(qint64 size)
{
    QByteArray bytes(static_cast<int>(size), '\0');
    for (int i = 0; i < bytes.size(); ++i) {
        bytes[i] = static_cast<char>(i % 251);
    }
    return bytes;
}

void TestCoreGroupFile::initTestCase()
{
    QVERIFY(dir.isValid());
    // The files that come are assembled under the app's data: the tests keep theirs apart.
    QStandardPaths::setTestModeEnabled(true);
    settings.setProxyAddr(QString());
    settings.setProxyPort(0);
    settings.setProxyType(MockSettings::ProxyType::ptNone);
}

void TestCoreGroupFile::cleanupTestCase()
{
    const QString data = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    QDir(data + QStringLiteral("/ngc_files")).removeRecursively();
    // And the folders test mode made for it, as far up as they are empty.
    QDir().rmpath(data);
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

    // The window's thread queued the file; its BEGIN and its chunks wait for the loop.
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

void TestCoreGroupFile::aGroupThatIsNotConnectedRefusesTheFile()
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

    // Nothing of it waits either: the next file is refused the same.
    QVERIFY(!core->sendGroupFileFromPath(group, QStringLiteral("refused.bin"), path));
    QCOMPARE(started.count(), 0);
}

void TestCoreGroupFile::aFileThatWaitsForAnotherIsRefusedOnceTheGroupIsNotConnected()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Files"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(group >= 0);
    const QString first = writeFile(QStringLiteral("first.bin"), 2 * payload);
    const QString second = writeFile(QStringLiteral("second.bin"), 2 * payload);
    QSignalSpy started(core.get(), &Core::groupFileSendStarted);

    // Both wait for the loop, the second for the chunks of the first.
    QVERIFY(core->sendGroupFileFromPath(group, QStringLiteral("first.bin"), first));
    QVERIFY(core->sendGroupFileFromPath(group, QStringLiteral("second.bin"), second));
    QCOMPARE(started.count(), 2);
    QVERIFY(started.at(0).at(4).toByteArray() != started.at(1).at(4).toByteArray());

    // A file chosen once the group is not connected is refused at once, though it would wait.
    Tox_Err_Group_Disconnect disconnectError;
    QVERIFY(tox_group_disconnect(core->getTox(), static_cast<uint32_t>(group), &disconnectError));
    QVERIFY(!core->sendGroupFileFromPath(group, QStringLiteral("second.bin"), second));
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

void TestCoreGroupFile::aChunkedFileThatComesPrivatelyArrives()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Files"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(group >= 0);
    Tox_Err_Group_Self_Query selfError;
    const uint32_t peer =
        tox_group_self_get_peer_id(core->getTox(), static_cast<uint32_t>(group), &selfError) + 1;
    const QByteArray msgId(32, 'p');
    const QByteArray file = fileBytes(2 * payload + 1234);
    QSignalSpy received(core.get(), &Core::groupFileReceived);

    // Android sends a large file to each member alone: its BEGIN and every chunk.
    hand(*core, group, peer, beginPacket(msgId, QStringLiteral("clip.mp4"), file.size()), true);
    hand(*core, group, peer, chunkPacket(msgId, file, 0), true);
    hand(*core, group, peer, chunkPacket(msgId, file, 1), true);
    QCOMPARE(received.count(), 0);
    hand(*core, group, peer, chunkPacket(msgId, file, 2), true);

    QCOMPARE(received.count(), 1);
    const QList<QVariant> args = received.takeFirst();
    QCOMPARE(args.at(0).toInt(), group);
    QCOMPARE(args.at(2).toString(), QStringLiteral("clip.mp4"));
    QCOMPARE(args.at(3).toByteArray(), file);
    QCOMPARE(args.at(5).toBool(), false);
    QCOMPARE(args.at(6).toByteArray(), msgId);
    QVERIFY(!QFile::exists(scratchPath(msgId)));
}

void TestCoreGroupFile::aChunkedFileThatComesBothWaysArrivesOnce()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Files"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(group >= 0);
    Tox_Err_Group_Self_Query selfError;
    const uint32_t peer =
        tox_group_self_get_peer_id(core->getTox(), static_cast<uint32_t>(group), &selfError) + 1;
    const QByteArray msgId(32, 'q');
    const QByteArray file = fileBytes(2 * payload + 77);
    const QByteArray begin = beginPacket(msgId, QStringLiteral("photo.jpg"), file.size());
    QSignalSpy received(core.get(), &Core::groupFileReceived);

    // Both copies of the BEGIN open one assembly, and both copies of a chunk fill one place.
    hand(*core, group, peer, begin, false);
    hand(*core, group, peer, chunkPacket(msgId, file, 0), true);
    hand(*core, group, peer, begin, true);
    hand(*core, group, peer, chunkPacket(msgId, file, 0), false);
    hand(*core, group, peer, chunkPacket(msgId, file, 2), false);
    QCOMPARE(received.count(), 0);
    hand(*core, group, peer, chunkPacket(msgId, file, 1), true);
    QCOMPARE(received.count(), 1);
    QCOMPARE(received.at(0).at(3).toByteArray(), file);

    // The copies that come after it change nothing, and a late BEGIN opens no assembly again.
    hand(*core, group, peer, chunkPacket(msgId, file, 2), true);
    hand(*core, group, peer, chunkPacket(msgId, file, 1), false);
    hand(*core, group, peer, begin, true);
    QVERIFY(!QFile::exists(scratchPath(msgId)));
    for (int chunk = 0; chunk < 3; ++chunk) {
        hand(*core, group, peer, chunkPacket(msgId, file, chunk), true);
    }
    QCOMPARE(received.count(), 1);
}

void TestCoreGroupFile::aRelayedFileThatNamesUsIsDroppedWithoutBurningItsId()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Files"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(group >= 0);
    Tox_Err_Group_Self_Query selfError;
    const uint32_t peer =
        tox_group_self_get_peer_id(core->getTox(), static_cast<uint32_t>(group), &selfError) + 1;
    const QByteArray msgId(32, 'r');
    const QByteArray bytes = fileBytes(2000);
    const QByteArray ourGroupKey = core->getGroupSelfPublicKey(group).getByteArray();
    QCOMPARE(ourGroupKey.size(), 32);
    const QByteArray member(32, '\x5a');
    // The key each file names, as the window gets it.
    QVector<ToxPk> senders;
    QVector<bool> relayed;
    connect(core.get(), &Core::groupFileReceived, this,
            [&](int, const ToxPk& sender, const QString&, const QByteArray& data, const QDateTime&,
                bool wasSynced, const QByteArray& id) {
                QCOMPARE(data, bytes);
                QCOMPARE(id, msgId);
                senders.append(sender);
                relayed.append(wasSynced);
            },
            Qt::DirectConnection);

    // Every member sees our key in the group: a record that names it is a forgery, or our own file
    // echoed back. So is one that names our Tox key, and one that names nobody.
    hand(*core, group, peer, syncPacket(msgId, ourGroupKey, QStringLiteral("x.png"), bytes), true);
    hand(*core, group, peer,
         syncPacket(msgId, core->getSelfPublicKey().getByteArray(), QStringLiteral("x.png"), bytes),
         true);
    hand(*core, group, peer, syncPacket(msgId, QByteArray(32, '\0'), QStringLiteral("x.png"), bytes),
         true);
    QVERIFY(senders.isEmpty());

    // None of them burnt the id: the record of the member who wrote it comes, and only once.
    hand(*core, group, peer, syncPacket(msgId, member, QStringLiteral("photo.png"), bytes), true);
    hand(*core, group, peer, syncPacket(msgId, member, QStringLiteral("photo.png"), bytes), true);
    QCOMPARE(senders, (QVector<ToxPk>{ToxPk(member)}));
    QCOMPARE(relayed, (QVector<bool>{true}));
}

QTEST_GUILESS_MAIN(TestCoreGroupFile)
#include "coregroupfile_test.moc"
