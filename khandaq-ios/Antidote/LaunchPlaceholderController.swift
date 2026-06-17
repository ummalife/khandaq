// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

class LaunchPlaceholderController: UIViewController {
    private let logoView = UIImageView(image: UIImage(named: "login-logo"))
    private let spinner: UIActivityIndicatorView = {
        if #available(iOS 13.0, *) {
            return UIActivityIndicatorView(activityIndicatorStyle: .medium)
        }
        return UIActivityIndicatorView(activityIndicatorStyle: .gray)
    }()
    private let statusLabel = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()

        view.backgroundColor = ThemeAppearance.placeholderBackgroundColor

        logoView.translatesAutoresizingMaskIntoConstraints = false
        logoView.contentMode = .scaleAspectFit

        spinner.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.text = String(localized: "launch_loading")
        statusLabel.font = UIFont.preferredFont(forTextStyle: .subheadline)
        statusLabel.textColor = ThemeAppearance.placeholderSecondaryTextColor
        statusLabel.textAlignment = .center

        view.addSubview(logoView)
        view.addSubview(spinner)
        view.addSubview(statusLabel)

        NSLayoutConstraint.activate([
            logoView.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            logoView.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: -36),
            logoView.widthAnchor.constraint(equalToConstant: 160),
            logoView.heightAnchor.constraint(equalToConstant: 160),

            spinner.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            spinner.topAnchor.constraint(equalTo: logoView.bottomAnchor, constant: 28),

            statusLabel.topAnchor.constraint(equalTo: spinner.bottomAnchor, constant: 12),
            statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
        ])

        spinner.startAnimating()
    }
}
