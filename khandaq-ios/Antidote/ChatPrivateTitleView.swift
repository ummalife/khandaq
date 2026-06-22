// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private struct Constants {
    static let StatusViewLeftOffset: CGFloat = 5.0
    static let StatusViewSize: CGFloat = 10.0
    // KHANDAQ design (Figma): contact avatar to the left of the name in the chat header.
    static let AvatarSize: CGFloat = 30.0
    static let AvatarGap: CGFloat = 8.0
}

class ChatPrivateTitleView: UIView {
    var name: String {
        get {
            return nameLabel.text ?? ""
        }
        set {
            nameLabel.text = newValue

            updateFrame()
        }
    }

    var userStatus: UserStatus {
        get {
            return statusView.userStatus
        }
        set {
            statusView.userStatus = newValue
            updateFrame()
        }
    }

    var presenceText: String {
        get {
            return statusLabel.text ?? ""
        }
        set {
            statusLabel.text = newValue
            updateFrame()
        }
    }

    var presenceIsOnline: Bool = false {
        didSet {
            updatePresenceColor()
        }
    }

    var connectionStatus: ConnectionStatus {
        get {
            return statusView.connectionStatus
        }
        set {
            statusView.connectionStatus = newValue

            updateFrame()
        }
    }

    var avatar: UIImage? {
        get {
            return avatarView.image
        }
        set {
            avatarView.image = newValue
            avatarView.isHidden = newValue == nil
            updateFrame()
        }
    }

    fileprivate var avatarView: UIImageView!
    fileprivate var nameLabel: UILabel!
    fileprivate var statusView: UserStatusView!
    fileprivate var statusLabel: UILabel!
    fileprivate var theme: Theme!

    init(theme: Theme) {
        super.init(frame: CGRect.zero)

        self.theme = theme
        backgroundColor = .clear

        createViews(theme)
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

private extension ChatPrivateTitleView {
    func createViews(_ theme: Theme) {
        avatarView = UIImageView()
        avatarView.contentMode = .scaleAspectFill
        avatarView.layer.cornerRadius = Constants.AvatarSize / 2.0
        avatarView.layer.masksToBounds = true
        avatarView.isHidden = true
        addSubview(avatarView)

        nameLabel = UILabel()
        nameLabel.textAlignment = .center
        nameLabel.textColor = theme.colorForType(.NormalText)
        nameLabel.font = UIFont.khandaqFontWithSize(16.0, weight: .bold)
        addSubview(nameLabel)

        statusView = UserStatusView()
        statusView.showExternalCircle = false
        statusView.theme = theme
        statusView.isHidden = true
        addSubview(statusView)

        statusLabel = UILabel()
        statusLabel.textAlignment = .center
        statusLabel.textColor = theme.colorForType(.NormalText)
        statusLabel.font = UIFont.khandaqFontWithSize(12.0, weight: .light)
        addSubview(statusLabel)

        avatarView.snp.makeConstraints {
            $0.leading.equalTo(self)
            $0.centerY.equalTo(self)
            $0.size.equalTo(Constants.AvatarSize)
        }

        nameLabel.snp.makeConstraints {
            $0.top.equalTo(self)
            $0.leading.equalTo(avatarView.snp.trailing).offset(Constants.AvatarGap)
        }

        statusView.snp.makeConstraints {
            $0.centerY.equalTo(nameLabel)
            $0.leading.equalTo(nameLabel.snp.trailing).offset(Constants.StatusViewLeftOffset)
            $0.trailing.equalTo(self)
            $0.size.equalTo(Constants.StatusViewSize)
        }

        statusLabel.snp.makeConstraints {
            $0.top.equalTo(nameLabel.snp.bottom)
            $0.leading.equalTo(nameLabel)
            $0.trailing.equalTo(nameLabel)
            $0.bottom.equalTo(self)
        }

        updatePresenceColor()
    }

    func updatePresenceColor() {
        guard let theme = theme else {
            return
        }

        statusLabel.textColor = presenceIsOnline
            ? theme.colorForType(.OnlineStatus)
            : theme.colorForType(.NormalText)
    }

    func updateFrame() {
        nameLabel.sizeToFit()
        statusLabel.sizeToFit()

        let textWidth = max(nameLabel.frame.size.width, statusLabel.frame.size.width)
        let avatarWidth = avatarView.isHidden ? 0 : (Constants.AvatarSize + Constants.AvatarGap)
        let textHeight = nameLabel.frame.size.height + statusLabel.frame.size.height
        frame.size.width = avatarWidth + textWidth
        frame.size.height = max(textHeight, avatarView.isHidden ? 0 : Constants.AvatarSize)
    }
}
