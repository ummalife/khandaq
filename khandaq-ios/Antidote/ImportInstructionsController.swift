// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

/// KHANDAQ design (Figma node 109:662): the "Импорт профиля" instructions screen — a left-aligned
/// title over a stack of numbered grey cards explaining how to open a .tox file into the app. The
/// actual import still happens via importToxProfileFromURL when a .tox file is opened.
class ImportInstructionsController: LoginBaseController {
    private struct Constants {
        static let SideMargin = 16.0
        static let TitleTop = 16.0
        static let CardSpacing = 8.0
        static let CardCornerRadius = 12.0
        static let CardPaddingH = 16.0
        static let CardPaddingV = 12.0
        static let NumberToTextGap = 10.0
    }

    override func loadView() {
        super.loadView()
        navigationItem.title = String(localized: "import_profile")
        buildContent()
    }

    private func buildContent() {
        let scrollView = UIScrollView()
        scrollView.alwaysBounceVertical = true
        view.addSubview(scrollView)
        scrollView.snp.makeConstraints { $0.edges.equalTo(view) }

        let content = UIView()
        scrollView.addSubview(content)
        content.snp.makeConstraints {
            $0.top.bottom.equalTo(scrollView)
            $0.leading.trailing.equalTo(view)
        }

        let titleLabel = UILabel()
        titleLabel.text = String(localized: "import_profile")
        titleLabel.font = UIFont.systemFont(ofSize: 24.0, weight: .semibold)
        titleLabel.textColor = theme.colorForType(.NormalText)
        titleLabel.numberOfLines = 0
        content.addSubview(titleLabel)
        titleLabel.snp.makeConstraints {
            $0.top.equalTo(content).offset(Constants.TitleTop)
            $0.leading.trailing.equalTo(content).inset(Constants.SideMargin)
        }

        let steps = [
            String(localized: "import_step_1"),
            String(localized: "import_step_2"),
            String(localized: "import_step_3"),
            String(localized: "import_step_4"),
        ]

        var previous: UIView = titleLabel
        for (index, text) in steps.enumerated() {
            // Step 3 (index 2) carries the share-sheet illustration.
            let illustration = index == 2 ? UIImage(named: "import-appicons") : nil
            let card = makeCard(number: index + 1, text: text, illustration: illustration)
            content.addSubview(card)
            card.snp.makeConstraints {
                $0.top.equalTo(previous.snp.bottom).offset(index == 0 ? 24.0 : Constants.CardSpacing)
                $0.leading.trailing.equalTo(content).inset(Constants.SideMargin)
            }
            previous = card
        }

        previous.snp.makeConstraints {
            $0.bottom.equalTo(content).offset(-24.0)
        }
    }

    private func makeCard(number: Int, text: String, illustration: UIImage?) -> UIView {
        let card = UIView()
        card.backgroundColor = theme.colorForType(.ChatInputBackground)
        card.layer.cornerRadius = Constants.CardCornerRadius
        card.layer.masksToBounds = true

        let numberLabel = UILabel()
        numberLabel.text = "\(number)"
        numberLabel.font = UIFont.systemFont(ofSize: 16.0, weight: .medium)
        numberLabel.textColor = theme.colorForType(.FriendCellStatus)
        numberLabel.setContentHuggingPriority(.required, for: .horizontal)
        card.addSubview(numberLabel)

        let textLabel = UILabel()
        textLabel.text = text
        textLabel.font = UIFont.systemFont(ofSize: 16.0)
        textLabel.textColor = theme.colorForType(.NormalText)
        textLabel.numberOfLines = 0
        card.addSubview(textLabel)

        numberLabel.snp.makeConstraints {
            $0.top.equalTo(card).offset(Constants.CardPaddingV)
            $0.leading.equalTo(card).offset(Constants.CardPaddingH)
        }

        textLabel.snp.makeConstraints {
            $0.top.equalTo(card).offset(Constants.CardPaddingV)
            $0.leading.equalTo(numberLabel.snp.trailing).offset(Constants.NumberToTextGap)
            $0.trailing.equalTo(card).offset(-Constants.CardPaddingH)
        }

        if let illustration = illustration {
            let imageView = UIImageView(image: illustration)
            imageView.contentMode = .scaleAspectFit
            imageView.layer.cornerRadius = 8.0
            imageView.layer.masksToBounds = true
            card.addSubview(imageView)
            imageView.snp.makeConstraints {
                $0.top.equalTo(textLabel.snp.bottom).offset(Constants.CardPaddingV)
                $0.leading.equalTo(textLabel)
                $0.trailing.lessThanOrEqualTo(card).offset(-Constants.CardPaddingH)
                $0.height.equalTo(95.0)
                $0.bottom.equalTo(card).offset(-Constants.CardPaddingV)
            }
        }
        else {
            textLabel.snp.makeConstraints {
                $0.bottom.equalTo(card).offset(-Constants.CardPaddingV)
            }
        }

        return card
    }
}
