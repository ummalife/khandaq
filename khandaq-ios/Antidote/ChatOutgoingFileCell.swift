// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private struct Constants {
    static let BigOffset = 20.0
    static let SmallOffset = 8.0
    static let ImageButtonSize = 180.0
    static let CloseButtonSize = 25.0
}

class ChatOutgoingFileCell: ChatGenericFileCell {
    private var statusImageView: UIImageView!

    override func setButtonImage(_ image: UIImage) {
        super.setButtonImage(image)

        loadingView.bottomLabel.isHidden = true

        if state == .cancelled {
            loadingView.bottomLabel.isHidden = false
            loadingView.centerImageView.image = nil
        }
    }

    func setVideoDurationLabel(_ text: String) {
        loadingView.bottomLabel.isHidden = false
        loadingView.bottomLabel.text = text
    }

    func setVideoPlayOverlay() {
        loadingView.centerImageView.image = UIImage.templateNamed("chat-file-play-big")
    }

    override func setupWithTheme(_ theme: Theme, model: BaseCellModel) {
        super.setupWithTheme(theme, model: model)

        guard let fileModel = model as? ChatOutgoingFileCellModel else {
            statusImageView.isHidden = true
            return
        }

        statusImageView.image = MessageStatusIcon.image(delivered: fileModel.delivered)
        statusImageView.tintColor = fileModel.delivered
            ? UIColor(red: 0.05, green: 0.65, blue: 0.91, alpha: 1.0)
            : UIColor(white: 0.58, alpha: 1.0)
        statusImageView.isHidden = fileModel.state != .done
    }

    override func createViews() {
        super.createViews()

        movableContentView.addSubview(loadingView)
        movableContentView.addSubview(captionLabel)
        voiceMessageView.isOutgoing = true
        movableContentView.addSubview(voiceMessageView)
        movableContentView.addSubview(cancelButton)
        movableContentView.addSubview(retryButton)

        statusImageView = UIImageView()
        statusImageView.contentMode = .scaleAspectFit
        loadingView.addSubview(statusImageView)
    }

    override func installConstraints() {
        super.installConstraints()

        statusImageView.snp.makeConstraints {
            // KHANDAQ (#107b): fix the height; width follows the glyph (single vs double check) intrinsically.
            $0.height.equalTo(14)
            $0.trailing.equalTo(loadingView).offset(-6)
            $0.bottom.equalTo(loadingView).offset(-4)
        }

        cancelButton.snp.makeConstraints {
            $0.trailing.equalTo(loadingView.snp.leading).offset(-Constants.SmallOffset)
            $0.top.equalTo(loadingView)
            $0.size.equalTo(Constants.CloseButtonSize)
        }

        retryButton.snp.makeConstraints {
            $0.center.equalTo(cancelButton)
            $0.size.equalTo(cancelButton)
        }

        loadingView.snp.makeConstraints {
            $0.trailing.equalTo(movableContentView).offset(-Constants.BigOffset)
            $0.top.equalTo(movableContentView).offset(Constants.SmallOffset)
            // KHANDAQ (#15): size comes from LoadingImageView (square by default, aspect for media).
        }

        // Caption sits directly under the media, aligned to it; bottom of the bubble follows the caption.
        captionLabel.snp.makeConstraints {
            captionTopConstraint = $0.top.equalTo(loadingView.snp.bottom).constraint
            $0.leading.equalTo(loadingView)
            $0.trailing.equalTo(loadingView)
            $0.bottom.equalTo(movableContentView).offset(-Constants.SmallOffset)
        }

        voiceMessageView.snp.makeConstraints {
            $0.trailing.equalTo(movableContentView).offset(-Constants.BigOffset)
            $0.leading.greaterThanOrEqualTo(movableContentView).offset(Constants.BigOffset)
            $0.top.equalTo(movableContentView).offset(Constants.SmallOffset)
            $0.bottom.equalTo(movableContentView).offset(-Constants.SmallOffset)
            $0.width.equalTo(260)
        }
    }

    override func updateViewsWithState(_ state: ChatGenericFileCellModel.State, fileModel: ChatGenericFileCellModel) {
        loadingView.imageButton.isUserInteractionEnabled = true
        loadingView.progressView.isHidden = true
        loadingView.topLabel.isHidden = true
        loadingView.bottomLabel.isHidden = false
        loadingView.bottomLabel.text = fileModel.fileName

        cancelButton.isHidden = false
        retryButton.isHidden = true

        switch state {
            case .waitingConfirmation:
                loadingView.imageButton.isUserInteractionEnabled = false
                loadingView.bottomLabel.text = String(localized: "chat_waiting")
            case .loading:
                loadingView.progressView.isHidden = false
            case .paused:
                break
            case .cancelled:
                loadingView.bottomLabel.text = String(localized: "chat_file_download_failed")
                cancelButton.isHidden = true
                retryButton.isHidden = false
            case .done:
                cancelButton.isHidden = true
                if fileModel.isVoiceMessage {
                    loadingView.isHidden = true
                    voiceMessageView.isHidden = false
                    loadingView.bottomLabel.isHidden = true
                }
                if let outgoingModel = fileModel as? ChatOutgoingFileCellModel {
                    statusImageView.isHidden = fileModel.isVoiceMessage
                    statusImageView.tintColor = outgoingModel.delivered
                        ? UIColor(red: 0.05, green: 0.65, blue: 0.91, alpha: 1.0)
                        : UIColor(white: 0.58, alpha: 1.0)
                }
        }
    }

    override func loadingViewPressed() {
        switch state {
            case .waitingConfirmation:
                break
            case .loading:
                pauseOrResumeHandle?()
            case .paused:
                pauseOrResumeHandle?()
            case .cancelled:
                retryHandle?()
            case .done:
                if !voiceMessageView.isHidden {
                    voiceMessageView.onPlayTapped?()
                }
                else {
                    openHandle?()
                }
        }
    }
}
