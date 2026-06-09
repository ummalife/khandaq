// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private struct Constants {
    static let TextViewMinWidth = 5.0
    static let TextViewMaxWidth = 260.0
    static let TextViewMinHeight = 10.0
    static let MapHeight = 120.0

    static let TextViewVerticalOffset = 1.0
    static let TextViewHorizontalOffset = 5.0
}

class BubbleView: UIView {
    fileprivate var textView: UITextView!
    fileprivate var mapImageView: UIImageView?
    fileprivate var locationTapRecognizer: UITapGestureRecognizer?
    var onLocationTap: (() -> Void)?

    var text: String? {
        get {
            return textView.text
        }
        set {
            textView.text = newValue
        }
    }

    var attributedText: NSAttributedString? {
        get {
            return textView.attributedText
        }
        set {
            textView.attributedText = newValue
        }
    }

    var textColor: UIColor {
        get {
            return textView.textColor!
        }
        set {
            textView.textColor = newValue
        }
    }

    var font: UIFont? {
        get {
            return textView.font
        }
        set {
            textView.font = newValue
        }
    }

    override var tintColor: UIColor! {
        didSet {
            textView.linkTextAttributes = [
                NSAttributedStringKey.foregroundColor.rawValue: tintColor,
                NSAttributedStringKey.underlineStyle.rawValue: NSUnderlineStyle.styleSingle.rawValue,
            ]
        }
    }

    var selectable: Bool {
        get {
            return textView.isSelectable
        }
        set {
            textView.isSelectable = newValue
        }
    }

    func setLocationMapImage(_ image: UIImage?) {
        if let image = image {
            ensureMapImageView()
            mapImageView?.image = image
            mapImageView?.isHidden = false
            textView.dataDetectorTypes = []
            installLocationTapRecognizer()
        }
        else {
            mapImageView?.isHidden = true
            mapImageView?.image = nil
            textView.dataDetectorTypes = .all
            removeLocationTapRecognizer()
        }

        updateTextConstraints(hasMap: image != nil)
    }

    fileprivate func ensureMapImageView() {
        guard mapImageView == nil else {
            return
        }

        let imageView = UIImageView()
        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        imageView.isUserInteractionEnabled = true
        addSubview(imageView)
        mapImageView = imageView

        imageView.snp.makeConstraints {
            $0.top.equalTo(self).offset(Constants.TextViewVerticalOffset)
            $0.leading.equalTo(self).offset(Constants.TextViewHorizontalOffset)
            $0.trailing.equalTo(self).offset(-Constants.TextViewHorizontalOffset)
            $0.width.lessThanOrEqualTo(Constants.TextViewMaxWidth)
            $0.height.equalTo(Constants.MapHeight)
        }
    }

    fileprivate func updateTextConstraints(hasMap: Bool) {
        textView.snp.remakeConstraints {
            if hasMap, let mapImageView = mapImageView {
                $0.top.equalTo(mapImageView.snp.bottom).offset(Constants.TextViewVerticalOffset)
            }
            else {
                $0.top.equalTo(self).offset(Constants.TextViewVerticalOffset)
            }

            $0.bottom.equalTo(self).offset(-Constants.TextViewVerticalOffset)
            $0.leading.equalTo(self).offset(Constants.TextViewHorizontalOffset)
            $0.trailing.equalTo(self).offset(-Constants.TextViewHorizontalOffset)

            $0.width.greaterThanOrEqualTo(Constants.TextViewMinWidth)
            $0.width.lessThanOrEqualTo(Constants.TextViewMaxWidth)
            $0.height.greaterThanOrEqualTo(Constants.TextViewMinHeight)
        }
    }

    fileprivate func installLocationTapRecognizer() {
        guard locationTapRecognizer == nil else {
            return
        }

        let recognizer = UITapGestureRecognizer(target: self, action: #selector(handleLocationTap))
        addGestureRecognizer(recognizer)
        locationTapRecognizer = recognizer
    }

    fileprivate func removeLocationTapRecognizer() {
        if let recognizer = locationTapRecognizer {
            removeGestureRecognizer(recognizer)
            locationTapRecognizer = nil
        }
    }

    @objc fileprivate func handleLocationTap() {
        onLocationTap?()
    }

    override init(frame: CGRect) {
        super.init(frame: frame)

        layer.cornerRadius = 12.0
        layer.masksToBounds = true

        textView = UITextView()
        textView.backgroundColor = .clear
        textView.isEditable = false
        textView.isScrollEnabled = false
        textView.dataDetectorTypes = .all
        textView.font = UIFont.systemFont(ofSize: 16.0)

        addSubview(textView)

        textView.snp.makeConstraints {
            $0.top.equalTo(self).offset(Constants.TextViewVerticalOffset)
            $0.bottom.equalTo(self).offset(-Constants.TextViewVerticalOffset)
            $0.leading.equalTo(self).offset(Constants.TextViewHorizontalOffset)
            $0.trailing.equalTo(self).offset(-Constants.TextViewHorizontalOffset)

            $0.width.greaterThanOrEqualTo(Constants.TextViewMinWidth)
            $0.width.lessThanOrEqualTo(Constants.TextViewMaxWidth)
            $0.height.greaterThanOrEqualTo(Constants.TextViewMinHeight)
        }
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}
