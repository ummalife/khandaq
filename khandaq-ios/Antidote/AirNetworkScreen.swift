// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

// The state of our own node in words (redesign stage 3, E4), opened from the quiet line under the name
// on the Air screen: what the state means for messages, whether the phone has internet and how the
// link goes, and a way to connect again. No addresses and no probe codes; the technical log is one
// row further, for support.

/// What the Network screen says for one state of our node.
struct AirNetworkReport: Equatable {
    let title: String
    let meaning: String
    let internet: String
    let link: String
    /// Messages leave at once: the dot is the live colour.
    let isLive: Bool
    /// "Connect again" helps only while the phone has internet and the node has not found the network.
    let offersReconnect: Bool
    /// Red only when there is no network at all, as the line on the Air screen.
    let isAlarm: Bool

    init(state: AirNetworkState) {
        switch state {
            case .direct:
                title = String(localized: "air_network_title_direct")
                meaning = String(localized: "air_network_meaning_direct")
                link = String(localized: "air_network_link_direct")
            case .relay:
                title = String(localized: "air_network_title_relay")
                meaning = String(localized: "air_network_meaning_relay")
                link = String(localized: "air_network_link_relay")
            case .connecting:
                title = String(localized: "air_network_title_connecting")
                meaning = String(localized: "air_network_meaning_connecting")
                link = String(localized: "air_network_link_none")
            case .offline:
                title = String(localized: "air_network_title_offline")
                meaning = String(localized: "air_network_meaning_offline")
                link = String(localized: "air_network_link_none")
        }
        internet = String(localized: state == .offline ? "air_network_internet_no" : "air_network_internet_yes")
        isLive = state == .direct || state == .relay
        offersReconnect = state == .connecting
        isAlarm = state == .offline
    }
}

/// Settings-like screen of the node's state; it follows the state while it is open.
final class AirNetworkController: AirScrollController {
    private let theme: Theme
    private let stateProvider: () -> AirNetworkState
    private let reconnect: () -> Void

    private let dot = UIView()
    private let titleLabel = AirKit.label(.heading, color: KQColor.ink, lines: 0)
    private let meaningLabel = AirKit.body("")
    private let internetRow = AirSettingRow(title: String(localized: "air_network_internet"), opens: false)
    private let linkRow = AirSettingRow(title: String(localized: "air_network_link"), opens: false)
    private let reconnectButton = AirButton(style: .normal, title: String(localized: "air_network_reconnect"))
    private var reconnectSection: UIStackView?
    private var observer: NSObjectProtocol?
    private(set) var report: AirNetworkReport?

    /// `state` reads our node now; `reconnect` asks for a reconnect at once.
    init(theme: Theme,
         state: @escaping () -> AirNetworkState = { AirSelfNetwork.state },
         reconnect: @escaping () -> Void = { ReconnectBackoffCoordinator.shared.scheduleReconnect(reason: .manual, urgent: true) }) {
        self.theme = theme
        self.stateProvider = state
        self.reconnect = reconnect
        super.init(nibName: nil, bundle: nil)
        title = String(localized: "air_settings_network")
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        if let observer = observer {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.network.screen"

        dot.layer.cornerRadius = KQSize.dot / 2
        dot.snp.makeConstraints {
            $0.size.equalTo(KQSize.dot)
        }
        titleLabel.accessibilityIdentifier = "air.network.title"
        titleLabel.accessibilityTraits = UIAccessibilityTraitHeader
        // The dot keeps to the first line of the title, which may wrap at the large text sizes.
        let dotColumn = UIView()
        dotColumn.addSubview(dot)
        dot.snp.makeConstraints {
            $0.leading.trailing.equalToSuperview()
            $0.top.equalToSuperview().offset(max(0, (ceil(KQFont.lineHeight(.heading)) - KQSize.dot) / 2))
            $0.bottom.lessThanOrEqualToSuperview()
        }
        let titleRow = UIStackView(arrangedSubviews: [dotColumn, titleLabel])
        titleRow.axis = .horizontal
        titleRow.alignment = .top
        titleRow.spacing = KQSpace.x8
        meaningLabel.accessibilityIdentifier = "air.network.meaning"
        addSection(nil, views: [titleRow, meaningLabel])

        internetRow.accessibilityIdentifier = "air.network.internet"
        linkRow.accessibilityIdentifier = "air.network.link"
        addSection(nil, rows: [internetRow, linkRow])

        reconnectButton.accessibilityIdentifier = "air.network.reconnect"
        reconnectButton.addTarget(self, action: #selector(reconnectTapped), for: .touchUpInside)
        // The whole section goes while the button does not help, so no gap is left in its place.
        reconnectSection = addSection(nil, views: [reconnectButton])

        let log = AirSettingRow(title: String(localized: "air_network_log"))
        log.accessibilityIdentifier = "air.network.log"
        log.addTarget(self, action: #selector(showLog), for: .touchUpInside)
        addSection(nil, rows: [log], note: String(localized: "air_network_log_note"))

        observer = NotificationCenter.default.addObserver(forName: AirSelfNetwork.didChange, object: nil, queue: .main) { [weak self] _ in
            self?.refresh(animated: true)
        }
        refresh(animated: false)
    }

    /// Shows the state now; a change cross-fades the words while the dot stays where it is.
    func refresh(animated: Bool) {
        let next = AirNetworkReport(state: stateProvider())
        guard next != report else {
            return
        }
        report = next
        let apply = {
            self.titleLabel.text = next.title
            self.titleLabel.textColor = next.isAlarm ? KQColor.danger : KQColor.ink
            self.meaningLabel.text = next.meaning
            self.internetRow.setValue(next.internet)
            self.linkRow.setValue(next.link)
            self.reconnectSection?.isHidden = !next.offersReconnect
        }
        dot.backgroundColor = next.isAlarm ? KQColor.danger : (next.isLive ? KQColor.live : KQColor.ink3)
        if animated && view.window != nil {
            UIView.transition(with: view, duration: KQMotion.duration(KQMotion.state), options: .transitionCrossDissolve, animations: apply, completion: nil)
        }
        else {
            apply()
        }
    }

    @objc private func reconnectTapped() {
        reconnect()
        _ = AirToast.show(String(localized: "air_network_reconnect_toast"), in: view)
    }

    @objc private func showLog() {
        navigationController?.pushViewController(NetworkDiagnosticsDetailController(theme: theme), animated: true)
    }
}
