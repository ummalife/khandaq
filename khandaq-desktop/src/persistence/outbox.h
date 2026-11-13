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

#pragma once

#include "src/core/chatid.h"
#include "src/core/extension.h"
#include "src/persistence/db/rawdatabase.h"

#include <QDateTime>
#include <QString>
#include <QVector>

#include <memory>

/**
 * @brief Our messages that have not reached the person yet, kept apart from the history.
 *
 * The queue lives in the profile database (table outbox, schema 12) whether the history is on or
 * off: a message written with the history off still waits for the person after a restart, and
 * switching the history off does not drop it. A message the delivery gave up on stays here as
 * failed, so the conversation can send it again.
 *
 * When the history is on, a queued message also has its history row, and the queue keeps that row
 * in step: delivered clears its pending mark, failed moves it to the broken messages, cancelled
 * removes it at the next start (before any chat log counts the rows).
 *
 * Every call comes from the thread that owns the chats. Writes go through RawDatabase::execLater,
 * in order; reads wait for the writes before them.
 */
class Outbox
{
public:
    struct Entry
    {
        qint64 id = -1;
        QDateTime timestamp;
        /** The text as the history stores it: an action keeps its prefix. */
        QString message;
        ExtensionSet extensionSet;
        /** The same message in the history, or -1 when it was written with the history off. */
        RowId historyId{-1};
        bool failed = false;
    };

    /** Opens the queue of db: removes what was cancelled, with its history rows. */
    explicit Outbox(std::shared_ptr<RawDatabase> db);

    /** The id the next queued message gets, for writing its history row and queue row at once. */
    qint64 reserveId();

    /**
     * The query that queues message id for the history row inserted right before it, in the same
     * transaction (it reads last_insert_rowid()).
     */
    static RawDatabase::Query linkedInsertQuery(qint64 id, const ChatId& chatId, const QString& message,
                                                const QDateTime& time, ExtensionSet extensions);

    /** Queues a message that has no history row. Returns its id. */
    qint64 add(const ChatId& chatId, const QString& message, const QDateTime& time,
               ExtensionSet extensions);

    /**
     * The queue of one chat, oldest first, without the cancelled messages. Pending history rows
     * that have no queue row yet (kept from a removed contact, say) join the queue first, and
     * broken ones (from before the queue existed) join it as failed.
     */
    QVector<Entry> entriesForChat(const ChatId& chatId);

    /** The message reached the person. */
    void remove(qint64 id);
    /** The delivery gave up on the message; it waits for the user to send it again. */
    void markFailed(qint64 id);
    /** The user took the message back. */
    void cancel(qint64 id);
    /** The contact is gone: their queue goes too. Their history, if kept, stays as it is. */
    void removeChat(const ChatId& chatId);

private:
    void purgeCancelled();
    void continueIds();

    std::shared_ptr<RawDatabase> db;
    qint64 nextId = 1;
};

/**
 * @brief The saved messages ("Favorites"): notes to yourself that stay on this device and are never
 * sent anywhere. They are kept whether the history is on or off.
 */
class SavedNotes
{
public:
    struct Note
    {
        qint64 id = -1;
        QDateTime timestamp;
        QString text;
    };

    explicit SavedNotes(std::shared_ptr<RawDatabase> db);

    /** Oldest first. */
    QVector<Note> all();
    /** Returns the new note's id. */
    qint64 add(const QDateTime& time, const QString& text);
    void remove(qint64 id);

private:
    std::shared_ptr<RawDatabase> db;
    qint64 nextId = 1;
};
