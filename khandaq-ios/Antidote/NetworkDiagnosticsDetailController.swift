// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

/// Loads network diagnostics off the main thread to avoid UI freezes (Android ProfileActivity parity).
final class NetworkDiagnosticsDetailController: UIViewController {
    private let theme: Theme
    private let activityIndicator = UIActivityIndicatorView(activityIndicatorStyle: .gray)
    private let statusLabel = UILabel()
    private var textController: TextViewController?

    init(theme: Theme) {
        self.theme = theme
        super.init(nibName: nil, bundle: nil)
        title = String(localized: "network_connections_title")
        edgesForExtendedLayout = UIRectEdge()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = theme.colorForType(.NormalBackground)

        statusLabel.font = UIFont.preferredFont(forTextStyle: .footnote)
        statusLabel.textColor = theme.colorForType(.FriendCellStatus)
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0
        statusLabel.text = String(localized: "network_connections_loading")

        view.addSubview(statusLabel)
        view.addSubview(activityIndicator)

        statusLabel.snp.makeConstraints {
            $0.center.equalToSuperview()
            $0.leading.trailing.equalToSuperview().inset(24)
        }

        activityIndicator.snp.makeConstraints {
            $0.centerX.equalToSuperview()
            $0.bottom.equalTo(statusLabel.snp.top).offset(-12)
        }

        activityIndicator.startAnimating()
        loadDiagnostics()
    }

    private func loadDiagnostics() {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let quality = ConnectionQualityMonitor.shared
            let body = """
            \(String(localized: "network_connections_beta_notice"))

            --- event log ---
            \(NetworkDiagnosticsLog.snapshot())

            --- runtime ---
            connection_quality=\(quality.level.rawValue)
            estimated_rtt_ms=\(quality.estimatedRttMs)
            """

            DispatchQueue.main.async {
                guard let self = self else {
                    return
                }

                self.activityIndicator.stopAnimating()
                self.statusLabel.isHidden = true

                let controller = TextViewController(
                        plainText: body,
                        backgroundColor: self.theme.colorForType(.NormalBackground),
                        titleColor: self.theme.colorForType(.NormalText),
                        textColor: self.theme.colorForType(.NormalText))

                self.addChildViewController(controller)
                self.view.addSubview(controller.view)
                controller.view.snp.makeConstraints {
                    $0.edges.equalTo(self.view)
                }
                controller.didMove(toParentViewController: self)
                self.textController = controller
            }
        }
    }
}
