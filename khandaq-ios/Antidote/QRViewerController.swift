// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

protocol QRViewerControllerDelegate: class {
    func qrViewerControllerDidFinishPresenting()
}

class QRViewerController: UIViewController {
    weak var delegate: QRViewerControllerDelegate?

    fileprivate let theme: Theme
    fileprivate let text: String

    fileprivate var previousBrightness: CGFloat = 1.0

    fileprivate var closeButton: UIButton!
    fileprivate var headingLabel: UILabel!
    fileprivate var subtitleLabel: UILabel!
    fileprivate var cardView: UIView!
    fileprivate var imageView: UIImageView!

    init(theme: Theme, text: String) {
        self.theme = theme
        self.text = text

        super.init(nibName: nil, bundle: nil)

        edgesForExtendedLayout = UIRectEdge()
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        loadViewWithBackgroundColor(theme.colorForType(.NormalBackground))

        installNavigationItems()
        createViews()
        installConstraints()
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = String(localized: "qr_screen_title")
        installKhandaqCircleBackButton(theme: theme)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)

        previousBrightness = UIScreen.main.brightness
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)

        UIScreen.main.brightness = previousBrightness
    }
}

extension QRViewerController {
    @objc func closeButtonPressed() {
        delegate?.qrViewerControllerDidFinishPresenting()
    }
}

private extension QRViewerController {
    func installNavigationItems() {
        navigationItem.rightBarButtonItem = UIBarButtonItem(
                barButtonSystemItem: .done,
                target: self,
                action: #selector(QRViewerController.closeButtonPressed))
    }

    func createViews() {
        // KHANDAQ design (Figma): "MyID вашего аккаунта" heading + subtitle above the QR.
        headingLabel = UILabel()
        headingLabel.text = String(localized: "qr_my_id_heading")
        headingLabel.font = UIFont.systemFont(ofSize: 17.0, weight: .semibold)
        headingLabel.textColor = theme.colorForType(.NormalText)
        view.addSubview(headingLabel)

        subtitleLabel = UILabel()
        subtitleLabel.text = String(localized: "qr_my_id_subtitle")
        subtitleLabel.font = UIFont.systemFont(ofSize: 14.0)
        subtitleLabel.textColor = theme.colorForType(.ChatListCellMessage)
        subtitleLabel.numberOfLines = 0
        view.addSubview(subtitleLabel)

        // KHANDAQ design (Figma): QR sits in a rounded card — kept white in both themes so the code
        // always scans.
        cardView = UIView()
        cardView.backgroundColor = .white
        cardView.layer.cornerRadius = 16.0
        cardView.layer.masksToBounds = true
        cardView.layer.borderWidth = 0.5
        cardView.layer.borderColor = theme.colorForType(.SeparatorsAndBorders).cgColor
        view.addSubview(cardView)

        imageView = UIImageView(image: qrImageFromText())
        imageView.contentMode = .scaleAspectFit
        cardView.addSubview(imageView)
    }

    func installConstraints() {
        headingLabel.snp.makeConstraints {
            $0.top.equalTo(view.safeAreaLayoutGuide).offset(20.0)
            $0.leading.equalTo(view).offset(20.0)
            $0.trailing.equalTo(view).offset(-20.0)
        }

        subtitleLabel.snp.makeConstraints {
            $0.top.equalTo(headingLabel.snp.bottom).offset(4.0)
            $0.leading.equalTo(headingLabel)
            $0.trailing.equalTo(headingLabel)
        }

        cardView.snp.makeConstraints {
            $0.top.equalTo(subtitleLabel.snp.bottom).offset(20.0)
            $0.leading.equalTo(view).offset(20.0)
            $0.trailing.equalTo(view).offset(-20.0)
            $0.height.equalTo(cardView.snp.width)
        }

        imageView.snp.makeConstraints {
            $0.edges.equalTo(cardView).inset(16.0)
        }
    }

    func qrImageFromText() -> UIImage {
        let filter = CIFilter(name:"CIQRCodeGenerator")!
        filter.setDefaults()
        filter.setValue(text.data(using: String.Encoding.utf8), forKey: "inputMessage")

        let ciImage = filter.outputImage!
        let screenBounds = UIScreen.main.bounds

        let scale = min(screenBounds.size.width / ciImage.extent.size.width, screenBounds.size.height / ciImage.extent.size.height)
        let transformedImage = ciImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

        return UIImage(ciImage: transformedImage)
    }
}
