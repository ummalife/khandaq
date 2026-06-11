// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private struct Constants {
    static let Offset = 10.0
    static let TitleColorKey = "TITLE_COLOR"
    static let TextColorKey = "TEXT_COLOR"
}

class TextViewController: UIViewController {
    fileprivate let resourceName: String?
    fileprivate let plainText: String?
    fileprivate let backgroundColor: UIColor
    fileprivate let titleColor: UIColor
    fileprivate let textColor: UIColor

    fileprivate var textView: UITextView!

    init(resourceName: String, backgroundColor: UIColor, titleColor: UIColor, textColor: UIColor) {
        self.resourceName = resourceName
        self.plainText = nil
        self.backgroundColor = backgroundColor
        self.titleColor = titleColor
        self.textColor = textColor

        super.init(nibName: nil, bundle: nil)
    }

    init(plainText: String, backgroundColor: UIColor, titleColor: UIColor, textColor: UIColor) {
        self.resourceName = nil
        self.plainText = plainText
        self.backgroundColor = backgroundColor
        self.titleColor = titleColor
        self.textColor = textColor

        super.init(nibName: nil, bundle: nil)
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        loadViewWithBackgroundColor(backgroundColor)

        createTextView()
        installConstraints()

        if plainText != nil {
            loadPlainText()
        } else {
            loadHtml()
        }
    }
}

private extension TextViewController {
    func createTextView() {
        textView = UITextView()
        textView.isEditable = false
        textView.backgroundColor = .clear
        view.addSubview(textView)
    }

    func installConstraints() {
        textView.snp.makeConstraints {
            $0.leading.top.equalTo(view).offset(Constants.Offset)
            $0.trailing.bottom.equalTo(view).offset(-Constants.Offset)
        }
    }

    func loadPlainText() {
        textView.text = plainText
        if #available(iOS 13.0, *) {
            textView.font = UIFont.monospacedSystemFont(ofSize: 12, weight: .regular)
        } else {
            textView.font = UIFont(name: "Menlo-Regular", size: 12) ?? UIFont.systemFont(ofSize: 12)
        }
        textView.textColor = textColor
    }

    func loadHtml() {
        do {
            struct FakeError: Error {}
            guard let resourceName = resourceName,
                  let htmlFilePath = Bundle.main.path(forResource: resourceName, ofType: "html") else {
                throw FakeError()
            }

            var htmlString = try NSString(contentsOfFile: htmlFilePath, encoding: String.Encoding.utf8.rawValue)
            htmlString = htmlString.replacingOccurrences(of: Constants.TitleColorKey, with: titleColor.hexString()) as NSString
            htmlString = htmlString.replacingOccurrences(of: Constants.TextColorKey, with: textColor.hexString()) as NSString

            guard let data = htmlString.data(using: String.Encoding.unicode.rawValue) else {
                throw FakeError()
            }
            let options = [ NSAttributedString.DocumentReadingOptionKey.documentType : NSAttributedString.DocumentType.html ]

            try textView.attributedText = NSAttributedString(data: data, options: options, documentAttributes: nil)
        }
        catch {
            handleErrorWithType(.cannotLoadHTML)
        }
    }
}
