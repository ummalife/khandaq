/*
    Copyright © 2015-2019 by The qTox Project Contributors

    This file is part of qTox, a Qt-based graphical interface for Tox.

    qTox is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    qTox is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with qTox.  If not, see <http://www.gnu.org/licenses/>.
*/

#include <QDebug>
#include <QtEndian>
#include <cassert>

#include "history.h"
#include "profile.h"
#include "settings.h"
#include "db/upgrades/dbupgrader.h"
#include "db/rawdatabase.h"
#include "src/core/toxpk.h"
#include "src/core/chatid.h"

namespace {
MessageState getMessageState(bool isPending, bool isBroken)
{
    assert(!(isPending && isBroken));
    MessageState messageState;

    if (isPending) {
        messageState = MessageState::pending;
    } else if (isBroken) {
        messageState = MessageState::broken;
    } else {
        messageState = MessageState::complete;
    }
    return messageState;
}

void addAuthorIdSubQuery(QString& queryString, QVector<QByteArray>& boundParams, const ToxPk& authorPk)
{
    boundParams.append(authorPk.getByteArray());
    queryString += "(SELECT id FROM authors WHERE public_key = ?)";
}

void addChatIdSubQuery(QString& queryString, QVector<QByteArray>& boundParams, const ChatId& chatId)
{
    boundParams.append(chatId.getByteArray());
    queryString += "(SELECT id FROM chats WHERE uuid = ?)";
}

/** KHANDAQ (beta feedback 2026-11-22): what a deletion points at, as deleted_messages keeps it. */
int anchorValue(Air::MessageEffect::Anchor anchor)
{
    return anchor == Air::MessageEffect::Anchor::File ? 1 : 0;
}

/** A deletion of a chat as History holds it: the author's key, what it points at and its id. */
QByteArray deletionKey(const QByteArray& author, int anchor, const QByteArray& anchorId)
{
    QByteArray key = author;
    key.append(static_cast<char>(anchor));
    key.append(anchorId);
    return key;
}

/**
 * KHANDAQ (0.2.57): the stamp of a file that has not ended (ToxFile::Stamp) is kept where an ended
 * one keeps its hash, which nothing reads back: a mark and three 64-bit numbers, so it is never
 * taken for a hash (32 bytes) nor for the empty one a row begins with.
 */
const QByteArray stampMark{"KST1"};
constexpr int stampRecordSize = 4 + 3 * 8;

ToxFile::Stamp stampOfRecord(const QByteArray& record)
{
    ToxFile::Stamp stamp;
    if (record.size() != stampRecordSize || !record.startsWith(stampMark)) {
        return stamp;
    }
    const char* const values = record.constData() + stampMark.size();
    stamp.bornMs = qFromBigEndian<qint64>(values);
    stamp.bytes = qFromBigEndian<qint64>(values + 8);
    stamp.modifiedMs = qFromBigEndian<qint64>(values + 16);
    return stamp;
}

RawDatabase::Query generateEnsurePkInChats(ChatId const& id)
{
    return RawDatabase::Query{QStringLiteral("INSERT OR IGNORE INTO chats (uuid) "
                                "VALUES (?)"), {id.getByteArray()}};
}

RawDatabase::Query generateEnsurePkInAuthors(ToxPk const& pk)
{
    return RawDatabase::Query{QStringLiteral("INSERT OR IGNORE INTO authors (public_key) "
                                "VALUES (?)"), {pk.getByteArray()}};
}

RawDatabase::Query generateUpdateAlias(ToxPk const& pk, QString const& dispName)
{
    QVector<QByteArray> boundParams;
    QString queryString = QStringLiteral(
            "INSERT OR IGNORE INTO aliases (owner, display_name) VALUES (");
    addAuthorIdSubQuery(queryString, boundParams, pk);
    queryString += ", ?);";
    boundParams += dispName.toUtf8();
    return RawDatabase::Query{queryString, boundParams};
}

RawDatabase::Query generateHistoryTableInsertion(char type, const QDateTime& time, const ChatId& chatId)
{
    QVector<QByteArray> boundParams;
    QString queryString = QStringLiteral("INSERT INTO history (message_type, timestamp, chat_id) "
                                      "VALUES ('%1', %2, ")
                                      .arg(type)
                                      .arg(time.toMSecsSinceEpoch());
    addChatIdSubQuery(queryString, boundParams, chatId);
    queryString += ");";
    return RawDatabase::Query(queryString, boundParams);
}

/**
 * @brief Generate query to insert new message in database
 * @param ChatId Chat ID to save.
 * @param message Message to save.
 * @param sender Sender to save.
 * @param time Time of message sending.
 * @param isDelivered True if message was already delivered.
 * @param dispName Name, which should be displayed.
 * @param insertIdCallback Function, called after query execution.
 */
QVector<RawDatabase::Query>
generateNewTextMessageQueries(const ChatId& chatId, const QString& message, const ToxPk& sender,
                              const QDateTime& time, bool isDelivered, ExtensionSet extensionSet,
                              QString dispName, std::function<void(RowId)> insertIdCallback,
                              const QByteArray& msgV3Id)
{
    QVector<RawDatabase::Query> queries;

    queries += generateEnsurePkInChats(chatId);
    queries += generateEnsurePkInAuthors(sender);
    queries += generateUpdateAlias(sender, dispName);
    queries += generateHistoryTableInsertion('T', time, chatId);

    QVector<QByteArray> boundParams;
    QString queryString = QStringLiteral(
                "INSERT INTO text_messages (id, message_type, sender_alias, message, msgv3_id) "
                "VALUES ( "
                "    last_insert_rowid(), "
                "    'T', "
                "    (SELECT id FROM aliases WHERE owner=");
    addAuthorIdSubQuery(queryString, boundParams, sender);
    queryString += " and display_name=?";
    boundParams += dispName.toUtf8();
    queryString += "), ?";
    boundParams += message.toUtf8();
    // KHANDAQ (0.2.57): NULL for a text without an id; a bound empty blob would not be NULL.
    if (msgV3Id.isEmpty()) {
        queryString += ", NULL";
    } else {
        queryString += ", ?";
        boundParams += msgV3Id;
    }
    queryString += ");";
    queries += RawDatabase::Query(queryString, boundParams, insertIdCallback);

    if (!isDelivered) {
        queries += RawDatabase::Query{
            QString("INSERT INTO faux_offline_pending (id, required_extensions) VALUES ("
                    "    last_insert_rowid(), %1"
                    ");")
                .arg(extensionSet.to_ulong())};
    }

    return queries;
}

QVector<RawDatabase::Query> generateNewSystemMessageQueries(const ChatId& chatId,
                                                            const SystemMessage& systemMessage)
{
    QVector<RawDatabase::Query> queries;

    queries += generateEnsurePkInChats(chatId);
    queries += generateHistoryTableInsertion('S', systemMessage.timestamp, chatId);

    QVector<QByteArray> blobs;
    std::transform(systemMessage.args.begin(), systemMessage.args.end(), std::back_inserter(blobs),
                   [](const QString& s) { return s.toUtf8(); });

    queries += RawDatabase::Query(QString("INSERT INTO system_messages (id, message_type, "
                                          "system_message_type, arg1, arg2, arg3, arg4)"
                                          "VALUES (last_insert_rowid(), 'S', %1, ?, ?, ?, ?)")
                                      .arg(static_cast<int>(systemMessage.messageType)),
                                  blobs);

    return queries;
}
} // namespace

/**
 * @class History
 * @brief Interacts with the profile database to save the chat history.
 *
 * @var QHash<QString, int64_t> History::peers
 * @brief Maps friend public keys to unique IDs by index.
 * Caches mappings to speed up message saving.
 */

FileDbInsertionData::FileDbInsertionData()
{
    static int id = qRegisterMetaType<FileDbInsertionData>();
    (void)id;
}

/**
 * @brief Prepares the database to work with the history.
 * @param db This database will be prepared for use with the history.
 */
History::History(std::shared_ptr<RawDatabase> db_, Settings& settings_, IMessageBoxManager& messageBoxManager)
    : db(db_)
    , settings(settings_)
{
    if (!isValid()) {
        qWarning() << "Database not open, init failed";
        return;
    }

    // foreign key support is not enabled by default, so needs to be enabled on every connection
    // support was added in sqlite 3.6.19, which is qTox's minimum supported version
    db->execNow(
        "PRAGMA foreign_keys = ON;");

    const auto upgradeSucceeded = DbUpgrader::dbSchemaUpgrade(db, messageBoxManager);

    // dbSchemaUpgrade may have put us in an invalid state
    if (!upgradeSucceeded) {
        db.reset();
        return;
    }

    outbox.reset(new Outbox(db));
    savedNotes.reset(new SavedNotes(db));

    connect(this, &History::fileInserted, this, &History::onFileInserted);
}

History::~History()
{
    if (!isValid()) {
        return;
    }

    // We could have execLater requests pending with a lambda attached,
    // so clear the pending transactions first
    db->sync();
}

/**
 * @brief Checks if the database was opened successfully
 * @return True if database if opened, false otherwise.
 */
bool History::isValid()
{
    return db && db->isOpen();
}

/**
 * @brief Waits until every query still queued has run, with the callback it carries.
 *
 * A chat log (ChatHistory) hands such callbacks to addNewMessage; they run on the database thread
 * and use the chat log. Call this before a chat log goes while the history stays.
 */
void History::sync()
{
    if (!isValid()) {
        return;
    }

    db->sync();
}

/**
 * @brief Checks if a chat has history
 * @param chatId
 * @return True if it does, false otherwise.
 */
bool History::historyExists(const ChatId& chatId)
{
    if (historyAccessBlocked()) {
        return false;
    }

    return !getMessagesForChat(chatId, 0, 1).empty();
}

/**
 * @brief Erases all the chat history from the database.
 */
void History::eraseHistory()
{
    if (!isValid()) {
        return;
    }

    deletions.clear();
    deletionsLoaded = true;
    db->execNow("DELETE FROM deleted_messages;"
                "DELETE FROM faux_offline_pending;"
                "DELETE FROM broken_messages;"
                "DELETE FROM text_messages;"
                "DELETE FROM file_transfers;"
                "DELETE FROM system_messages;"
                "DELETE FROM history;"
                "DELETE FROM chats;"
                "DELETE FROM aliases;"
                "DELETE FROM authors;"
                "VACUUM;");
}

/**
 * @brief Erases the chat history of one chat.
 * @param chatId Chat ID to erase.
 */
void History::removeChatHistory(const ChatId& chatId)
{
    if (!isValid()) {
        return;
    }

    QVector<RawDatabase::Query> queries;
    QVector<QByteArray> boundParams;
    QString queryString = QStringLiteral(
        "DELETE FROM faux_offline_pending "
        "WHERE faux_offline_pending.id IN ( "
        "    SELECT faux_offline_pending.id FROM faux_offline_pending "
        "    LEFT JOIN history ON faux_offline_pending.id = history.id "
        "    WHERE chat_id=");
    addChatIdSubQuery(queryString, boundParams, chatId);
    queryString += QStringLiteral(")");
    queries += {queryString, boundParams};
    boundParams.clear();

    queryString = QStringLiteral(
        "DELETE FROM broken_messages "
        "WHERE broken_messages.id IN ( "
        "    SELECT broken_messages.id FROM broken_messages "
        "    LEFT JOIN history ON broken_messages.id = history.id "
        "    WHERE chat_id=");
    addChatIdSubQuery(queryString, boundParams, chatId);
    queryString += QStringLiteral(")");
    queries += {queryString, boundParams};
    boundParams.clear();

    queryString = QStringLiteral(
        "DELETE FROM text_messages "
        "WHERE id IN ("
        "   SELECT id from history "
        "   WHERE message_type = 'T' AND chat_id=");
    addChatIdSubQuery(queryString, boundParams, chatId);
    queryString += QStringLiteral(")");
    queries += {queryString, boundParams};
    boundParams.clear();

    queryString = QStringLiteral(
        "DELETE FROM file_transfers "
        "WHERE id IN ( "
        "    SELECT id from history "
        "    WHERE message_type = 'F' AND chat_id=");
    addChatIdSubQuery(queryString, boundParams, chatId);
    queryString += QStringLiteral(")");
    queries += {queryString, boundParams};
    boundParams.clear();

    queryString = QStringLiteral(
        "DELETE FROM system_messages "
        "WHERE id IN ( "
        "   SELECT id from history "
        "   WHERE message_type = 'S' AND chat_id=");
    addChatIdSubQuery(queryString, boundParams, chatId);
    queryString += QStringLiteral(")");
    queries += {queryString, boundParams};
    boundParams.clear();

    queryString = QStringLiteral(
        "DELETE FROM history WHERE chat_id=");
    addChatIdSubQuery(queryString, boundParams, chatId);
    queries += {queryString, boundParams};
    boundParams.clear();

    queries +=
        RawDatabase::Query(QStringLiteral("DELETE FROM deleted_messages WHERE chat_uuid = ?;"),
                           {chatId.getByteArray()});
    deletions.remove(chatId.getByteArray());

    queryString = QStringLiteral(
        "DELETE FROM chats WHERE id=");
    addChatIdSubQuery(queryString, boundParams, chatId);
    queries += {queryString, boundParams};
    boundParams.clear();

    queries += RawDatabase::Query{QStringLiteral(
        "DELETE FROM aliases WHERE id NOT IN ( "
        "   SELECT DISTINCT sender_alias FROM text_messages "
        "   UNION "
        "   SELECT DISTINCT sender_alias FROM file_transfers)")};

    queries += RawDatabase::Query{QStringLiteral(
        "DELETE FROM authors WHERE id NOT IN ( "
        "   SELECT DISTINCT owner FROM aliases)")};

    if (!db->execNow(queries)) {
        qWarning() << "Failed to remove friend's history";
    } else {
        db->execNow(RawDatabase::Query{QStringLiteral("VACUUM")});
    }
}

void History::onFileInserted(RowId dbId, QByteArray fileId)
{
    auto& fileInfo = fileInfos[fileId];
    if (fileInfo.finished) {
        const QString filePath = erasedFiles.contains(fileId) ? QString() : fileInfo.filePath;
        db->execLater(generateFileFinished(dbId, fileInfo.success, filePath, fileInfo.fileHash));
        fileInfos.remove(fileId);
    } else {
        fileInfo.finished = false;
        fileInfo.fileId = dbId;
    }
}

QVector<RawDatabase::Query>
History::generateNewFileTransferQueries(const ChatId& chatId, const ToxPk& sender,
                                        const QDateTime& time, const QString& dispName,
                                        const FileDbInsertionData& insertionData)
{
    QVector<RawDatabase::Query> queries;

    queries += generateEnsurePkInChats(chatId);
    queries += generateEnsurePkInAuthors(sender);
    queries += generateUpdateAlias(sender, dispName);
    queries += generateHistoryTableInsertion('F', time, chatId);

    std::weak_ptr<History> weakThis = shared_from_this();
    auto fileId = insertionData.fileId;

    QString queryString;
    queryString += QStringLiteral(
                               "INSERT INTO file_transfers "
                               "    (id, message_type, sender_alias, "
                               "    file_restart_id, file_name, file_path, "
                               "    file_hash, file_size, direction, file_state) "
                               "VALUES ( "
                               "    last_insert_rowid(), "
                               "    'F', "
                               "    (SELECT id FROM aliases WHERE owner=");
    QVector<QByteArray> boundParams;
    addAuthorIdSubQuery(queryString, boundParams, sender);
    queryString +=  " AND display_name=?";
    boundParams += dispName.toUtf8();
    queryString += "), ?";
    boundParams += insertionData.fileId;
    queryString += ", ?";
    boundParams += insertionData.fileName.toUtf8();
    queryString += ", ?";
    boundParams += insertionData.filePath.toUtf8();
    queryString += ", ?";
    boundParams += insertionData.stamp == ToxFile::Stamp() ? QByteArray()
                                                           : stampRecord(insertionData.stamp);
    queryString += QStringLiteral(", %1, %2, %3);")
                        .arg(insertionData.size)
                        .arg(insertionData.direction)
                        .arg(ToxFile::CANCELED);
    queries += RawDatabase::Query(queryString, boundParams,
                           [weakThis, fileId](RowId id) {
                               auto pThis = weakThis.lock();
                               if (pThis)
                                   emit pThis->fileInserted(id, fileId);
                           });
    return queries;
}

RawDatabase::Query History::generateFileFinished(RowId id, bool success, const QString& filePath,
                                                 const QByteArray& fileHash)
{
    auto file_state = success ? ToxFile::FINISHED : ToxFile::CANCELED;
    if (filePath.length()) {
        return RawDatabase::Query(QStringLiteral("UPDATE file_transfers "
                                                 "SET file_state = %1, file_path = ?, file_hash = ?"
                                                 "WHERE id = %2")
                                      .arg(file_state)
                                      .arg(id.get()),
                                  {filePath.toUtf8(), fileHash});
    } else {
        return RawDatabase::Query(QStringLiteral("UPDATE file_transfers "
                                                 "SET file_state = %1 "
                                                 "WHERE id = %2")
                                      .arg(file_state)
                                      .arg(id.get()));
    }
}

QByteArray History::stampRecord(const ToxFile::Stamp& stamp)
{
    QByteArray record = stampMark;
    for (const qint64 value : {stamp.bornMs, stamp.bytes, stamp.modifiedMs}) {
        char bytes[8];
        qToBigEndian<qint64>(value, bytes);
        record.append(bytes, sizeof(bytes));
    }
    return record;
}

void History::addNewFileMessage(const ChatId& chatId, const QByteArray& fileId,
                                const QString& fileName, const QString& filePath, int64_t size,
                                const ToxPk& sender, const QDateTime& time, QString const& dispName,
                                ToxFile::FileDirection direction, const ToxFile::Stamp& stamp)
{
    if (historyAccessBlocked()) {
        return;
    }

    // This is an incredibly far from an optimal way of implementing this,
    // but given the frequency that people are going to be initiating a file
    // transfer we can probably live with it.

    // Since both inserting an alias for a user and inserting a file transfer
    // will generate new ids, there is no good way to inject both new ids into the
    // history query without refactoring our RawDatabase::Query and processor loops.

    // What we will do instead is chain callbacks to try to get reasonable behavior.
    // We can call the generateNewMessageQueries() fn to insert a message with an empty
    // message in it, and get the id with the callbck. Once we have the id we can ammend
    // the data to have our newly inserted file_id as well

    std::weak_ptr<History> weakThis = shared_from_this();
    FileDbInsertionData insertionData;
    insertionData.fileId = fileId;
    insertionData.fileName = fileName;
    insertionData.filePath = filePath;
    insertionData.size = size;
    insertionData.direction = direction;
    insertionData.stamp = stamp;

    auto queries = generateNewFileTransferQueries(chatId, sender, time, dispName, insertionData);

    db->execLater(queries);
}

void History::addNewSystemMessage(const ChatId& chatId, const SystemMessage& systemMessage)
{
    if (historyAccessBlocked())
        return;

    const auto queries = generateNewSystemMessageQueries(chatId, systemMessage);

    db->execLater(queries);
}

/**
 * @brief Saves a chat message in the database.
 * @param chatId Chat ID to save.
 * @param message Message to save.
 * @param sender Sender to save.
 * @param time Time of message sending.
 * @param isDelivered True if message was already delivered.
 * @param dispName Name, which should be displayed.
 * @param insertIdCallback Function, called after query execution.
 */
void History::addNewMessage(const ChatId& chatId, const QString& message, const ToxPk& sender,
                            const QDateTime& time, bool isDelivered, ExtensionSet extensionSet,
                            QString dispName, const std::function<void(RowId)>& insertIdCallback,
                            const QByteArray& msgV3Id)
{
    if (historyAccessBlocked()) {
        return;
    }

    db->execLater(generateNewTextMessageQueries(chatId, message, sender, time, isDelivered,
                                                extensionSet, dispName, insertIdCallback, msgV3Id));
}

void History::setFileFinished(const QByteArray& fileId, bool success, const QString& filePath,
                              const QByteArray& fileHash)
{
    if (historyAccessBlocked()) {
        return;
    }

    auto& fileInfo = fileInfos[fileId];
    // A file its sender deleted keeps its place erased (eraseFileEntry).
    const QString shownPath = erasedFiles.contains(fileId) ? QString() : filePath;
    if (fileInfo.fileId.get() == -1) {
        // Its row is not in yet, as for a group file that arrives whole: onFileInserted ends it.
        fileInfo.finished = true;
        fileInfo.success = success;
        fileInfo.filePath = shownPath;
        fileInfo.fileHash = fileHash;
        return;
    }

    db->execLater(generateFileFinished(fileInfo.fileId, success, shownPath, fileHash));
    fileInfos.remove(fileId);
}

/**
 * @brief Saves one of our messages that has not reached the person yet: its history row, marked as
 * pending, and its row in the outgoing queue, in one transaction.
 * @param outboxId The id reserved for it with Outbox::reserveId().
 */
void History::addNewOutgoingMessage(const ChatId& chatId, const QString& message,
                                    const ToxPk& sender, const QDateTime& time,
                                    ExtensionSet extensions, const QString& dispName,
                                    qint64 outboxId, const QByteArray& msgV3Id)
{
    if (historyAccessBlocked()) {
        return;
    }

    auto queries = generateNewTextMessageQueries(chatId, message, sender, time, false, extensions,
                                                 dispName, {}, msgV3Id);
    queries += Outbox::linkedInsertQuery(outboxId, chatId, message, time, extensions, msgV3Id);
    db->execLater(queries);
}

/**
 * @brief The outgoing queue, kept whether the history is on or off.
 * @return Null when the database could not be opened.
 */
Outbox* History::getOutbox()
{
    return isValid() ? outbox.get() : nullptr;
}

/**
 * @brief The saved messages, kept whether the history is on or off.
 * @return Null when the database could not be opened.
 */
SavedNotes* History::getSavedNotes()
{
    return isValid() ? savedNotes.get() : nullptr;
}

size_t History::getNumMessagesForChat(const ChatId& chatId)
{
    if (historyAccessBlocked()) {
        return 0;
    }

    return getNumMessagesForChatBeforeDate(chatId, QDateTime());
}

size_t History::getNumMessagesForChatBeforeDate(const ChatId& chatId, const QDateTime& date)
{
    if (historyAccessBlocked()) {
        return 0;
    }

    QString queryText = QString("SELECT COUNT(history.id) "
                                "FROM history "
                                "JOIN chats ON chat_id = chats.id "
                                "WHERE chats.uuid = ?");

    if (date.isNull()) {
        queryText += ";";
    } else {
        queryText += QString(" AND timestamp < %1;").arg(date.toMSecsSinceEpoch());
    }

    size_t numMessages = 0;
    auto rowCallback = [&numMessages](const QVector<QVariant>& row) {
        numMessages = row[0].toLongLong();
    };

    db->execNow({queryText, {chatId.getByteArray()}, rowCallback});

    return numMessages;
}

QList<History::HistMessage> History::getMessagesForChat(const ChatId& chatId, size_t firstIdx,
                                                          size_t lastIdx)
{
    if (historyAccessBlocked()) {
        return {};
    }

    QList<HistMessage> messages;

    auto rowCallback = [&chatId, &messages](const QVector<QVariant>& row) {
        // If the select statement is changed please update these constants
        constexpr auto messageOffset = 6;
        constexpr auto fileOffset = 7;
        constexpr auto senderOffset = 13;
        constexpr auto systemOffset = 15;
        constexpr auto msgV3Offset = 20;
        constexpr auto fileHashOffset = 21;

        auto it = row.begin();

        const auto id = RowId{(*it++).toLongLong()};
        const auto messageType = (*it++).toString();
        const auto timestamp = QDateTime::fromMSecsSinceEpoch((*it++).toLongLong());
        const auto isPending = !(*it++).isNull();
        // If NULL this should just reutrn 0 which is an empty extension set, good enough for now
        const auto requiredExtensions = ExtensionSet((*it++).toLongLong());
        const auto isBroken = !(*it++).isNull();
        const auto messageState = getMessageState(isPending, isBroken);

        // Intentionally arrange query so message types are at the end so we don't have to think
        // about the iterator jumping around after handling the different types.
        assert(messageType.size() == 1);
        switch (messageType[0].toLatin1()) {
        case 'T': {
            it = std::next(row.begin(), messageOffset);
            // An empty text reads as null: a message its author deleted (setMessageText), which
            // the conversation leaves out.
            const auto messageContent = (*it++).toString();
            it = std::next(row.begin(), senderOffset);
            const auto senderKey = ToxPk{(*it++).toByteArray()};
            const auto senderName = QString::fromUtf8((*it++).toByteArray().replace('\0', ""));
            HistMessage text(id, messageState, requiredExtensions, timestamp, chatId.clone(),
                             senderName, senderKey, messageContent);
            text.msgV3Id = row[msgV3Offset].toByteArray();
            messages += text;
            break;
        }
        case 'F': {
            it = std::next(row.begin(), fileOffset);
            assert(!it->isNull());
            const auto fileKind = TOX_FILE_KIND_DATA;
            const auto resumeFileId = (*it++).toByteArray();
            const auto fileName = (*it++).toString();
            const auto filePath = (*it++).toString();
            const auto filesize = (*it++).toLongLong();
            const auto direction = static_cast<ToxFile::FileDirection>((*it++).toLongLong());
            const auto status = static_cast<ToxFile::FileStatus>((*it++).toLongLong());

            ToxFile file(0, 0, fileName, filePath, filesize, direction);
            file.fileKind = fileKind;
            file.resumeFileId = resumeFileId;
            file.status = status;
            // KHANDAQ (0.2.57): a row keeps no word of who stopped a file of theirs: one that ended
            // cancelled counts as refused here, so an offer of it again waits for the person after
            // a restart too (ToxFile::refusedHere). One its sender stopped waits all the same.
            file.refusedHere = direction == ToxFile::RECEIVING && status == ToxFile::CANCELED;
            file.stamp = stampOfRecord(row[fileHashOffset].toByteArray());

            it = std::next(row.begin(), senderOffset);
            const auto senderKey = ToxPk{(*it++).toByteArray()};
            const auto senderName = QString::fromUtf8((*it++).toByteArray().replace('\0', ""));
            messages += HistMessage(id, messageState, timestamp, chatId.clone(), senderName,
                                    senderKey, file);
            break;
        }
        default:
        case 'S':
            it = std::next(row.begin(), systemOffset);
            assert(!it->isNull());
            SystemMessage systemMessage;
            systemMessage.messageType = static_cast<SystemMessageType>((*it++).toLongLong());
            systemMessage.timestamp = timestamp;

            auto argEnd = std::next(it, systemMessage.args.size());
            std::transform(it, argEnd, systemMessage.args.begin(), [](const QVariant& arg) {
                return QString::fromUtf8(arg.toByteArray().replace('\0', ""));
            });
            it = argEnd;

            messages += HistMessage(id, timestamp, chatId.clone(), systemMessage);
            break;
        }
    };

    // Don't forget to update the rowCallback if you change the selected columns!
    QString queryString =
        QStringLiteral(
            "SELECT history.id, history.message_type, history.timestamp, faux_offline_pending.id, "
            "    faux_offline_pending.required_extensions, broken_messages.id, text_messages.message, "
            "    file_restart_id, file_name, file_path, file_size, file_transfers.direction, "
            "    file_state, authors.public_key as sender_key, aliases.display_name, "
            "    system_messages.system_message_type, system_messages.arg1, system_messages.arg2, "
            "    system_messages.arg3, system_messages.arg4, text_messages.msgv3_id, "
            "    file_transfers.file_hash "
            "FROM history "
            "LEFT JOIN text_messages ON history.id = text_messages.id "
            "LEFT JOIN file_transfers ON history.id = file_transfers.id "
            "LEFT JOIN system_messages ON system_messages.id == history.id "
            "LEFT JOIN aliases ON text_messages.sender_alias = aliases.id OR "
            "file_transfers.sender_alias = aliases.id "
            "LEFT JOIN authors ON aliases.owner = authors.id "
            "LEFT JOIN faux_offline_pending ON faux_offline_pending.id = history.id "
            "LEFT JOIN broken_messages ON broken_messages.id = history.id "
            "WHERE history.chat_id = ");
    QVector<QByteArray> boundParams;
    addChatIdSubQuery(queryString, boundParams, chatId);
    queryString += QStringLiteral(
            " LIMIT %1 OFFSET %2;")
            .arg(lastIdx - firstIdx)
            .arg(firstIdx);
    db->execNow({queryString, boundParams, rowCallback});

    return messages;
}

QList<History::HistMessage> History::getUndeliveredMessagesForChat(const ChatId& chatId)
{
    if (historyAccessBlocked()) {
        return {};
    }

    QList<History::HistMessage> ret;
    auto rowCallback = [&chatId, &ret](const QVector<QVariant>& row) {
        auto it = row.begin();
        // dispName and message could have null bytes, QString::fromUtf8
        // truncates on null bytes so we strip them
        auto id = RowId{(*it++).toLongLong()};
        auto timestamp = QDateTime::fromMSecsSinceEpoch((*it++).toLongLong());
        auto isPending = !(*it++).isNull();
        auto extensionSet = ExtensionSet((*it++).toLongLong());
        auto isBroken = !(*it++).isNull();
        auto messageContent = (*it++).toString();
        auto senderKey = ToxPk{(*it++).toByteArray()};
        auto displayName = QString::fromUtf8((*it++).toByteArray().replace('\0', ""));

        MessageState messageState = getMessageState(isPending, isBroken);

        ret += {id,          messageState, extensionSet,  timestamp, chatId.clone(),
                displayName, senderKey,    messageContent};
    };

    QString queryString =
        QStringLiteral(
            "SELECT history.id, history.timestamp, faux_offline_pending.id, "
            "    faux_offline_pending.required_extensions, broken_messages.id, text_messages.message, "
            "    authors.public_key as sender_key, aliases.display_name "
            "FROM history "
            "JOIN text_messages ON history.id = text_messages.id "
            "JOIN aliases ON text_messages.sender_alias = aliases.id "
            "JOIN authors ON aliases.owner = authors.id "
            "JOIN faux_offline_pending ON faux_offline_pending.id = history.id "
            "LEFT JOIN broken_messages ON broken_messages.id = history.id "
            "WHERE history.chat_id = ");
    QVector<QByteArray> boundParams;
    addChatIdSubQuery(queryString, boundParams, chatId);
    // In the order they were written: they are sent again in it.
    queryString += QStringLiteral(" AND history.message_type = 'T' ORDER BY history.id;");
    db->execNow({queryString, boundParams, rowCallback});

    return ret;
}

/**
 * @brief Search phrase in chat messages
 * @param chatId Chat ID
 * @param from a date message where need to start a search
 * @param phrase what need to find
 * @param parameter for search
 * @return date of the message where the phrase was found
 */
QDateTime History::getDateWhereFindPhrase(const ChatId& chatId, const QDateTime& from,
                                          QString phrase, const ParameterSearch& parameter)
{
    if (historyAccessBlocked()) {
        return QDateTime();
    }

    QDateTime result;
    auto rowCallback = [&result](const QVector<QVariant>& row) {
        result = QDateTime::fromMSecsSinceEpoch(row[0].toLongLong());
    };

    phrase.replace("'", "''");

    QString message;

    switch (parameter.filter) {
    case FilterSearch::Register:
        message = QStringLiteral("text_messages.message LIKE '%%1%'").arg(phrase);
        break;
    case FilterSearch::WordsOnly:
        message = QStringLiteral("text_messages.message REGEXP '%1'")
                      .arg(SearchExtraFunctions::generateFilterWordsOnly(phrase).toLower());
        break;
    case FilterSearch::RegisterAndWordsOnly:
        message = QStringLiteral("REGEXPSENSITIVE(text_messages.message, '%1')")
                      .arg(SearchExtraFunctions::generateFilterWordsOnly(phrase));
        break;
    case FilterSearch::Regular:
        message = QStringLiteral("text_messages.message REGEXP '%1'").arg(phrase);
        break;
    case FilterSearch::RegisterAndRegular:
        message = QStringLiteral("REGEXPSENSITIVE(text_messages.message '%1')").arg(phrase);
        break;
    default:
        message = QStringLiteral("LOWER(text_messages.message) LIKE '%%1%'").arg(phrase.toLower());
        break;
    }

    QDateTime date = from;

    if (!date.isValid()) {
        date = QDateTime::currentDateTime();
    }

    if (parameter.period == PeriodSearch::AfterDate || parameter.period == PeriodSearch::BeforeDate) {

#if (QT_VERSION >= QT_VERSION_CHECK(5, 15, 0))
        date = parameter.date.startOfDay();
#else
        date = QDateTime(parameter.date);
#endif
    }

    QString period;
    switch (parameter.period) {
    case PeriodSearch::WithTheFirst:
        period = QStringLiteral("ORDER BY timestamp ASC LIMIT 1;");
        break;
    case PeriodSearch::AfterDate:
        period = QStringLiteral("AND timestamp > '%1' ORDER BY timestamp ASC LIMIT 1;")
                     .arg(date.toMSecsSinceEpoch());
        break;
    case PeriodSearch::BeforeDate:
        period = QStringLiteral("AND timestamp < '%1' ORDER BY timestamp DESC LIMIT 1;")
                     .arg(date.toMSecsSinceEpoch());
        break;
    default:
        period = QStringLiteral("AND timestamp < '%1' ORDER BY timestamp DESC LIMIT 1;")
                     .arg(date.toMSecsSinceEpoch());
        break;
    }

    auto query = RawDatabase::Query(
        QStringLiteral("SELECT timestamp "
                       "FROM history "
                       "JOIN chats ON chat_id = chats.id "
                       "JOIN text_messages ON history.id = text_messages.id "
                       "WHERE chats.uuid = ? "
                       "AND %1 "
                       "%2")
            .arg(message)
            .arg(period),
            {chatId.getByteArray()},
            rowCallback);

    db->execNow(query);

    return result;
}

/**
 * @brief Gets date boundaries in conversation with friendPk. History doesn't model conversation indexes,
 * but we can count messages between us and friendPk to effectively give us an index. This function
 * returns how many messages have happened between us <-> friendPk each time the date changes
 * @param[in] chatId ChatId of conversation to retrieve
 * @param[in] from Start date to look from
 * @param[in] maxNum Maximum number of date boundaries to retrieve
 * @note This API may seem a little strange, why not use QDate from and QDate to? The intent is to
 * have an API that can be used to get the first item after a date (for search) and to get a list
 * of date changes (for loadHistory). We could write two separate queries but the query is fairly
 * intricate compared to our other ones so reducing duplication of it is preferable.
 */
QList<History::DateIdx> History::getNumMessagesForChatBeforeDateBoundaries(const ChatId& chatId,
                                                                             const QDate& from,
                                                                             size_t maxNum)
{
    if (historyAccessBlocked()) {
        return {};
    }

    QList<DateIdx> dateIdxs;
    auto rowCallback = [&dateIdxs](const QVector<QVariant>& row) {
        DateIdx dateIdx;
        dateIdx.numMessagesIn = row[0].toLongLong();
        dateIdx.date =
            QDateTime::fromMSecsSinceEpoch(row[1].toLongLong() * 24 * 60 * 60 * 1000).date();
        dateIdxs.append(dateIdx);
    };

    // No guarantee that this is the most efficient way to do this...
    // We want to count messages that happened for a friend before a
    // certain date. We do this by re-joining our table a second time
    // but this time with the only filter being that our id is less than
    // the ID of the corresponding row in the table that is grouped by day
    auto countMessagesForFriend =
        QStringLiteral("SELECT COUNT(*) - 1 " // Count - 1 corresponds to 0 indexed message id for friend
                "FROM history countHistory "            // Import unfiltered table as countHistory
                "JOIN chats ON chat_id = chats.id " // link chat_id to chat.id
                "WHERE chats.uuid = ?"          // filter this conversation
                "AND countHistory.id <= history.id"); // and filter that our unfiltered table history id only has elements up to history.id

    auto limitString = (maxNum) ? QString("LIMIT %1").arg(maxNum) : QString("");

    auto query = RawDatabase::Query(QStringLiteral(
                        "SELECT (%1), (timestamp / 1000 / 60 / 60 / 24) AS day "
                               "FROM history "
                               "JOIN chats ON chat_id = chats.id "
                               "WHERE chats.uuid = ? "
                               "AND timestamp >= %2 "
                               "GROUP by day "
                               "%3;")
                           .arg(countMessagesForFriend)
#if (QT_VERSION >= QT_VERSION_CHECK(5, 15, 0))
                           .arg(QDateTime(from.startOfDay()).toMSecsSinceEpoch())
#else
                           .arg(QDateTime(from).toMSecsSinceEpoch())
#endif
                           .arg(limitString),
                           {chatId.getByteArray(), chatId.getByteArray()},
                           rowCallback);

    db->execNow(query);

    return dateIdxs;
}

/**
 * @brief Marks a message as delivered.
 * Removing message from the faux-offline pending messages list.
 *
 * @param id Message ID.
 */
void History::markAsDelivered(RowId messageId)
{
    if (historyAccessBlocked()) {
        return;
    }

    db->execLater(QString("DELETE FROM faux_offline_pending WHERE id=%1;").arg(messageId.get()));
}

/**
* @brief Determines if history access should be blocked
* @return True if history should not be accessed
*/
bool History::historyAccessBlocked()
{
    if (!settings.getEnableLogging()) {
        assert(false);
        qCritical() << "Blocked history access while history is disabled";
        return true;
    }

    if (!isValid()) {
        return true;
    }

    return false;
}

void History::setMessageText(const ChatId& chatId, const ToxPk& author, const QDateTime& time,
                             const QString& current, const QString& text)
{
    if (historyAccessBlocked()) {
        return;
    }

    QVector<QByteArray> boundParams;
    boundParams += text.toUtf8();
    QString queryString = QStringLiteral("UPDATE text_messages SET message = ? WHERE id = ("
                                         "SELECT history.id FROM history "
                                         "JOIN text_messages ON text_messages.id = history.id "
                                         "JOIN aliases ON text_messages.sender_alias = aliases.id "
                                         "WHERE history.chat_id = ");
    addChatIdSubQuery(queryString, boundParams, chatId);
    queryString += QStringLiteral(" AND aliases.owner = ");
    addAuthorIdSubQuery(queryString, boundParams, author);
    queryString += QStringLiteral(" AND history.timestamp = %1 AND text_messages.message = ? "
                                  "ORDER BY history.id DESC LIMIT 1);")
                       .arg(time.toMSecsSinceEpoch());
    boundParams += current.toUtf8();
    db->execLater(RawDatabase::Query(queryString, boundParams));
}

void History::reopenFileMessage(const ChatId& chatId, const QByteArray& fileId,
                                ToxFile::FileDirection direction)
{
    // A row this run inserted and that has not ended is still known to it.
    if (historyAccessBlocked() || fileInfos.contains(fileId) || erasedFiles.contains(fileId)) {
        return;
    }
    QVector<QByteArray> boundParams{fileId};
    QString queryString = QStringLiteral("SELECT file_transfers.id FROM file_transfers "
                                         "JOIN history ON history.id = file_transfers.id "
                                         "WHERE file_transfers.file_restart_id = ? "
                                         "AND file_transfers.direction = %1 AND history.chat_id = ")
                              .arg(static_cast<int>(direction));
    addChatIdSubQuery(queryString, boundParams, chatId);
    queryString += QStringLiteral(" ORDER BY file_transfers.id DESC LIMIT 1;");
    RowId found{-1};
    db->execNow({queryString, boundParams,
                 [&found](const QVector<QVariant>& row) { found = RowId{row[0].toLongLong()}; }});
    if (found.get() != -1) {
        fileInfos[fileId].fileId = found;
    }
}

void History::setFileUnderway(const ChatId& chatId, const QByteArray& fileId,
                              const QString& filePath, const ToxFile::Stamp& stamp)
{
    if (historyAccessBlocked() || erasedFiles.contains(fileId)) {
        return;
    }
    // By the id rather than the row: the row of a new file may not be in yet, and the queue of the
    // database runs this after the insert.
    QVector<QByteArray> boundParams{filePath.toUtf8(), stampRecord(stamp), fileId};
    QString queryString =
        QStringLiteral("UPDATE file_transfers SET file_state = %1, file_path = ?, file_hash = ? "
                       "WHERE id = (SELECT max(file_transfers.id) FROM file_transfers "
                       "JOIN history ON history.id = file_transfers.id "
                       "WHERE file_transfers.file_restart_id = ? "
                       "AND file_transfers.direction = %2 AND history.chat_id = ")
            .arg(static_cast<int>(ToxFile::BROKEN))
            .arg(static_cast<int>(ToxFile::RECEIVING));
    addChatIdSubQuery(queryString, boundParams, chatId);
    queryString += QStringLiteral(");");
    db->execLater(RawDatabase::Query(queryString, boundParams));
}

bool History::laterFileGoesTo(const ChatId& chatId, const QByteArray& fileId,
                              const QString& filePath)
{
    if (historyAccessBlocked() || filePath.isEmpty()) {
        return false;
    }
    QVector<QByteArray> boundParams{filePath.toUtf8(), fileId};
    QString queryString =
        QStringLiteral("SELECT 1 FROM file_transfers WHERE direction = %1 AND file_path = ? "
                       "AND id > (SELECT max(file_transfers.id) FROM file_transfers "
                       "JOIN history ON history.id = file_transfers.id "
                       "WHERE file_transfers.file_restart_id = ? "
                       "AND file_transfers.direction = %1 AND history.chat_id = ")
            .arg(static_cast<int>(ToxFile::RECEIVING));
    addChatIdSubQuery(queryString, boundParams, chatId);
    queryString += QStringLiteral(") LIMIT 1;");
    bool found = false;
    db->execNow({queryString, boundParams, [&found](const QVector<QVariant>&) { found = true; }});
    return found;
}

History::SentFile History::sentFile(const ChatId& chatId, const QByteArray& fileId)
{
    SentFile sent;
    if (historyAccessBlocked() || fileId.isEmpty() || erasedFiles.contains(fileId)) {
        return sent;
    }
    QVector<QByteArray> boundParams{fileId};
    QString queryString =
        QStringLiteral("SELECT file_transfers.file_name, file_transfers.file_path, "
                       "file_transfers.file_size, history.timestamp FROM file_transfers "
                       "JOIN history ON history.id = file_transfers.id "
                       "WHERE file_transfers.file_restart_id = ? "
                       "AND file_transfers.direction = %1 AND history.chat_id = ")
            .arg(static_cast<int>(ToxFile::SENDING));
    addChatIdSubQuery(queryString, boundParams, chatId);
    queryString += QStringLiteral(" ORDER BY file_transfers.id DESC LIMIT 1;");
    db->execNow({queryString, boundParams, [&sent](const QVector<QVariant>& row) {
                     sent.fileName = QString::fromUtf8(row[0].toByteArray().replace('\0', ""));
                     sent.filePath = QString::fromUtf8(row[1].toByteArray().replace('\0', ""));
                     sent.size = row[2].toLongLong();
                     sent.sentMs = row[3].toLongLong();
                 }});
    return sent;
}

void History::eraseFileEntry(const ChatId& chatId, const QByteArray& fileId, const ToxPk& author)
{
    if (historyAccessBlocked()) {
        return;
    }

    if (author.isEmpty()) {
        erasedFiles.insert(fileId);
    }
    QVector<QByteArray> boundParams;
    boundParams += QByteArray();
    boundParams += QByteArray();
    boundParams += fileId;
    QString queryString = QStringLiteral("UPDATE file_transfers SET file_name = ?, file_path = ? "
                                         "WHERE file_restart_id = ? AND id IN "
                                         "(SELECT id FROM history WHERE chat_id = ");
    addChatIdSubQuery(queryString, boundParams, chatId);
    queryString += QStringLiteral(")");
    if (!author.isEmpty()) {
        queryString +=
            QStringLiteral(" AND sender_alias IN (SELECT id FROM aliases WHERE owner = ");
        addAuthorIdSubQuery(queryString, boundParams, author);
        queryString += QStringLiteral(")");
    }
    queryString += QStringLiteral(";");
    db->execLater(RawDatabase::Query(queryString, boundParams));
}

constexpr qint64 History::deletionKeepMs;

void History::addDeletion(const ChatId& chatId, const ToxPk& author,
                          Air::MessageEffect::Anchor anchor, const QByteArray& anchorId)
{
    if (historyAccessBlocked() || author.isEmpty() || anchorId.isEmpty()) {
        return;
    }
    loadDeletions();
    const int kind = anchorValue(anchor);
    QSet<QByteArray>& kept = deletions[chatId.getByteArray()];
    const QByteArray key = deletionKey(author.getByteArray(), kind, anchorId);
    if (kept.contains(key)) {
        return;
    }
    kept.insert(key);
    db->execLater(RawDatabase::Query(
        QStringLiteral(
            "INSERT OR IGNORE INTO deleted_messages "
            "(chat_uuid, author, anchor, anchor_id, deleted_at) VALUES (?, ?, %1, ?, %2);")
            .arg(kind)
            .arg(QDateTime::currentMSecsSinceEpoch()),
        {chatId.getByteArray(), author.getByteArray(), anchorId}));
}

bool History::isDeleted(const ChatId& chatId, const ToxPk& author,
                        Air::MessageEffect::Anchor anchor, const QByteArray& anchorId)
{
    if (historyAccessBlocked() || author.isEmpty() || anchorId.isEmpty()) {
        return false;
    }
    loadDeletions();
    const auto kept = deletions.constFind(chatId.getByteArray());
    return kept != deletions.constEnd()
           && kept->contains(deletionKey(author.getByteArray(), anchorValue(anchor), anchorId));
}

void History::loadDeletions()
{
    if (deletionsLoaded) {
        return;
    }
    deletionsLoaded = true;
    QVector<RawDatabase::Query> queries;
    queries +=
        RawDatabase::Query(QStringLiteral("DELETE FROM deleted_messages WHERE deleted_at < %1;")
                               .arg(QDateTime::currentMSecsSinceEpoch() - deletionKeepMs));
    queries += RawDatabase::Query(
        QStringLiteral("SELECT chat_uuid, author, anchor, anchor_id FROM deleted_messages;"),
        [this](const QVector<QVariant>& row) {
            deletions[row[0].toByteArray()].insert(
                deletionKey(row[1].toByteArray(), row[2].toInt(), row[3].toByteArray()));
        });
    if (!db->execNow(queries)) {
        qWarning() << "Failed to read the deletions kept in the history";
    }
}

QVector<History::KeptText> History::textsOf(const ChatId& chatId, const ToxPk& author, int limit)
{
    QVector<KeptText> texts;
    if (historyAccessBlocked() || limit <= 0) {
        return texts;
    }
    QVector<QByteArray> boundParams;
    QString queryString = QStringLiteral("SELECT history.timestamp, text_messages.message "
                                         "FROM history "
                                         "JOIN text_messages ON text_messages.id = history.id "
                                         "JOIN aliases ON aliases.id = text_messages.sender_alias "
                                         "WHERE length(text_messages.message) > 0 "
                                         "AND history.chat_id = ");
    addChatIdSubQuery(queryString, boundParams, chatId);
    queryString += QStringLiteral(" AND aliases.owner = ");
    addAuthorIdSubQuery(queryString, boundParams, author);
    queryString += QStringLiteral(" ORDER BY history.id DESC LIMIT %1;").arg(limit);
    // Read as getMessagesForChat reads them, so that setMessageText finds the words again.
    db->execNow({queryString, boundParams, [&texts](const QVector<QVariant>& row) {
                     texts.append(KeptText{QDateTime::fromMSecsSinceEpoch(row[0].toLongLong()),
                                           row[1].toString()});
                 }});
    return texts;
}

qint64 History::ownItemTime(const ChatId& chatId, const ToxPk& self, const Air::SeenAnchor& anchor)
{
    if (historyAccessBlocked() || anchor.isEmpty()) {
        return -1;
    }

    QVector<QByteArray> boundParams{anchor.id};
    QString queryString;
    if (anchor.anchor == Air::MessageEffect::Anchor::File) {
        queryString = QStringLiteral("SELECT history.timestamp FROM file_transfers "
                                     "JOIN history ON history.id = file_transfers.id "
                                     "WHERE file_transfers.file_restart_id = ? "
                                     "AND file_transfers.direction = %1 AND history.chat_id = ")
                          .arg(static_cast<int>(ToxFile::SENDING));
        addChatIdSubQuery(queryString, boundParams, chatId);
    } else {
        queryString = QStringLiteral("SELECT history.timestamp FROM text_messages "
                                     "JOIN history ON history.id = text_messages.id "
                                     "JOIN aliases ON aliases.id = text_messages.sender_alias "
                                     "WHERE text_messages.msgv3_id = ? AND history.chat_id = ");
        addChatIdSubQuery(queryString, boundParams, chatId);
        queryString += QStringLiteral(" AND aliases.owner = ");
        addAuthorIdSubQuery(queryString, boundParams, self);
    }
    queryString += QStringLiteral(" ORDER BY history.id DESC LIMIT 1;");

    qint64 timeMs = -1;
    db->execNow({queryString, boundParams,
                 [&timeMs](const QVector<QVariant>& row) { timeMs = row[0].toLongLong(); }});
    return timeMs;
}

Air::SeenAnchor History::newestIncomingAnchor(const ChatId& chatId, const ToxPk& self)
{
    if (historyAccessBlocked()) {
        return {};
    }

    // A text keeps no words once its author deleted it, a file neither name nor place
    // (setMessageText, eraseFileEntry): the person cannot have seen those.
    QVector<QByteArray> boundParams;
    QString queryString = QStringLiteral(
        "SELECT history.timestamp, text_messages.msgv3_id, file_transfers.file_restart_id "
        "FROM history "
        "LEFT JOIN text_messages ON text_messages.id = history.id "
        "LEFT JOIN file_transfers ON file_transfers.id = history.id "
        "LEFT JOIN aliases ON aliases.id = text_messages.sender_alias "
        "WHERE history.chat_id = ");
    addChatIdSubQuery(queryString, boundParams, chatId);
    queryString +=
        QStringLiteral(" AND ((text_messages.msgv3_id IS NOT NULL "
                       "AND length(text_messages.message) > 0 AND aliases.owner IS NOT ");
    addAuthorIdSubQuery(queryString, boundParams, self);
    queryString += QStringLiteral(") OR (file_transfers.direction = %1 "
                                  "AND length(file_transfers.file_name) > 0)) "
                                  "ORDER BY history.id DESC LIMIT 1;")
                       .arg(static_cast<int>(ToxFile::RECEIVING));

    Air::SeenAnchor anchor;
    db->execNow({queryString, boundParams, [&anchor](const QVector<QVariant>& row) {
                     anchor.timeMs = row[0].toLongLong();
                     if (row[1].isNull()) {
                         anchor.anchor = Air::MessageEffect::Anchor::File;
                         anchor.id = row[2].toByteArray();
                     } else {
                         anchor.anchor = Air::MessageEffect::Anchor::Text;
                         anchor.id = row[1].toByteArray();
                     }
                 }});
    return anchor;
}

bool History::hasText(const ChatId& chatId, const ToxPk& author, const QByteArray& msgV3Id)
{
    if (historyAccessBlocked() || msgV3Id.isEmpty()) {
        return false;
    }

    QVector<QByteArray> boundParams{msgV3Id};
    QString queryString = QStringLiteral("SELECT COUNT(*) FROM text_messages "
                                         "JOIN history ON history.id = text_messages.id "
                                         "JOIN aliases ON aliases.id = text_messages.sender_alias "
                                         "WHERE text_messages.msgv3_id = ? AND history.chat_id = ");
    addChatIdSubQuery(queryString, boundParams, chatId);
    queryString += QStringLiteral(" AND aliases.owner = ");
    addAuthorIdSubQuery(queryString, boundParams, author);
    queryString += QStringLiteral(";");

    qint64 count = 0;
    db->execNow({queryString, boundParams,
                 [&count](const QVector<QVariant>& row) { count = row[0].toLongLong(); }});
    return count > 0;
}

bool History::hasGroupText(const ChatId& chatId, const ToxPk& author, const QString& text,
                           qint64 sinceMs)
{
    if (historyAccessBlocked() || text.isEmpty()) {
        return false;
    }

    // The words as generateNewTextMessageQueries writes them: a blob, which no text equals.
    QVector<QByteArray> boundParams{text.toUtf8()};
    QString queryString = QStringLiteral("SELECT 1 FROM text_messages "
                                         "JOIN history ON history.id = text_messages.id "
                                         "JOIN aliases ON aliases.id = text_messages.sender_alias "
                                         "WHERE text_messages.message = ? "
                                         "AND history.timestamp >= %1 AND history.chat_id = ")
                              .arg(sinceMs);
    addChatIdSubQuery(queryString, boundParams, chatId);
    queryString += QStringLiteral(" AND aliases.owner = ");
    addAuthorIdSubQuery(queryString, boundParams, author);
    queryString += QStringLiteral(" LIMIT 1;");

    bool found = false;
    db->execNow(
        {queryString, boundParams, [&found](const QVector<QVariant>&) { found = true; }});
    return found;
}

QString History::lastAuthorName(const ToxPk& author)
{
    if (historyAccessBlocked()) {
        return QString();
    }

    // The newest name first; a name kept as the key is none (Air::authorUnnamed).
    QVector<QByteArray> boundParams;
    QString queryString = QStringLiteral("SELECT display_name FROM aliases WHERE owner = ");
    addAuthorIdSubQuery(queryString, boundParams, author);
    // The names are kept as blobs, which only a blob equals: the key in both cases it is written in.
    queryString += QStringLiteral(" AND length(display_name) > 0 AND display_name NOT IN (?, ?) "
                                  "ORDER BY id DESC LIMIT 1;");
    boundParams.append(author.toString().toUpper().toUtf8());
    boundParams.append(author.toString().toLower().toUtf8());

    QString name;
    db->execNow({queryString, boundParams, [&name](const QVector<QVariant>& row) {
                     name = QString::fromUtf8(row[0].toByteArray());
                 }});
    return name;
}

void History::nameUnnamedEntries(const ChatId& chatId, const ToxPk& author, const QString& name)
{
    if (name.trimmed().isEmpty() || name.compare(author.toString(), Qt::CaseInsensitive) == 0
        || historyAccessBlocked()) {
        return;
    }

    // The aliases of the author with no name in them: an empty one, or the key, which a member who
    // set no name is kept with (FriendList::decideNickname). Most authors have none: nothing to do.
    QVector<QByteArray> findParams;
    QString findQuery = QStringLiteral("SELECT id FROM aliases WHERE owner = ");
    addAuthorIdSubQuery(findQuery, findParams, author);
    findQuery += QStringLiteral(" AND (length(display_name) = 0 OR display_name IN (?, ?));");
    findParams.append(author.toString().toUpper().toUtf8());
    findParams.append(author.toString().toLower().toUtf8());
    QStringList unnamed;
    db->execNow({findQuery, findParams, [&unnamed](const QVector<QVariant>& row) {
                     unnamed.append(QString::number(row[0].toLongLong()));
                 }});
    if (unnamed.isEmpty()) {
        return;
    }

    QVector<RawDatabase::Query> queries;
    queries += generateUpdateAlias(author, name);
    for (const QString& table : {QStringLiteral("text_messages"), QStringLiteral("file_transfers")}) {
        QVector<QByteArray> boundParams;
        QString queryString =
            QStringLiteral("UPDATE %1 SET sender_alias = (SELECT id FROM aliases WHERE owner = ")
                .arg(table);
        addAuthorIdSubQuery(queryString, boundParams, author);
        queryString += QStringLiteral(" AND display_name = ?) ");
        boundParams.append(name.toUtf8());
        // The ids are numbers the database gave: nothing of the author's own goes in here.
        queryString += QStringLiteral("WHERE sender_alias IN (%1) AND id IN "
                                      "(SELECT id FROM history WHERE chat_id = ")
                           .arg(unnamed.join(QLatin1Char(',')));
        addChatIdSubQuery(queryString, boundParams, chatId);
        queryString += QStringLiteral(");");
        queries += RawDatabase::Query{queryString, boundParams};
    }
    db->execLater(queries);
}

void History::markAsBroken(RowId messageId, BrokenMessageReason reason)
{
    if (!isValid()) {
        return;
    }

    QVector<RawDatabase::Query> queries;
    queries += RawDatabase::Query(QString("DELETE FROM faux_offline_pending WHERE id=%1;").arg(messageId.get()));
    queries += RawDatabase::Query(QString("INSERT INTO broken_messages (id, reason) "
                                          "VALUES (%1, %2);")
                                          .arg(messageId.get())
                                          .arg(static_cast<int64_t>(reason)));

    db->execLater(queries);
}
