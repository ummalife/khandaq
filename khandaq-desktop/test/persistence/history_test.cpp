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

#include "src/persistence/history.h"
#include "src/core/groupid.h"
#include "src/core/toxfile.h"
#include "src/core/toxpk.h"
#include "src/persistence/db/rawdatabase.h"
#include "src/persistence/settings.h"
#include "src/widget/tool/imessageboxmanager.h"

#include <QApplication>
#include <QDateTime>
#include <QTemporaryDir>
#include <QTemporaryFile>
#include <QtTest/QtTest>

#include <memory>

namespace {
const GroupId family{QByteArray(GroupId::size, 'g')};
const ToxPk maryam{QByteArray(ToxPk::size, 'm')};
const ToxPk self{QByteArray(ToxPk::size, 's')};
const QByteArray fileId{"0123456789abcdef0123456789abcdef"};
const QString filePath{QStringLiteral("/files/photo.jpg")};

class SilentMessageBoxes : public IMessageBoxManager
{
public:
    ~SilentMessageBoxes() override = default;
    void showInfo(const QString& title, const QString& msg) override
    {
        std::ignore = title;
        std::ignore = msg;
    }
    void showWarning(const QString& title, const QString& msg) override
    {
        std::ignore = title;
        std::ignore = msg;
    }
    void showError(const QString& title, const QString& msg) override
    {
        std::ignore = title;
        std::ignore = msg;
    }
    bool askQuestion(const QString& title, const QString& msg, bool defaultAns = false,
                     bool warning = true, bool yesno = true) override
    {
        std::ignore = title;
        std::ignore = msg;
        std::ignore = warning;
        std::ignore = yesno;
        return defaultAns;
    }
    bool askQuestion(const QString& title, const QString& msg, const QString& button1,
                     const QString& button2, bool defaultAns = false, bool warning = true) override
    {
        std::ignore = title;
        std::ignore = msg;
        std::ignore = button1;
        std::ignore = button2;
        std::ignore = warning;
        return defaultAns;
    }
    void confirmExecutableOpen(const QFileInfo& file) override
    {
        std::ignore = file;
    }
};
} // namespace

/**
 * The files of a group in the history, on a real database: what a later start reads of them.
 */
class TestHistory : public QObject
{
    Q_OBJECT
private slots:
    void initTestCase();
    void init();
    void cleanup();
    void cleanupTestCase();
    void aGroupFileFromAMemberIsStoredAsReceived();
    void aGroupFileWeSendIsStoredAsSent();
    void aFileThatEndsBeforeItsRowIsInKeepsItsEnd();
    void textsBetweenTwoPeopleKeepTheirMsgV3Ids();
    void aReadReceiptFindsOnlyOurOwnMessage();
    void theNewestMessageOfThePersonIsWhatWeSaw();
    void anAcceptedFileOfThePersonIsKeptAsInterrupted();
    void aFileThatGoesOnAfterARestartEndsInItsRow();
    void aFileOfThePersonThatEndedCancelledIsRefusedAfterARestart();
    void anInterruptedFileKeepsWhatTellsItsPartAfterARestart();
    void aLaterFileOfAPersonThatGoesToThePathIsKnown();

private:
    /** The one file of the chat, as a later start reads it. */
    ToxFile storedFile(const ChatId& chat = family);
    /** A later start of the app: a history of its own on the same database. */
    void restart();
    /** Lets the history learn the row of what it put in (History::onFileInserted). */
    void letRowsIn();

    SilentMessageBoxes messageBoxes;
    std::unique_ptr<Settings> settings;
    std::unique_ptr<QTemporaryFile> file;
    std::shared_ptr<History> history;
};

void TestHistory::initTestCase()
{
    // As Nexus does at start: the rows the database put in reach the history through a queue.
    qRegisterMetaType<RowId>("RowId");
    settings.reset(new Settings(messageBoxes));
    settings->setEnableLogging(true);
}

void TestHistory::init()
{
    file.reset(new QTemporaryFile());
    QVERIFY(file->open());
    file->close();
    auto db = std::make_shared<RawDatabase>(file->fileName(), QString(), QByteArray());
    history = std::make_shared<History>(db, *settings, messageBoxes);
    QVERIFY(history->isValid());
}

void TestHistory::cleanup()
{
    history.reset();
    file.reset();
}

void TestHistory::cleanupTestCase()
{
    settings.reset();
}

ToxFile TestHistory::storedFile(const ChatId& chat)
{
    const auto messages = history->getMessagesForChat(chat, 0, 1);
    if (messages.size() != 1
        || messages.first().content.getType() != HistMessageContentType::file) {
        return ToxFile();
    }
    return messages.first().content.asFile();
}

void TestHistory::letRowsIn()
{
    history->sync();
    QCoreApplication::processEvents();
}

void TestHistory::restart()
{
    history->sync();
    history.reset();
    auto db = std::make_shared<RawDatabase>(file->fileName(), QString(), QByteArray());
    history = std::make_shared<History>(db, *settings, messageBoxes);
    QVERIFY(history->isValid());
}

/**
 * @brief 0.2.56: a file a member sent the group is stored as received, so that a later start shows
 * it as one that came, not as one of ours that did not go. Its sender is the member, never the
 * chat, which told the direction for a file from a person only.
 */
void TestHistory::aGroupFileFromAMemberIsStoredAsReceived()
{
    history->addNewFileMessage(family, fileId, QStringLiteral("photo.jpg"), filePath, 1234, maryam,
                               QDateTime::currentDateTime(), QStringLiteral("Maryam"),
                               ToxFile::RECEIVING);
    letRowsIn();
    history->setFileFinished(fileId, true, filePath, QByteArray("hash"));

    QTRY_COMPARE(storedFile().status, ToxFile::FINISHED);
    QCOMPARE(storedFile().direction, ToxFile::RECEIVING);
    QCOMPARE(storedFile().fileName, QStringLiteral("photo.jpg"));
}

/**
 * @brief A file we send the group stays ours.
 */
void TestHistory::aGroupFileWeSendIsStoredAsSent()
{
    history->addNewFileMessage(family, fileId, QStringLiteral("photo.jpg"), filePath, 1234, self,
                               QDateTime::currentDateTime(), QStringLiteral("Me"),
                               ToxFile::SENDING);
    letRowsIn();
    history->setFileFinished(fileId, true, filePath, QByteArray("hash"));

    QTRY_COMPARE(storedFile().status, ToxFile::FINISHED);
    QCOMPARE(storedFile().direction, ToxFile::SENDING);
}

/**
 * @brief 0.2.56: a group file that arrives whole ends at once (Widget::dispatchGroupFileToChatLog),
 * before the database has put its row in. The end waits for the row instead of being forgotten,
 * which left the file at the state a row begins with (cancelled) for every later start.
 */
void TestHistory::aFileThatEndsBeforeItsRowIsInKeepsItsEnd()
{
    history->addNewFileMessage(family, fileId, QStringLiteral("photo.jpg"), filePath, 1234, maryam,
                               QDateTime::currentDateTime(), QStringLiteral("Maryam"),
                               ToxFile::RECEIVING);
    history->setFileFinished(fileId, true, filePath, QByteArray("hash"));

    QTRY_COMPARE(storedFile().status, ToxFile::FINISHED);
    QCOMPARE(storedFile().filePath, filePath);
    QCOMPARE(storedFile().direction, ToxFile::RECEIVING);
}

/**
 * @brief KHANDAQ (0.2.57): a text between two people keeps the msgV3 id it went or came with
 * (schema 13); one without, from 0.2.56 or an old client, keeps none.
 */
void TestHistory::textsBetweenTwoPeopleKeepTheirMsgV3Ids()
{
    const QByteArray theirs(32, '\x01');
    const QByteArray ours(32, '\x02');
    const QDateTime start = QDateTime::fromMSecsSinceEpoch(1700000000000);
    history->addNewMessage(maryam, QStringLiteral("Salam"), maryam, start, true, ExtensionSet(),
                           QStringLiteral("Maryam"), {}, theirs);
    history->addNewMessage(maryam, QStringLiteral("plain"), maryam, start.addSecs(1), true,
                           ExtensionSet(), QStringLiteral("Maryam"));
    history->addNewMessage(maryam, QStringLiteral("Wa alaykum"), self, start.addSecs(2), true,
                           ExtensionSet(), QStringLiteral("Me"), {}, ours);

    const auto messages = history->getMessagesForChat(maryam, 0, 3);
    QCOMPARE(messages.size(), 3);
    QCOMPARE(messages[0].msgV3Id, theirs);
    QVERIFY(messages[1].msgV3Id.isEmpty());
    QCOMPARE(messages[2].msgV3Id, ours);

    // A text that comes again is known by its author and id, in that chat only.
    QVERIFY(history->hasText(maryam, maryam, theirs));
    QVERIFY(!history->hasText(maryam, self, theirs));
    QVERIFY(!history->hasText(maryam, maryam, ours));
    QVERIFY(!history->hasText(maryam, maryam, QByteArray()));
    QVERIFY(!history->hasText(ToxPk(QByteArray(ToxPk::size, 'o')), maryam, theirs));
}

/**
 * @brief A read receipt names one of our texts by its id or one of our files by its file id: the
 * time it has in the conversation. One of theirs, or of another chat, names nothing.
 */
void TestHistory::aReadReceiptFindsOnlyOurOwnMessage()
{
    const QByteArray theirs(32, '\x01');
    const QByteArray ours(32, '\x02');
    const QDateTime start = QDateTime::fromMSecsSinceEpoch(1700000000123);
    history->addNewMessage(maryam, QStringLiteral("Salam"), maryam, start, true, ExtensionSet(),
                           QStringLiteral("Maryam"), {}, theirs);
    history->addNewMessage(maryam, QStringLiteral("Wa alaykum"), self, start.addSecs(2), true,
                           ExtensionSet(), QStringLiteral("Me"), {}, ours);
    const QByteArray ourFile = QByteArray(32, 'F');
    history->addNewFileMessage(maryam, ourFile, QStringLiteral("plan.pdf"), filePath, 10, self,
                               start.addSecs(3), QStringLiteral("Me"), ToxFile::SENDING);
    history->addNewFileMessage(maryam, fileId, QStringLiteral("photo.jpg"), filePath, 10, maryam,
                               start.addSecs(4), QStringLiteral("Maryam"), ToxFile::RECEIVING);
    letRowsIn();

    Air::SeenAnchor anchor;
    anchor.anchor = Air::MessageEffect::Anchor::Text;
    anchor.id = ours;
    QCOMPARE(history->ownItemTime(maryam, self, anchor), start.addSecs(2).toMSecsSinceEpoch());
    anchor.id = theirs;
    QCOMPARE(history->ownItemTime(maryam, self, anchor), qint64{-1});
    anchor.id = QByteArray(32, '\x7F');
    QCOMPARE(history->ownItemTime(maryam, self, anchor), qint64{-1});
    anchor.id = ours;
    QCOMPARE(history->ownItemTime(ToxPk(QByteArray(ToxPk::size, 'o')), self, anchor), qint64{-1});

    anchor.anchor = Air::MessageEffect::Anchor::File;
    anchor.id = ourFile;
    QCOMPARE(history->ownItemTime(maryam, self, anchor), start.addSecs(3).toMSecsSinceEpoch());
    anchor.id = fileId;
    QCOMPARE(history->ownItemTime(maryam, self, anchor), qint64{-1});
    // A text id is no file id.
    anchor.id = ours;
    QCOMPARE(history->ownItemTime(maryam, self, anchor), qint64{-1});
}

/**
 * @brief What a read receipt we send names: the newest text of the person that has a msgV3 id, or
 * the newest file they sent, whichever came last; never ours, and never one they deleted.
 */
void TestHistory::theNewestMessageOfThePersonIsWhatWeSaw()
{
    QVERIFY(history->newestIncomingAnchor(maryam, self).isEmpty());

    const QByteArray first(32, '\x01');
    const QByteArray later(32, '\x03');
    const QDateTime start = QDateTime::fromMSecsSinceEpoch(1700000000000);
    history->addNewMessage(maryam, QStringLiteral("Salam"), maryam, start, true, ExtensionSet(),
                           QStringLiteral("Maryam"), {}, first);
    // From an old client: no id, so no receipt can name it.
    history->addNewMessage(maryam, QStringLiteral("plain"), maryam, start.addSecs(1), true,
                           ExtensionSet(), QStringLiteral("Maryam"));
    history->addNewMessage(maryam, QStringLiteral("ours"), self, start.addSecs(2), true,
                           ExtensionSet(), QStringLiteral("Me"), {}, QByteArray(32, '\x02'));

    Air::SeenAnchor anchor = history->newestIncomingAnchor(maryam, self);
    QCOMPARE(anchor.anchor, Air::MessageEffect::Anchor::Text);
    QCOMPARE(anchor.id, first);
    QCOMPARE(anchor.timeMs, start.toMSecsSinceEpoch());

    history->addNewFileMessage(maryam, fileId, QStringLiteral("photo.jpg"), filePath, 10, maryam,
                               start.addSecs(3), QStringLiteral("Maryam"), ToxFile::RECEIVING);
    history->addNewFileMessage(maryam, QByteArray(32, 'F'), QStringLiteral("plan.pdf"), filePath,
                               10, self, start.addSecs(4), QStringLiteral("Me"), ToxFile::SENDING);
    letRowsIn();
    anchor = history->newestIncomingAnchor(maryam, self);
    QCOMPARE(anchor.anchor, Air::MessageEffect::Anchor::File);
    QCOMPARE(anchor.id, fileId);
    QCOMPARE(anchor.timeMs, start.addSecs(3).toMSecsSinceEpoch());

    history->addNewMessage(maryam, QStringLiteral("later"), maryam, start.addSecs(5), true,
                           ExtensionSet(), QStringLiteral("Maryam"), {}, later);
    QCOMPARE(history->newestIncomingAnchor(maryam, self).id, later);

    // Deleted by her: the one before it again.
    history->setMessageText(maryam, maryam, start.addSecs(5), QStringLiteral("later"), QString());
    QCOMPARE(history->newestIncomingAnchor(maryam, self).id, fileId);
    history->eraseFileEntry(maryam, fileId);
    QCOMPARE(history->newestIncomingAnchor(maryam, self).id, first);

    // Another chat has none of it.
    QVERIFY(history->newestIncomingAnchor(ToxPk(QByteArray(ToxPk::size, 'o')), self).isEmpty());
}

/**
 * @brief KHANDAQ (0.2.57): a file of the person, once accepted, is kept as interrupted with the
 * path it goes to, until its end writes the row again: a restart in the middle of it knows what
 * came.
 */
void TestHistory::anAcceptedFileOfThePersonIsKeptAsInterrupted()
{
    history->addNewFileMessage(maryam, fileId, QStringLiteral("video.mp4"), QString(), 5000, maryam,
                               QDateTime::currentDateTime(), QStringLiteral("Maryam"),
                               ToxFile::RECEIVING);
    history->setFileUnderway(maryam, fileId, filePath);
    history->sync();
    QCOMPARE(storedFile(maryam).status, ToxFile::BROKEN);
    QCOMPARE(storedFile(maryam).filePath, filePath);
    QCOMPARE(storedFile(maryam).resumeFileId, fileId);

    letRowsIn();
    history->setFileFinished(fileId, true, filePath, QByteArray("hash"));
    history->sync();
    QCOMPARE(storedFile(maryam).status, ToxFile::FINISHED);

    // The same id in another chat is another file.
    const ToxPk other{QByteArray(ToxPk::size, 'o')};
    history->addNewFileMessage(other, fileId, QStringLiteral("video.mp4"), QString(), 5000, other,
                               QDateTime::currentDateTime(), QStringLiteral("Omar"),
                               ToxFile::RECEIVING);
    history->setFileUnderway(maryam, fileId, QStringLiteral("/files/elsewhere.mp4"));
    history->sync();
    QCOMPARE(storedFile(other).status, ToxFile::CANCELED);
    QCOMPARE(storedFile(other).filePath, QString());
}

/**
 * @brief KHANDAQ (0.2.57): after a restart, a file offered again with the id of its row goes on in
 * that row: its end writes it, and no row is added. Its hash may not be known.
 */
void TestHistory::aFileThatGoesOnAfterARestartEndsInItsRow()
{
    history->addNewFileMessage(maryam, fileId, QStringLiteral("video.mp4"), QString(), 5000, maryam,
                               QDateTime::currentDateTime(), QStringLiteral("Maryam"),
                               ToxFile::RECEIVING);
    history->setFileUnderway(maryam, fileId, filePath);
    letRowsIn();
    restart();
    QCOMPARE(storedFile(maryam).status, ToxFile::BROKEN);

    // Without the row again, its end has nowhere to go.
    history->setFileFinished(fileId, true, filePath, QByteArray());
    history->sync();
    QCOMPARE(storedFile(maryam).status, ToxFile::BROKEN);

    restart();
    history->reopenFileMessage(maryam, fileId, ToxFile::SENDING);
    history->reopenFileMessage(maryam, fileId, ToxFile::RECEIVING);
    history->setFileFinished(fileId, true, filePath, QByteArray());
    history->sync();
    QCOMPARE(storedFile(maryam).status, ToxFile::FINISHED);
    QCOMPARE(storedFile(maryam).filePath, filePath);
    QCOMPARE(history->getNumMessagesForChat(maryam), size_t{1});
}

/**
 * @brief KHANDAQ (0.2.57): a file of the person whose row ended cancelled counts as one they
 * refused after a restart: the row does not say who stopped it, so an offer of it again waits for
 * their tap (ToxFile::refusedHere). One that was interrupted, and one of ours, are none.
 */
void TestHistory::aFileOfThePersonThatEndedCancelledIsRefusedAfterARestart()
{
    history->addNewFileMessage(maryam, fileId, QStringLiteral("video.mp4"), QString(), 5000, maryam,
                               QDateTime::currentDateTime(), QStringLiteral("Maryam"),
                               ToxFile::RECEIVING);
    const ToxPk omar{QByteArray(ToxPk::size, 'o')};
    const QByteArray cutId{"fedcba9876543210fedcba9876543210"};
    history->addNewFileMessage(omar, cutId, QStringLiteral("talk.mkv"), QString(), 5000, omar,
                               QDateTime::currentDateTime(), QStringLiteral("Omar"),
                               ToxFile::RECEIVING);
    history->setFileUnderway(omar, cutId, filePath);
    const ToxPk yusuf{QByteArray(ToxPk::size, 'y')};
    const QByteArray oursId{"00112233445566778899aabbccddeeff"};
    history->addNewFileMessage(yusuf, oursId, QStringLiteral("notes.zip"), filePath, 5000, self,
                               QDateTime::currentDateTime(), QStringLiteral("Me"),
                               ToxFile::SENDING);
    letRowsIn();
    history->setFileFinished(fileId, false, QString(), QByteArray());
    history->setFileFinished(oursId, false, filePath, QByteArray());
    restart();

    QCOMPARE(storedFile(maryam).status, ToxFile::CANCELED);
    QVERIFY(storedFile(maryam).refusedHere);
    QCOMPARE(storedFile(omar).status, ToxFile::BROKEN);
    QVERIFY(!storedFile(omar).refusedHere);
    QCOMPARE(storedFile(yusuf).status, ToxFile::CANCELED);
    QVERIFY(!storedFile(yusuf).refusedHere);
}

/**
 * @brief KHANDAQ (0.2.57): an interrupted file of the person keeps what tells the part it left
 * (ToxFile::Stamp) for a later start, as it changes; once it ends, its hash takes that place and
 * nothing of it is read as a stamp.
 */
void TestHistory::anInterruptedFileKeepsWhatTellsItsPartAfterARestart()
{
    history->addNewFileMessage(maryam, fileId, QStringLiteral("video.mp4"), QString(), 5000, maryam,
                               QDateTime::currentDateTime(), QStringLiteral("Maryam"),
                               ToxFile::RECEIVING);
    ToxFile::Stamp made;
    made.bornMs = 1700000000123;
    history->setFileUnderway(maryam, fileId, filePath, made);
    letRowsIn();
    restart();
    QCOMPARE(storedFile(maryam).status, ToxFile::BROKEN);
    QCOMPARE(storedFile(maryam).stamp, made);

    ToxFile::Stamp cut = made;
    cut.bytes = 2500;
    cut.modifiedMs = 1700000004567;
    history->setFileUnderway(maryam, fileId, filePath, cut);
    restart();
    QCOMPARE(storedFile(maryam).stamp, cut);

    history->reopenFileMessage(maryam, fileId, ToxFile::RECEIVING);
    history->setFileFinished(fileId, true, filePath, QByteArray(32, '\x5c'));
    restart();
    QCOMPARE(storedFile(maryam).status, ToxFile::FINISHED);
    QCOMPARE(storedFile(maryam).stamp, ToxFile::Stamp());
}

/**
 * @brief KHANDAQ (0.2.57): a file of a person that came after another goes to that one's path too:
 * what is there may be the later one. One that came before, one of ours, or another path is none.
 */
void TestHistory::aLaterFileOfAPersonThatGoesToThePathIsKnown()
{
    history->addNewFileMessage(maryam, fileId, QStringLiteral("photo.jpg"), QString(), 5000, maryam,
                               QDateTime::currentDateTime(), QStringLiteral("Maryam"),
                               ToxFile::RECEIVING);
    history->setFileUnderway(maryam, fileId, filePath);
    const ToxPk omar{QByteArray(ToxPk::size, 'o')};
    const QByteArray laterId{"fedcba9876543210fedcba9876543210"};
    history->addNewFileMessage(omar, laterId, QStringLiteral("photo.jpg"), QString(), 7000, omar,
                               QDateTime::currentDateTime(), QStringLiteral("Omar"),
                               ToxFile::RECEIVING);
    history->setFileUnderway(omar, laterId, filePath);
    const ToxPk yusuf{QByteArray(ToxPk::size, 'y')};
    history->addNewFileMessage(yusuf, QByteArray("00112233445566778899aabbccddeeff"),
                               QStringLiteral("photo.jpg"), filePath, 9000, self,
                               QDateTime::currentDateTime(), QStringLiteral("Me"),
                               ToxFile::SENDING);
    letRowsIn();

    QVERIFY(history->laterFileGoesTo(maryam, fileId, filePath));
    QVERIFY(!history->laterFileGoesTo(omar, laterId, filePath));
    QVERIFY(!history->laterFileGoesTo(maryam, fileId, QStringLiteral("/files/other.jpg")));
    QVERIFY(!history->laterFileGoesTo(maryam, laterId, filePath));
}

int main(int argc, char* argv[])
{
    // The settings need a platform, not a screen: CI runs the tests without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    // Settings reads and creates its folder under the home folder: a scratch one, not the user's.
    QTemporaryDir home;
    if (!home.isValid()) {
        return 1;
    }
    qputenv("HOME", home.path().toLocal8Bit());
    QApplication app(argc, argv);
    TestHistory test;
    return QTest::qExec(&test, argc, argv);
}

#include "history_test.moc"
