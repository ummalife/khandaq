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

#include "src/persistence/outbox.h"
#include "src/core/toxpk.h"
#include "src/persistence/db/rawdatabase.h"
#include "src/persistence/db/upgrades/dbupgrader.h"

#include <QDateTime>
#include <QTemporaryFile>
#include <QtTest/QtTest>

#include <memory>

namespace {
const ToxPk maryam{QByteArray(32, 'm')};
const ToxPk omar{QByteArray(32, 'o')};

QDateTime at(int minute)
{
    return QDateTime(QDate(2026, 11, 10), QTime(9, minute), Qt::UTC);
}
} // namespace

/**
 * The outgoing queue and the saved messages, on a real database of the current schema: the queue
 * does not need the history, keeps a history row in step when there is one, and survives a
 * restart (a new Outbox on the same file).
 */
class TestOutbox : public QObject
{
    Q_OBJECT
private slots:
    void init();
    void cleanup();
    void queueWorksWithoutHistory();
    void deliveredMessageClearsItsPendingMark();
    void failedMessageStaysWithItsText();
    void cancelledMessageLeavesAtTheNextStart();
    void pendingHistoryRowsJoinTheQueue();
    void brokenHistoryRowsJoinTheQueueAsFailed();
    void erasingTheHistoryKeepsTheQueue();
    void removingAContactDropsOnlyTheirQueue();
    void savedNotesStayAfterARestart();

private:
    void open();
    /** A pending history row of chat, with its queue row, as History::addNewOutgoingMessage writes it. */
    qint64 addWithHistory(const ToxPk& chat, const QString& text, int minute);
    int count(const QString& query);

    std::unique_ptr<QTemporaryFile> file;
    std::shared_ptr<RawDatabase> db;
    std::unique_ptr<Outbox> outbox;
};

void TestOutbox::init()
{
    file.reset(new QTemporaryFile());
    QVERIFY(file->open());
    file->close();
    db.reset(new RawDatabase{file->fileName(), {}, {}});
    QVERIFY(db->execNow("PRAGMA foreign_keys = ON;"));
    QVERIFY(DbUpgrader::createCurrentSchema(*db));
    QVector<RawDatabase::Query> setup;
    setup +=
        RawDatabase::Query("INSERT INTO chats (id, uuid) VALUES (1, ?);", {maryam.getByteArray()});
    setup += RawDatabase::Query("INSERT INTO chats (id, uuid) VALUES (2, ?);", {omar.getByteArray()});
    setup += RawDatabase::Query("INSERT INTO authors (id, public_key) VALUES (1, ?);",
                                {QByteArray(32, 'a')});
    setup += RawDatabase::Query("INSERT INTO aliases (id, owner, display_name) VALUES (1, 1, ?);",
                                {QByteArray("Amina")});
    QVERIFY(db->execNow(setup));
    open();
}

void TestOutbox::cleanup()
{
    outbox.reset();
    db.reset();
    file.reset();
}

void TestOutbox::open()
{
    outbox.reset(new Outbox(db));
}

qint64 TestOutbox::addWithHistory(const ToxPk& chat, const QString& text, int minute)
{
    const qint64 id = outbox->reserveId();
    const int chatRow = chat == maryam ? 1 : 2;
    QVector<RawDatabase::Query> queries;
    queries += RawDatabase::Query(QStringLiteral("INSERT INTO history (message_type, timestamp, "
                                                 "chat_id) VALUES ('T', %1, %2);")
                                      .arg(at(minute).toMSecsSinceEpoch())
                                      .arg(chatRow));
    queries += RawDatabase::Query("INSERT INTO text_messages (id, message_type, sender_alias, "
                                  "message) VALUES (last_insert_rowid(), 'T', 1, ?);",
                                  {text.toUtf8()});
    queries += RawDatabase::Query("INSERT INTO faux_offline_pending (id, required_extensions) "
                                  "VALUES (last_insert_rowid(), 0);");
    queries += Outbox::linkedInsertQuery(id, chat, text, at(minute), ExtensionSet());
    db->execLater(queries);
    return id;
}

int TestOutbox::count(const QString& query)
{
    int result = -1;
    db->execNow(RawDatabase::Query(query, [&result](const QVector<QVariant>& row) {
        result = row[0].toInt();
    }));
    return result;
}

void TestOutbox::queueWorksWithoutHistory()
{
    const qint64 first = outbox->add(maryam, "Assalamu alaykum", at(1), ExtensionSet());
    const qint64 second = outbox->add(maryam, "/me waves", at(2), ExtensionSet());
    QVERIFY(second > first);

    open();
    const auto entries = outbox->entriesForChat(maryam);
    QCOMPARE(entries.size(), 2);
    QCOMPARE(entries[0].id, first);
    QCOMPARE(entries[0].message, QStringLiteral("Assalamu alaykum"));
    QCOMPARE(entries[0].timestamp, at(1));
    QCOMPARE(entries[0].historyId.get(), int64_t{-1});
    QVERIFY(!entries[0].failed);
    QCOMPARE(entries[1].message, QStringLiteral("/me waves"));
    QVERIFY(outbox->entriesForChat(omar).isEmpty());
    QCOMPARE(count("SELECT COUNT(*) FROM history;"), 0);

    // Ids go on after a restart instead of starting over.
    QVERIFY(outbox->add(maryam, "again", at(3), ExtensionSet()) > second);
}

void TestOutbox::deliveredMessageClearsItsPendingMark()
{
    const qint64 id = addWithHistory(maryam, "See you at six", 5);
    const auto entries = outbox->entriesForChat(maryam);
    QCOMPARE(entries.size(), 1);
    QVERIFY(entries[0].historyId.get() > 0);

    outbox->remove(id);
    QVERIFY(outbox->entriesForChat(maryam).isEmpty());
    QCOMPARE(count("SELECT COUNT(*) FROM faux_offline_pending;"), 0);
    QCOMPARE(count("SELECT COUNT(*) FROM history;"), 1);
    QCOMPARE(count("SELECT COUNT(*) FROM broken_messages;"), 0);
}

void TestOutbox::failedMessageStaysWithItsText()
{
    const qint64 linked = addWithHistory(maryam, "Send me the address", 6);
    const qint64 plain = outbox->add(maryam, "And the time", at(7), ExtensionSet());
    outbox->markFailed(linked);
    outbox->markFailed(plain);

    open();
    const auto entries = outbox->entriesForChat(maryam);
    QCOMPARE(entries.size(), 2);
    QVERIFY(entries[0].failed);
    QCOMPARE(entries[0].message, QStringLiteral("Send me the address"));
    QVERIFY(entries[1].failed);
    // The history row is broken now, as History::markAsBroken leaves it.
    QCOMPARE(count("SELECT COUNT(*) FROM faux_offline_pending;"), 0);
    QCOMPARE(count("SELECT COUNT(*) FROM broken_messages;"), 1);
}

void TestOutbox::cancelledMessageLeavesAtTheNextStart()
{
    const qint64 linked = addWithHistory(maryam, "Wrong chat", 8);
    const qint64 plain = outbox->add(maryam, "Also wrong", at(9), ExtensionSet());
    const qint64 kept = addWithHistory(maryam, "This one stays", 10);
    outbox->cancel(linked);
    outbox->cancel(plain);

    // Gone from the queue at once; the history row stays while chat logs count on its index.
    auto entries = outbox->entriesForChat(maryam);
    QCOMPARE(entries.size(), 1);
    QCOMPARE(entries[0].id, kept);
    QCOMPARE(count("SELECT COUNT(*) FROM history;"), 2);
    QCOMPARE(count("SELECT COUNT(*) FROM outbox;"), 2);

    open();
    entries = outbox->entriesForChat(maryam);
    QCOMPARE(entries.size(), 1);
    QCOMPARE(entries[0].message, QStringLiteral("This one stays"));
    QCOMPARE(count("SELECT COUNT(*) FROM history;"), 1);
    QCOMPARE(count("SELECT COUNT(*) FROM text_messages;"), 1);
    QCOMPARE(count("SELECT COUNT(*) FROM faux_offline_pending;"), 1);
    QCOMPARE(count("SELECT COUNT(*) FROM outbox;"), 1);
}

void TestOutbox::pendingHistoryRowsJoinTheQueue()
{
    // A pending row without a queue row, as a kept history leaves it after the contact was removed.
    QVector<RawDatabase::Query> queries;
    queries +=
        RawDatabase::Query(QStringLiteral("INSERT INTO history (id, message_type, timestamp, "
                                          "chat_id) VALUES (40, 'T', %1, 2);")
                               .arg(at(11).toMSecsSinceEpoch()));
    queries += RawDatabase::Query("INSERT INTO text_messages (id, message_type, sender_alias, "
                                  "message) VALUES (40, 'T', 1, ?);",
                                  {QByteArray("Bring the keys")});
    queries += RawDatabase::Query("INSERT INTO faux_offline_pending (id, required_extensions) "
                                  "VALUES (40, 0);");
    QVERIFY(db->execNow(queries));

    const auto entries = outbox->entriesForChat(omar);
    QCOMPARE(entries.size(), 1);
    QCOMPARE(entries[0].historyId.get(), int64_t{40});
    QCOMPARE(entries[0].message, QStringLiteral("Bring the keys"));
    QCOMPARE(entries[0].timestamp, at(11));

    // Joined once, and the ids handed out next do not collide with it.
    QCOMPARE(outbox->entriesForChat(omar).size(), 1);
    QVERIFY(outbox->add(omar, "And the book", at(12), ExtensionSet()) > entries[0].id);
    QCOMPARE(outbox->entriesForChat(omar).size(), 2);
}

void TestOutbox::brokenHistoryRowsJoinTheQueueAsFailed()
{
    // A message that broke before the queue existed: a broken row and its text, no queue row.
    QVector<RawDatabase::Query> queries;
    queries +=
        RawDatabase::Query(QStringLiteral("INSERT INTO history (id, message_type, timestamp, "
                                          "chat_id) VALUES (50, 'T', %1, 1);")
                               .arg(at(20).toMSecsSinceEpoch()));
    queries += RawDatabase::Query("INSERT INTO text_messages (id, message_type, sender_alias, "
                                  "message) VALUES (50, 'T', 1, ?);",
                                  {QByteArray("Send me the address, please")});
    queries += RawDatabase::Query("INSERT INTO broken_messages (id, reason) VALUES (50, 1);");
    QVERIFY(db->execNow(queries));

    // It joins the queue as failed, linked to its row, so it can be sent again.
    const auto entries = outbox->entriesForChat(maryam);
    QCOMPARE(entries.size(), 1);
    QVERIFY(entries[0].failed);
    QCOMPARE(entries[0].historyId.get(), int64_t{50});
    QCOMPARE(entries[0].message, QStringLiteral("Send me the address, please"));
    QCOMPARE(entries[0].timestamp, at(20));
    QVERIFY(outbox->entriesForChat(omar).isEmpty());

    // Once: a message that failed in the queue is not added a second time either.
    const qint64 queued = addWithHistory(maryam, "Failed in the queue", 21);
    outbox->markFailed(queued);
    const auto again = outbox->entriesForChat(maryam);
    QCOMPARE(again.size(), 2);
    QVERIFY(again[0].failed && again[1].failed);
    QCOMPARE(again[1].id, queued);

    // Sending it again takes the old one back: its rows go at the next start.
    outbox->cancel(entries[0].id);
    open();
    QCOMPARE(outbox->entriesForChat(maryam).size(), 1);
    QCOMPARE(count("SELECT COUNT(*) FROM history WHERE id = 50;"), 0);
    QCOMPARE(count("SELECT COUNT(*) FROM broken_messages WHERE id = 50;"), 0);
}

void TestOutbox::erasingTheHistoryKeepsTheQueue()
{
    addWithHistory(maryam, "Still waiting", 13);
    QVERIFY(db->execNow("DELETE FROM faux_offline_pending; DELETE FROM text_messages; "
                        "DELETE FROM history; DELETE FROM chats;"));

    const auto entries = outbox->entriesForChat(maryam);
    QCOMPARE(entries.size(), 1);
    QCOMPARE(entries[0].message, QStringLiteral("Still waiting"));
    QCOMPARE(entries[0].historyId.get(), int64_t{-1});
}

void TestOutbox::removingAContactDropsOnlyTheirQueue()
{
    outbox->add(maryam, "For Maryam", at(14), ExtensionSet());
    outbox->add(omar, "For Omar", at(15), ExtensionSet());
    outbox->removeChat(omar);
    QVERIFY(outbox->entriesForChat(omar).isEmpty());
    QCOMPARE(outbox->entriesForChat(maryam).size(), 1);
}

void TestOutbox::savedNotesStayAfterARestart()
{
    SavedNotes notes{db};
    const qint64 first = notes.add(at(16), "Buy dates");
    const qint64 second = notes.add(at(17), "Call the library");
    notes.remove(first);
    notes.add(at(18), "Read the second volume");

    SavedNotes reopened{db};
    const auto all = reopened.all();
    QCOMPARE(all.size(), 2);
    QCOMPARE(all[0].id, second);
    QCOMPARE(all[0].text, QStringLiteral("Call the library"));
    QCOMPARE(all[0].timestamp, at(17));
    QCOMPARE(all[1].text, QStringLiteral("Read the second volume"));
    QVERIFY(reopened.add(at(19), "Next") > all[1].id);
}

QTEST_GUILESS_MAIN(TestOutbox)
#include "outbox_test.moc"
