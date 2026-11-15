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

private:
    /** The one file of the group, as a later start reads it. */
    ToxFile storedFile();
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

ToxFile TestHistory::storedFile()
{
    const auto messages = history->getMessagesForChat(family, 0, 1);
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
