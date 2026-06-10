// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import AVFoundation

class QRScannerController: UIViewController {
    var didScanStringsBlock: (([String]) -> Void)?
    var cancelBlock: (() -> Void)?

    fileprivate let theme: Theme

    fileprivate var previewLayer: AVCaptureVideoPreviewLayer?
    fileprivate var captureSession: AVCaptureSession?

    fileprivate var aimView: QRScannerAimView!
    fileprivate var deniedContainerView: UIView?

    var pauseScanning: Bool = false {
        didSet {
            guard let captureSession = captureSession else {
                return
            }

            pauseScanning ? captureSession.stopRunning() : captureSession.startRunning()

            if !pauseScanning {
                aimView.frame = CGRect.zero
            }
        }
    }

    init(theme: Theme) {
        self.theme = theme

        super.init(nibName: nil, bundle: nil)

        createBarButtonItems()

        NotificationCenter.default.addObserver(
                self,
                selector: #selector(QRScannerController.applicationDidEnterBackground),
                name: NSNotification.Name.UIApplicationDidEnterBackground,
                object: nil)

        NotificationCenter.default.addObserver(
                self,
                selector: #selector(QRScannerController.applicationWillEnterForeground),
                name: NSNotification.Name.UIApplicationWillEnterForeground,
                object: nil)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        loadViewWithBackgroundColor(theme.colorForType(.NormalBackground))

        createViewsAndLayers()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)

        updateCameraAccessState()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)

        captureSession?.stopRunning()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()

        previewLayer?.frame = view.bounds
    }
}

// MARK: Actions
extension QRScannerController {
    @objc func cancelButtonPressed() {
        cancelBlock?()
    }

    @objc func openSettingsButtonPressed() {
        guard let url = URL(string: UIApplicationOpenSettingsURLString) else {
            return
        }
        UIApplication.shared.open(url)
    }
}

// MARK: Notifications
extension QRScannerController {
    @objc func applicationDidEnterBackground() {
        captureSession?.stopRunning()
    }

    @objc func applicationWillEnterForeground() {
        updateCameraAccessState()
    }
}

extension QRScannerController: AVCaptureMetadataOutputObjectsDelegate {
    func metadataOutput(_ captureOutput: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let previewLayer = previewLayer else {
            return
        }

        let readableObjects = metadataObjects.filter {
            $0 is AVMetadataMachineReadableCodeObject
        }.map {
            previewLayer.transformedMetadataObject(for: $0 ) as! AVMetadataMachineReadableCodeObject
        }

        guard !readableObjects.isEmpty else {
            return
        }

        aimView.frame = readableObjects[0].bounds

        let strings = readableObjects.map {
            $0.stringValue!
        }

        didScanStringsBlock?(strings)
    }
}

private extension QRScannerController {
    func updateCameraAccessState() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            hideCameraDeniedUI()
            configureCaptureSessionIfNeeded()
            if !pauseScanning {
                captureSession?.startRunning()
            }
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    self?.updateCameraAccessState()
                }
            }
        case .denied, .restricted:
            showCameraDeniedUI()
        @unknown default:
            showCameraDeniedUI()
        }
    }

    func configureCaptureSessionIfNeeded() {
        guard captureSession == nil else {
            return
        }

        let session = AVCaptureSession()
        let input = captureSessionInput()
        let output = AVCaptureMetadataOutput()

        if let input = input, session.canAddInput(input) {
            session.addInput(input)
        } else {
            showCameraDeniedUI()
            return
        }

        if session.canAddOutput(output) {
            session.addOutput(output)

            output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)

            if output.availableMetadataObjectTypes.contains(AVMetadataObject.ObjectType.qr) {
                output.metadataObjectTypes = [AVMetadataObject.ObjectType.qr]
            }
        }

        captureSession = session

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = AVLayerVideoGravity.resizeAspectFill
        view.layer.insertSublayer(layer, at: 0)
        previewLayer = layer
        previewLayer?.frame = view.bounds

        if let deniedContainerView = deniedContainerView {
            view.bringSubview(toFront: deniedContainerView)
            view.bringSubview(toFront: aimView)
        }
    }

    func captureSessionInput() -> AVCaptureDeviceInput? {
        guard let device = AVCaptureDevice.default(for: AVMediaType.video) else {
            return nil
        }

        if device.isAutoFocusRangeRestrictionSupported {
            do {
                try device.lockForConfiguration()
                device.autoFocusRangeRestriction = .near
                device.unlockForConfiguration()
            }
            catch {
                // nop
            }
        }

        return try? AVCaptureDeviceInput(device: device)
    }

    func showCameraDeniedUI() {
        captureSession?.stopRunning()
        previewLayer?.removeFromSuperlayer()
        previewLayer = nil
        captureSession = nil

        aimView.isHidden = true

        if deniedContainerView == nil {
            let container = UIView()
            container.translatesAutoresizingMaskIntoConstraints = false

            let label = UILabel()
            label.translatesAutoresizingMaskIntoConstraints = false
            label.text = String(localized: "camera_access_denied_message")
            label.textAlignment = .center
            label.numberOfLines = 0
            label.textColor = theme.colorForType(.NormalText)

            let button = UIButton(type: .system)
            button.translatesAutoresizingMaskIntoConstraints = false
            button.setTitle(String(localized: "open_settings"), for: .normal)
            button.addTarget(self, action: #selector(QRScannerController.openSettingsButtonPressed), for: .touchUpInside)

            container.addSubview(label)
            container.addSubview(button)
            view.addSubview(container)

            NSLayoutConstraint.activate([
                container.centerXAnchor.constraint(equalTo: view.centerXAnchor),
                container.centerYAnchor.constraint(equalTo: view.centerYAnchor),
                container.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
                container.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -24),
                label.topAnchor.constraint(equalTo: container.topAnchor),
                label.leadingAnchor.constraint(equalTo: container.leadingAnchor),
                label.trailingAnchor.constraint(equalTo: container.trailingAnchor),
                button.topAnchor.constraint(equalTo: label.bottomAnchor, constant: 16),
                button.centerXAnchor.constraint(equalTo: container.centerXAnchor),
                button.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            ])

            deniedContainerView = container
        }

        deniedContainerView?.isHidden = false
        view.bringSubview(toFront: deniedContainerView!)
    }

    func hideCameraDeniedUI() {
        deniedContainerView?.isHidden = true
        aimView.isHidden = false
    }

    func createBarButtonItems() {
        navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .cancel, target: self, action: #selector(QRScannerController.cancelButtonPressed))
    }

    func createViewsAndLayers() {
        aimView = QRScannerAimView(theme: theme)
        view.addSubview(aimView)
        view.bringSubview(toFront: aimView)
    }
}
