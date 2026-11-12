// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

/// The window of the in-app band (redesign stage 3, E22), above the app's windows and under the PIN
/// lock. Since the app adopted scenes a window made from a frame belongs to no scene, so nothing in
/// this one ever showed (neither the connection pill nor the banners); it joins the app's scene and
/// takes the app's theme. It is up only while a band shows, so that it never decides the look of the
/// status bar over the screen under it, and touches outside the band go to the app.
final class NotificationWindow: UIWindow {
    /// How long a band stays when nobody touches it.
    static let bandSeconds: TimeInterval = 4.5

    /// The states the session reports for the connection pill, which E4 retired: our node is told by
    /// the line under the name on the Air screen and by the hint under the message field.
    enum ConnectionPillState {
        case connecting
        case connected
        case offline
        case degraded
        case stable
    }

    private var bannerView: NotificationBannerView?
    private var bannerDismissTimer: Timer?

    init() {
        super.init(frame: UIScreen.main.bounds)

        windowLevel = UIWindowLevelStatusBar + 500
        backgroundColor = .clear
        windowScene = AppDelegate.shared.window?.windowScene
            ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        // The band resolves the design tokens in the app's theme, not only the system's.
        overrideUserInterfaceStyle = ThemeAppearance.windowInterfaceStyle
        AppLanguage.applyDirection(to: self)
        isHidden = true

        let root = UIViewController()
        root.view = ViewPassingGestures()
        root.view.backgroundColor = .clear
        rootViewController = root
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        for subview in subviews {
            let converted = convert(point, to: subview)

            if subview.hitTest(converted, with: event) != nil {
                return true
            }
        }

        return false
    }

    // MARK: - In-app band

    /// Shows a band in place of the one up now. `tile` nil shows the words alone; `hint` says what a tap
    /// opens.
    func showBanner(title: String, body: String, tile: AirTileView.Style?, photo: UIImage?, hint: String, onTap: @escaping () -> Void) {
        dismissBanner(animated: false)
        isHidden = false

        let banner = NotificationBannerView(title: title, body: body, tile: tile, photo: photo, hint: hint)
        banner.onTap = { [weak self] in
            self?.dismissBanner(animated: true)
            onTap()
        }
        banner.onDismiss = { [weak self] in
            self?.dismissBanner(animated: true)
        }
        addSubview(banner)

        banner.snp.makeConstraints {
            $0.top.equalTo(self.safeAreaLayoutGuide.snp.top).offset(KQSpace.x8)
            $0.leading.equalTo(self.safeAreaLayoutGuide).offset(KQSpace.x12)
            $0.trailing.equalTo(self.safeAreaLayoutGuide).offset(-KQSpace.x12)
        }

        bannerView = banner

        // It comes down from above the safe area.
        layoutIfNeeded()
        banner.transform = CGAffineTransform(translationX: 0, y: -(banner.frame.maxY + KQSpace.x20))
        UIView.animate(withDuration: KQMotion.duration(KQMotion.layout), delay: 0, options: .curveEaseOut, animations: {
            banner.transform = .identity
        }, completion: nil)

        bannerDismissTimer?.invalidate()
        bannerDismissTimer = Timer.scheduledTimer(withTimeInterval: NotificationWindow.bandSeconds, repeats: false) { [weak self] _ in
            self?.dismissBanner(animated: true)
        }
    }

    func dismissBanner(animated: Bool) {
        bannerDismissTimer?.invalidate()
        bannerDismissTimer = nil

        guard let banner = bannerView else {
            return
        }
        bannerView = nil

        let removal = { [weak self] in
            banner.removeFromSuperview()
            // Hidden again unless the next band already took its place.
            if let self = self, self.bannerView == nil {
                self.isHidden = true
            }
        }

        guard animated else {
            removal()
            return
        }

        UIView.animate(withDuration: KQMotion.duration(KQMotion.state), delay: 0, options: .curveEaseIn, animations: {
            banner.transform = CGAffineTransform(translationX: 0, y: -(banner.frame.maxY + KQSpace.x20))
            banner.alpha = 0.0
        }, completion: { _ in
            removal()
        })
    }
}

/// The band an incoming message, request or invitation comes as while the app is open (E22): under the
/// safe area, the tile the Air screen shows for the chat, the sender and then the text, on the raised
/// surface. A tap opens what it tells of; a swipe up (or the VoiceOver escape) puts it away, and it
/// leaves by itself.
final class NotificationBannerView: UIView {
    var onTap: (() -> Void)?
    var onDismiss: (() -> Void)?

    init(title: String, body: String, tile: AirTileView.Style?, photo: UIImage?, hint: String) {
        super.init(frame: .zero)
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitButton
        accessibilityIdentifier = "air.banner"
        accessibilityLabel = [title, body].filter { !$0.isEmpty }.joined(separator: ", ")
        accessibilityHint = hint

        let backdrop = UIView()
        backdrop.backgroundColor = KQColor.raised
        backdrop.layer.cornerRadius = KQRadius.sheet
        backdrop.layer.masksToBounds = true
        backdrop.isUserInteractionEnabled = false
        addSubview(backdrop)

        // Lifted off the screen under it by a shadow rather than a frame.
        layer.cornerRadius = KQRadius.sheet
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.18
        layer.shadowRadius = KQSpace.x12
        layer.shadowOffset = CGSize(width: 0, height: KQSpace.x4)

        let tileView = AirTileView(side: KQSize.tilePhone)
        if let tile = tile {
            tileView.configure(style: tile, photo: photo)
        }
        tileView.isHidden = tile == nil
        addSubview(tileView)

        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = KQFont.font(.label, weight: 600)
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = KQColor.ink
        titleLabel.numberOfLines = 1
        addSubview(titleLabel)

        let bodyLabel = UILabel()
        bodyLabel.text = body
        bodyLabel.font = KQFont.font(.label, weight: 400)
        bodyLabel.adjustsFontForContentSizeCategory = true
        bodyLabel.textColor = KQColor.ink2
        bodyLabel.numberOfLines = 2
        bodyLabel.isHidden = body.isEmpty
        addSubview(bodyLabel)

        backdrop.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }

        tileView.snp.makeConstraints {
            $0.leading.equalToSuperview().offset(KQSpace.x12)
            $0.centerY.equalToSuperview()
            $0.size.equalTo(KQSize.tilePhone)
            $0.top.greaterThanOrEqualToSuperview().offset(KQSpace.x12)
            $0.bottom.lessThanOrEqualToSuperview().offset(-KQSpace.x12)
        }

        titleLabel.snp.makeConstraints {
            if tile == nil {
                $0.leading.equalToSuperview().offset(KQSpace.x16)
            }
            else {
                $0.leading.equalTo(tileView.snp.trailing).offset(KQSpace.x12)
            }
            $0.trailing.equalToSuperview().offset(-KQSpace.x16)
            $0.top.equalToSuperview().offset(KQSpace.x12)
        }

        bodyLabel.snp.makeConstraints {
            $0.leading.equalTo(titleLabel)
            $0.trailing.equalTo(titleLabel)
            $0.top.equalTo(titleLabel.snp.bottom).offset(KQSpace.x2)
        }
        // The band ends under the last line it shows.
        let last: UIView = body.isEmpty ? titleLabel : bodyLabel
        last.snp.makeConstraints {
            $0.bottom.equalToSuperview().offset(-KQSpace.x12)
        }

        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped))
        addGestureRecognizer(tap)

        let pan = UIPanGestureRecognizer(target: self, action: #selector(panned(_:)))
        addGestureRecognizer(pan)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func accessibilityActivate() -> Bool {
        onTap?()
        return true
    }

    override func accessibilityPerformEscape() -> Bool {
        onDismiss?()
        return true
    }

    @objc private func tapped() {
        onTap?()
    }

    @objc private func panned(_ gesture: UIPanGestureRecognizer) {
        let translationY = gesture.translation(in: self).y
        switch gesture.state {
            case .changed:
                // Up only.
                transform = CGAffineTransform(translationX: 0, y: min(0, translationY))
            case .ended, .cancelled:
                if translationY < -KQSpace.x20 {
                    onDismiss?()
                }
                else {
                    UIView.animate(withDuration: KQMotion.duration(KQMotion.state)) {
                        self.transform = .identity
                    }
                }
            default:
                break
        }
    }
}
