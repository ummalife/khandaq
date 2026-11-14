// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 The caption the phone sends with its photo, in the phone's own chat (QA-Live 14.11). A 1:1 chat lists its
 messages by sortTimestamp, the newest first, and merges a caption into the file just older than it. The
 file message is stored the moment the file is offered; the caption only once its send operation has run,
 and it was sorted by its msgV3 timestamp, which has whole seconds. A photo sent a moment earlier in the
 same second sorted by its own fractional time, after the start of that second, so the caption came out
 older than its photo: above it, and not merged.

 The texts go through objcTox's own chats submanager into a Realm of the test's own, with a stand-in for
 Tox that sends every message at once. The file messages are stored as OCTSubmanagerFilesImpl stores them
 when it offers a file.
 */
final class OwnCaptionOrderTests: XCTestCase {
    private var directory: URL?
    private var realmManager: OCTRealmManager?
    private var messageClass: AnyClass?
    private var dataSource: SendingDataSource?
    private var chats: OCTSubmanagerChats?
    private var chat: OCTChat?

    override func setUp() {
        super.setUp()
        do {
            try makeChat()
        }
        catch {
            XCTFail("no chat to send in: \(error)")
        }
    }

    override func tearDown() {
        chats = nil
        dataSource = nil
        chat = nil
        messageClass = nil
        realmManager = nil
        if let directory = directory {
            try? FileManager.default.removeItem(at: directory)
        }
        super.tearDown()
    }

    func testACaptionSortsRightAfterThePhotoItWasSentWith() throws {
        waitForTheFirstHalfOfASecond()

        try storeFile("stripes.png")
        try sendAndWait("Stripes from the iPhone")

        XCTAssertEqual(try shownOrder(), ["Stripes from the iPhone", "stripes.png"],
                       "the caption is not the message right after its photo")
    }

    /// The preview sends every photo first and the caption with the last item, a video included.
    func testACaptionSortsAfterTheLastOfSeveralItems() throws {
        waitForTheFirstHalfOfASecond()

        try storeFile("north-exit.jpg")
        try storeFile("stripes.png")
        try storeFile("clip.mov")
        try sendAndWait("Three from the trip")

        XCTAssertEqual(try shownOrder(), ["Three from the trip", "clip.mov", "stripes.png", "north-exit.jpg"])
    }

    /// The other way round: a text is stored only after its send operation has run, after a photo
    /// offered the moment after it, and must still sort before that photo.
    func testATextSentBeforeAPhotoStaysBeforeIt() throws {
        waitForTheFirstHalfOfASecond()

        let stored = try send("Look at this")
        try storeFile("stripes.png")
        wait(for: [stored], timeout: 5)

        XCTAssertEqual(try shownOrder(), ["stripes.png", "Look at this"])
    }

    // MARK: - Helpers

    private func makeChat() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("own-caption-order-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        self.directory = directory

        let realmManagerClass = try appClass("OCTRealmManager", as: OCTRealmManager.Type.self)
        let realmManager = try XCTUnwrap(realmManagerClass.init(databaseFileURL: directory.appendingPathComponent("chat.realm"),
                                                                encryptionKey: nil))
        let friend = try appClass("OCTFriend", as: OCTFriend.Type.self).init()
        friend.nickname = "QA Mac"
        friend.publicKey = String(repeating: "A", count: 64)
        friend.capabilities2 = ""
        realmManager.add(friend)
        chat = realmManager.getOrCreateChat(with: friend)
        messageClass = try appClass("OCTMessageAbstract", as: AnyClass.self)
        self.realmManager = realmManager

        let dataSource = SendingDataSource(realmManager: realmManager)
        let chats = try appClass("OCTSubmanagerChatsImpl", as: NSObject.Type.self).init()
        chats.setValue(dataSource, forKey: "dataSource")
        self.dataSource = dataSource
        self.chats = try XCTUnwrap(chats as? OCTSubmanagerChats, "OCTSubmanagerChatsImpl sends no chats")
    }

    /// objcTox and Realm are linked into this test bundle as well as into the app, and the two copies do
    /// not mix: an object of one copy is an invalid value in a Realm query of the other. A class Swift
    /// names here is the bundle's copy, while a class looked up by its name is the one loaded first, the
    /// app's, which the chats submanager uses too; so every objcTox object of the test is made from the
    /// class of its name.
    private func appClass<T>(_ name: String, as type: T.Type) throws -> T {
        guard let cls = NSClassFromString(name) else {
            throw NSError(domain: "OwnCaptionOrderTests", code: 1, userInfo: [NSLocalizedDescriptionKey: "no class \(name)"])
        }
        return unsafeBitCast(cls, to: type)
    }

    /// Everything a test sends then falls in one second, where a whole-second sort key cannot tell the
    /// messages apart: before the fix the caption then sorted before its photo every time.
    private func waitForTheFirstHalfOfASecond() {
        while Date().timeIntervalSince1970.truncatingRemainder(dividingBy: 1) > 0.5 {
            usleep(10_000)
        }
    }

    /// A file this phone offers, as OCTSubmanagerFilesImpl.sendFileAtPath stores it once Tox took it.
    private func storeFile(_ name: String) throws {
        let realmManager = try XCTUnwrap(self.realmManager)
        let chat = try XCTUnwrap(self.chat)
        let path = try XCTUnwrap(directory).appendingPathComponent(name).path
        _ = realmManager.addMessage(withFileNumber: 0, fileType: .waitingConfirmation, fileSize: 6_266, fileName: name,
                                    filePath: path, fileUTI: nil, chat: chat, sender: nil)
    }

    /// Sends `text` as ChatInputViewManager sends a caption; the expectation is met once it is stored.
    private func send(_ text: String) throws -> XCTestExpectation {
        let chats = try XCTUnwrap(self.chats)
        let chat = try XCTUnwrap(self.chat)
        let stored = expectation(description: "\(text) is stored")
        chats.sendMessage(to: chat, text: text, type: .normal, successBlock: { _ in
            stored.fulfill()
        }, failureBlock: { error in
            XCTFail("\(text) was not sent: \(String(describing: error))")
            stored.fulfill()
        })
        return stored
    }

    private func sendAndWait(_ text: String) throws {
        wait(for: [try send(text)], timeout: 5)
    }

    /// The chat as ChatPrivateController lists it: by sortTimestamp, the newest first.
    private func shownOrder() throws -> [String] {
        let realmManager = try XCTUnwrap(self.realmManager)
        let chat = try XCTUnwrap(self.chat)
        let messageClass: AnyClass = try XCTUnwrap(self.messageClass)
        let messages = realmManager.objects(with: messageClass,
                                            predicate: NSPredicate(format: "chatUniqueIdentifier == %@", chat.uniqueIdentifier))
            .sortedResults(usingKeyPath: "sortTimestamp", ascending: false)
        return (0..<messages.count).map { index -> String in
            let message = messages.object(at: index) as? NSObject
            return message?.value(forKeyPath: "messageText.text") as? String
                ?? message?.value(forKeyPath: "messageFile.fileName") as? String
                ?? "?"
        }
    }
}

/// Stands in for OCTTox: every message goes out at once.
private final class ImmediateTox: NSObject {
    @objc(sendMessageWithFriendNumber:type:message:msgv3HashHex:msgv3tssec:error:)
    func sendMessage(friendNumber: OCTToxFriendNumber, type: OCTToxMessageType, message: String, msgv3HashHex: String?,
                     msgv3tssec: UInt32, error: NSErrorPointer) -> OCTToxMessageId {
        return 1
    }
}

/// What OCTSubmanagerChatsImpl asks of OCTManager while it sends a text and stores it.
private final class SendingDataSource: NSObject {
    private let tox = ImmediateTox()
    private let realmManager: OCTRealmManager
    private let notificationCenter = NotificationCenter()

    init(realmManager: OCTRealmManager) {
        self.realmManager = realmManager
        super.init()
    }

    @objc func managerGetTox() -> NSObject {
        return tox
    }

    @objc func managerGetRealmManager() -> OCTRealmManager {
        return realmManager
    }

    @objc func managerGetNotificationCenter() -> NotificationCenter {
        return notificationCenter
    }

    @objc func managerUseFauxOfflineMessaging() -> Bool {
        return false
    }
}
