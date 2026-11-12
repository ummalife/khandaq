// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 Sign-in, first launch and moving a profile in (redesign stage 4, E19 and E20): the folder a new profile
 gets, why a profile did not open, what a profile file holds before it is moved in, the rule for a new
 password, and the key of a profile made without a password. The keychain is touched only through items
 these tests make and remove themselves; the stored password of the profile in use is never written.
 */
class AirProfileAccessTests: XCTestCase {
    private let password = "пароль1234"
    private var folders = [URL]()

    override func tearDown() {
        for folder in folders {
            ProfileDeviceKey.remove(profilePath: folder.path)
            try? FileManager.default.removeItem(at: folder)
        }
        folders = []
        super.tearDown()
    }

    // MARK: The folder of a new profile

    func testAFolderNameKeepsThePersonsNameWhenItIsSafe() {
        XCTAssertEqual(ProfileFolderName.make(from: "Самир Валиев", existing: [], fallback: "Profile"), "Самир Валиев")
        XCTAssertEqual(ProfileFolderName.make(from: "سمير", existing: [], fallback: "Profile"), "سمير")
    }

    func testAFolderNameDropsWhatAPathGivesMeaningTo() {
        XCTAssertEqual(ProfileFolderName.clean("a/b:c\\d"), "a b c d")
        XCTAssertEqual(ProfileFolderName.clean("..hidden"), "hidden")
        XCTAssertEqual(ProfileFolderName.clean("  two\n lines\t"), "two lines")
        XCTAssertEqual(ProfileFolderName.clean(String(repeating: "x", count: 200)).count, ProfileFolderName.maxLength)
    }

    func testAFolderNameFallsBackWhenNothingIsLeft() {
        XCTAssertEqual(ProfileFolderName.make(from: "/ : /", existing: [], fallback: "Профиль"), "Профиль")
        XCTAssertEqual(ProfileFolderName.make(from: "...", existing: [], fallback: ""), "Profile")
    }

    func testAFolderNameIsUniqueWhateverTheCase() {
        XCTAssertEqual(ProfileFolderName.make(from: "Samir", existing: ["samir"], fallback: "Profile"), "Samir 2")
        XCTAssertEqual(ProfileFolderName.make(from: "Samir", existing: ["Samir", "SAMIR 2"], fallback: "Profile"), "Samir 3")
    }

    // MARK: Why a profile did not open

    func testAWrongPasswordIsToldApartFromADamagedFile() throws {
        let sealed = try OCTToxEncryptSave.encryptData(makeSave(name: "Samir").data, withPassphrase: password)

        XCTAssertEqual(failure(.passphraseFailed), .wrongPassword)
        // The history key did not open: the save says whether the password was wrong or the key is damaged.
        XCTAssertEqual(failure(.databaseKeyDecryptFailed, saveData: sealed, password: "другой1234"), .wrongPassword)
        if case .damaged = failure(.databaseKeyDecryptFailed, saveData: sealed, password: password) {
        }
        else {
            XCTFail("a history key that does not open with the password that opens the save is damaged")
        }
        XCTAssertEqual(failure(.databaseKeyDecryptFailed, saveData: nil, password: password), .wrongPassword)
    }

    func testASaveThatDoesNotOpenIsAWrongPasswordOnlyWithoutHistory() {
        XCTAssertEqual(failure(.toxFileDecryptFailed, historyExisted: false), .wrongPassword)
        if case .damaged = failure(.toxFileDecryptFailed, historyExisted: true) {
        }
        else {
            XCTFail("a save that does not open with the password that opens the history is damaged")
        }
    }

    func testNoAccessAndDamageCarryTheirCodeForDetails() {
        let noAccess = failure(.databaseKeyCannotReadKey)
        XCTAssertEqual(noAccess, .noAccess(details: "\(kOCTManagerErrorDomain) \(OCTManagerInitError.databaseKeyCannotReadKey.rawValue)"))
        XCTAssertEqual(noAccess.text, String(localized: "air_signin_no_access"))
        XCTAssertNotNil(noAccess.details)

        let damaged = failure(.createToxBadFormat)
        XCTAssertEqual(damaged.text, String(localized: "air_signin_damaged"))
        XCTAssertNil(ProfileOpenFailure.wrongPassword.details)
    }

    func testAnErrorFromElsewhereStillHasWordsAndACode() {
        let error = NSError(domain: "org.example.Other", code: 7, userInfo: nil)
        let result = ProfileOpenFailure.from(error, saveData: nil, password: password, historyExisted: false)
        guard case .other(let title, _, let details) = result else {
            return XCTFail("an unknown error is not a wrong password")
        }
        XCTAssertFalse(title.isEmpty)
        XCTAssertEqual(details, "org.example.Other 7")
    }

    // MARK: A profile file before it is moved in

    func testAPlainSaveShowsItsNameAndId() throws {
        let save = try makeSave(name: "Amina")
        XCTAssertFalse(ProfileFileReader.isEncrypted(save.data))
        XCTAssertEqual(ProfileFileReader.read(save.data, password: nil), .preview(ProfileFilePreview(name: "Amina", address: save.address)))
    }

    func testAnEncryptedSaveOpensOnlyWithItsPassword() throws {
        let save = try makeSave(name: "Amina")
        let sealed = try OCTToxEncryptSave.encryptData(save.data, withPassphrase: password)
        XCTAssertTrue(ProfileFileReader.isEncrypted(sealed))
        XCTAssertEqual(ProfileFileReader.read(sealed, password: password), .preview(ProfileFilePreview(name: "Amina", address: save.address)))
        XCTAssertEqual(ProfileFileReader.read(sealed, password: "другой1234"), .failure(.wrongPassword))
        XCTAssertEqual(ProfileFileReader.read(sealed, password: nil), .failure(.wrongPassword))
    }

    func testAFileThatIsNotAProfileIsDamagedNotAWrongPassword() throws {
        let sealed = try OCTToxEncryptSave.encryptData(makeSave(name: "Amina").data, withPassphrase: password)
        if case .failure(.damaged) = ProfileFileReader.read(Data(sealed.prefix(40)), password: password) {
        }
        else {
            XCTFail("a cut encrypted file is damaged")
        }
        if case .failure(.damaged) = ProfileFileReader.read(Data(repeating: 7, count: 300), password: nil) {
        }
        else {
            XCTFail("bytes that are no save are damaged")
        }
    }

    // MARK: A new password

    func testANewPasswordWaitsWithItsReason() {
        XCTAssertEqual(AirNewPassword.reason("", repeated: ""), String(localized: "air_first_password_empty"))
        XCTAssertEqual(AirNewPassword.reason("abc1", repeated: "abc1"), String(localized: "air_first_password_short"))
        XCTAssertEqual(AirNewPassword.reason("abcdefgh", repeated: "abcdefgh"), String(localized: "air_first_password_weak"))
        XCTAssertEqual(AirNewPassword.reason("12345678", repeated: "12345678"), String(localized: "air_first_password_weak"))
        XCTAssertEqual(AirNewPassword.reason(password, repeated: ""), String(localized: "air_first_password_repeat_first"))
        XCTAssertEqual(AirNewPassword.reason(password, repeated: "пароль1235"), String(localized: "air_first_password_mismatch"))
        XCTAssertNil(AirNewPassword.reason(password, repeated: password))
    }

    // MARK: A profile without a password

    func testTheKeyOfAProfileWithoutAPasswordLivesInTheKeychainAndGoesWithIt() throws {
        let folder = try makeFolder()
        XCTAssertFalse(ProfileDeviceKey.hasKey(profilePath: folder.path))
        XCTAssertNil(ProfileDeviceKey.key(profilePath: folder.path))

        guard let key = ProfileDeviceKey.create(profilePath: folder.path) else {
            return XCTFail("the keychain of the simulator refused the key")
        }
        XCTAssertEqual(key.count, ProfileDeviceKey.keyByteCount * 2)
        XCTAssertTrue(ProfileDeviceKey.hasKey(profilePath: folder.path))
        XCTAssertEqual(ProfileDeviceKey.key(profilePath: folder.path), key)
        // The folder names the key; the key itself is never in it.
        let marker = try Data(contentsOf: ProfileDeviceKey.markerURL(profilePath: folder.path))
        XCTAssertFalse(String(decoding: marker, as: UTF8.self).contains(key))

        guard let identifier = ProfileDeviceKey.identifier(profilePath: folder.path) else {
            return XCTFail("no identifier in the marker")
        }
        ProfileDeviceKey.remove(profilePath: folder.path)
        XCTAssertFalse(ProfileDeviceKey.hasKey(profilePath: folder.path))
        XCTAssertNil(KeychainManager().deviceKey(forIdentifier: identifier))
    }

    func testEveryProfileGetsItsOwnKey() throws {
        let first = try makeFolder()
        let second = try makeFolder()
        guard let one = ProfileDeviceKey.create(profilePath: first.path),
              let two = ProfileDeviceKey.create(profilePath: second.path) else {
            return XCTFail("the keychain of the simulator refused the key")
        }
        XCTAssertNotEqual(one, two)
        XCTAssertNotEqual(ProfileDeviceKey.identifier(profilePath: first.path), ProfileDeviceKey.identifier(profilePath: second.path))
    }

    func testAProfileSealedWithItsKeyOpensWithItAndNotWithAnother() throws {
        let folder = try makeFolder()
        guard let key = ProfileDeviceKey.create(profilePath: folder.path) else {
            return XCTFail("the keychain of the simulator refused the key")
        }
        let save = try makeSave(name: "Samir")
        let sealed = try OCTToxEncryptSave.encryptData(save.data, withPassphrase: key)
        XCTAssertEqual(ProfileFileReader.read(sealed, password: key), .preview(ProfileFilePreview(name: "Samir", address: save.address)))
        XCTAssertEqual(ProfileFileReader.read(sealed, password: password), .failure(.wrongPassword))
    }

    // MARK: A profile without a password whose key is gone (stage 5)

    func testAKeyGoneFromTheKeychainIsMissingNotLocked() throws {
        let folder = try makeFolder()
        XCTAssertNil(ProfileDeviceKey.lookup(profilePath: folder.path), "a profile with a password has no key to look up")

        guard let key = ProfileDeviceKey.create(profilePath: folder.path),
              let identifier = ProfileDeviceKey.identifier(profilePath: folder.path) else {
            return XCTFail("the keychain of the simulator refused the key")
        }
        XCTAssertEqual(ProfileDeviceKey.lookup(profilePath: folder.path), .found(key))

        // What a new iPhone restored from a backup, or a reset keychain, leaves: the folder still names
        // the key, the keychain has none.
        XCTAssertTrue(KeychainManager().setDeviceKey(nil, forIdentifier: identifier))
        XCTAssertTrue(ProfileDeviceKey.hasKey(profilePath: folder.path))
        XCTAssertEqual(ProfileDeviceKey.lookup(profilePath: folder.path), .missing)
        XCTAssertNil(ProfileDeviceKey.key(profilePath: folder.path))
        // Asking again changes nothing: it is not a keychain that opens later.
        XCTAssertEqual(ProfileDeviceKey.lookup(profilePath: folder.path), .missing)

        ProfileDeviceKey.remove(profilePath: folder.path)
        XCTAssertNil(ProfileDeviceKey.lookup(profilePath: folder.path))
    }

    func testAFolderNamingAKeyThisKeychainNeverHadIsMissing() throws {
        let folder = try makeFolder()
        // A folder moved in from another iPhone: its marker names an item made there.
        let identifier = UUID().uuidString
        try Data(identifier.utf8).write(to: ProfileDeviceKey.markerURL(profilePath: folder.path), options: .atomic)
        XCTAssertEqual(KeychainManager().lookUpDeviceKey(forIdentifier: identifier), .missing)
        XCTAssertEqual(ProfileDeviceKey.lookup(profilePath: folder.path), .missing)
    }

    func testAnEmptyItemOpensNothingAndCountsAsMissing() throws {
        let identifier = UUID().uuidString
        defer {
            KeychainManager().setDeviceKey(nil, forIdentifier: identifier)
        }
        XCTAssertTrue(KeychainManager().setDeviceKey("", forIdentifier: identifier))
        XCTAssertEqual(KeychainManager().lookUpDeviceKey(forIdentifier: identifier), .missing)
    }

    func testTheSignInRowSaysWhyAProfileDoesNotOpenHere() {
        let missing = AirSignInProfile(name: "Samir", needsPassword: false, keyMissing: true)
        XCTAssertEqual(missing.detail, String(localized: "air_signin_key_missing_short"))
        XCTAssertEqual(AirSignInProfile(name: "Samir", needsPassword: false).detail, String(localized: "air_signin_no_password_short"))
        XCTAssertNil(AirSignInProfile(name: "Samir", needsPassword: true).detail)
    }

    // MARK: Helpers

    private func failure(_ code: OCTManagerInitError,
                         saveData: Data? = nil,
                         password: String = "пароль1234",
                         historyExisted: Bool = false) -> ProfileOpenFailure {
        let error = NSError(domain: kOCTManagerErrorDomain, code: code.rawValue, userInfo: nil)
        return ProfileOpenFailure.from(error, saveData: saveData, password: password, historyExisted: historyExisted)
    }

    /// A real save: a new Tox identity with a name, made without the network.
    private func makeSave(name: String) throws -> (data: Data, address: String) {
        let options = OCTToxOptions()
        options.udpEnabled = false
        options.ipv6Enabled = false
        options.localDiscoveryEnabled = false
        let tox = try OCTTox(options: options, savedData: nil)
        try tox.setNickname(name)
        return (tox.save(), tox.userAddress)
    }

    private func makeFolder() throws -> URL {
        let folder = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("profile-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true, attributes: nil)
        folders.append(folder)
        return folder
    }
}
