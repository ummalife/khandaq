// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

/// KHANDAQ design (Figma): the chat "+" opens a small rounded popup anchored above it
/// (Галерея / Камера / Аудио / Геолокация), instead of a native bottom action sheet.
final class AttachmentMenuController: UIViewController {
    struct Item {
        let title: String
        let systemImage: String
        let action: () -> Void
    }

    private let theme: Theme
    private let items: [Item]

    private let card = UIView()

    init(theme: Theme, items: [Item], sourceView: UIView) {
        self.theme = theme
        self.items = items

        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .overFullScreen
        modalTransitionStyle = .crossDissolve
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        view.backgroundColor = .clear

        // Full-screen dim button BELOW the card handles outside-taps; the card (added after) is on top
        // and receives row taps — no gesture-vs-control conflicts.
        let dimButton = UIButton(type: .custom)
        dimButton.backgroundColor = UIColor.black.withAlphaComponent(0.12)
        dimButton.addTarget(self, action: #selector(backgroundTapped), for: .touchUpInside)
        view.addSubview(dimButton)
        dimButton.snp.makeConstraints { $0.edges.equalTo(view) }

        // Same surface as an incoming bubble so it reads as a chat overlay in both themes.
        card.backgroundColor = theme.colorForType(.ChatIncomingBubble)
        card.layer.cornerRadius = 16.0
        card.layer.masksToBounds = true
        card.layer.shadowColor = UIColor.black.cgColor
        card.layer.shadowOpacity = 0.18
        card.layer.shadowRadius = 14.0
        card.layer.shadowOffset = CGSize(width: 0, height: 4)
        card.layer.masksToBounds = false
        view.addSubview(card)

        let stack = UIStackView()
        stack.axis = .vertical
        stack.alignment = .fill
        card.addSubview(stack)
        stack.snp.makeConstraints { $0.edges.equalTo(card) }

        for (index, item) in items.enumerated() {
            let row = makeRow(item, index: index)
            stack.addArrangedSubview(row)

            if index < items.count - 1 {
                let separator = UIView()
                separator.backgroundColor = theme.colorForType(.SeparatorsAndBorders).withAlphaComponent(0.5)
                stack.addArrangedSubview(separator)
                separator.snp.makeConstraints { $0.height.equalTo(0.5) }
            }
        }

        card.snp.makeConstraints {
            $0.width.equalTo(232.0)
            // Anchored above the input bar at the bottom-left, opening upward (over the "+").
            $0.leading.equalTo(view).offset(12.0)
            $0.bottom.equalTo(view.safeAreaLayoutGuide.snp.bottom).offset(-56.0)
        }
    }

    private func makeRow(_ item: Item, index: Int) -> UIButton {
        let row = UIButton(type: .system)
        row.tag = index
        row.tintColor = theme.colorForType(.LinkText)
        row.contentHorizontalAlignment = .left
        row.snp.makeConstraints { $0.height.equalTo(52.0) }

        let iconView = UIImageView()
        iconView.isUserInteractionEnabled = false
        iconView.contentMode = .scaleAspectFit
        iconView.tintColor = theme.colorForType(.LinkText)
        if #available(iOS 13.0, *) {
            let config = UIImage.SymbolConfiguration(pointSize: 20.0, weight: .regular)
            iconView.image = UIImage(systemName: item.systemImage, withConfiguration: config)
        }
        row.addSubview(iconView)

        let label = UILabel()
        label.isUserInteractionEnabled = false
        label.text = item.title
        label.font = UIFont.systemFont(ofSize: 16.0)
        label.textColor = theme.colorForType(.NormalText)
        row.addSubview(label)

        iconView.snp.makeConstraints {
            $0.leading.equalTo(row).offset(18.0)
            $0.centerY.equalTo(row)
            $0.width.equalTo(24.0)
        }
        label.snp.makeConstraints {
            $0.leading.equalTo(iconView.snp.trailing).offset(14.0)
            $0.trailing.equalTo(row).offset(-16.0)
            $0.centerY.equalTo(row)
        }

        row.addTarget(self, action: #selector(rowTapped(_:)), for: .touchUpInside)
        return row
    }

    @objc private func rowTapped(_ sender: UIButton) {
        let item = items[sender.tag]
        dismiss(animated: true) {
            // Small hop so the presenting controller is fully settled before the action presents its
            // own picker/sheet.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                item.action()
            }
        }
    }

    @objc private func backgroundTapped() {
        dismiss(animated: true, completion: nil)
    }
}
