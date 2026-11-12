// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

protocol ProfileMainControllerDelegate: class {
    func profileMainControllerLogout(_ controller: ProfileMainController)
    func profileMainControllerDeleteProfile(_ controller: ProfileMainController)
    func profileMainControllerShowProfileDetails(_ controller: ProfileMainController)
    func profileMainControllerDidChangeAvatar(_ controller: ProfileMainController)
    func profileMainControllerDidChangeUserName(_ controller: ProfileMainController)
}

/// "You" (redesign stage 3, E17), opened from the "you" block of the Air screen: first the QR code of
/// your ID with Share ID and Copy ID, then your photo, name and status, edited where they stand, then
/// Details (the ID in its three parts, Change antispam with its consequence, Password and PIN), and at
/// the bottom Log out and Delete the profile from this device, each asked once with what it does.
class ProfileMainController: AirScrollController {
    weak var delegate: ProfileMainControllerDelegate?

    fileprivate weak var submanagerUser: OCTSubmanagerUser!

    fileprivate let qrView = AirQRView()
    fileprivate let shareButton = AirButton(style: .ink, title: String(localized: "air_you_share"))
    fileprivate let copyButton = AirButton(style: .normal, title: String(localized: "air_you_copy"), icon: "copy")
    fileprivate let photoButton = AirYouPhotoButton()
    fileprivate let nameField = AirYouField(placeholder: String(localized: "air_you_name_placeholder"),
                                            maxBytes: Int(kOCTToxMaxNameLength))
    fileprivate let statusField = AirYouField(placeholder: String(localized: "air_you_status_placeholder"),
                                              maxBytes: Int(kOCTToxMaxStatusMessageLength))
    fileprivate let idParts = AirIdPartsView()
    /// What Log out means: it changes when a profile without a password gets one (stage 4).
    fileprivate let logoutNote = AirKit.note("")

    init(theme: Theme, submanagerUser: OCTSubmanagerUser) {
        self.submanagerUser = submanagerUser
        super.init(nibName: nil, bundle: nil)
        title = String(localized: "air_you")
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// The QR card at the head of the screen, which the tile of the "you" block grows into.
    var qrPlate: UIView {
        return qrView
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.you.screen"
        buildSections()
        refresh()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        refresh()
    }
}

// MARK: - Sections

private extension ProfileMainController {
    func buildSections() {
        // 1. The QR code of your ID, and sharing it.
        shareButton.accessibilityIdentifier = "air.you.share"
        shareButton.addTarget(self, action: #selector(shareTapped), for: .touchUpInside)
        copyButton.accessibilityIdentifier = "air.you.copy"
        copyButton.addTarget(self, action: #selector(copyTapped), for: .touchUpInside)
        let qrHolder = UIView()
        qrHolder.addSubview(qrView)
        qrView.snp.makeConstraints {
            $0.top.bottom.centerX.equalToSuperview()
            $0.width.equalToSuperview().priority(.high)
            $0.width.lessThanOrEqualToSuperview()
            $0.width.lessThanOrEqualTo(AirQRView.maxSide)
        }
        addSection(nil, views: [qrHolder, AirKit.buttonRow([shareButton, copyButton], traits: traitCollection)])

        // 2. Photo, name and status, edited where they stand.
        photoButton.addTarget(self, action: #selector(photoTapped), for: .touchUpInside)
        nameField.field.accessibilityIdentifier = "air.you.name"
        nameField.field.accessibilityLabel = String(localized: "air_you_name")
        nameField.field.returnKeyType = .done
        nameField.onCommit = { [weak self] text in
            self?.commitName(text)
        }
        statusField.field.accessibilityIdentifier = "air.you.status"
        statusField.field.accessibilityLabel = String(localized: "air_you_status")
        statusField.field.returnKeyType = .done
        statusField.onCommit = { [weak self] text in
            self?.commitStatus(text)
        }
        let nameColumn = AirKit.column([AirKit.heading(String(localized: "air_you_name")), nameField], spacing: KQSpace.x8)
        let nameRow = UIStackView(arrangedSubviews: [photoButton, nameColumn])
        nameRow.axis = .horizontal
        nameRow.alignment = .bottom
        nameRow.spacing = KQSpace.x12
        let statusColumn = AirKit.column([AirKit.heading(String(localized: "air_you_status")), statusField], spacing: KQSpace.x8)
        addSection(nil, views: [nameRow, statusColumn])

        // 3. Details: the ID in its parts, the antispam and the protection of the profile.
        let antispam = AirSettingRow(title: String(localized: "air_you_antispam"), opens: false)
        antispam.accessibilityIdentifier = "air.you.antispam"
        antispam.addTarget(self, action: #selector(antispamTapped), for: .touchUpInside)
        let security = AirSettingRow(title: String(localized: "air_you_security"))
        security.accessibilityIdentifier = "air.you.security"
        security.addTarget(self, action: #selector(securityTapped), for: .touchUpInside)
        let section = addSection(String(localized: "air_you_details"), views: [idParts], rows: [antispam, security])
        section.accessibilityIdentifier = "air.you.details"

        // 4. Log out, with what it means said before it is pressed.
        let logout = AirButton(style: .normal, title: String(localized: "air_you_logout"))
        logout.accessibilityIdentifier = "air.you.logout"
        logout.addTarget(self, action: #selector(logoutTapped), for: .touchUpInside)
        let logoutSection = addSection(nil, views: [logout, logoutNote])

        // 5. Deleting the profile, set apart at the very bottom.
        let delete = AirButton(style: .danger, title: String(localized: "air_you_delete"))
        delete.accessibilityIdentifier = "air.you.delete"
        delete.addTarget(self, action: #selector(deleteTapped), for: .touchUpInside)
        column.setCustomSpacing(KQSpace.x48, after: logoutSection)
        addSection(nil, views: [delete])
    }

    func refresh() {
        guard isViewLoaded, let user = submanagerUser else {
            return
        }
        logoutNote.text = logoutMessage
        let address = user.userAddress
        qrView.text = address
        idParts.configure(address: address)

        let name = user.userName() ?? ""
        if !nameField.isEditing {
            nameField.text = name
        }
        if !statusField.isEditing {
            statusField.text = user.userStatusMessage() ?? ""
        }
        let photo = user.userAvatar().flatMap { UIImage(data: $0) }
        photoButton.configure(initials: AirInitials.from(name.isEmpty ? String(localized: "air_you") : name), photo: photo)
    }
}

// MARK: - Actions

private extension ProfileMainController {
    @objc func shareTapped() {
        guard let address = submanagerUser?.userAddress else {
            return
        }
        let share = UIActivityViewController(activityItems: [address], applicationActivities: nil)
        share.popoverPresentationController?.sourceView = shareButton
        share.popoverPresentationController?.sourceRect = shareButton.bounds
        present(share, animated: true, completion: nil)
    }

    @objc func copyTapped() {
        guard let address = submanagerUser?.userAddress else {
            return
        }
        UIPasteboard.general.string = address
        AirToast.show(String(localized: "air_you_copied"), in: view)
    }

    func commitName(_ text: String) {
        let name = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let current = submanagerUser.userName() ?? ""
        // A name cannot be emptied: the field takes the name back.
        guard !name.isEmpty, name != current else {
            nameField.text = current
            return
        }
        do {
            try submanagerUser.setUserName(name)
            nameField.text = name
            refresh()
            delegate?.profileMainControllerDidChangeUserName(self)
            UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, String(localized: "air_you_saved"))
        }
        catch let error as NSError {
            nameField.text = current
            handleErrorWithType(.toxSetInfoCodeName, error: error)
        }
    }

    func commitStatus(_ text: String) {
        let status = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let current = submanagerUser.userStatusMessage() ?? ""
        guard status != current else {
            statusField.text = current
            return
        }
        do {
            try submanagerUser.setUserStatusMessage(status)
            statusField.text = status
            UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, String(localized: "air_you_saved"))
        }
        catch let error as NSError {
            statusField.text = current
            handleErrorWithType(.toxSetInfoCodeStatusMessage, error: error)
        }
    }

    // E21: the questions of You come from below, say what happens in one sentence and put the verb
    // on the button.
    @objc func antispamTapped() {
        let sheet = AirConfirm.sheet(title: String(localized: "air_you_antispam_title"),
                                     message: String(localized: "air_you_antispam_message"),
                                     verb: String(localized: "air_you_antispam_action"),
                                     source: view) { [weak self] in
            guard let self = self, let user = self.submanagerUser else {
                return
            }
            user.nospam = AirYouId.newAntispam(replacing: user.nospam)
            self.refresh()
            AirToast.show(String(localized: "air_you_antispam_done"), in: self.view)
        }
        present(sheet, animated: true, completion: nil)
    }

    @objc func securityTapped() {
        delegate?.profileMainControllerShowProfileDetails(self)
    }

    /// What logging out means for this profile: one made without a password opens again without one
    /// (its key stays in this iPhone's keychain, stage 4).
    private var logoutMessage: String {
        return String(localized: ProfileDeviceKey.activeProfileHasKey ? "air_you_logout_message_device" : "air_you_logout_message")
    }

    @objc func logoutTapped() {
        let sheet = AirConfirm.sheet(title: String(localized: "air_you_logout_title"),
                                     message: logoutMessage,
                                     verb: String(localized: "air_you_logout"),
                                     style: .default,
                                     source: view) { [weak self] in
            guard let self = self else {
                return
            }
            self.delegate?.profileMainControllerLogout(self)
        }
        present(sheet, animated: true, completion: nil)
    }

    @objc func deleteTapped() {
        let name = UserDefaultsManager().lastActiveProfile ?? ""
        let sheet = AirConfirm.sheet(title: String(localized: "air_you_delete_title"),
                                     message: String(localized: "air_you_delete_message_format", name.isEmpty ? String(localized: "air_you") : name),
                                     verb: String(localized: "air_you_delete_action"),
                                     source: view) { [weak self] in
            guard let self = self else {
                return
            }
            self.delegate?.profileMainControllerDeleteProfile(self)
        }
        present(sheet, animated: true, completion: nil)
    }
}

// MARK: - Photo

extension ProfileMainController: UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [String : Any]) {
        dismiss(animated: true, completion: nil)

        // KHANDAQ (#8): the picker runs with allowsEditing, so prefer the user's crop/zoom result.
        guard let image = (info[UIImagePickerControllerEditedImage] ?? info[UIImagePickerControllerOriginalImage]) as? UIImage else {
            return
        }

        applyAvatarImage(image)
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        dismiss(animated: true, completion: nil)
    }
}

private extension ProfileMainController {
    struct PNGFromDataError: Error {}

    @objc func photoTapped() {
        let alert = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        alert.popoverPresentationController?.sourceView = photoButton
        alert.popoverPresentationController?.sourceRect = photoButton.bounds

        if UIImagePickerController.isSourceTypeAvailable(.camera) {
            alert.addAction(UIAlertAction(title: String(localized: "photo_from_camera"), style: .default) { [unowned self] _ -> Void in
                MediaPermission.requestCameraAccess(from: self) { granted in
                    guard granted else {
                        return
                    }

                    let controller = UIImagePickerController()
                    controller.sourceType = .camera
                    controller.delegate = self
                    // KHANDAQ (#8): let the user crop/zoom the shot before it becomes the avatar.
                    controller.allowsEditing = true
                    if UIImagePickerController.isCameraDeviceAvailable(.front) {
                        controller.cameraDevice = .front
                    }
                    self.present(controller, animated: true, completion: nil)
                }
            })
        }

        alert.addAction(UIAlertAction(title: String(localized: "photo_from_photo_library"), style: .default) { [unowned self] _ -> Void in
            // KHANDAQ (#8): PHPicker has no crop step, so the avatar was auto-center-cropped with no
            // way to zoom/reposition. UIImagePickerController with allowsEditing gives the native
            // move-and-scale square crop (and needs no photo permission since iOS 11).
            if UIImagePickerController.isSourceTypeAvailable(.photoLibrary) {
                let controller = UIImagePickerController()
                controller.sourceType = .photoLibrary
                controller.delegate = self
                controller.allowsEditing = true
                self.present(controller, animated: true, completion: nil)
            }
        })

        if submanagerUser.userAvatar() != nil {
            alert.addAction(UIAlertAction(title: String(localized: "alert_delete"), style: .destructive) { [unowned self] _ -> Void in
                self.removeAvatar()
            })
        }

        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))

        present(alert, animated: true, completion: nil)
    }

    func removeAvatar() {
        do {
            try submanagerUser.setUserAvatar(nil)
            refresh()
            delegate?.profileMainControllerDidChangeAvatar(self)
        }
        catch let error as NSError {
            handleErrorWithType(.changeAvatar, error: error)
        }
    }

    func applyAvatarImage(_ image: UIImage) {
        var croppedImage = image
        if croppedImage.size.width != croppedImage.size.height {
            let side = min(croppedImage.size.width, croppedImage.size.height)
            let x = (croppedImage.size.width - side) / 2
            let y = (croppedImage.size.height - side) / 2
            let rect = CGRect(x: x, y: y, width: side, height: side)
            croppedImage = croppedImage.cropWithRect(rect)
        }

        let data: Data
        do {
            data = try pngDataFromImage(croppedImage)
        }
        catch {
            handleErrorWithType(.convertImageToPNG, error: nil)
            return
        }

        do {
            try submanagerUser.setUserAvatar(data)
            refresh()
            delegate?.profileMainControllerDidChangeAvatar(self)
        }
        catch let error as NSError {
            handleErrorWithType(.changeAvatar, error: error)
        }
    }

    func pngDataFromImage(_ image: UIImage) throws -> Data {
        var imageSize = image.size

        // Maximum png size will be (4 * width * height)
        // * 1.5 to get as big avatar size as possible
        while OCTToxFileSize(4 * imageSize.width * imageSize.height) > OCTToxFileSize(1.5 * Double(kOCTManagerMaxAvatarSize)) {
            imageSize.width *= 0.9
            imageSize.height *= 0.9
        }

        imageSize.width = ceil(imageSize.width)
        imageSize.height = ceil(imageSize.height)

        var data: Data
        var tempImage = image

        repeat {
            // UIGraphicsBeginImageContext throws on apps linked against the iOS 17+ SDK
            // (the avatar could not be set at all on iOS 26).
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            format.opaque = false
            let renderer = UIGraphicsImageRenderer(size: imageSize, format: format)
            let drawSize = imageSize
            tempImage = renderer.image { _ in
                tempImage.draw(in: CGRect(origin: CGPoint.zero, size: drawSize))
            }

            guard let theData = UIImagePNGRepresentation(tempImage) else {
                throw PNGFromDataError()
            }
            data = theData

            imageSize.width *= 0.9
            imageSize.height *= 0.9
        } while (OCTToxFileSize(data.count) > kOCTManagerMaxAvatarSize)

        return data
    }
}

// MARK: - Opening from the "you" block

/// The "you" block opening "You" (E17): your tile flies to the head of "You" and grows as it fades,
/// while the QR card comes out of the tile's place and grows to its own, so the ink tile turns into the
/// white card; the rest of the screen comes in under them. Only transform and opacity move. With Reduce
/// Motion nothing travels: the screen fades in, at the capped duration. Going back is the usual pop, so
/// the edge swipe keeps working.
final class AirYouTransition: NSObject, UINavigationControllerDelegate, UIViewControllerAnimatedTransitioning {
    func navigationController(_ navigationController: UINavigationController,
                              animationControllerFor operation: UINavigationControllerOperation,
                              from fromVC: UIViewController,
                              to toVC: UIViewController) -> UIViewControllerAnimatedTransitioning? {
        guard operation == .push, fromVC is ChatListController, toVC is ProfileMainController else {
            return nil
        }
        return self
    }

    func transitionDuration(using transitionContext: UIViewControllerContextTransitioning?) -> TimeInterval {
        return KQMotion.duration(KQMotion.reveal)
    }

    func animateTransition(using context: UIViewControllerContextTransitioning) {
        guard let from = context.viewController(forKey: .from) as? ChatListController,
              let to = context.viewController(forKey: .to) as? ProfileMainController else {
            context.completeTransition(!context.transitionWasCancelled)
            return
        }
        let container = context.containerView
        to.view.frame = context.finalFrame(for: to)
        container.addSubview(to.view)
        to.view.layoutIfNeeded()
        guard context.isAnimated else {
            context.completeTransition(!context.transitionWasCancelled)
            return
        }

        let source = from.youTile
        let target = to.qrPlate
        let start = source.convert(source.bounds, to: container)
        let end = target.convert(target.bounds, to: container)
        let duration = transitionDuration(using: context)
        to.view.alpha = 0

        let travels = !UIAccessibilityIsReduceMotionEnabled() && source.window != nil
            && container.bounds.intersects(start) && start.width > 0 && end.width > 0
        guard travels, let snapshot = source.snapshotView(afterScreenUpdates: false) else {
            UIView.animate(withDuration: duration, animations: {
                to.view.alpha = 1
            }, completion: { _ in
                context.completeTransition(!context.transitionWasCancelled)
            })
            return
        }

        snapshot.frame = start
        container.addSubview(snapshot)
        source.alpha = 0
        target.transform = AirYouTransition.transform(laying: end, over: start)
        let overTheCard = AirYouTransition.transform(laying: start, over: end)

        let options = UIViewKeyframeAnimationOptions(rawValue: UIViewAnimationOptions.curveEaseInOut.rawValue)
        UIView.animateKeyframes(withDuration: duration, delay: 0, options: options, animations: {
            UIView.addKeyframe(withRelativeStartTime: 0, relativeDuration: 1) {
                to.view.alpha = 1
                target.transform = .identity
                snapshot.transform = overTheCard
            }
            // The tile is gone before its enlarged pixels could show.
            UIView.addKeyframe(withRelativeStartTime: 0, relativeDuration: 0.3) {
                snapshot.alpha = 0
            }
        }, completion: { _ in
            snapshot.removeFromSuperview()
            source.alpha = 1
            target.transform = .identity
            to.view.alpha = 1
            context.completeTransition(!context.transitionWasCancelled)
        })
    }

    /// Lays a view that stands at `frame` over `other`: centre onto centre, scaled by the widths.
    static func transform(laying frame: CGRect, over other: CGRect) -> CGAffineTransform {
        let scale = other.width / frame.width
        return CGAffineTransform(translationX: other.midX - frame.midX, y: other.midY - frame.midY)
            .scaledBy(x: scale, y: scale)
    }
}

// MARK: - The ID

/// Your ID as "You" shows it: its three parts, and a new antispam.
enum AirYouId {
    struct Parts: Equatable {
        let key: String
        let antispam: String
        let check: String
    }

    /// The key, the antispam and the check of a whole ID; nil for anything else.
    static func parts(of address: String) -> Parts? {
        let hex = AirToxId.clean(address)
        guard hex.count == AirToxId.length else {
            return nil
        }
        let key = String(hex.prefix(AirToxId.keyLength))
        let antispam = String(hex.dropFirst(AirToxId.keyLength).prefix(AirToxId.antispamLength))
        let check = String(hex.suffix(AirToxId.checkLength))
        return Parts(key: key, antispam: antispam, check: check)
    }

    /// Any other 32-bit number than the antispam in use: the old ID stops taking requests.
    static func newAntispam(replacing current: OCTToxNoSpam) -> OCTToxNoSpam {
        var value = current
        while value == current {
            value = OCTToxNoSpam.random(in: OCTToxNoSpam.min...OCTToxNoSpam.max)
        }
        return value
    }
}

/// The QR code of your ID, dark modules on white in both themes: that is what cameras read best.
final class AirQRView: UIView {
    /// The whole width of any phone; on a tablet the card stops growing here.
    static let maxSide: CGFloat = 400

    var text = "" {
        didSet {
            if text != oldValue {
                render()
            }
        }
    }

    private let imageView = UIImageView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        // Set off by its ground, as every card is: no frame around it.
        backgroundColor = KQColor.fixedWhite
        layer.cornerRadius = KQRadius.card
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitImage
        accessibilityLabel = String(localized: "air_you_qr")
        accessibilityHint = String(localized: "air_you_qr_hint")
        accessibilityIdentifier = "air.you.qr"

        imageView.contentMode = .scaleAspectFit
        imageView.layer.magnificationFilter = kCAFilterNearest
        // The card sets the size; the image of the code scales into it rather than pushing it wider.
        for axis in [UILayoutConstraintAxis.horizontal, .vertical] {
            imageView.setContentCompressionResistancePriority(.defaultLow, for: axis)
            imageView.setContentHuggingPriority(.defaultLow, for: axis)
        }
        addSubview(imageView)
        imageView.snp.makeConstraints {
            $0.edges.equalToSuperview().inset(KQSpace.x16)
        }
        snp.makeConstraints {
            $0.height.equalTo(snp.width)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func render() {
        guard !text.isEmpty, let filter = CIFilter(name: "CIQRCodeGenerator") else {
            imageView.image = nil
            return
        }
        filter.setValue(text.data(using: .utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else {
            imageView.image = nil
            return
        }
        // Whole pixels per module; the image view scales it up without smoothing.
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        guard let image = CIContext().createCGImage(scaled, from: scaled.extent) else {
            imageView.image = nil
            return
        }
        imageView.image = UIImage(cgImage: image)
    }
}

/// The three parts of your ID, each named, in groups of four.
final class AirIdPartsView: UIView {
    private let keyValue = AirKit.label(.mono, color: KQColor.ink, lines: 0)
    private let antispamValue = AirKit.label(.mono, color: KQColor.ink, lines: 0)
    private let checkValue = AirKit.label(.mono, color: KQColor.ink, lines: 0)

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = KQColor.surface
        layer.cornerRadius = KQRadius.card
        accessibilityIdentifier = "air.you.id"

        let rows = [
            part(String(localized: "air_you_part_key"), keyValue),
            part(String(localized: "air_you_part_antispam"), antispamValue),
            part(String(localized: "air_you_part_check"), checkValue),
        ]
        let stack = AirKit.column(rows, spacing: KQSpace.x12)
        // An ID reads left to right in every language.
        stack.semanticContentAttribute = .forceLeftToRight
        addSubview(stack)
        stack.snp.makeConstraints {
            $0.edges.equalToSuperview().inset(KQSpace.x16)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(address: String) {
        guard let parts = AirYouId.parts(of: address) else {
            keyValue.text = address
            antispamValue.text = nil
            checkValue.text = nil
            return
        }
        set(keyValue, AirKeyFormat.lines(parts.key))
        set(antispamValue, AirKeyFormat.lines(parts.antispam))
        set(checkValue, AirKeyFormat.lines(parts.check))
    }

    private func part(_ name: String, _ value: UILabel) -> UIView {
        let title = AirKit.label(.caption, color: KQColor.ink3)
        title.text = name
        value.isAccessibilityElement = true
        value.accessibilityLabel = name
        value.textAlignment = .left
        return AirKit.column([title, value], spacing: KQSpace.x4)
    }

    private func set(_ label: UILabel, _ text: String) {
        label.text = text
        label.accessibilityValue = text.replacingOccurrences(of: "\n", with: " ")
    }
}

/// Your photo, or your initials on the ink tile; a tap changes it.
final class AirYouPhotoButton: UIControl {
    static let side: CGFloat = KQSpace.x64

    private let tile = AirTileView(side: AirYouPhotoButton.side)

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitButton
        accessibilityLabel = String(localized: "air_you_photo")
        accessibilityHint = String(localized: "air_you_photo_hint")
        accessibilityIdentifier = "air.you.photo"
        tile.isUserInteractionEnabled = false
        addSubview(tile)
        tile.snp.makeConstraints {
            $0.edges.equalToSuperview()
            $0.size.equalTo(AirYouPhotoButton.side)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var isHighlighted: Bool {
        didSet {
            alpha = isHighlighted ? 0.6 : 1
        }
    }

    func configure(initials: String, photo: UIImage?) {
        tile.configure(style: .me(initials: initials), photo: photo)
    }
}

/// A framed one-line field that keeps what Tox carries (`maxBytes` in UTF-8) and hands its text over
/// when the editing ends: Done, or a tap elsewhere.
final class AirYouField: UIView, UITextFieldDelegate {
    let field = UITextField()
    var onCommit: ((String) -> Void)?

    private let maxBytes: Int

    var text: String {
        get {
            return field.text ?? ""
        }
        set {
            field.text = newValue
        }
    }

    var isEditing: Bool {
        return field.isEditing
    }

    init(placeholder: String, maxBytes: Int) {
        self.maxBytes = maxBytes
        super.init(frame: .zero)
        layer.cornerRadius = KQRadius.field
        layer.borderWidth = KQStroke.hairline

        field.font = KQFont.font(.body)
        field.adjustsFontForContentSizeCategory = true
        field.textColor = KQColor.ink
        field.tintColor = KQColor.ink
        field.attributedPlaceholder = NSAttributedString(string: placeholder,
                                                         attributes: [NSAttributedStringKey.foregroundColor: KQColor.ink3])
        field.autocorrectionType = .no
        field.clearButtonMode = .whileEditing
        field.delegate = self
        addSubview(field)
        field.snp.makeConstraints {
            $0.top.bottom.equalToSuperview()
            $0.leading.trailing.equalToSuperview().inset(KQSpace.x12)
            $0.height.greaterThanOrEqualTo(KQSize.targetIos)
        }
        applyColors()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        applyColors()
    }

    func textField(_ textField: UITextField, shouldChangeCharactersIn range: NSRange, replacementString string: String) -> Bool {
        let current = textField.text ?? ""
        guard let swiftRange = Range(range, in: current) else {
            return false
        }
        let next = current.replacingCharacters(in: swiftRange, with: string)
        return next.utf8.count <= maxBytes && !next.contains("\n")
    }

    func textFieldDidBeginEditing(_ textField: UITextField) {
        applyColors()
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()
        return true
    }

    func textFieldDidEndEditing(_ textField: UITextField) {
        applyColors()
        onCommit?(text)
    }

    private func applyColors() {
        let focused = field.isEditing
        backgroundColor = focused ? KQColor.raised : KQColor.sunken
        layer.borderColor = airCGColor(focused ? KQColor.ink : KQColor.line)
    }
}
