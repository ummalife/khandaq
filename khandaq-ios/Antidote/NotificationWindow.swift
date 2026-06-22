// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private struct Constants {
    static let AnimationDuration = 0.3
    static let ConnectingBlinkPeriod = 1.0
}

class NotificationWindow: UIWindow {
    fileprivate let theme: Theme

    fileprivate var connectingView: UIView!
    fileprivate var connectingViewLabel: UILabel!
    fileprivate var connectingViewTopConstraint: Constraint!

    // KHANDAQ: custom in-app banner (replaces LNNotificationsUI, which ignored the Dynamic Island).
    fileprivate var bannerView: NotificationBannerView?
    fileprivate var bannerDismissTimer: Timer?

    fileprivate var connectionAutoHideTimer: Timer?

    enum ConnectionPillState {
        case connecting   // blue, "Connecting…"
        case connected    // green, "Online", then auto-hides
        case offline      // red, "No connection"
        case degraded     // yellow, "Weak connection", auto-hides (shown periodically while weak)
        case stable       // green, "Connection stable", auto-hides once on recovery
    }

    init(theme: Theme) {
        self.theme = theme

        super.init(frame: UIScreen.main.bounds)

        windowLevel = UIWindowLevelStatusBar + 500
        backgroundColor = .clear
        isHidden = false

        createRootViewController()
        createConnectingView()
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

    // MARK: - In-app banner

    func showBanner(title: String, body: String, icon: UIImage?, onTap: @escaping () -> Void) {
        dismissBanner(animated: false)

        let banner = NotificationBannerView(theme: theme, title: title, body: body, icon: icon)
        banner.onTap = { [weak self] in
            self?.dismissBanner(animated: true)
            onTap()
        }
        banner.onDismiss = { [weak self] in
            self?.dismissBanner(animated: true)
        }
        addSubview(banner)

        banner.snp.makeConstraints {
            $0.top.equalTo(self.safeAreaLayoutGuide.snp.top).offset(8.0)
            $0.leading.equalTo(self).offset(10.0)
            $0.trailing.equalTo(self).offset(-10.0)
        }

        bannerView = banner

        // Slide in from above the safe area.
        layoutIfNeeded()
        banner.transform = CGAffineTransform(translationX: 0, y: -(banner.frame.maxY + 20))
        UIView.animate(withDuration: Constants.AnimationDuration, delay: 0, options: .curveEaseOut, animations: {
            banner.transform = .identity
        }, completion: nil)

        bannerDismissTimer?.invalidate()
        bannerDismissTimer = Timer.scheduledTimer(withTimeInterval: 4.5, repeats: false) { [weak self] _ in
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

        let removal = {
            banner.removeFromSuperview()
        }

        guard animated else {
            removal()
            return
        }

        UIView.animate(withDuration: Constants.AnimationDuration, delay: 0, options: .curveEaseIn, animations: {
            banner.transform = CGAffineTransform(translationX: 0, y: -(banner.frame.maxY + 20))
            banner.alpha = 0.0
        }, completion: { _ in
            removal()
        })
    }

    func setConnectionState(_ state: ConnectionPillState, animated: Bool) {
        connectionAutoHideTimer?.invalidate()
        connectionAutoHideTimer = nil

        connectingViewLabel.layer.removeAllAnimations()
        connectingViewLabel.alpha = 1.0

        switch state {
            case .connecting:
                connectingView.backgroundColor = theme.colorForType(.ConnectingBackground)
                connectingViewLabel.text = String(localized: "connecting_label")
                showConnectingView(true, animated: animated)
                startConnectingViewAnimation()   // gentle blink only while actually connecting

            case .offline:
                connectingView.backgroundColor = UIColor(red: 0.83, green: 0.18, blue: 0.18, alpha: 1.0)
                connectingViewLabel.text = String(localized: "conn_state_offline")
                showConnectingView(true, animated: animated)

            case .connected:
                connectingView.backgroundColor = UIColor(red: 0.17, green: 0.6, blue: 0.33, alpha: 1.0)
                connectingViewLabel.text = String(localized: "conn_state_connected")
                showConnectingView(true, animated: animated)
                // Flash "Online" briefly, then slide away.
                connectionAutoHideTimer = Timer.scheduledTimer(withTimeInterval: 1.6, repeats: false) { [weak self] _ in
                    self?.showConnectingView(false, animated: true)
                }

            case .degraded:
                connectingView.backgroundColor = UIColor(red: 0.91, green: 0.65, blue: 0.10, alpha: 1.0)
                connectingViewLabel.text = String(localized: "conn_state_weak")
                showConnectingView(true, animated: animated)
                // A periodic nag so the user notices their link is unstable.
                connectionAutoHideTimer = Timer.scheduledTimer(withTimeInterval: 3.0, repeats: false) { [weak self] _ in
                    self?.showConnectingView(false, animated: true)
                }

            case .stable:
                connectingView.backgroundColor = UIColor(red: 0.17, green: 0.6, blue: 0.33, alpha: 1.0)
                connectingViewLabel.text = String(localized: "conn_state_stable")
                showConnectingView(true, animated: animated)
                connectionAutoHideTimer = Timer.scheduledTimer(withTimeInterval: 1.8, repeats: false) { [weak self] _ in
                    self?.showConnectingView(false, animated: true)
                }
        }
    }

    func showConnectingView(_ show: Bool, animated: Bool) {
        let showPreparation = { [unowned self] in
            self.connectingView.isHidden = false
        }

        let showBlock = { [unowned self] in
            // Drop the pill below a standard 44pt navigation bar so it stops covering the screen
            // title (the chat header name was hidden behind it). It floats just under the header.
            self.connectingViewTopConstraint.update(offset: 50.0)
            self.layoutIfNeeded()
        }

        let showCompletion = {}

        let hidePreparation = {}

        let hideBlock = { [unowned self] in
            // Tuck the pill up above the safe-area top so it slides out under the island.
            self.connectingViewTopConstraint.update(offset: -(self.connectingView.frame.size.height + 12.0))
            self.layoutIfNeeded()
            self.connectingViewLabel.layer.removeAllAnimations()
        }

        let hideCompletion = { [unowned self] in
            self.connectingView.isHidden = true
        }

        show ? showPreparation() : hidePreparation()

        if animated {
            UIView.animate(withDuration: Constants.AnimationDuration, animations: {
                show ? showBlock() : hideBlock()
            }, completion: { finished in
                show ? showCompletion() : hideCompletion()
            })
        }
        else {
            show ? showBlock() : hideBlock()
            show ? showCompletion() : hideCompletion()
        }
    }
}

private extension NotificationWindow {
    func createRootViewController() {
        rootViewController = UIViewController()
        rootViewController!.view = ViewPassingGestures()
        rootViewController!.view.backgroundColor = .clear
    }

    func createConnectingView() {
        // KHANDAQ: a compact centered pill BELOW the Dynamic Island / notch instead of a full-width
        // bar painted over the whole status-bar area (which on iPhone 17 / iOS 26 covered the island
        // in solid blue and collided with the clock). Android-style subtle "Connecting…" chip.
        connectingView = UIView()
        connectingView.backgroundColor = theme.colorForType(.ConnectingBackground)
        connectingView.layer.cornerRadius = 13.0
        connectingView.layer.masksToBounds = true
        connectingView.isHidden = true   // shown only via setConnectionState
        addSubview(connectingView)

        connectingViewLabel = UILabel()
        connectingViewLabel.textColor = theme.colorForType(.ConnectingText)
        connectingViewLabel.backgroundColor = .clear
        let quality = ConnectionQualityMonitor.shared.level.localizedName
        connectingViewLabel.text = String(localized: "connecting_label") + " · " + quality
        connectingViewLabel.textAlignment = .center
        connectingViewLabel.font = UIFont.khandaqFontWithSize(12.0, weight: .light)
        connectingView.addSubview(connectingViewLabel)

        connectingView.snp.makeConstraints {
            // Anchor below the safe-area top (under the island), centered, sized to its content.
            connectingViewTopConstraint = $0.top.equalTo(self.safeAreaLayoutGuide.snp.top).offset(6.0).constraint
            $0.centerX.equalTo(self)
            $0.height.equalTo(26.0)
            $0.leading.greaterThanOrEqualTo(self).offset(12.0)
            $0.trailing.lessThanOrEqualTo(self).offset(-12.0)
        }

        connectingViewLabel.snp.makeConstraints {
            $0.top.bottom.equalTo(connectingView)
            $0.leading.equalTo(connectingView).offset(14.0)
            $0.trailing.equalTo(connectingView).offset(-14.0)
        }
    }

    func startConnectingViewAnimation() {
        connectingViewLabel.alpha = 0.0
        UIView.animate(withDuration: Constants.ConnectingBlinkPeriod, delay: 0.0, options: [.repeat, .autoreverse], animations: {
            self.connectingViewLabel.alpha = 1.0
        }, completion: nil)
    }

    func stopConnectingViewAnimation() {
        stopConnectingViewAnimation()
    }
}

/// KHANDAQ: a safe-area-aware in-app notification banner (rounded card under the Dynamic Island /
/// notch). Replaces LNNotificationsUI, which painted at y=0 and collided with the island.
final class NotificationBannerView: UIView {
    var onTap: (() -> Void)?
    var onDismiss: (() -> Void)?

    init(theme: Theme, title: String, body: String, icon: UIImage?) {
        super.init(frame: .zero)

        let backdrop = UIView()
        backdrop.backgroundColor = UIColor(red: 0.13, green: 0.13, blue: 0.15, alpha: 0.98)
        backdrop.layer.cornerRadius = 18.0
        backdrop.layer.masksToBounds = true
        backdrop.isUserInteractionEnabled = false
        addSubview(backdrop)

        layer.cornerRadius = 18.0
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.25
        layer.shadowRadius = 12.0
        layer.shadowOffset = CGSize(width: 0, height: 4)

        let iconView = UIImageView(image: icon)
        iconView.contentMode = .scaleAspectFill
        iconView.layer.cornerRadius = 18.0
        iconView.layer.masksToBounds = true
        iconView.backgroundColor = UIColor(white: 1.0, alpha: 0.1)
        addSubview(iconView)

        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = UIFont.systemFont(ofSize: 15, weight: .semibold)
        titleLabel.textColor = .white
        titleLabel.numberOfLines = 1
        addSubview(titleLabel)

        let bodyLabel = UILabel()
        bodyLabel.text = body
        bodyLabel.font = UIFont.systemFont(ofSize: 14)
        bodyLabel.textColor = UIColor(white: 1.0, alpha: 0.85)
        bodyLabel.numberOfLines = 2
        addSubview(bodyLabel)

        backdrop.snp.makeConstraints { $0.edges.equalToSuperview() }

        iconView.snp.makeConstraints {
            $0.leading.equalToSuperview().offset(12)
            $0.centerY.equalToSuperview()
            $0.width.height.equalTo(36)
            $0.top.greaterThanOrEqualToSuperview().offset(10)
            $0.bottom.lessThanOrEqualToSuperview().offset(-10)
        }

        titleLabel.snp.makeConstraints {
            $0.leading.equalTo(iconView.snp.trailing).offset(10)
            $0.trailing.equalToSuperview().offset(-14)
            $0.top.equalToSuperview().offset(11)
        }

        bodyLabel.snp.makeConstraints {
            $0.leading.equalTo(titleLabel)
            $0.trailing.equalTo(titleLabel)
            $0.top.equalTo(titleLabel.snp.bottom).offset(2)
            $0.bottom.equalToSuperview().offset(-11)
        }

        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped))
        addGestureRecognizer(tap)

        let pan = UIPanGestureRecognizer(target: self, action: #selector(panned(_:)))
        addGestureRecognizer(pan)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    @objc private func tapped() {
        onTap?()
    }

    @objc private func panned(_ gesture: UIPanGestureRecognizer) {
        let translationY = gesture.translation(in: self).y
        switch gesture.state {
            case .changed:
                // Allow dragging up only.
                transform = CGAffineTransform(translationX: 0, y: min(0, translationY))
            case .ended, .cancelled:
                if translationY < -20 {
                    onDismiss?()
                }
                else {
                    UIView.animate(withDuration: 0.2) { self.transform = .identity }
                }
            default:
                break
        }
    }
}
