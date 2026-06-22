// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

protocol WalkthroughControllerDelegate: class {
    func walkthroughControllerCreateAccount(_ controller: WalkthroughController)
    func walkthroughControllerImportProfile(_ controller: WalkthroughController)
}

/// KHANDAQ design (Figma): first-launch onboarding carousel shown when no profile exists yet.
/// Three intro pages (no-number → encryption → decentralized) with a top segmented progress bar;
/// the last page offers "Создать аккаунт" / "Импорт профиля".
class WalkthroughController: LoginBaseController {
    private struct Page {
        let title: String
        let subtitle: String
        let image: String
    }

    weak var delegate: WalkthroughControllerDelegate?

    private let pages: [Page] = [
        Page(title: String(localized: "onboarding_no_number_title"),
             subtitle: String(localized: "onboarding_no_number_subtitle"),
             image: "ob-nonumber"),
        Page(title: String(localized: "onboarding_encryption_title"),
             subtitle: String(localized: "onboarding_encryption_subtitle"),
             image: "ob-crypto"),
        Page(title: String(localized: "onboarding_decentralized_title"),
             subtitle: String(localized: "onboarding_decentralized_subtitle"),
             image: "ob-peer"),
    ]

    private var scrollView: UIScrollView!
    private var segments: [UIView] = []
    private var primaryButton: RoundedButton!
    private var importButton: UIButton!
    private var currentPage = 0

    override func loadView() {
        super.loadView()
        navigationController?.setNavigationBarHidden(true, animated: false)
        buildSegments()
        buildButtons()
        buildScrollView()
        updateForPage(0)
    }

    private func buildSegments() {
        let container = UIView()
        view.addSubview(container)
        container.snp.makeConstraints {
            $0.top.equalTo(view.safeAreaLayoutGuide).offset(12.0)
            $0.leading.trailing.equalTo(view).inset(16.0)
            $0.height.equalTo(4.0)
        }
        for index in 0..<pages.count {
            let seg = UIView()
            seg.backgroundColor = theme.colorForType(.SeparatorsAndBorders)
            seg.layer.cornerRadius = 2.0
            container.addSubview(seg)
            segments.append(seg)
            seg.snp.makeConstraints {
                $0.top.bottom.equalTo(container)
                $0.width.equalTo(container).multipliedBy(1.0 / CGFloat(pages.count)).offset(-4.0)
                if index == 0 {
                    $0.leading.equalTo(container)
                } else {
                    $0.leading.equalTo(segments[index - 1].snp.trailing).offset(6.0)
                }
            }
        }
    }

    private func buildButtons() {
        primaryButton = RoundedButton(theme: theme, type: .login)
        primaryButton.addTarget(self, action: #selector(primaryPressed), for: .touchUpInside)
        view.addSubview(primaryButton)
        primaryButton.snp.makeConstraints {
            $0.leading.trailing.equalTo(view).inset(16.0)
            $0.bottom.equalTo(view.safeAreaLayoutGuide).offset(-12.0)
            $0.height.equalTo(56.0)
        }

        importButton = UIButton(type: .system)
        importButton.setTitle(String(localized: "import_profile"), for: .normal)
        importButton.setTitleColor(theme.colorForType(.LinkText), for: .normal)
        importButton.titleLabel?.font = UIFont.systemFont(ofSize: 16.0, weight: .medium)
        importButton.addTarget(self, action: #selector(importPressed), for: .touchUpInside)
        importButton.isHidden = true
        view.addSubview(importButton)
        importButton.snp.makeConstraints {
            $0.leading.trailing.equalTo(view).inset(16.0)
            $0.bottom.equalTo(primaryButton.snp.top).offset(-10.0)
            $0.height.equalTo(44.0)
        }
    }

    private func buildScrollView() {
        scrollView = UIScrollView()
        scrollView.isPagingEnabled = true
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.delegate = self
        view.insertSubview(scrollView, at: 0)
        scrollView.snp.makeConstraints {
            $0.top.equalTo(view.safeAreaLayoutGuide).offset(36.0)
            $0.leading.trailing.equalTo(view)
            $0.bottom.equalTo(importButton.snp.top).offset(-10.0)
        }

        let content = UIView()
        scrollView.addSubview(content)
        content.snp.makeConstraints {
            $0.edges.equalTo(scrollView)
            $0.height.equalTo(scrollView)
        }

        var previous: UIView?
        for page in pages {
            let pageView = makePageView(page)
            content.addSubview(pageView)
            pageView.snp.makeConstraints {
                $0.top.bottom.equalTo(content)
                $0.width.equalTo(view)
                if let previous = previous {
                    $0.leading.equalTo(previous.snp.trailing)
                } else {
                    $0.leading.equalTo(content)
                }
            }
            previous = pageView
        }
        previous?.snp.makeConstraints { $0.trailing.equalTo(content) }
    }

    private func makePageView(_ page: Page) -> UIView {
        let container = UIView()

        let titleLabel = UILabel()
        titleLabel.text = page.title
        titleLabel.font = UIFont.systemFont(ofSize: 24.0, weight: .semibold)
        titleLabel.textColor = theme.colorForType(.NormalText)
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 0
        container.addSubview(titleLabel)

        let subtitleLabel = UILabel()
        subtitleLabel.text = page.subtitle
        subtitleLabel.font = UIFont.systemFont(ofSize: 16.0)
        subtitleLabel.textColor = theme.colorForType(.FriendCellStatus)
        subtitleLabel.textAlignment = .center
        subtitleLabel.numberOfLines = 0
        container.addSubview(subtitleLabel)

        let imageView = UIImageView(image: UIImage(named: page.image))
        imageView.contentMode = .scaleAspectFit
        container.addSubview(imageView)

        titleLabel.snp.makeConstraints {
            $0.top.equalTo(container).offset(24.0)
            $0.leading.trailing.equalTo(container).inset(16.0)
        }
        subtitleLabel.snp.makeConstraints {
            $0.top.equalTo(titleLabel.snp.bottom).offset(8.0)
            $0.leading.trailing.equalTo(container).inset(24.0)
        }
        imageView.snp.makeConstraints {
            $0.centerX.equalTo(container)
            $0.top.greaterThanOrEqualTo(subtitleLabel.snp.bottom).offset(24.0)
            $0.centerY.equalTo(container).offset(40.0)
            $0.width.lessThanOrEqualTo(container).multipliedBy(0.62)
            $0.height.lessThanOrEqualTo(container).multipliedBy(0.4)
        }

        return container
    }

    private func updateForPage(_ page: Int) {
        currentPage = page
        for (index, seg) in segments.enumerated() {
            seg.backgroundColor = index <= page
                ? theme.colorForType(.LinkText)
                : theme.colorForType(.SeparatorsAndBorders)
        }
        let isLast = page == pages.count - 1
        primaryButton.setTitle(String(localized: isLast ? "onboarding_create_account" : "onboarding_next"),
                               for: UIControlState())
        importButton.isHidden = !isLast
    }

    @objc private func primaryPressed() {
        if currentPage < pages.count - 1 {
            let next = currentPage + 1
            scrollView.setContentOffset(CGPoint(x: CGFloat(next) * view.bounds.width, y: 0), animated: true)
            updateForPage(next)
        } else {
            delegate?.walkthroughControllerCreateAccount(self)
        }
    }

    @objc private func importPressed() {
        delegate?.walkthroughControllerImportProfile(self)
    }
}

extension WalkthroughController: UIScrollViewDelegate {
    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        guard view.bounds.width > 0 else { return }
        let page = Int(round(scrollView.contentOffset.x / view.bounds.width))
        updateForPage(max(0, min(pages.count - 1, page)))
    }
}
