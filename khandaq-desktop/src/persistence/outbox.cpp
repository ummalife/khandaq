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

#include "outbox.h"

#include <QDebug>
#include <QStringList>

#include <algorithm>

namespace {
enum class OutboxState
{
    Waiting = 0,
    Failed = 1,
    Cancelled = 2,
};

QString idList(const QVector<qint64>& ids)
{
    QStringList parts;
    for (qint64 id : ids) {
        parts << QString::number(id);
    }
    return parts.join(QLatin1Char(','));
}

/** The history row of queue row id, as a subquery. */
QString historyRowOf(qint64 id)
{
    return QStringLiteral("(SELECT history_id FROM outbox WHERE id = %1)").arg(id);
}

qint64 maxId(RawDatabase& db, const QString& table)
{
    qint64 result = 0;
    db.execNow(RawDatabase::Query(QStringLiteral("SELECT MAX(id) FROM %1;").arg(table),
                                  [&result](const QVector<QVariant>& row) {
                                      result = row[0].toLongLong();
                                  }));
    return result;
}
} // namespace

Outbox::Outbox(std::shared_ptr<RawDatabase> db_)
    : db{std::move(db_)}
{
    purgeCancelled();
    continueIds();
}

void Outbox::purgeCancelled()
{
    QVector<qint64> historyRows;
    db->execNow(RawDatabase::Query(
        QStringLiteral("SELECT history_id FROM outbox WHERE state = %1 AND history_id IS NOT NULL;")
            .arg(static_cast<int>(OutboxState::Cancelled)),
        [&historyRows](const QVector<QVariant>& row) { historyRows << row[0].toLongLong(); }));

    QVector<RawDatabase::Query> queries;
    if (!historyRows.isEmpty()) {
        const QString ids = idList(historyRows);
        for (const char* table :
             {"faux_offline_pending", "broken_messages", "text_messages", "history"}) {
            queries += RawDatabase::Query(
                QStringLiteral("DELETE FROM %1 WHERE id IN (%2);").arg(QLatin1String(table), ids));
        }
    }
    queries += RawDatabase::Query(QStringLiteral("DELETE FROM outbox WHERE state = %1;")
                                      .arg(static_cast<int>(OutboxState::Cancelled)));
    if (!db->execNow(queries)) {
        qWarning() << "Outbox: failed to remove the cancelled messages";
    }
}

void Outbox::continueIds()
{
    nextId = maxId(*db, QStringLiteral("outbox")) + 1;
}

qint64 Outbox::reserveId()
{
    return nextId++;
}

RawDatabase::Query Outbox::linkedInsertQuery(qint64 id, const ChatId& chatId, const QString& message,
                                             const QDateTime& time, ExtensionSet extensions)
{
    return RawDatabase::Query(QStringLiteral(
                                  "INSERT INTO outbox (id, chat_uuid, timestamp, message, "
                                  "required_extensions, history_id, state) "
                                  "VALUES (%1, ?, %2, ?, %3, last_insert_rowid(), %4);")
                                  .arg(id)
                                  .arg(time.toMSecsSinceEpoch())
                                  .arg(extensions.to_ulong())
                                  .arg(static_cast<int>(OutboxState::Waiting)),
                              {chatId.getByteArray(), message.toUtf8()});
}

qint64 Outbox::add(const ChatId& chatId, const QString& message, const QDateTime& time,
                   ExtensionSet extensions)
{
    const qint64 id = reserveId();
    db->execLater(
        RawDatabase::Query(QStringLiteral("INSERT INTO outbox (id, chat_uuid, timestamp, message, "
                                          "required_extensions, history_id, state) "
                                          "VALUES (%1, ?, %2, ?, %3, NULL, %4);")
                               .arg(id)
                               .arg(time.toMSecsSinceEpoch())
                               .arg(extensions.to_ulong())
                               .arg(static_cast<int>(OutboxState::Waiting)),
                           {chatId.getByteArray(), message.toUtf8()}));
    return id;
}

QVector<Outbox::Entry> Outbox::entriesForChat(const ChatId& chatId)
{
    // Pending history rows of this chat without a queue row join the queue, linked to their rows.
    // Their ids come after the queue's own; the order below is by time.
    const RawDatabase::Query adoptPending(
        QStringLiteral(
            "INSERT INTO outbox (chat_uuid, timestamp, message, required_extensions, "
            "history_id, state) "
            "SELECT chats.uuid, history.timestamp, text_messages.message, "
            "faux_offline_pending.required_extensions, history.id, %1 "
            "FROM faux_offline_pending "
            "JOIN history ON history.id = faux_offline_pending.id "
            "JOIN text_messages ON text_messages.id = history.id "
            "JOIN chats ON chats.id = history.chat_id "
            "WHERE chats.uuid = ? "
            "AND history.id NOT IN (SELECT history_id FROM outbox WHERE history_id IS NOT NULL) "
            "ORDER BY history.id;")
            .arg(static_cast<int>(OutboxState::Waiting)),
        {chatId.getByteArray()});
    // Messages that broke before the queue existed have their text in the history: they join it as
    // failed, so the conversation offers to send them again like any message the delivery gave up
    // on. A message that failed in the queue has its queue row already.
    const RawDatabase::Query adoptBroken(
        QStringLiteral(
            "INSERT INTO outbox (chat_uuid, timestamp, message, required_extensions, "
            "history_id, state) "
            "SELECT chats.uuid, history.timestamp, text_messages.message, 0, history.id, %1 "
            "FROM broken_messages "
            "JOIN history ON history.id = broken_messages.id "
            "JOIN text_messages ON text_messages.id = history.id "
            "JOIN chats ON chats.id = history.chat_id "
            "WHERE chats.uuid = ? "
            "AND history.id NOT IN (SELECT history_id FROM outbox WHERE history_id IS NOT NULL) "
            "ORDER BY history.id;")
            .arg(static_cast<int>(OutboxState::Failed)),
        {chatId.getByteArray()});
    qint64 highest = 0;
    const RawDatabase::Query lastId(QStringLiteral("SELECT MAX(id) FROM outbox;"),
                                    [&highest](const QVector<QVariant>& row) {
                                        highest = row[0].toLongLong();
                                    });
    QVector<Entry> entries;
    const RawDatabase::Query listing(
        QStringLiteral("SELECT id, timestamp, message, required_extensions, history_id, state "
                       "FROM outbox WHERE chat_uuid = ? AND state != %1 "
                       "ORDER BY timestamp, id;")
            .arg(static_cast<int>(OutboxState::Cancelled)),
        {chatId.getByteArray()}, [&entries](const QVector<QVariant>& row) {
            Entry entry;
            entry.id = row[0].toLongLong();
            entry.timestamp = QDateTime::fromMSecsSinceEpoch(row[1].toLongLong());
            entry.message = QString::fromUtf8(row[2].toByteArray());
            entry.extensionSet = ExtensionSet(row[3].toLongLong());
            entry.historyId = RowId{row[4].isNull() ? -1 : row[4].toLongLong()};
            entry.failed = row[5].toInt() == static_cast<int>(OutboxState::Failed);
            entries << entry;
        });

    // One wait for all four: every conversation asks at start, and each query waited for is a
    // round trip to the database thread.
    if (db->execNow(QVector<RawDatabase::Query>{adoptPending, adoptBroken, lastId, listing})) {
        // Adopted rows took ids after the highest; the ids handed out go on from there.
        nextId = std::max(nextId, highest + 1);
        return entries;
    }
    // The transaction was rolled back: nothing was adopted; the queue is listed as it stands.
    qWarning() << "Outbox: failed to queue the pending or broken history messages";
    entries.clear();
    continueIds();
    db->execNow(listing);
    return entries;
}

void Outbox::remove(qint64 id)
{
    db->execLater(
        {RawDatabase::Query(
             QStringLiteral("DELETE FROM faux_offline_pending WHERE id = %1;").arg(historyRowOf(id))),
         RawDatabase::Query(QStringLiteral("DELETE FROM outbox WHERE id = %1;").arg(id))});
}

void Outbox::markFailed(qint64 id)
{
    // The history row turns broken the way History::markAsBroken does it; the reason stays the
    // generic one, as the dispatcher reports it.
    db->execLater(
        {RawDatabase::Query(QStringLiteral("INSERT OR IGNORE INTO broken_messages (id, reason) "
                                           "SELECT id, 0 FROM faux_offline_pending WHERE id = %1;")
                                .arg(historyRowOf(id))),
         RawDatabase::Query(
             QStringLiteral("DELETE FROM faux_offline_pending WHERE id = %1;").arg(historyRowOf(id))),
         RawDatabase::Query(QStringLiteral("UPDATE outbox SET state = %1 WHERE id = %2;")
                                .arg(static_cast<int>(OutboxState::Failed))
                                .arg(id))});
}

void Outbox::cancel(qint64 id)
{
    // Without a history row nothing else holds the message, so it goes right away.
    db->execLater({RawDatabase::Query(QStringLiteral("DELETE FROM outbox WHERE id = %1 AND "
                                                     "history_id IS NULL;")
                                          .arg(id)),
                   RawDatabase::Query(QStringLiteral("UPDATE outbox SET state = %1 WHERE id = %2;")
                                          .arg(static_cast<int>(OutboxState::Cancelled))
                                          .arg(id))});
}

void Outbox::removeChat(const ChatId& chatId)
{
    db->execLater(RawDatabase::Query(QStringLiteral("DELETE FROM outbox WHERE chat_uuid = ?;"),
                                     {chatId.getByteArray()}));
}

SavedNotes::SavedNotes(std::shared_ptr<RawDatabase> db_)
    : db{std::move(db_)}
{
    nextId = maxId(*db, QStringLiteral("notes")) + 1;
}

QVector<SavedNotes::Note> SavedNotes::all()
{
    QVector<Note> notes;
    db->execNow(RawDatabase::Query(QStringLiteral("SELECT id, timestamp, message FROM notes "
                                                  "ORDER BY timestamp, id;"),
                                   [&notes](const QVector<QVariant>& row) {
                                       Note note;
                                       note.id = row[0].toLongLong();
                                       note.timestamp =
                                           QDateTime::fromMSecsSinceEpoch(row[1].toLongLong());
                                       note.text = QString::fromUtf8(row[2].toByteArray());
                                       notes << note;
                                   }));
    return notes;
}

qint64 SavedNotes::add(const QDateTime& time, const QString& text)
{
    const qint64 id = nextId++;
    db->execLater(RawDatabase::Query(QStringLiteral("INSERT INTO notes (id, timestamp, message) "
                                                    "VALUES (%1, %2, ?);")
                                         .arg(id)
                                         .arg(time.toMSecsSinceEpoch()),
                                     {text.toUtf8()}));
    return id;
}

void SavedNotes::remove(qint64 id)
{
    db->execLater(QStringLiteral("DELETE FROM notes WHERE id = %1;").arg(id));
}
