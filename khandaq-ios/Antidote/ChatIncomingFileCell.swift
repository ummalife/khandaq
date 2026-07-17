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

class ChatIncomingFileCell: ChatGenericFileCell {
    override func setButtonImage(_ image: UIImage) {
        super.setButtonImage(image)
        loadingView.bottomLabel.isHidden = true
    }

    func setVideoPlayOverlay() {
        loadingView.centerImageView.image = UIImage.templateNamed("chat-file-play-big")
    }

    func setVideoDurationLabel(_ text: String) {
        loadingView.bottomLabel.isHidden = false
        loadingView.bottomLabel.text = text
    }

    override func createViews() {
        super.createViews()

        // KHANDAQ (#162): children live in movableContentView so the day-pill / unread-band reserved
        // offset (applied to movableContentView's top) pushes the whole bubble down instead of the
        // separators being drawn over it.
        movableContentView.addSubview(loadingView)
        movableContentView.addSubview(captionLabel)
        movableContentView.addSubview(voiceMessageView)
        movableContentView.addSubview(cancelButton)
        movableContentView.addSubview(retryButton)
        movableContentView.addSubview(reactionsLabel)
    }

    override func installConstraints() {
        super.installConstraints()

        loadingView.snp.makeConstraints {
            // Horizontal on contentView (movableContentView is pre-shifted in DateonmessageMode).
            $0.leading.equalTo(contentView).offset(Constants.BigOffset)
            $0.top.equalTo(movableContentView).offset(Constants.SmallOffset)
            // KHANDAQ (#15): size comes from LoadingImageView (square by default, aspect for media).
        }

        captionLabel.snp.makeConstraints {
            captionTopConstraint = $0.top.equalTo(loadingView.snp.bottom).constraint
            $0.leading.equalTo(loadingView)
            $0.trailing.equalTo(loadingView)
            captionBottomConstraint = $0.bottom.equalTo(movableContentView).offset(-Constants.SmallOffset).constraint
        }

        voiceMessageView.snp.makeConstraints {
            $0.leading.equalTo(contentView).offset(Constants.BigOffset)
            $0.trailing.lessThanOrEqualTo(contentView).offset(-Constants.BigOffset)
            $0.top.equalTo(movableContentView).offset(Constants.SmallOffset)
            voiceBottomConstraint = $0.bottom.equalTo(movableContentView).offset(-Constants.SmallOffset).constraint
            $0.width.equalTo(260)
        }

        cancelButton.snp.makeConstraints {
            $0.leading.equalTo(loadingView.snp.trailing).offset(Constants.SmallOffset)
            $0.top.equalTo(loadingView)
            $0.size.equalTo(Constants.CloseButtonSize)
        }

        retryButton.snp.makeConstraints {
            $0.center.equalTo(cancelButton)
            $0.size.equalTo(cancelButton)
        }
    }

    override func updateViewsWithState(_ state: ChatGenericFileCellModel.State, fileModel: ChatGenericFileCellModel) {
        loadingView.imageButton.isUserInteractionEnabled = true
        loadingView.progressView.isHidden = true
        loadingView.topLabel.isHidden = false
        loadingView.topLabel.text = fileModel.fileName
        loadingView.bottomLabel.text = fileModel.fileSize
        loadingView.bottomLabel.isHidden = false

        cancelButton.isHidden = false
        retryButton.isHidden = true

        switch state {
            case .waitingConfirmation:
                loadingView.centerImageView.image = UIImage.templateNamed("chat-file-download-big")
            case .loading:
                loadingView.progressView.isHidden = false
            case .paused:
                break
            case .cancelled:
                loadingView.setCancelledImage()
                loadingView.imageButton.isUserInteractionEnabled = true
                loadingView.centerImageView.image = UIImage.templateNamed("chat-file-download-big")
                cancelButton.isHidden = true
                retryButton.isHidden = false
                loadingView.bottomLabel.text = String(localized: "chat_file_download_failed")
            case .done:
                cancelButton.isHidden = true
                loadingView.topLabel.isHidden = true
                if fileModel.isVoiceMessage {
                    loadingView.bottomLabel.isHidden = true
                    voiceMessageView.isHidden = false
                    loadingView.isHidden = true
                }
                else {
                    loadingView.bottomLabel.text = fileModel.fileName
                }
        }
    }

    override func loadingViewPressed() {
        switch state {
            case .waitingConfirmation:
                startLoadingHandle?()
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
