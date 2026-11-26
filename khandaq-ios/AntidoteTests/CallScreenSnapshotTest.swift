// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 The call screens of redesign stage 3 (E9), dark in both themes: an incoming call with Decline,
 Answer with video and Answer; a call that is on, with its timer in tabular figures, the link and the
 row of controls; the wait for someone offline. The row of four controls must fit the narrowest phone
 the app runs on (320 pt) without its circles touching.
 */
class CallScreenSnapshotTest: SnapshotBaseTest {
    private var window: UIWindow?

    override func tearDown() {
        window?.isHidden = true
        window = nil
        super.tearDown()
    }

    func testIncomingCall() {
        let controller = CallIncomingController(theme: theme, callerName: "Amina Karimova")
        controller.link = .direct
        verifyScreen(controller)
    }

    func testACallThatIsOn() {
        let controller = CallActiveController(theme: theme, callerName: "Amina Karimova")
        controller.link = .relay
        controller.state = .active(duration: 252)
        verifyScreen(controller)
    }

    func testWaitingForSomeoneOffline() {
        let controller = AirCallWaitingController(name: "Yusuf Rahman", shortName: "Yusuf", hasPush: true)
        verifyScreen(controller)
    }

    func testTheRowOfControlsFitsTheNarrowestPhone() {
        let controller = CallActiveController(theme: theme, callerName: "Amina Karimova")
        controller.state = .active(duration: 5)
        host(controller, size: CGSize(width: 320, height: 568))

        let identifiers = ["air.call.mic", "air.call.speaker", "air.call.camera", "air.call.end"]
        let frames = identifiers.compactMap { identifier -> CGRect? in
            guard let button = controller.view.firstSubview(withIdentifier: identifier) else {
                XCTFail("no \(identifier) on the call screen")
                return nil
            }
            return button.convert(button.bounds, to: controller.view)
        }
        XCTAssertEqual(frames.count, identifiers.count)
        for frame in frames {
            XCTAssertEqual(frame.width, 64, accuracy: 0.5, "a control lost its 64 pt circle")
            XCTAssertGreaterThanOrEqual(frame.minX, 0)
            XCTAssertLessThanOrEqual(frame.maxX, 320)
        }
        for (left, right) in zip(frames, frames.dropFirst()) {
            XCTAssertLessThan(left.maxX, right.minX, "two controls touch")
        }
        FBSnapshotVerifyView(controller.view, identifier: "320")
    }

    private func verifyScreen(_ controller: UIViewController) {
        host(controller, size: CGSize(width: 390, height: 844))
        verifyView(controller.view)
    }

    /// In a window of its own, so the dark override of the call screens reaches every view as it does
    /// in the app.
    private func host(_ controller: UIViewController, size: CGSize) {
        let window = UIWindow(frame: CGRect(origin: .zero, size: size))
        window.rootViewController = controller
        window.isHidden = false
        controller.view.frame = window.bounds
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()
        self.window = window
    }
}

/**
 KHANDAQ (calls stage, 26.11): the real call screen on each class of device, with test cards for the
 videos (the other person's 9:16 and 16:9, your own 9:16): a video call with the controls, the videos
 swapped, a call without video. Each screen is held to the rules of the calls stage: the box of your
 picture inside the safe edges and clear of the head and the row (R5), the shape of every video kept
 (R1, R2), the row at most 384 wide in the middle (R7), the group of a call without video in the middle
 (R9), no debug words (R10), the same screen in both themes of the app (R12). With KHANDAQ_CALL_SHOTS_DIR
 set (TEST_RUNNER_KHANDAQ_CALL_SHOTS_DIR for xcodebuild) the screens are also written there as PNG.
 */
class CallStageScreenshotTest: SnapshotBaseTest {
    private struct Device {
        let name: String
        let size: CGSize
        /// The notch, the status bar and the home indicator of the device, as the safe area gives them.
        let insets: UIEdgeInsets
        let isPad: Bool
    }

    private enum Scene: String {
        /// The other person's video upright, yours in the box.
        case remotePortrait = "remote-portrait"
        /// The other person's video on its side.
        case remoteLandscape = "remote-landscape"
        /// After a tap on the box: your camera on the stage, theirs in the box.
        case swapped
        /// No video either way.
        case audio
    }

    private let devices = [
        Device(name: "phone-320x568", size: CGSize(width: 320, height: 568),
               insets: UIEdgeInsets(top: 20, left: 0, bottom: 0, right: 0), isPad: false),
        Device(name: "phone-375x667", size: CGSize(width: 375, height: 667),
               insets: UIEdgeInsets(top: 20, left: 0, bottom: 0, right: 0), isPad: false),
        Device(name: "phone-393x852", size: CGSize(width: 393, height: 852),
               insets: UIEdgeInsets(top: 59, left: 0, bottom: 34, right: 0), isPad: false),
        Device(name: "phone-430x932", size: CGSize(width: 430, height: 932),
               insets: UIEdgeInsets(top: 59, left: 0, bottom: 34, right: 0), isPad: false),
        Device(name: "phone-852x393", size: CGSize(width: 852, height: 393),
               insets: UIEdgeInsets(top: 0, left: 59, bottom: 21, right: 59), isPad: false),
        Device(name: "ipad-820x1180", size: CGSize(width: 820, height: 1180),
               insets: UIEdgeInsets(top: 24, left: 0, bottom: 20, right: 0), isPad: true),
        Device(name: "ipad-1180x820", size: CGSize(width: 1180, height: 820),
               insets: UIEdgeInsets(top: 24, left: 0, bottom: 20, right: 0), isPad: true),
    ]

    private var windows: [UIWindow] = []

    override func tearDown() {
        windows.forEach {
            $0.isHidden = true
            $0.rootViewController = nil
        }
        windows = []
        super.tearDown()
    }

    func testTheVideoCallOnEachDevice() {
        for scene in [Scene.remotePortrait, .remoteLandscape, .swapped] {
            var sheet: [UIImage] = []
            for device in devices {
                let (controller, image) = render(device, scene: scene)
                checkStage(controller, device: device, scene: scene)
                checkRow(controller, device: device)
                checkNoDebugWords(controller, device: device)
                save(image, name: "\(device.name)-\(scene.rawValue)")
                sheet.append(image)
            }
            save(contactSheet(sheet), name: "sheet-\(scene.rawValue)")
        }
    }

    func testTheCallWithoutVideoOnEachDevice() {
        var sheet: [UIImage] = []
        for device in devices {
            let (controller, image) = render(device, scene: .audio)
            checkRow(controller, device: device)
            checkNoDebugWords(controller, device: device)

            // R9: the tile, 112 on phones and 128 on tablets, and the group in the middle between the
            // safe top and the row.
            let tile = controller.topContainer.subviews.first { $0 is AirTileView }
            XCTAssertEqual(tile?.isHidden, false, device.name)
            XCTAssertEqual(tile?.bounds.width ?? 0, device.isPad ? 128 : 112, accuracy: 0.5, device.name)
            let head = controller.topContainer.frame
            let top = controller.view.safeAreaInsets.top
            let row = rowFrame(controller)
            XCTAssertGreaterThanOrEqual(head.minY, top - 0.5, device.name)
            XCTAssertLessThanOrEqual(head.maxY, row.minY - 16 + 0.5, device.name)
            if head.height + 32 < row.minY - top {
                XCTAssertEqual(head.midY, (top + row.minY) / 2, accuracy: 1, "\(device.name): the group is off the middle")
            }
            for slot in videoSlots(controller) {
                XCTAssertTrue(slot.isHidden, device.name)
            }
            save(image, name: "\(device.name)-audio")
            sheet.append(image)
        }
        save(contactSheet(sheet), name: "sheet-audio")
    }

    func testTheBoxFollowsTheRowWhenTheControlsHide() {
        let device = devices[2]
        let (controller, image) = render(device, scene: .remotePortrait, controlsShown: false)
        // 16 in from the safe bottom instead of 16 above the row.
        XCTAssertEqual(currentBox(controller)?.frame, CGRect(x: 275, y: 621, width: 102, height: 181))
        // Nothing of the row or the head is left in sight.
        let row = rowFrame(controller)
        XCTAssertGreaterThanOrEqual(row.minY, device.size.height)
        XCTAssertLessThanOrEqual(controller.topContainer.frame.maxY, 0.5)
        save(image, name: "\(device.name)-controls-hidden")
    }

    func testTheBoxStartsBottomLeftInARightToLeftLanguage() {
        let device = devices[2]
        let (controller, image) = render(device, scene: .remotePortrait, rightToLeft: true)
        let box = currentBox(controller)
        XCTAssertEqual(box?.frame.minX ?? 0, 16, accuracy: 0.5)
        XCTAssertEqual(box?.frame.size, CGSize(width: 102, height: 181))
        // The switch of the camera in the top trailing corner: the left one.
        let switchCamera = controller.view.firstSubview(withIdentifier: "air.call.switch.camera")
        XCTAssertEqual(switchCamera?.frame, CGRect(x: 6, y: 6, width: 32, height: 32))
        checkStage(controller, device: device, scene: .remotePortrait)
        save(image, name: "\(device.name)-right-to-left")
    }

    func testALandscapeCameraGivesALandscapeBox() {
        let device = devices[4]
        let (controller, image) = render(device, scene: .remotePortrait, ownFrame: CGSize(width: 1280, height: 720))
        XCTAssertEqual(currentBox(controller)?.frame.size, CGSize(width: 181, height: 102))
        checkStage(controller, device: device, scene: .remotePortrait, ownFrame: CGSize(width: 1280, height: 720))
        save(image, name: "\(device.name)-own-landscape")
    }

    func testTheSwitchOfTheCameraIsInTheBoxWithItsFullTarget() {
        let device = devices[2]
        let (controller, _) = render(device, scene: .remotePortrait)
        guard let box = currentBox(controller), let button = controller.view.firstSubview(withIdentifier: "air.call.switch.camera") else {
            return XCTFail("no box or no switch of the camera")
        }
        XCTAssertTrue(button.superview === box)
        XCTAssertEqual(button.frame, CGRect(x: box.bounds.width - 38, y: 6, width: 32, height: 32))
        // The 44 pt target around the 32 pt circle, all of it inside the box.
        XCTAssertTrue(button.point(inside: CGPoint(x: -5.5, y: -5.5), with: nil))
        XCTAssertTrue(button.point(inside: CGPoint(x: 37.5, y: 37.5), with: nil))
        XCTAssertFalse(button.point(inside: CGPoint(x: -6.5, y: 16), with: nil))

        // After a swap it stays in the corner of the box, now the other person's video.
        controller.boxTapped()
        controller.view.layoutIfNeeded()
        let swappedBox = currentBox(controller)
        XCTAssertEqual(swappedBox?.accessibilityIdentifier, "air.call.video.remote")
        XCTAssertTrue(button.superview === swappedBox)
        controller.boxTapped()
        controller.view.layoutIfNeeded()
        XCTAssertEqual(currentBox(controller)?.accessibilityIdentifier, "air.call.video.own")

        // Your camera turned off while swapped: theirs takes the stage back and no switch is left on it.
        controller.boxTapped()
        controller.view.layoutIfNeeded()
        controller.outgoingVideo = false
        controller.videoPreviewLayer = nil
        controller.view.layoutIfNeeded()
        XCTAssertNil(currentBox(controller))
        XCTAssertTrue(button.isHidden)
        XCTAssertEqual(controller.view.firstSubview(withIdentifier: "air.call.video.remote")?.frame, controller.view.bounds)
    }

    func testTheLightAndTheDarkThemeOfTheAppRenderTheSameCallScreen() {
        let device = devices[2]
        for scene in [Scene.remotePortrait, .swapped, .audio] {
            let (_, dark) = render(device, scene: scene, style: .dark)
            let (_, light) = render(device, scene: scene, style: .light)
            XCTAssertEqual(UIImagePNGRepresentation(dark), UIImagePNGRepresentation(light), "\(scene.rawValue) differs between the themes")
            save(light, name: "\(device.name)-\(scene.rawValue)-app-light")
            save(dark, name: "\(device.name)-\(scene.rawValue)-app-dark")
        }
    }

    // MARK: - Rendering

    private func render(_ device: Device,
                        scene: Scene,
                        style: UIUserInterfaceStyle = .dark,
                        ownFrame: CGSize = CGSize(width: 720, height: 1280),
                        controlsShown: Bool = true,
                        rightToLeft: Bool = false) -> (CallActiveController, UIImage) {
        let controller = CallActiveController(theme: theme, callerName: "Amina Karimova")
        if device.isPad, #available(iOS 17.0, *) {
            controller.traitOverrides.userInterfaceIdiom = .pad
        }
        controller.link = .relay
        controller.state = .active(duration: 252)
        if rightToLeft {
            // Every view, as an Arabic phone mirrors them: the row and the chip too, not only the box.
            controller.view.forceRightToLeft()
        }

        let window = UIWindow(frame: CGRect(origin: .zero, size: device.size))
        window.overrideUserInterfaceStyle = style
        window.rootViewController = controller
        window.isHidden = false
        windows.append(window)
        controller.view.frame = window.bounds
        // The simulator's own screen gives the window its insets, once it has laid out; the device's
        // take their place.
        for _ in 0..<2 {
            controller.view.layoutIfNeeded()
            let actual = controller.view.safeAreaInsets
            let extra = controller.additionalSafeAreaInsets
            controller.additionalSafeAreaInsets = UIEdgeInsets(top: extra.top + device.insets.top - actual.top,
                                                               left: extra.left + device.insets.left - actual.left,
                                                               bottom: extra.bottom + device.insets.bottom - actual.bottom,
                                                               right: extra.right + device.insets.right - actual.right)
        }
        controller.view.layoutIfNeeded()
        XCTAssertEqual(controller.view.safeAreaInsets, device.insets, "\(device.name): the safe area is not the device's")

        let landscapeStage = device.size.width >= device.size.height
        if scene != .audio {
            let remoteSize: CGSize
            switch scene {
                case .remoteLandscape:
                    remoteSize = CGSize(width: 1280, height: 720)
                case .remotePortrait, .audio:
                    remoteSize = CGSize(width: 720, height: 1280)
                case .swapped:
                    remoteSize = landscapeStage ? CGSize(width: 1280, height: 720) : CGSize(width: 720, height: 1280)
            }
            let remote = UIImageView(image: card(remoteSize, title: "THEM", color: UIColor(red: 0.1, green: 0.42, blue: 0.47, alpha: 1)))
            controller.outgoingVideo = true
            controller.videoFeed = remote
            let own = CALayer()
            own.contents = card(ownFrame, title: "YOU", color: UIColor(red: 0.62, green: 0.33, blue: 0.12, alpha: 1)).cgImage
            controller.videoPreviewLayer = own
        }
        controller.view.layoutIfNeeded()
        if scene == .swapped {
            controller.boxTapped()
        }
        if !controlsShown {
            controller.tapOnView()
        }
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()

        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 2
        format.opaque = true
        let image = UIGraphicsImageRenderer(bounds: controller.view.bounds, format: format).image { context in
            controller.view.layer.render(in: context.cgContext)
        }
        return (controller, image)
    }

    /// A test card: a grid of squares and a circle, which a stretch would turn into oblongs and an
    /// ellipse, a yellow border that shows the crop, and its title.
    private func card(_ size: CGSize, title: String, color: UIColor) -> UIImage {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            let cg = context.cgContext
            color.setFill()
            cg.fill(CGRect(origin: .zero, size: size))

            UIColor(white: 1, alpha: 0.3).setStroke()
            cg.setLineWidth(2)
            for x in stride(from: CGFloat(0), through: size.width, by: 80) {
                cg.move(to: CGPoint(x: x, y: 0))
                cg.addLine(to: CGPoint(x: x, y: size.height))
            }
            for y in stride(from: CGFloat(0), through: size.height, by: 80) {
                cg.move(to: CGPoint(x: 0, y: y))
                cg.addLine(to: CGPoint(x: size.width, y: y))
            }
            cg.strokePath()

            let side = min(size.width, size.height) * 0.6
            UIColor.white.setStroke()
            cg.setLineWidth(12)
            cg.strokeEllipse(in: CGRect(x: (size.width - side) / 2, y: (size.height - side) / 2, width: side, height: side))

            UIColor.yellow.setStroke()
            cg.setLineWidth(16)
            cg.stroke(CGRect(origin: .zero, size: size).insetBy(dx: 8, dy: 8))

            let words = "\(title) \(Int(size.width))x\(Int(size.height))" as NSString
            words.draw(at: CGPoint(x: 48, y: 48), withAttributes: [
                NSAttributedStringKey.font: UIFont.boldSystemFont(ofSize: 56),
                NSAttributedStringKey.foregroundColor: UIColor.white,
            ])
        }
    }

    // MARK: - Checks

    private func videoSlots(_ controller: CallActiveController) -> [AirCallVideoSlot] {
        return ["air.call.video.remote", "air.call.video.own"].compactMap {
            controller.view.firstSubview(withIdentifier: $0) as? AirCallVideoSlot
        }
    }

    private func currentBox(_ controller: CallActiveController) -> AirCallVideoSlot? {
        return videoSlots(controller).first { !$0.isHidden && $0.isBox }
    }

    private func rowFrame(_ controller: CallActiveController) -> CGRect {
        let controls = ["air.call.mic", "air.call.speaker", "air.call.camera", "air.call.end"].compactMap {
            controller.view.firstSubview(withIdentifier: $0)?.superview
        }
        return controls.reduce(CGRect.null) { $0.union($1.convert($1.bounds, to: controller.view)) }
    }

    /// R1, R2, R5: the stage covers the screen and keeps the frame's shape; the box has the camera's
    /// shape and size and stands 16 inside the safe edges, 16 clear of the row and the head.
    private func checkStage(_ controller: CallActiveController,
                            device: Device,
                            scene: Scene,
                            ownFrame: CGSize = CGSize(width: 720, height: 1280)) {
        let view = controller.view!
        let name = "\(device.name) \(scene.rawValue)"
        guard let box = currentBox(controller),
              let stage = videoSlots(controller).first(where: { !$0.isHidden && !$0.isBox }) else {
            return XCTFail("\(name): no stage or no box")
        }
        XCTAssertEqual(stage.frame, view.bounds, name)
        XCTAssertEqual(box.frame.size, AirCallStage.ownSize(frame: ownFrame, stage: view.bounds.size), name)
        XCTAssertEqual(box.layer.cornerRadius, KQRadius.card, name)

        // Every video in the shape of its frame.
        for slot in [stage, box] {
            let frame = slot.frameSize ?? .zero
            let content = slot.subviews.first { $0 is UIImageView }?.frame ?? slot.layer.sublayers?.first?.frame ?? .zero
            XCTAssertGreaterThan(content.height, 0, name)
            XCTAssertEqual(content.width / max(content.height, 1), frame.width / max(frame.height, 1), accuracy: 0.01,
                           "\(name): a video is stretched")
            // Covering the box always; on the stage covering or fitting by R1.
            let mode: AirCallStage.ScaleMode = slot.isBox ? .fill : AirCallStage.scaleMode(frame: frame, stage: view.bounds.size)
            if mode == .fill {
                XCTAssertTrue(content.insetBy(dx: -0.5, dy: -0.5).contains(slot.bounds), "\(name): a video does not cover its place")
            }
            else {
                XCTAssertTrue(slot.bounds.insetBy(dx: -0.5, dy: -0.5).contains(content), "\(name): a video does not fit its place")
            }
        }

        let insets = view.safeAreaInsets
        let safe = UIEdgeInsetsInsetRect(view.bounds, insets).insetBy(dx: 16, dy: 16)
        XCTAssertTrue(safe.insetBy(dx: -0.5, dy: -0.5).contains(box.frame), "\(name): the box leaves the safe edges")
        let clearances = [rowFrame(controller), controller.headContentFrame()].map { $0.insetBy(dx: -15.5, dy: -15.5) }
        for clearance in clearances {
            XCTAssertFalse(clearance.intersects(box.frame), "\(name): the box is closer than 16 to the row or the head")
        }
        XCTAssertTrue(controller.headOverVideo, name)
    }

    /// R7: four 64 pt circles in equal columns, at most 384 wide, in the middle of the safe width.
    private func checkRow(_ controller: CallActiveController, device: Device) {
        let view = controller.view!
        let row = rowFrame(controller)
        let safe = UIEdgeInsetsInsetRect(view.bounds, view.safeAreaInsets)
        XCTAssertLessThanOrEqual(row.width, 384.5, device.name)
        XCTAssertEqual(row.midX, safe.midX, accuracy: 0.5, device.name)
        XCTAssertEqual(row.maxY, safe.maxY - 32, accuracy: 1, device.name)
        for identifier in ["air.call.mic", "air.call.speaker", "air.call.camera", "air.call.end"] {
            XCTAssertEqual(view.firstSubview(withIdentifier: identifier)?.bounds.size, CGSize(width: 64, height: 64), device.name)
        }
    }

    /// R10: the only words on the screen are the head's and the controls'.
    private func checkNoDebugWords(_ controller: CallActiveController, device: Device) {
        let row = ["air.call.mic", "air.call.speaker", "air.call.camera", "air.call.end"].compactMap {
            controller.view.firstSubview(withIdentifier: $0)?.superview
        }
        for label in controller.view.visibleLabels() where !(label.text ?? "").isEmpty {
            let owned = label.isDescendant(of: controller.topContainer) || row.contains { label.isDescendant(of: $0) }
            XCTAssertTrue(owned, "\(device.name): \"\(label.text ?? "")\" is on the call screen")
        }
    }

    // MARK: - Files

    private func save(_ image: UIImage, name: String) {
        guard let directory = ProcessInfo.processInfo.environment["KHANDAQ_CALL_SHOTS_DIR"], !directory.isEmpty else {
            return
        }
        let url = URL(fileURLWithPath: directory, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: nil)
        if let data = UIImagePNGRepresentation(image) {
            XCTAssertNoThrow(try data.write(to: url.appendingPathComponent("\(name).png")))
        }
    }

    /// The screens of one scene side by side, at one height, to look at together.
    private func contactSheet(_ images: [UIImage]) -> UIImage {
        let height: CGFloat = 600
        let gap: CGFloat = 24
        let widths = images.map { $0.size.width * height / $0.size.height }
        let size = CGSize(width: widths.reduce(gap) { $0 + $1 + gap }, height: height + 2 * gap)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor(white: 0.5, alpha: 1).setFill()
            context.fill(CGRect(origin: .zero, size: size))
            var x = gap
            for (image, width) in zip(images, widths) {
                image.draw(in: CGRect(x: x, y: gap, width: width, height: height))
                x += width + gap
            }
        }
    }
}

private extension UIView {
    func visibleLabels() -> [UILabel] {
        guard !isHidden, alpha > 0.01 else {
            return []
        }
        var labels = subviews.flatMap { $0.visibleLabels() }
        if let label = self as? UILabel {
            labels.append(label)
        }
        return labels
    }
}

private extension UIView {
    func firstSubview(withIdentifier identifier: String) -> UIView? {
        if accessibilityIdentifier == identifier {
            return self
        }
        for view in subviews {
            if let found = view.firstSubview(withIdentifier: identifier) {
                return found
            }
        }
        return nil
    }
}
