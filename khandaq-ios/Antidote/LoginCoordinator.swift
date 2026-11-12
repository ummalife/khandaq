// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import UniformTypeIdentifiers

protocol LoginCoordinatorDelegate: class {
    /// `options` start the session: first launch ends in the command line.
    func loginCoordinatorDidLogin(_ coordinator: LoginCoordinator, manager: OCTManager, password: String, options: CoordinatorOptions?)
}

/// Everything before a profile is open (redesign stage 4, E19 and E20): first launch in three steps,
/// sign-in, and moving a profile in from a file (Files, the share sheet, or Settings after logging out).
class LoginCoordinator: NSObject {
    weak var delegate: LoginCoordinatorDelegate?

    fileprivate let window: UIWindow
    fileprivate let theme: Theme
    fileprivate let navigationController: UINavigationController

    /// A profile made on first launch, open while its ID shows on the last step.
    fileprivate var created: (manager: OCTManager, password: String)?
    /// The file being moved in.
    fileprivate var importing: ImportedFile?
    /// The page the import started from: another file starts over from there.
    fileprivate weak var importOrigin: UIViewController?

    /// A profile save is a few kilobytes, a large one a few megabytes: anything bigger is not one.
    fileprivate static let maxImportBytes = 16 * 1024 * 1024

    init(theme: Theme, window: UIWindow) {
        self.window = window
        self.theme = theme

        switch InterfaceIdiom.current() {
            case .iPhone:
                self.navigationController = PortraitNavigationController()
            case .iPad:
                self.navigationController = UINavigationController()
        }

        super.init()

        let appearance = UINavigationBarAppearance()
        appearance.configureWithTransparentBackground()
        appearance.backgroundColor = KQColor.bg
        appearance.shadowColor = .clear
        let bar = navigationController.navigationBar
        bar.standardAppearance = appearance
        bar.scrollEdgeAppearance = appearance
        bar.compactAppearance = appearance
        bar.tintColor = KQColor.ink
        navigationController.delegate = self
    }
}

private struct ImportedFile {
    let url: URL
    let data: Data
    let fileName: String
    let encrypted: Bool
    var password: String?
    var preview: ProfileFilePreview?
}

private enum ProfileOpenResult {
    case opened(OCTManager)
    case failed(ProfileOpenFailure)
}

// MARK: TopCoordinatorProtocol
extension LoginCoordinator: TopCoordinatorProtocol {
    func startWithOptions(_ options: CoordinatorOptions?) {
        navigationController.setViewControllers([makeRootController()], animated: false)
        window.rootViewController = navigationController
    }

    func handleLocalNotification(_ notification: UILocalNotification) {
        // nop
    }

    func handleNotificationUserInfo(_ userInfo: [AnyHashable: Any]) {
        // nop: not logged in
    }

    /// A profile file opened in Khandaq from the share sheet, or chosen in Settings before logging out.
    func handleInboxURL(_ url: URL) {
        guard url.isToxURL() else {
            return
        }
        importOrigin = navigationController.viewControllers.first
        beginImport(url)
    }
}

// MARK: - First launch
private extension LoginCoordinator {
    func makeRootController() -> UIViewController {
        let profiles = signInProfiles()
        guard !profiles.isEmpty else {
            return makeNameController()
        }
        let last = UserDefaultsManager().lastActiveProfile
        let selected = profiles.firstIndex { $0.name == last } ?? 0
        return makeSignInController(profiles: profiles, selected: selected)
    }

    func makeNameController() -> AirFirstNameController {
        let controller = AirFirstNameController()
        controller.onNext = { [weak self] name in
            self?.showPasswordStep(name: name)
        }
        controller.onMoveProfile = { [weak self, weak controller] in
            self?.pickProfileFile(from: controller)
        }
        return controller
    }

    func showPasswordStep(name: String) {
        let controller = AirPasswordChoiceController(step: 2, mainTitle: String(localized: "air_first_create"))
        controller.onChoose = { [weak self, weak controller] choice in
            guard let self = self, let controller = controller else {
                return
            }
            self.createProfile(name: name, choice: choice, page: controller)
        }
        navigationController.pushViewController(controller, animated: true)
    }

    /// Makes the profile, opens it and shows its ID. A profile that did not open is removed again: it
    /// was new and empty, nothing is lost.
    func createProfile(name: String, choice: AirPasswordChoiceController.Choice, page: AirPasswordChoiceController) {
        page.setBusy(String(localized: "air_first_creating"))
        let profiles = ProfileManager()
        let folder = ProfileFolderName.make(from: name, existing: profiles.allProfileNames, fallback: String(localized: "air_first_default_folder"))
        do {
            try profiles.createProfileWithName(folder)
        }
        catch let error as NSError {
            page.showFailure(String(localized: "air_first_create_failed"), details: ErrorAlert.details(of: error))
            return
        }
        let path = profiles.pathForProfileWithName(folder)
        let secret: String
        switch choice {
            case .password(let password):
                secret = password
            case .deviceKey:
                guard let key = ProfileDeviceKey.create(profilePath: path) else {
                    try? ProfileManager().deleteProfileWithName(folder)
                    page.showFailure(String(localized: "air_first_keychain_failed"), details: nil)
                    return
                }
                secret = key
        }

        open(folder, secret: secret) { [weak self, weak page] result in
            guard let self = self else {
                return
            }
            switch result {
                case .opened(let manager):
                    _ = try? manager.user.setUserName(name)
                    // Kept at once: if the app is closed on the last step, the profile opens by itself.
                    UserDefaultsManager().lastActiveProfile = folder
                    KeychainManager().toxPasswordForActiveAccount = secret
                    self.created = (manager, secret)
                    self.showIdStep(address: manager.user.userAddress)
                case .failed(let failure):
                    try? ProfileManager().deleteProfileWithName(folder)
                    page?.showFailure(failure.text, details: failure.details)
            }
        }
    }

    func showIdStep(address: String) {
        let controller = AirIdReadyController(address: address)
        controller.onAddFirstPerson = { [weak self] in
            self?.finishFirstLaunch()
        }
        navigationController.setViewControllers([controller], animated: true)
    }

    func finishFirstLaunch() {
        guard let created = created else {
            return
        }
        self.created = nil
        delegate?.loginCoordinatorDidLogin(self,
                                           manager: created.manager,
                                           password: created.password,
                                           options: [ActiveSessionCoordinator.focusCommandLineKey: true])
    }
}

// MARK: - Sign-in
private extension LoginCoordinator {
    func signInProfiles() -> [AirSignInProfile] {
        let profiles = ProfileManager()
        return profiles.allProfileNames.map { name in
            let path = profiles.pathForProfileWithName(name)
            let encrypted = OCTManagerConfiguration.configurationWithBaseDirectory(path).map { ProfileOpening.isEncrypted($0) } ?? false
            let key = ProfileDeviceKey.lookup(profilePath: path)
            return AirSignInProfile(name: name, needsPassword: encrypted && key == nil, keyMissing: encrypted && key == .missing)
        }
    }

    func makeSignInController(profiles: [AirSignInProfile], selected: Int) -> AirSignInController {
        let controller = AirSignInController(profiles: profiles, selected: selected)
        controller.onSignIn = { [weak self, weak controller] profile, password in
            guard let controller = controller else {
                return
            }
            self?.signIn(profile, password: password, page: controller)
        }
        controller.onCreateProfile = { [weak self] in
            guard let self = self else {
                return
            }
            self.navigationController.pushViewController(self.makeNameController(), animated: true)
        }
        controller.onMoveProfile = { [weak self, weak controller] in
            self?.pickProfileFile(from: controller)
        }
        controller.onDeleteProfile = { [weak self] profile in
            self?.deleteProfile(profile.name)
        }
        return controller
    }

    func signIn(_ profile: AirSignInProfile, password: String?, page: AirSignInController) {
        let path = ProfileManager().pathForProfileWithName(profile.name)
        let secret: String
        if let password = password {
            secret = password
        }
        else if let key = ProfileDeviceKey.lookup(profilePath: path) {
            switch key {
                case .found(let value):
                    secret = value
                case .missing:
                    // Made without a password on another iPhone, or the keychain was reset: no try opens
                    // it here, so the page says so and offers the ways on (stage 5).
                    page.showKeyMissing()
                    return
                case .unavailable:
                    // The keychain cannot be read before the first unlock after a restart.
                    page.showFailure(.noAccess(details: "\(ProfileOpening.errorDomain) 4"))
                    return
            }
        }
        else {
            // A file that is not encrypted yet (an old profile): it gets a password or a key first.
            choosePasswordForUnencrypted(profile.name)
            return
        }

        page.setBusy(true)
        open(profile.name, secret: secret) { [weak self, weak page] result in
            switch result {
                case .opened(let manager):
                    self?.enter(manager, profile: profile.name, password: secret)
                case .failed(.wrongPassword) where password == nil:
                    // A key from this keychain that does not fit the profile is no key for it either.
                    page?.showKeyMissing()
                case .failed(let failure):
                    page?.showFailure(failure)
            }
        }
    }

    func choosePasswordForUnencrypted(_ name: String) {
        let controller = AirPasswordChoiceController(step: nil, mainTitle: String(localized: "air_signin_go"))
        controller.onChoose = { [weak self, weak controller] choice in
            guard let self = self, let controller = controller else {
                return
            }
            let path = ProfileManager().pathForProfileWithName(name)
            let secret: String
            switch choice {
                case .password(let password):
                    secret = password
                case .deviceKey:
                    guard let key = ProfileDeviceKey.create(profilePath: path) else {
                        controller.showFailure(String(localized: "air_first_keychain_failed"), details: nil)
                        return
                    }
                    secret = key
            }
            controller.setBusy(String(localized: "air_signin_opening"))
            self.open(name, secret: secret) { [weak self, weak controller] result in
                switch result {
                    case .opened(let manager):
                        self?.enter(manager, profile: name, password: secret)
                    case .failed(let failure):
                        if case .deviceKey = choice {
                            ProfileDeviceKey.remove(profilePath: path)
                        }
                        controller?.showFailure(failure.text, details: failure.details)
                }
            }
        }
        navigationController.pushViewController(controller, animated: true)
    }

    func deleteProfile(_ name: String) {
        do {
            try ProfileManager().deleteProfileWithName(name)
        }
        catch let error as NSError {
            handleErrorWithType(.deleteProfile, error: error)
            return
        }
        let userDefaults = UserDefaultsManager()
        if userDefaults.lastActiveProfile == name {
            userDefaults.lastActiveProfile = nil
            KeychainManager().deleteActiveAccountData()
        }
        navigationController.setViewControllers([makeRootController()], animated: false)
    }

    /// Hands the open profile to the app: the session starts and the screen opens into Air.
    func enter(_ manager: OCTManager, profile: String, password: String, options: CoordinatorOptions? = nil) {
        UserDefaultsManager().lastActiveProfile = profile
        // KHANDAQ (#15): profiles made without a name published "Khandaq" as their name in groups and
        // showed "(null)" in invitations. Seed it from the profile's name once.
        if (manager.user.userName() ?? "").isEmpty {
            _ = try? manager.user.setUserName(profile)
        }
        delegate?.loginCoordinatorDidLogin(self, manager: manager, password: password, options: options)
    }

    /// Opens a profile with its password or key. What objcTox would get wrong is checked first: a save
    /// it cannot read, and a history key left without a history by an earlier wrong password.
    func open(_ profile: String, secret: String, completion: @escaping (ProfileOpenResult) -> Void) {
        let path = ProfileManager().pathForProfileWithName(profile)
        guard let configuration = OCTManagerConfiguration.configurationWithBaseDirectory(path) else {
            completion(.failed(.noAccess(details: "\(ProfileOpening.errorDomain) 5")))
            return
        }
        if let failure = ProfileOpening.unreadableFile(configuration) {
            completion(.failed(failure))
            return
        }
        ProfileOpening.removeKeyWithoutHistory(configuration)
        let historyExisted = ProfileOpening.historyExists(configuration)
        let saveData = ProfileOpening.saveData(configuration)

        ToxFactory.createToxWithConfiguration(configuration, encryptPassword: secret, successBlock: { manager in
            completion(.opened(manager))
        }, failureBlock: { error in
            log("LoginCoordinator: the profile did not open, \(ErrorAlert.details(of: error as NSError))")
            // Telling a wrong password from a damaged key may run scrypt: not on the main thread.
            DispatchQueue.global(qos: .userInitiated).async {
                let failure = ProfileOpenFailure.from(error as NSError, saveData: saveData, password: secret, historyExisted: historyExisted)
                DispatchQueue.main.async {
                    completion(.failed(failure))
                }
            }
        })
    }
}

// MARK: - Moving a profile in
private extension LoginCoordinator {
    func pickProfileFile(from page: UIViewController?) {
        importOrigin = page ?? navigationController.viewControllers.first
        #if DEBUG
        if let url = AirDemoImportFile.makeIfAsked() {
            beginImport(url)
            return
        }
        #endif
        // A copy: the file in Files stays as it is, the copy is the app's to remove.
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [UTType.data], asCopy: true)
        picker.delegate = self
        navigationController.present(picker, animated: true, completion: nil)
    }

    func beginImport(_ url: URL) {
        let fileName = url.lastPathComponent
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
        guard size <= LoginCoordinator.maxImportBytes else {
            showImportProblem(String(localized: "air_import_not_profile"), details: nil)
            return
        }
        guard let data = try? Data(contentsOf: url) else {
            showImportProblem(String(localized: "air_signin_no_access"), details: "\(ProfileOpening.errorDomain) 6")
            return
        }
        let encrypted = ProfileFileReader.isEncrypted(data)
        importing = ImportedFile(url: url, data: data, fileName: fileName, encrypted: encrypted, password: nil, preview: nil)

        guard encrypted else {
            switch ProfileFileReader.read(data, password: nil) {
                case .preview(let preview):
                    importing?.preview = preview
                    showImportPreview(preview, first: true)
                case .failure:
                    showImportProblem(String(localized: "air_import_not_profile"), details: nil)
            }
            return
        }
        let page = AirImportPasswordController(fileName: fileName)
        page.onRead = { [weak self, weak page] password in
            guard let page = page else {
                return
            }
            self?.readImport(password: password, page: page)
        }
        showFirstImportPage(page)
    }

    /// The first page of an import stands right after the page it started from.
    func showFirstImportPage(_ page: UIViewController) {
        var stack = navigationController.viewControllers
        if let origin = importOrigin, let index = stack.firstIndex(of: origin) {
            stack = Array(stack.prefix(through: index))
        }
        navigationController.setViewControllers(stack + [page], animated: true)
    }

    func readImport(password: String, page: AirImportPasswordController) {
        guard let file = importing else {
            return
        }
        page.setBusy(true)
        DispatchQueue.global(qos: .userInitiated).async { [weak self, weak page] in
            let outcome = ProfileFileReader.read(file.data, password: password)
            DispatchQueue.main.async {
                switch outcome {
                    case .preview(let preview):
                        page?.setBusy(false)
                        self?.importing?.password = password
                        self?.importing?.preview = preview
                        self?.showImportPreview(preview, first: false)
                    case .failure(let failure):
                        page?.showFailure(failure)
                }
            }
        }
    }

    func showImportPreview(_ preview: ProfileFilePreview, first: Bool) {
        let choiceFollows = importing?.encrypted == false
        let page = AirImportPreviewController(preview: preview,
                                              mainTitle: String(localized: choiceFollows ? "air_first_next" : "air_import_confirm"))
        page.onConfirm = { [weak self, weak page] in
            guard let page = page else {
                return
            }
            self?.confirmImport(page)
        }
        page.onOtherFile = { [weak self] in
            self?.pickProfileFile(from: self?.importOrigin)
        }
        if first {
            showFirstImportPage(page)
        }
        else {
            navigationController.pushViewController(page, animated: true)
        }
    }

    func showImportProblem(_ problem: String, details: String?) {
        importing = nil
        let page = AirImportProblemController(problem: problem, details: details)
        page.onOtherFile = { [weak self] in
            self?.pickProfileFile(from: self?.importOrigin)
        }
        showFirstImportPage(page)
    }

    func confirmImport(_ page: AirImportPreviewController) {
        guard let file = importing else {
            return
        }
        if file.encrypted, let password = file.password {
            moveIn(file, choice: .password(password), busy: { [weak page] busy in
                page?.setBusy(busy)
            }, fail: { [weak page] failure in
                page?.showFailure(failure)
            })
            return
        }
        // A file without a password gets one, or the key of this iPhone, before it is moved in.
        let choice = AirPasswordChoiceController(step: nil, mainTitle: String(localized: "air_import_confirm"))
        choice.onChoose = { [weak self, weak choice] answer in
            self?.moveIn(file, choice: answer, busy: { busy in
                choice?.setBusy(busy ? String(localized: "air_import_moving") : nil)
            }, fail: { failure in
                choice?.showFailure(failure.text, details: failure.details)
            })
        }
        navigationController.pushViewController(choice, animated: true)
    }

    /// Makes the profile's folder (with its key first, when it has no password), writes the save into
    /// it and opens it. If it does not open, the folder goes again and the file stays where it was, so
    /// trying again is the same as the first time.
    func moveIn(_ file: ImportedFile,
                choice: AirPasswordChoiceController.Choice,
                busy: @escaping (Bool) -> Void,
                fail: @escaping (ProfileOpenFailure) -> Void) {
        guard let preview = file.preview else {
            return
        }
        busy(true)
        let profiles = ProfileManager()
        let fallback = (file.fileName as NSString).deletingPathExtension
        let folder = ProfileFolderName.make(from: preview.name, existing: profiles.allProfileNames, fallback: fallback)
        let failed: (ProfileOpenFailure) -> Void = { failure in
            try? ProfileManager().deleteProfileWithName(folder)
            fail(failure)
        }
        do {
            try profiles.createProfileWithName(folder)
        }
        catch let error as NSError {
            fail(.other(title: String(localized: "air_import_failed_title"), message: nil, details: ErrorAlert.details(of: error)))
            return
        }
        let path = profiles.pathForProfileWithName(folder)
        let secret: String
        switch choice {
            case .password(let password):
                secret = password
            case .deviceKey:
                guard let key = ProfileDeviceKey.create(profilePath: path) else {
                    failed(.other(title: String(localized: "air_first_keychain_failed"), message: nil, details: "\(ProfileOpening.errorDomain) 7"))
                    return
                }
                secret = key
        }
        guard let configuration = OCTManagerConfiguration.configurationWithBaseDirectory(path) else {
            failed(.noAccess(details: "\(ProfileOpening.errorDomain) 5"))
            return
        }
        do {
            try file.data.write(to: URL(fileURLWithPath: configuration.fileStorage.pathForToxSaveFile), options: .atomic)
        }
        catch let error as NSError {
            failed(.noAccess(details: ErrorAlert.details(of: error)))
            return
        }

        open(folder, secret: secret) { [weak self] result in
            switch result {
                case .opened(let manager):
                    self?.removeHandedCopy(file.url)
                    self?.importing = nil
                    self?.enter(manager, profile: folder, password: secret)
                case .failed(let failure):
                    failed(failure)
            }
        }
    }
}

private extension LoginCoordinator {
    /// The copy the app was handed (the share sheet's Inbox, the picker's copy, the walk's file) goes
    /// once its profile is in. Nothing outside the app's own container is ever removed.
    func removeHandedCopy(_ url: URL) {
        let home = URL(fileURLWithPath: NSHomeDirectory()).resolvingSymlinksInPath().path + "/"
        guard url.isFileURL, url.resolvingSymlinksInPath().path.hasPrefix(home) else {
            return
        }
        try? FileManager.default.removeItem(at: url)
    }
}

// MARK: - UIDocumentPickerDelegate
extension LoginCoordinator: UIDocumentPickerDelegate {
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else {
            return
        }
        beginImport(url)
    }
}

// MARK: - UINavigationControllerDelegate
extension LoginCoordinator: UINavigationControllerDelegate {
    /// The pages slide in the direction of travel as every push does; with reduced motion they fade.
    func navigationController(_ navigationController: UINavigationController,
                              animationControllerFor operation: UINavigationControllerOperation,
                              from fromVC: UIViewController,
                              to toVC: UIViewController) -> UIViewControllerAnimatedTransitioning? {
        return UIAccessibilityIsReduceMotionEnabled() ? AirFadeTransition() : nil
    }
}

#if DEBUG
/// The UI tests cannot drive the Files picker. With UI_TESTING and DEMO_IMPORT_FILE the picker hands
/// over a real profile file made on the spot instead: a new Tox identity with a made-up name, sealed
/// with the test password, so the real reading of a file is what the walk sees.
enum AirDemoImportFile {
    static let password = "пароль1234"
    static let name = "Амина Юсупова"

    static func makeIfAsked() -> URL? {
        let arguments = ProcessInfo.processInfo.arguments
        guard arguments.contains("UI_TESTING"), arguments.contains("DEMO_IMPORT_FILE") else {
            return nil
        }
        let options = OCTToxOptions()
        options.udpEnabled = false
        options.ipv6Enabled = false
        options.localDiscoveryEnabled = false
        guard let tox = try? OCTTox(options: options, savedData: nil) else {
            return nil
        }
        try? tox.setNickname(name)
        guard let sealed = try? OCTToxEncryptSave.encryptData(tox.save(), withPassphrase: password) else {
            return nil
        }
        let url = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("amina.tox")
        do {
            try sealed.write(to: url, options: .atomic)
        }
        catch {
            return nil
        }
        return url
    }
}
#endif
