// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 A deleted message stayed readable in the quote of every reply to it (inbox #10, 27.11), in a group and in Saved
 Messages: a message was answered, then deleted, and the reply still showed its words. A reply
 carries the words it quotes; objcTox now remembers each message it removes by the marks a reply header names its
 message by, the local id and the date, in the same write, and the quote of a reply to it says it was deleted. The
 rule is Android's (ReplyTargetTombstonesTest), case for case.
 */
final class ReplyTargetDeletionTests: XCTestCase {
    private let t: Int64 = 1_764_244_800_000
    private var directories = [URL]()
    private var kept = [AnyObject]()

    override func tearDown() {
        for directory in directories {
            try? FileManager.default.removeItem(at: directory)
        }
        directories.removeAll()
        kept.removeAll()
        super.tearDown()
    }

    private func reply(_ localId: Int64, _ dateMs: Int64) -> MessageReplyHelper.ReplyMeta {
        var meta = MessageReplyHelper.ReplyMeta()
        meta.localMessageId = localId
        meta.sortTimestampMs = dateMs
        meta.senderName = "Tester"
        meta.previewText = "Lunch at one?"
        return meta
    }

    private func mark(_ localId: Int64, _ dateMs: Int64) -> MessageReplyHelper.ReplyTargetMark {
        return MessageReplyHelper.ReplyTargetMark(localId: localId, dateMs: dateMs)
    }

    private func deleted(_ meta: MessageReplyHelper.ReplyMeta,
                         _ marks: [MessageReplyHelper.ReplyTargetMark],
                         reply replyLocalId: Int64 = 42,
                         live: [MessageReplyHelper.ReplyTargetMark]?) -> Bool {
        return MessageReplyHelper.isTargetDeleted(meta, deleted: marks, replyLocalId: replyLocalId) { _ in live }
    }

    // MARK: - The rule

    func testAReplyWrittenHereSaysItsMessageWasDeleted() {
        // The message was 41, the reply to it is 42 a second later.
        XCTAssertTrue(deleted(reply(41, t), [mark(41, t)], live: [mark(42, t + 1000)]))
        // By its id, even when another message has the very same date.
        XCTAssertTrue(deleted(reply(41, t), [mark(41, t)], live: [mark(40, t)]))
    }

    func testAMessageStillHereWithTheIdIsTheOneQuoted() {
        XCTAssertFalse(deleted(reply(40, t), [mark(41, t)], live: [mark(40, t + 900)]))
    }

    func testAReplyFromAnotherDeviceNamesItsMessageByTheDateAndTheNearestWins() {
        // Another device's ids mean nothing here: 7 is no message of ours.
        XCTAssertTrue(deleted(reply(7, t + 300), [mark(41, t)], reply: 50, live: [mark(43, t + 2000)]))
        XCTAssertFalse(deleted(reply(7, t + 2100), [mark(41, t)], reply: 50, live: [mark(43, t + 2000)]))
        // As far from both: the quote stays as it came.
        XCTAssertFalse(deleted(reply(7, t + 1000), [mark(41, t)], reply: 50, live: [mark(43, t + 2000)]))
    }

    func testTheReplyItselfAndARowBeingDeletedAreNoCandidates() {
        XCTAssertTrue(deleted(reply(7, t + 1500), [mark(41, t)], live: [mark(42, t + 1600)]))
        XCTAssertTrue(deleted(reply(41, t), [mark(41, t)], live: [mark(41, t)]))
    }

    func testWithTheChatUnknownOnlyTheIdTells() {
        XCTAssertTrue(deleted(reply(41, t), [mark(41, t)], live: nil))
        XCTAssertFalse(deleted(reply(7, t), [mark(41, t)], live: nil))
    }

    func testAQuoteWithoutADateOrFarFromEveryDeletionStays() {
        let notAsked: (Int64) -> [MessageReplyHelper.ReplyTargetMark]? = { _ in
            XCTFail("the messages of the chat are read only when a deletion is near the date")
            return []
        }
        XCTAssertFalse(MessageReplyHelper.isTargetDeleted(reply(41, 0), deleted: [mark(41, t)], replyLocalId: 42, live: notAsked))
        XCTAssertFalse(MessageReplyHelper.isTargetDeleted(reply(41, -5), deleted: [mark(41, t)], replyLocalId: 42, live: notAsked))
        var legacy = reply(41, t)
        legacy.legacyQuote = true
        XCTAssertFalse(MessageReplyHelper.isTargetDeleted(legacy, deleted: [mark(41, t)], replyLocalId: 42, live: notAsked))
        XCTAssertFalse(MessageReplyHelper.isTargetDeleted(reply(41, t + MessageReplyHelper.replyTargetWindowMs + 1),
                                                          deleted: [mark(41, t)], replyLocalId: 42, live: notAsked))
        XCTAssertFalse(MessageReplyHelper.isTargetDeleted(reply(41, t), deleted: [], replyLocalId: 42, live: notAsked))
        // A header from elsewhere may carry any number: the far end of the range is no crash.
        XCTAssertFalse(MessageReplyHelper.isTargetDeleted(reply(41, Int64.max), deleted: [mark(41, t)], replyLocalId: 42,
                                                          live: notAsked))
    }

    // MARK: - What the reply keeps and says

    func testADraftKeptBeforeReadsAsBeforeAndTheFlagIsNeverKept() throws {
        let earlier = #"{"localMessageId":41,"senderPubkeyTail":"AB12AB12","sortTimestampMs":1764244800000,"senderName":"Tester","previewText":"Lunch at one?","legacyQuote":false}"#
        let meta = try JSONDecoder().decode(MessageReplyHelper.ReplyMeta.self, from: Data(earlier.utf8))
        XCTAssertEqual(meta.localMessageId, 41)
        XCTAssertEqual(meta.previewText, "Lunch at one?")
        XCTAssertFalse(meta.targetDeleted)

        var marked = meta
        marked.targetDeleted = true
        let json = String(decoding: try JSONEncoder().encode(marked), as: UTF8.self)
        XCTAssertFalse(json.contains("targetDeleted"))
        XCTAssertEqual(MessageReplyHelper.encode(marked, bodyText: "Sure"), MessageReplyHelper.encode(meta, bodyText: "Sure"))
    }

    func testTheQuoteOfADeletedMessageIsHeardAsItIsShown() {
        var meta = reply(41, t)
        meta.targetDeleted = true
        let spoken = MessageReplyHelper.spokenQuote(meta)
        XCTAssertEqual(spoken, String(localized: "chat_reply_header", "Tester") + ", " + String(localized: "chat_reply_deleted"))
        XCTAssertFalse(spoken?.contains("Lunch at one?") ?? true)
        XCTAssertNotEqual(String(localized: "chat_reply_deleted"), "chat_reply_deleted")
    }

    func testTheMarksObjcToxKeepsAreReadAndOneItCannotReadIsLeftOut() {
        let uniqueIdentifier = "1F0B8E2C-5D3A-4C61-9E0A-6B2D7C4E8F10"
        let marks = MessageReplyHelper.replyTargetMarks(fromLines: ["\(t) \(uniqueIdentifier)", "x y", "0 abc", "\(t)", "", "-4 abc"])
        XCTAssertEqual(marks, [mark(MessageReplyHelper.stableLocalId(uniqueIdentifier), t)])
    }

    // MARK: - objcTox and the chat

    func testARemovedMessageIsRememberedInTheSameWriteAndTheReplyToItSaysSo() throws {
        let (chat, realmManager) = try makeChat()
        let question = try store("Lunch at one?", at: 1_764_244_800.25, in: chat, realmManager)
        let other = try store("Another one", at: 1_764_244_830, in: chat, realmManager)
        let answer = try store("Sure", at: 1_764_244_801.5, in: chat, realmManager)
        let questionMeta = reply(MessageReplyHelper.stableLocalId(question.uniqueIdentifier),
                                   try XCTUnwrap(MessageReplyHelper.replyDateMs(question.dateInterval)))
        let otherMeta = reply(MessageReplyHelper.stableLocalId(other.uniqueIdentifier),
                              try XCTUnwrap(MessageReplyHelper.replyDateMs(other.dateInterval)))
        let questionLine = "\(questionMeta.sortTimestampMs) \(question.uniqueIdentifier!)"
        let chatId: String = chat.uniqueIdentifier
        let targets = ChatDeletedReplyTargets { realmManager.deletedReplyTargets(inChatWithUniqueIdentifier: chatId) }
        let messages = try results(of: chat, realmManager)

        XCTAssertEqual(targets.marked(questionMeta, of: answer, in: messages)?.targetDeleted, false)

        realmManager.removeMessages([question])
        XCTAssertEqual(realmManager.deletedReplyTargets(inChatWithUniqueIdentifier: chatId), [questionLine])
        // Read once until the chat's messages change.
        XCTAssertEqual(targets.marked(questionMeta, of: answer, in: messages)?.targetDeleted, false)
        targets.reset()
        XCTAssertEqual(targets.marked(questionMeta, of: answer, in: messages)?.targetDeleted, true)
        XCTAssertEqual(targets.marked(otherMeta, of: answer, in: messages)?.targetDeleted, false)
        XCTAssertNil(targets.marked(nil, of: answer, in: messages))

        // Another chat has deletions of its own.
        let (elsewhere, _) = try makeChat(realmManager)
        XCTAssertEqual(realmManager.deletedReplyTargets(inChatWithUniqueIdentifier: elsewhere.uniqueIdentifier), [])

        // A removal of the whole conversation takes them with the replies.
        realmManager.removeAllMessages(in: chat, removeChat: false)
        XCTAssertEqual(realmManager.deletedReplyTargets(inChatWithUniqueIdentifier: chatId), [])
    }

    func testAReplyFromAnotherDeviceIsWeighedAgainstTheMessagesStillInTheChat() throws {
        let (chat, realmManager) = try makeChat()
        let question = try store("Lunch at one?", at: 1_764_244_800, in: chat, realmManager)
        let nextQuestion = try store("Or at two?", at: 1_764_244_802, in: chat, realmManager)
        let answer = try store("Sure", at: 1_764_244_812, in: chat, realmManager)
        let deletedMs = try XCTUnwrap(MessageReplyHelper.replyDateMs(question.dateInterval))
        let liveMs = try XCTUnwrap(MessageReplyHelper.replyDateMs(nextQuestion.dateInterval))
        let chatId: String = chat.uniqueIdentifier
        let targets = ChatDeletedReplyTargets { realmManager.deletedReplyTargets(inChatWithUniqueIdentifier: chatId) }
        let messages = try results(of: chat, realmManager)
        realmManager.removeMessages([question])

        // The query of the chat finds the message still there within the window, its edge included, and no further.
        XCTAssertEqual(ChatDeletedReplyTargets.messages(near: liveMs, in: messages).map { $0.dateMs }, [liveMs])
        let window = MessageReplyHelper.replyTargetWindowMs
        XCTAssertEqual(ChatDeletedReplyTargets.messages(near: liveMs - window, in: messages).map { $0.dateMs }, [liveMs])
        XCTAssertEqual(ChatDeletedReplyTargets.messages(near: liveMs - window - 1, in: messages).count, 0)

        // Another device's ids mean nothing here (7); its date of a message is ours give or take.
        XCTAssertEqual(targets.marked(reply(7, deletedMs + 300), of: answer, in: messages)?.targetDeleted, true)
        XCTAssertEqual(targets.marked(reply(7, liveMs - 200), of: answer, in: messages)?.targetDeleted, false)
    }

    func testAChatKeepsItsNewestRemovals() throws {
        let (chat, realmManager) = try makeChat()
        var rows = [OCTMessageAbstract]()
        // One write for them all: a write of its own for each takes seconds.
        let realm: RLMRealm = realmManager.realm
        realm.beginWriteTransaction()
        for i in 0..<503 {
            rows.append(try store("m\(i)", at: 1_764_244_800 + TimeInterval(i * 60), in: chat, realmManager))
        }
        try realm.commitWriteTransaction()
        let lines = rows.map { "\(MessageReplyHelper.replyDateMs($0.dateInterval)!) \($0.uniqueIdentifier!)" }
        realmManager.removeMessages(Array(rows[0..<3]))
        realmManager.removeMessages(Array(rows[3...]))
        XCTAssertEqual(realmManager.deletedReplyTargets(inChatWithUniqueIdentifier: chat.uniqueIdentifier),
                       Array(lines[3...]))
    }

    // MARK: - A profile of its own

    private func makeChat(_ existing: OCTRealmManager? = nil) throws -> (OCTChat, OCTRealmManager) {
        let realmManager: OCTRealmManager
        if let existing = existing {
            realmManager = existing
        }
        else {
            let directory = FileManager.default.temporaryDirectory
                .appendingPathComponent("reply-targets-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
            directories.append(directory)
            let realmManagerClass = try GroupTestBed.appClass("OCTRealmManager", as: OCTRealmManager.Type.self)
            realmManager = try XCTUnwrap(realmManagerClass.init(databaseFileURL: directory.appendingPathComponent("profile.realm"),
                                                                encryptionKey: nil))
            kept.append(realmManager)
        }
        let chat = try GroupTestBed.appClass("OCTChat", as: OCTChat.Type.self).init()
        chat.isGroup = true
        chat.groupNumber = 12
        chat.groupName = "Book club"
        realmManager.add(chat)
        return (chat, realmManager)
    }

    @discardableResult
    private func store(_ text: String,
                       at date: TimeInterval,
                       in chat: OCTChat,
                       _ realmManager: OCTRealmManager) throws -> OCTMessageAbstract {
        let messageText = try GroupTestBed.appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        messageText.text = text
        messageText.isDelivered = true
        let message = try GroupTestBed.appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self).init()
        message.chatUniqueIdentifier = chat.uniqueIdentifier
        message.dateInterval = date
        message.sortTimestamp = date
        message.messageText = messageText
        let realm: RLMRealm = realmManager.realm
        if realm.inWriteTransaction {
            realm.add(message)
        }
        else {
            realmManager.add(message)
        }
        return message
    }

    /// The chat's messages as the chat controllers hold them.
    private func results(of chat: OCTChat, _ realmManager: OCTRealmManager) throws -> Results<OCTMessageAbstract> {
        let messageClass: AnyClass = try XCTUnwrap(NSClassFromString("OCTMessageAbstract"))
        let rows = realmManager.objects(with: messageClass,
                                        predicate: NSPredicate(format: "chatUniqueIdentifier == %@", chat.uniqueIdentifier))
        return Results<OCTMessageAbstract>(results: try XCTUnwrap(rows))
    }
}
