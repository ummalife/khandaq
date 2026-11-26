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

#include "src/model/notes.h"
#include "src/core/icoreidhandler.h"
#include "src/friendlist.h"
#include "src/model/sessionchatlog.h"
#include "src/persistence/db/rawdatabase.h"
#include "src/persistence/db/upgrades/dbupgrader.h"
#include "src/persistence/outbox.h"
#include "src/grouplist.h"

#include <QDateTime>
#include <QSignalSpy>
#include <QTemporaryFile>
#include <QtTest/QtTest>

#include <memory>

namespace {
class SelfIds : public ICoreIdHandler
{
public:
    ToxId getSelfId() const override
    {
        std::terminate();
        return ToxId();
    }

    ToxPk getSelfPublicKey() const override
    {
        return ToxPk(QByteArray(32, 's'));
    }

    QString getUsername() const override
    {
        return QStringLiteral("Maryam");
    }
};

QDateTime at(int minute)
{
    return QDateTime(QDate(2026, 11, 10), QTime(9, minute), Qt::UTC);
}
} // namespace

/**
 * Saved Messages: a note deleted where the log shows it leaves the database and the log, and only
 * that note; the indexes of the others stay where they are. KHANDAQ (beta feedback 2026-11-22): a
 * note edited takes its new words in the log and the database, marked "edited" after a restart too.
 */
class TestNotes : public QObject
{
    Q_OBJECT
private slots:
    void init();
    void cleanup();
    void aDeletedNoteLeavesTheDatabaseAndTheLog();
    void aNoteWrittenNowCanBeDeletedToo();
    void onlyANoteCanBeDeletedAndOnlyOnce();
    void withoutADatabaseANoteIsOnlyHidden();
    void anEditedNoteKeepsItsWordsAfterARestart();
    void aNoteWrittenNowCanBeEditedToo();
    void anEditMakesAnActionOrUndoesOne();
    void onlyANoteTakesAnEditAndOnlyWithNewWords();
    void withoutADatabaseAnEditStaysUntilTheAppQuits();

private:
    std::unique_ptr<SessionChatLog> open(SavedNotes* notes);

    std::unique_ptr<QTemporaryFile> file;
    std::shared_ptr<RawDatabase> db;
    std::unique_ptr<SavedNotes> notes;
    std::unique_ptr<NotesDispatcher> dispatcher;
    SelfIds selfIds;
    FriendList friendList;
    GroupList groupList;
};

void TestNotes::init()
{
    // The spies read the index each update carries.
    qRegisterMetaType<ChatLogIdx>();
    file.reset(new QTemporaryFile());
    QVERIFY(file->open());
    file->close();
    db.reset(new RawDatabase{file->fileName(), {}, {}});
    QVERIFY(DbUpgrader::createCurrentSchema(*db));
    notes.reset(new SavedNotes(db));
}

void TestNotes::cleanup()
{
    dispatcher.reset();
    notes.reset();
    db.reset();
    file.reset();
}

std::unique_ptr<SessionChatLog> TestNotes::open(SavedNotes* savedNotes)
{
    dispatcher.reset(new NotesDispatcher(savedNotes));
    return makeNotesLog(savedNotes, *dispatcher, selfIds, friendList, groupList);
}

void TestNotes::aDeletedNoteLeavesTheDatabaseAndTheLog()
{
    notes->add(at(1), QStringLiteral("Buy dates"));
    notes->add(at(2), QStringLiteral("Call the library"));
    notes->add(at(3), QStringLiteral("Read the second volume"));
    const auto log = open(notes.get());
    QCOMPARE(log->getNextIdx(), ChatLogIdx(3));

    QSignalSpy updated(log.get(), &IChatLog::itemUpdated);
    QVERIFY(dispatcher->removeNote(ChatLogIdx(1)));
    QCOMPARE(updated.count(), 1);
    QCOMPARE(qvariant_cast<ChatLogIdx>(updated[0][0]), ChatLogIdx(1));

    // The note is gone from the log, the others keep their place.
    QVERIFY(log->isShown(ChatLogIdx(0)));
    QVERIFY(!log->isShown(ChatLogIdx(1)));
    QVERIFY(log->isCancelled(ChatLogIdx(1)));
    QVERIFY(log->isShown(ChatLogIdx(2)));
    QCOMPARE(log->getNextIdx(), ChatLogIdx(3));
    QVERIFY(!log->searchForward({ChatLogIdx(0), 0}, QStringLiteral("library"), ParameterSearch()).found);
    QVERIFY(!log->searchBackward({ChatLogIdx(2), 0}, QStringLiteral("library"), ParameterSearch()).found);
    QVERIFY(log->searchForward({ChatLogIdx(0), 0}, QStringLiteral("volume"), ParameterSearch()).found);

    // And from the database: the next start does not bring it back.
    SavedNotes reopened{db};
    const auto stored = reopened.all();
    QCOMPARE(stored.size(), 2);
    QCOMPARE(stored[0].text, QStringLiteral("Buy dates"));
    QCOMPARE(stored[1].text, QStringLiteral("Read the second volume"));
}

void TestNotes::aNoteWrittenNowCanBeDeletedToo()
{
    notes->add(at(1), QStringLiteral("Buy dates"));
    const auto log = open(notes.get());

    dispatcher->sendMessage(false, QStringLiteral("Bring the book back"));
    dispatcher->sendMessage(true, QStringLiteral("waves"));
    QCOMPARE(log->getNextIdx(), ChatLogIdx(3));
    QVERIFY(dispatcher->hasNote(ChatLogIdx(1)));
    QVERIFY(dispatcher->hasNote(ChatLogIdx(2)));

    QVERIFY(dispatcher->removeNote(ChatLogIdx(1)));
    QVERIFY(!log->isShown(ChatLogIdx(1)));
    QVERIFY(log->isShown(ChatLogIdx(2)));

    SavedNotes reopened{db};
    const auto stored = reopened.all();
    QCOMPARE(stored.size(), 2);
    QCOMPARE(stored[0].text, QStringLiteral("Buy dates"));
    QCOMPARE(stored[1].text, NotesDispatcher::storedText(true, QStringLiteral("waves")));
}

void TestNotes::onlyANoteCanBeDeletedAndOnlyOnce()
{
    notes->add(at(1), QStringLiteral("Buy dates"));
    const auto log = open(notes.get());
    QSignalSpy updated(log.get(), &IChatLog::itemUpdated);

    QVERIFY(!dispatcher->hasNote(ChatLogIdx(5)));
    QVERIFY(!dispatcher->removeNote(ChatLogIdx(5)));
    QVERIFY(dispatcher->removeNote(ChatLogIdx(0)));
    QVERIFY(!dispatcher->hasNote(ChatLogIdx(0)));
    QVERIFY(!dispatcher->removeNote(ChatLogIdx(0)));
    QCOMPARE(updated.count(), 1);

    // Hiding an index the log does not have changes nothing.
    log->hideItem(ChatLogIdx(7));
    QCOMPARE(updated.count(), 1);
    QVERIFY(log->isShown(ChatLogIdx(7)));
}

void TestNotes::withoutADatabaseANoteIsOnlyHidden()
{
    const auto log = open(nullptr);
    dispatcher->sendMessage(false, QStringLiteral("Buy dates"));
    QVERIFY(dispatcher->hasNote(ChatLogIdx(0)));
    QVERIFY(dispatcher->removeNote(ChatLogIdx(0)));
    QVERIFY(!log->isShown(ChatLogIdx(0)));
    QVERIFY(notes->all().isEmpty());
}

void TestNotes::anEditedNoteKeepsItsWordsAfterARestart()
{
    notes->add(at(1), QStringLiteral("Buy dates"));
    notes->add(at(2), QStringLiteral("Call the library"));
    const auto log = open(notes.get());
    QSignalSpy updated(log.get(), &IChatLog::itemUpdated);
    QCOMPARE(dispatcher->editableText(ChatLogIdx(1)), QStringLiteral("Call the library"));

    const QString atNine = QStringLiteral("Call the library at nine");
    QVERIFY(dispatcher->editNote(ChatLogIdx(1), false, atNine));
    QCOMPARE(updated.count(), 1);
    QCOMPARE(qvariant_cast<ChatLogIdx>(updated[0][0]), ChatLogIdx(1));
    const ChatLogMessage& edited = log->at(ChatLogIdx(1)).getContentAsMessage();
    QCOMPARE(edited.message.content, atNine);
    QVERIFY(!edited.message.isAction);
    QVERIFY(edited.marks.edited);
    QVERIFY(edited.marks.editedAt > 0);
    QVERIFY(!log->at(ChatLogIdx(0)).getContentAsMessage().marks.edited);
    QCOMPARE(dispatcher->editableText(ChatLogIdx(1)), atNine);
    const SearchPos start{ChatLogIdx(0), 0};
    QVERIFY(log->searchForward(start, QStringLiteral("nine"), ParameterSearch()).found);

    // The next start: the database keeps the words and when they changed, and the note its place.
    SavedNotes reopened{db};
    const auto stored = reopened.all();
    QCOMPARE(stored.size(), 2);
    QCOMPARE(stored[0].text, QStringLiteral("Buy dates"));
    QVERIFY(!stored[0].editedAt.isValid());
    QCOMPARE(stored[1].text, atNine);
    QCOMPARE(stored[1].timestamp, at(2));
    QVERIFY(stored[1].editedAt.isValid());
    const quint32 editedAt = edited.marks.editedAt;
    const auto restarted = open(&reopened);
    const ChatLogMessage& shown = restarted->at(ChatLogIdx(1)).getContentAsMessage();
    QCOMPARE(shown.message.content, atNine);
    QCOMPARE(shown.message.timestamp, at(2));
    QVERIFY(shown.marks.edited);
    QCOMPARE(shown.marks.editedAt, editedAt);
    QVERIFY(!restarted->at(ChatLogIdx(0)).getContentAsMessage().marks.edited);
    // It is still a note: edited again, or deleted.
    QVERIFY(dispatcher->editNote(ChatLogIdx(1), false, QStringLiteral("Library: done")));
    QVERIFY(dispatcher->removeNote(ChatLogIdx(0)));
    SavedNotes again{db};
    const auto left = again.all();
    QCOMPARE(left.size(), 1);
    QCOMPARE(left[0].text, QStringLiteral("Library: done"));
}

void TestNotes::aNoteWrittenNowCanBeEditedToo()
{
    const auto log = open(notes.get());
    dispatcher->sendMessage(false, QStringLiteral("Bring the book back"));
    QVERIFY(dispatcher->editNote(ChatLogIdx(0), false, QStringLiteral("Bring both books back")));
    QCOMPARE(log->at(ChatLogIdx(0)).getContentAsMessage().message.content,
             QStringLiteral("Bring both books back"));

    SavedNotes reopened{db};
    const auto stored = reopened.all();
    QCOMPARE(stored.size(), 1);
    QCOMPARE(stored[0].text, QStringLiteral("Bring both books back"));
    QVERIFY(stored[0].editedAt.isValid());
}

void TestNotes::anEditMakesAnActionOrUndoesOne()
{
    notes->add(at(1), NotesDispatcher::storedText(true, QStringLiteral("waves")));
    notes->add(at(2), QStringLiteral("Buy dates"));
    const auto log = open(notes.get());
    // The field takes an action as it is typed.
    QCOMPARE(dispatcher->editableText(ChatLogIdx(0)),
             NotesDispatcher::storedText(true, QStringLiteral("waves")));

    QVERIFY(dispatcher->editNote(ChatLogIdx(0), false, QStringLiteral("waves back")));
    QVERIFY(dispatcher->editNote(ChatLogIdx(1), true, QStringLiteral("buys dates")));
    QVERIFY(!log->at(ChatLogIdx(0)).getContentAsMessage().message.isAction);
    QVERIFY(log->at(ChatLogIdx(1)).getContentAsMessage().message.isAction);
    // The kind alone is a change too.
    QVERIFY(dispatcher->editNote(ChatLogIdx(1), false, QStringLiteral("buys dates")));
    QVERIFY(dispatcher->editNote(ChatLogIdx(1), true, QStringLiteral("buys dates")));

    SavedNotes reopened{db};
    const auto restarted = open(&reopened);
    const Message& first = restarted->at(ChatLogIdx(0)).getContentAsMessage().message;
    const Message& second = restarted->at(ChatLogIdx(1)).getContentAsMessage().message;
    QVERIFY(!first.isAction);
    QCOMPARE(first.content, QStringLiteral("waves back"));
    QVERIFY(second.isAction);
    QCOMPARE(second.content, QStringLiteral("buys dates"));
}

void TestNotes::onlyANoteTakesAnEditAndOnlyWithNewWords()
{
    notes->add(at(1), QStringLiteral("Buy dates"));
    notes->add(at(2), QStringLiteral("Call the library"));
    const auto log = open(notes.get());
    QSignalSpy updated(log.get(), &IChatLog::itemUpdated);

    QCOMPARE(dispatcher->editableText(ChatLogIdx(5)), QString());
    QVERIFY(!dispatcher->editNote(ChatLogIdx(5), false, QStringLiteral("Buy figs")));
    QVERIFY(!dispatcher->editNote(ChatLogIdx(0), false, QStringLiteral(" \n ")));
    QVERIFY(!dispatcher->editNote(ChatLogIdx(0), false, QStringLiteral("Buy dates")));
    QCOMPARE(updated.count(), 0);
    QVERIFY(!log->at(ChatLogIdx(0)).getContentAsMessage().marks.edited);

    QVERIFY(dispatcher->removeNote(ChatLogIdx(1)));
    QCOMPARE(dispatcher->editableText(ChatLogIdx(1)), QString());
    QVERIFY(!dispatcher->editNote(ChatLogIdx(1), false, QStringLiteral("Call at nine")));

    SavedNotes reopened{db};
    const auto stored = reopened.all();
    QCOMPARE(stored.size(), 1);
    QCOMPARE(stored[0].text, QStringLiteral("Buy dates"));
    QVERIFY(!stored[0].editedAt.isValid());
}

void TestNotes::withoutADatabaseAnEditStaysUntilTheAppQuits()
{
    const auto log = open(nullptr);
    dispatcher->sendMessage(false, QStringLiteral("Buy dates"));
    QVERIFY(dispatcher->editNote(ChatLogIdx(0), false, QStringLiteral("Buy figs")));
    QCOMPARE(log->at(ChatLogIdx(0)).getContentAsMessage().message.content,
             QStringLiteral("Buy figs"));
    QVERIFY(log->at(ChatLogIdx(0)).getContentAsMessage().marks.edited);
    QVERIFY(notes->all().isEmpty());
}

QTEST_GUILESS_MAIN(TestNotes)
#include "notes_test.moc"
