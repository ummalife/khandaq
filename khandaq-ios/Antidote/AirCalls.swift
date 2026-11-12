// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import SnapKit
import UIKit

// Calls (redesign stage 3, E9): no crash and no silent refusal. Before a call the app checks the
// microphone, and the camera for a video call; a refusal is explained with the way to the settings.
// A call to someone who is offline says it will not get through and offers to wait for them. The call
// screens are dark in both themes: the person and the state of the link at the top, the 64 pt controls
// at the bottom, in the thumb zone. A call stands in the transcript as an entry like a text.

/// What the call screens and the transcript say about a call.
enum AirCallText {
    /// The length of a call: "0:42", "12:05", "1:02:09"; shown in tabular figures.
    static func duration(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite, seconds > 0 else {
            return "0:00"
        }
        let total = Int(seconds.rounded(.down))
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let rest = total % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, rest)
        }
        return String(format: "%d:%02d", minutes, rest)
    }

    /// A call in the transcript: "Call, 4:12"; theirs that nobody answered is missed, ours had no answer.
    static func entry(answered: Bool, duration: TimeInterval, incoming: Bool) -> String {
        if answered {
            return String(localized: "air_call_entry_format", self.duration(duration))
        }
        return String(localized: incoming ? "air_call_missed" : "air_call_no_answer")
    }

    /// "Yusuf is offline: the call will not get through".
    static func offlineTitle(name: String) -> String {
        return String(localized: "air_call_offline_title_format", name)
    }

    /// What waiting does: with a push token their phone gets a signal without text first.
    static func offlineDetail(hasPush: Bool) -> String {
        return String(localized: hasPush ? "air_call_offline_push" : "air_call_offline_wait")
    }

    static func waiting(name: String) -> String {
        return String(localized: "air_call_waiting_format", name)
    }

    static func waitingDetail(hasPush: Bool) -> String {
        return String(localized: hasPush ? "air_call_waiting_push" : "air_call_waiting_no_push")
    }

    /// The line under the state on the call screen.
    static func line(_ link: AirLinkState) -> String {
        switch link {
            case .direct:
                return String(localized: "air_call_line_direct")
            case .relay:
                return String(localized: "air_call_line_relay")
            case .none:
                return String(localized: "air_call_line_none")
        }
    }
}

extension AirLinkState {
    /// The link to the other person of a call right now.
    init(friend: OCTFriend?) {
        guard let friend = friend, !friend.isInvalidated else {
            self = .none
            return
        }
        switch friend.connectionStatus {
            case .UDP:
                self = .direct
            case .TCP:
                self = .relay
            default:
                self = .none
        }
    }
}

/// The microphone and the camera for a call.
enum AirCallAccess {
    enum Device {
        case microphone
        case camera
    }

    /// The camera for a video call: asks once, then answers on the main queue.
    static func requestCamera(_ completion: @escaping (Bool) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized:
                completion(true)
            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    DispatchQueue.main.async {
                        completion(granted)
                    }
                }
            default:
                completion(false)
        }
    }

    /// The camera was refused before, so a video call goes with sound alone.
    static var cameraRefused: Bool {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        return status == .denied || status == .restricted
    }

    /// Says what a refused device means for the call and offers the settings; `alternative` adds one
    /// more way on ("Call without video").
    static func explain(_ device: Device, from root: UIViewController?, alternative: (title: String, action: () -> Void)? = nil) {
        guard let presenter = topController(from: root) else {
            return
        }
        let isMicrophone = device == .microphone
        let alert = UIAlertController(title: String(localized: isMicrophone ? "air_call_mic_title" : "air_call_camera_title"),
                                      message: String(localized: isMicrophone ? "air_call_mic_message" : "air_call_camera_message"),
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: String(localized: "air_call_open_settings"), style: .default) { _ in
            if let url = URL(string: UIApplicationOpenSettingsURLString) {
                UIApplication.shared.open(url)
            }
        })
        if let alternative = alternative {
            alert.addAction(UIAlertAction(title: alternative.title, style: .default) { _ in
                alternative.action()
            })
        }
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        presenter.present(alert, animated: true, completion: nil)
    }

    /// The controller an alert about a call goes over: the topmost one that is not on its way out.
    static func topController(from root: UIViewController?) -> UIViewController? {
        var top = root
        while let next = top?.presentedViewController, !next.isBeingDismissed {
            top = next
        }
        return top
    }
}

// MARK: - Views

/// The ground of the call screens and of the PIN lock: one flat lock-top colour, dark in both themes, so
/// the white words and the filled buttons read the same by day and by night. Flat, because the brief
/// allows no gradients (it was lock-top to lock-bottom until stage 5).
final class AirCallBackdropView: UIView {
    override init(frame: CGRect) {
        super.init(frame: frame)
        applyColors()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        applyColors()
    }

    private func applyColors() {
        backgroundColor = KQColor.lockTop
    }
}

/// A control of a call: the 64 pt circle and its word under it.
final class AirCallControl: UIView {
    let button: CallButton
    private let caption = UILabel()

    init(button: CallButton, title: String) {
        self.button = button
        super.init(frame: .zero)

        button.accessibilityLabel = title
        addSubview(button)

        caption.text = title
        caption.font = KQFont.font(.caption)
        caption.adjustsFontForContentSizeCategory = true
        caption.textColor = KQColor.fixedWhite
        caption.textAlignment = .center
        caption.numberOfLines = 2
        caption.adjustsFontSizeToFitWidth = true
        caption.minimumScaleFactor = 0.8
        caption.isAccessibilityElement = false
        addSubview(caption)

        button.snp.makeConstraints {
            $0.top.centerX.equalToSuperview()
            $0.size.equalTo(KQSpace.x64)
            $0.leading.greaterThanOrEqualToSuperview()
            $0.trailing.lessThanOrEqualToSuperview()
        }
        caption.snp.makeConstraints {
            $0.top.equalTo(button.snp.bottom).offset(KQSpace.x8)
            $0.leading.trailing.bottom.equalToSuperview()
            // The width it takes alone; a row of controls shares its width out equally instead, so four
            // fit on a 320 pt screen.
            $0.width.equalTo(KQSpace.x64 + KQSpace.x24).priority(.high)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    var title: String {
        get {
            return caption.text ?? ""
        }
        set {
            caption.text = newValue
            button.accessibilityLabel = newValue
        }
    }
}

/// Waiting for someone who is offline before a call: their name, what the app is doing, and Cancel.
/// The call starts on its own when they come online (ChatPrivateController watches for it). Nothing
/// moves while it waits: the brief bans endless animation.
final class AirCallWaitingController: UIViewController {
    var onCancel: (() -> Void)?

    private let name: String
    private let shortName: String
    private let hasPush: Bool

    /// `name` heads the screen; `shortName` goes into "Waiting for Yusuf to come online".
    init(name: String, shortName: String, hasPush: Bool) {
        self.name = name
        self.shortName = shortName
        self.hasPush = hasPush
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .overFullScreen
        modalTransitionStyle = .crossDissolve
        overrideUserInterfaceStyle = .dark
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var preferredStatusBarStyle: UIStatusBarStyle {
        return .lightContent
    }

    override func loadView() {
        view = AirCallBackdropView()
        view.accessibilityViewIsModal = true

        let nameLabel = UILabel()
        nameLabel.text = name
        nameLabel.font = KQFont.font(.display)
        nameLabel.adjustsFontForContentSizeCategory = true
        nameLabel.textColor = KQColor.fixedWhite
        nameLabel.textAlignment = .center
        nameLabel.numberOfLines = 2
        nameLabel.accessibilityTraits = UIAccessibilityTraitHeader

        let clock = UIImageView(image: AirIcon.image("clock"))
        clock.tintColor = KQColor.fixedWhite
        clock.contentMode = .scaleAspectFit
        clock.isAccessibilityElement = false

        let waitingLabel = UILabel()
        waitingLabel.text = AirCallText.waiting(name: shortName)
        waitingLabel.font = KQFont.font(.body)
        waitingLabel.adjustsFontForContentSizeCategory = true
        waitingLabel.textColor = KQColor.fixedWhite
        waitingLabel.textAlignment = .center
        waitingLabel.numberOfLines = 0

        let detailLabel = UILabel()
        detailLabel.text = AirCallText.waitingDetail(hasPush: hasPush)
        detailLabel.font = KQFont.font(.label)
        detailLabel.adjustsFontForContentSizeCategory = true
        detailLabel.textColor = KQColor.fixedWhite
        detailLabel.textAlignment = .center
        detailLabel.numberOfLines = 0

        let head = UIStackView(arrangedSubviews: [nameLabel, clock, waitingLabel, detailLabel])
        head.axis = .vertical
        head.alignment = .center
        head.spacing = KQSpace.x12
        head.setCustomSpacing(KQSpace.x24, after: nameLabel)
        view.addSubview(head)

        let cancel = AirCallControl(button: CallButton(theme: nil, type: .decline, buttonSize: .big),
                                    title: String(localized: "air_call_cancel"))
        cancel.button.accessibilityIdentifier = "air.call.waiting.cancel"
        cancel.button.addTarget(self, action: #selector(cancelPressed), for: .touchUpInside)
        view.addSubview(cancel)

        head.snp.makeConstraints {
            $0.top.equalTo(view.safeAreaLayoutGuide).offset(KQSpace.x48)
            $0.leading.trailing.equalToSuperview().inset(KQSpace.x24)
        }
        clock.snp.makeConstraints {
            $0.size.equalTo(KQSize.icon)
        }
        cancel.snp.makeConstraints {
            $0.centerX.equalToSuperview()
            $0.top.greaterThanOrEqualTo(head.snp.bottom).offset(KQSpace.x24)
            $0.bottom.equalTo(view.safeAreaLayoutGuide).offset(-KQSpace.x32)
        }
    }

    @objc private func cancelPressed() {
        onCancel?()
    }
}

// MARK: - Transcript

/// A call in the transcript: the time column and the author as for a text, then the phone glyph and
/// "Call, 4:12", "Missed call" or "No answer". ChatIncomingCallCell and ChatOutgoingCallCell only say
/// which way it went.
class AirCallEntryCell: ChatMovableDateCell {
    /// The way the call went; ChatOutgoingCallCell says false.
    var isIncoming: Bool {
        return true
    }

    private let entry = AirEntryFrame()
    private let body = UIView()
    private let iconView = UIImageView()
    private let label = UILabel()
    private var entryModel: ChatMovableDateCellModel?

    override func setupWithTheme(_ theme: Theme, model: BaseCellModel) {
        super.setupWithTheme(theme, model: model)

        guard let model = model as? ChatMovableDateCellModel else {
            assert(false, "Wrong model \(model) passed to cell \(self)")
            return
        }
        entryModel = model

        var answered = false
        var duration: TimeInterval = 0
        if let incoming = model as? ChatIncomingCallCellModel {
            answered = incoming.answered
            duration = incoming.callDuration
        }
        else if let outgoing = model as? ChatOutgoingCallCellModel {
            answered = outgoing.answered
            duration = outgoing.callDuration
        }

        label.text = AirCallText.entry(answered: answered, duration: duration, incoming: isIncoming)
        label.font = answered ? KQFont.tabular(KQFont.font(.body)) : KQFont.font(.body)
        label.textColor = answered ? KQColor.ink : KQColor.ink2
        iconView.tintColor = KQColor.ink2

        entry.configure(model, firstLine: ceil(KQFont.lineHeight(.body)))
        entry.mark.isHidden = true

        canBeCopied = false
        canBeReacted = false
        accessibilityLabel = entry.spokenLabel(model)
        accessibilityValue = [transcriptMarksSpoken, label.text].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ", ")
    }

    override func createViews() {
        revealsTime = false
        super.createViews()

        iconView.image = AirIcon.image("phone")
        iconView.contentMode = .scaleAspectFit
        iconView.isAccessibilityElement = false
        body.addSubview(iconView)

        label.adjustsFontForContentSizeCategory = true
        label.numberOfLines = 0
        label.isAccessibilityElement = false
        body.addSubview(label)

        movableContentView.addSubview(body)
        isAccessibilityElement = true
    }

    override func installConstraints() {
        super.installConstraints()

        entry.install(in: self, body: body)
        iconView.snp.makeConstraints {
            $0.leading.equalToSuperview()
            $0.centerY.equalTo(label.snp.firstBaseline).offset(-KQFont.font(.body).xHeight / 2)
            $0.size.equalTo(KQSize.icon)
        }
        label.snp.makeConstraints {
            $0.top.bottom.equalToSuperview()
            $0.leading.equalTo(iconView.snp.trailing).offset(KQSpace.x8)
            $0.trailing.lessThanOrEqualToSuperview()
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        guard let model = entryModel else {
            return
        }
        entry.configure(model, firstLine: ceil(KQFont.lineHeight(.body)))
    }
}

// ChatEditable
extension AirCallEntryCell {
    override func shouldShowMenu() -> Bool {
        return true
    }

    override func menuTargetRect() -> CGRect {
        return body.frame
    }
}
