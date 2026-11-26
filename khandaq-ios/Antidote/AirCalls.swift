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

// MARK: - Stage

/// KHANDAQ (calls stage, 26.11): the geometry of a video call as plain values, by the rules Android and
/// the desktop follow too (calls stage SPEC, R1 to R6): how a video takes its place without being
/// stretched, the size and the corner of your own picture, where it lands when let go, and which video
/// is the big one. AirCallTests checks it with exact numbers.
enum AirCallStage {
    /// How a frame takes a place: covering it with a centred crop, or whole inside it with bars.
    enum ScaleMode {
        case fill
        case fit
    }

    /// A corner of the stage as it is on the screen; your picture starts bottom trailing.
    enum Corner {
        case topLeft
        case topRight
        case bottomLeft
        case bottomRight

        static let all: [Corner] = [.topLeft, .topRight, .bottomLeft, .bottomRight]

        var isTop: Bool {
            return self == .topLeft || self == .topRight
        }

        var isLeft: Bool {
            return self == .topLeft || self == .bottomLeft
        }

        /// Bottom right, or bottom left in a right-to-left language.
        static func bottomTrailing(rightToLeft: Bool) -> Corner {
            return rightToLeft ? .bottomLeft : .bottomRight
        }
    }

    /// The two videos of a call: the other person's and your own camera.
    enum Video {
        case remote
        case own
    }

    /// R3: the short side of your picture is this share of the stage's short side, within the limits.
    static let ownShortSideShare: CGFloat = 0.26
    static let ownShortSideMin: CGFloat = 88
    static let ownShortSideMax: CGFloat = 168
    /// R2: the shape of your picture until the camera says its own: 3:4, upright.
    static let unknownOwnFrame = CGSize(width: 3, height: 4)
    /// R5: the gap around your picture, from the safe edges, the head and the row of controls.
    static let gap = KQSpace.x16
    /// R6: a release carries your picture on at the speed it was let go with for this long, in seconds.
    static let releaseProjection: CGFloat = 0.15
    /// R7: the row of controls is four columns of 96 at most, in the middle of a wider stage.
    static let rowMaxWidth: CGFloat = 4 * 96
    /// R9: the person's tile on a call without video.
    static let avatarSidePhone: CGFloat = 112
    static let avatarSideTablet: CGFloat = 128

    /// Portrait is taller than wide; a square counts as landscape.
    static func isPortrait(_ size: CGSize) -> Bool {
        return size.height > size.width
    }

    /// R1: a frame fills the stage while both are portrait or both landscape, and fits it otherwise.
    static func scaleMode(frame: CGSize, stage: CGSize) -> ScaleMode {
        return isPortrait(frame) == isPortrait(stage) ? .fill : .fit
    }

    /// The rect a frame takes in `bounds`, centred and in the frame's own shape: covering the bounds
    /// (fill) or inside them (fit). A frame of no size takes the bounds.
    static func contentRect(frame: CGSize, in bounds: CGRect, mode: ScaleMode) -> CGRect {
        guard frame.width > 0, frame.height > 0, bounds.width > 0, bounds.height > 0 else {
            return bounds
        }
        let scaleX = bounds.width / frame.width
        let scaleY = bounds.height / frame.height
        let scale = mode == .fill ? max(scaleX, scaleY) : min(scaleX, scaleY)
        let width = frame.width * scale
        let height = frame.height * scale
        return CGRect(x: bounds.midX - width / 2, y: bounds.midY - height / 2, width: width, height: height)
    }

    /// R2: a camera frame as its preview shows it. Turned a quarter (90 or 270 degrees) it stands
    /// upright: 1280x720 becomes 720x1280.
    static func shownFrame(_ frame: CGSize, rotationAngle: CGFloat) -> CGSize {
        let quarters = Int((rotationAngle / 90).rounded())
        return quarters % 2 == 0 ? frame : CGSize(width: frame.height, height: frame.width)
    }

    /// R2, R3: the size of your picture. Its short side comes from the stage's short side, its long side
    /// from the shape of the frame (3:4 upright while unknown).
    static func ownSize(frame: CGSize?, stage: CGSize) -> CGSize {
        var shape = unknownOwnFrame
        if let frame = frame, frame.width > 0, frame.height > 0 {
            shape = frame
        }
        let share = (ownShortSideShare * min(stage.width, stage.height)).rounded()
        let short = min(max(share, ownShortSideMin), ownShortSideMax)
        let long = (short * max(shape.width, shape.height) / min(shape.width, shape.height)).rounded()
        return isPortrait(shape) ? CGSize(width: short, height: long) : CGSize(width: long, height: short)
    }

    /// R5: where your picture may stand: the stage within its safe edges (the notch, the status bar,
    /// the home indicator), 16 in from them.
    static func ownArea(stage: CGRect, safeInsets: UIEdgeInsets) -> CGRect {
        return CGRect(x: stage.minX + safeInsets.left + gap,
                      y: stage.minY + safeInsets.top + gap,
                      width: stage.width - safeInsets.left - safeInsets.right - 2 * gap,
                      height: stage.height - safeInsets.top - safeInsets.bottom - 2 * gap)
    }

    /// R5: your picture in a corner of the area, 16 clear of what it would cross on the stage. Crossing
    /// the head sends a top corner down below it; crossing the row of controls sends a bottom corner up
    /// above it. With no room left it stays inside the area all the same.
    static func ownRect(size: CGSize, corner: Corner, area: CGRect, obstacles: [CGRect]) -> CGRect {
        var rect = CGRect(x: corner.isLeft ? area.minX : area.maxX - size.width,
                          y: corner.isTop ? area.minY : area.maxY - size.height,
                          width: size.width,
                          height: size.height)
        let clearances = obstacles.filter { !$0.isEmpty }.map { $0.insetBy(dx: -gap, dy: -gap) }
        if corner.isTop {
            for clearance in clearances.sorted(by: { $0.minY < $1.minY }) where rect.intersects(clearance) {
                rect.origin.y = clearance.maxY
            }
        }
        else {
            for clearance in clearances.sorted(by: { $0.maxY > $1.maxY }) where rect.intersects(clearance) {
                rect.origin.y = clearance.minY - size.height
            }
        }
        rect.origin.y = max(area.minY, min(rect.origin.y, area.maxY - size.height))
        return rect
    }

    /// R6: where a release carries a point, at the speed it was let go with.
    static func projected(_ point: CGPoint, velocity: CGPoint) -> CGPoint {
        return CGPoint(x: point.x + velocity.x * releaseProjection, y: point.y + velocity.y * releaseProjection)
    }

    /// R6: the corner your picture snaps to on release: the one whose place lies nearest to where the
    /// release carries its centre.
    static func snapCorner(center: CGPoint, velocity: CGPoint, size: CGSize, area: CGRect, obstacles: [CGRect]) -> Corner {
        let target = projected(center, velocity: velocity)
        func distance(_ corner: Corner) -> CGFloat {
            let rect = ownRect(size: size, corner: corner, area: area, obstacles: obstacles)
            return hypot(rect.midX - target.x, rect.midY - target.y)
        }
        var nearest = Corner.all[0]
        for corner in Corner.all.dropFirst() where distance(corner) < distance(nearest) {
            nearest = corner
        }
        return nearest
    }

    /// R2, R6: which video takes the stage and which the box. Your picture stands in the box while it is
    /// sent; a tap swaps the two, which needs both.
    static func placement(remote: Bool, own: Bool, swapped: Bool) -> (stage: Video?, box: Video?) {
        switch (remote, own) {
            case (true, true):
                return swapped ? (.own, .remote) : (.remote, .own)
            case (true, false):
                return (.remote, nil)
            case (false, true):
                return (nil, .own)
            case (false, false):
                return (nil, nil)
        }
    }
}

/// KHANDAQ (calls stage, 26.11): the size of the frame a layer of a call shows (R2): your camera's
/// format turned as its preview turns it, or the image a layer holds. Nil while nothing says.
enum AirCallCamera {
    static func shownFrame(of layer: CALayer) -> CGSize? {
        if let preview = layer as? AVCaptureVideoPreviewLayer {
            return shownFrame(ofPreview: preview)
        }
        guard let contents = layer.contents, CFGetTypeID(contents as CFTypeRef) == CGImage.typeID else {
            return nil
        }
        let image = contents as! CGImage
        guard image.width > 0, image.height > 0 else {
            return nil
        }
        return CGSize(width: image.width, height: image.height)
    }

    private static func shownFrame(ofPreview preview: AVCaptureVideoPreviewLayer) -> CGSize? {
        let devices = (preview.session?.inputs ?? []).compactMap { ($0 as? AVCaptureDeviceInput)?.device }
        guard let camera = devices.first(where: { $0.hasMediaType(.video) }), let connection = preview.connection else {
            return nil
        }
        let dimensions = CMVideoFormatDescriptionGetDimensions(camera.activeFormat.formatDescription)
        guard dimensions.width > 0, dimensions.height > 0 else {
            return nil
        }
        let frame = CGSize(width: CGFloat(dimensions.width), height: CGFloat(dimensions.height))
        return AirCallStage.shownFrame(frame, rotationAngle: rotationAngle(of: connection))
    }

    /// The turn of the preview: its angle on iOS 17 and later, its orientation before.
    private static func rotationAngle(of connection: AVCaptureConnection) -> CGFloat {
        if #available(iOS 17.0, *) {
            return connection.videoRotationAngle
        }
        switch connection.videoOrientation {
            case .landscapeLeft, .landscapeRight:
                return 0
            default:
                return 90
        }
    }
}

/// KHANDAQ (calls stage, 26.11): the person on a call without video (R9), as the conversation's header
/// shows them: their photo, else their initials in their tone.
struct AirCallPerson {
    let tile: AirTileView.Style
    let photo: UIImage?

    init(name: String, friend: OCTFriend?) {
        let live = friend.flatMap { $0.isInvalidated ? nil : $0 }
        tile = .person(initials: AirInitials.from(name), tone: AirTone.index(for: live?.publicKey ?? name))
        photo = live?.avatarData.flatMap { UIImage(data: $0) }
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
        // KHANDAQ (calls stage, 26.11): one line in the label style that shrinks to 80 % before it would
        // wrap (R7); a second line ("Switch camera" in Russian on Android) pushed its circle out of line.
        caption.font = KQFont.font(.label)
        caption.adjustsFontForContentSizeCategory = true
        caption.textColor = KQColor.fixedWhite
        caption.textAlignment = .center
        caption.numberOfLines = 1
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

/// KHANDAQ (calls stage, 26.11): a place on the call stage with one video in it, cut to its bounds: the
/// whole stage, or the box of the picture in picture (R1, R2, R4). The video keeps the shape of its
/// frame. A view says that shape as its intrinsic size (objcTox's feed does, from its frames); a layer
/// through `layerFrame`.
final class AirCallVideoSlot: UIView {
    /// The box: rounded, with a hairline, the video always covering it. On the stage a frame of the
    /// other orientation fits instead, between black bars.
    var isBox = false {
        didSet {
            guard isBox != oldValue else {
                return
            }
            applyLook()
            setNeedsLayout()
        }
    }

    /// The size of the frame `videoLayer` shows; nil while unknown.
    var layerFrame: CGSize? {
        didSet {
            if layerFrame != oldValue {
                setNeedsLayout()
            }
        }
    }

    private(set) var video: UIView?
    private(set) var videoLayer: CALayer?

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = true
        applyLook()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// Puts a view of video in, in place of the one there was.
    func show(_ newVideo: UIView?) {
        guard newVideo !== video else {
            return
        }
        if video?.superview === self {
            video?.removeFromSuperview()
        }
        video = newVideo
        if let newVideo = newVideo {
            // Placed by its frame here; a feed that was pinned with constraints before lets them go.
            newVideo.translatesAutoresizingMaskIntoConstraints = true
            insertSubview(newVideo, at: 0)
        }
        setNeedsLayout()
    }

    /// Puts a layer of video in, in place of the one there was.
    func show(layer newLayer: CALayer?) {
        guard newLayer !== videoLayer else {
            return
        }
        if videoLayer?.superlayer === layer {
            videoLayer?.removeFromSuperlayer()
        }
        videoLayer = newLayer
        if let newLayer = newLayer {
            layer.insertSublayer(newLayer, at: 0)
        }
        setNeedsLayout()
    }

    /// The size of the frame shown, once known.
    var frameSize: CGSize? {
        if let video = video {
            let size = video.intrinsicContentSize
            return size.width > 0 && size.height > 0 ? size : nil
        }
        return videoLayer == nil ? nil : layerFrame
    }

    /// R1 on the stage; the box is always covered.
    var scaleMode: AirCallStage.ScaleMode {
        guard !isBox, let frame = frameSize else {
            return .fill
        }
        return AirCallStage.scaleMode(frame: frame, stage: bounds.size)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let rect = frameSize.map { AirCallStage.contentRect(frame: $0, in: bounds, mode: scaleMode) } ?? bounds
        video?.frame = rect
        if let videoLayer = videoLayer {
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            videoLayer.frame = rect
            CATransaction.commit()
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        applyLook()
    }

    /// R4: the box has the card's corners, a hairline of white at 20 % and the lock-bottom ground until
    /// the first frame. R1: the stage shows black bars around a frame that fits.
    private func applyLook() {
        backgroundColor = isBox ? KQColor.lockBottom : KQColor.fixedBlack
        layer.cornerRadius = isBox ? KQRadius.card : 0
        layer.borderWidth = isBox ? KQStroke.hairline : 0
        layer.borderColor = airCGColor(KQColor.fixedWhite.withAlphaComponent(0.2))
    }
}

/// KHANDAQ (calls stage, 26.11): the link on the call screen as a chip under the state (R8): a pill of
/// the dark scrim with the link mark and its words in white, legible over any video. It was a bare line.
final class AirCallLinkChip: UIView {
    let label = UILabel()
    private let mark = AirLinkMarkView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = KQColor.scrim

        label.font = KQFont.font(.label)
        label.adjustsFontForContentSizeCategory = true
        label.textColor = KQColor.fixedWhite
        label.numberOfLines = 0
        label.isAccessibilityElement = true
        // Short of room on the call screen the person's tile gives way before the words.
        label.setContentCompressionResistancePriority(UILayoutPriority(rawValue: 999), for: .vertical)

        let row = UIStackView(arrangedSubviews: [mark, label])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = KQSpace.x8
        addSubview(row)
        row.snp.makeConstraints {
            $0.top.bottom.equalToSuperview().inset(KQSpace.x4)
            $0.leading.trailing.equalToSuperview().inset(KQSpace.x10)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(_ link: AirLinkState) {
        mark.configure(link)
        label.text = AirCallText.line(link)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        layer.cornerRadius = min(KQRadius.round, bounds.height / 2)
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
