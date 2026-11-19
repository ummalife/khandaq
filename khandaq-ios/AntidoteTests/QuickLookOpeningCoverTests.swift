// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import QuickLook
import XCTest
@testable import Antidote

/**
 A file opens with a spinner over the screen it was tapped on, until QuickLook is up (0.2.63, O1). A 25 MB PDF
 took 3-4 s to come up on an iPhone, and nothing showed meanwhile, so the tap looked lost.
 */
final class QuickLookOpeningCoverTests: XCTestCase {
    private var window: UIWindow?
    private var fileURL: URL?

    override func tearDown() {
        window?.rootViewController?.dismiss(animated: false, completion: nil)
        window?.isHidden = true
        window = nil
        if let url = fileURL {
            try? FileManager.default.removeItem(at: url)
        }
        super.tearDown()
    }

    func testTheSpinnerCoversTheScreenUntilQuickLookIsUp() throws {
        let presenter = showPresenter()
        let preview = try previewOfAPDF()

        QuickLookPreviewController.present(preview, from: presenter)

        let cover = try XCTUnwrap(covers(on: presenter).first, "nothing shows between the tap and QuickLook")
        XCTAssertEqual(cover.frame, presenter.view.bounds, "the cover does not take the touches of the whole screen")
        XCTAssertEqual(cover.accessibilityLabel, String(localized: "launch_loading"))
        XCTAssertEqual(cover.accessibilityIdentifier, QuickLookOpeningCover.identifier)
        let spinner = try XCTUnwrap(cover.descendants.compactMap { $0 as? UIActivityIndicatorView }.first, "no spinner")
        XCTAssertTrue(spinner.isAnimating, "the spinner stands still")

        let up = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            presenter.presentedViewController === preview && self.covers(on: presenter).isEmpty
        }, object: nil)
        wait(for: [up], timeout: 15)
    }

    /// UIKit refuses a second presentation without calling its completion: a cover put up then would stay
    /// until the timeout, taking every touch.
    func testAPresenterThatAlreadyShowsSomethingGetsNoCover() throws {
        let presenter = showPresenter()
        presenter.present(UIViewController(), animated: false, completion: nil)
        let preview = try previewOfAPDF()

        QuickLookPreviewController.present(preview, from: presenter)

        XCTAssertTrue(covers(on: presenter).isEmpty, "a cover went up over a presenter that cannot present QuickLook")
    }

    func testAPresenterOffScreenGetsNoCover() throws {
        let presenter = UIViewController()
        presenter.loadViewIfNeeded()
        let preview = try previewOfAPDF()

        QuickLookPreviewController.present(preview, from: presenter)

        XCTAssertTrue(covers(on: presenter).isEmpty, "a cover went up over a screen that is not shown")
    }

    // MARK: - Helpers

    private func showPresenter() -> UIViewController {
        let presenter = UIViewController()
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = presenter
        window.makeKeyAndVisible()
        self.window = window
        return presenter
    }

    private func covers(on presenter: UIViewController) -> [QuickLookOpeningCover] {
        return presenter.view.subviews.compactMap { $0 as? QuickLookOpeningCover }
    }

    private func previewOfAPDF() throws -> QuickLookPreviewController {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("o1-\(UUID().uuidString).pdf")
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792))
        try renderer.writePDF(to: url) { context in
            context.beginPage()
            ("O1" as NSString).draw(at: CGPoint(x: 72, y: 72), withAttributes: nil)
        }
        fileURL = url

        let source = OneFileSource(url: url)
        let preview = QuickLookPreviewController()
        preview.dataSource = source
        preview.dataSourceStorage = source
        return preview
    }
}

private final class OneFileSource: NSObject, QuickLookPreviewControllerDataSource {
    weak var previewController: QuickLookPreviewController?
    private let url: URL

    init(url: URL) {
        self.url = url
    }

    func numberOfPreviewItems(in controller: QLPreviewController) -> Int {
        return 1
    }

    func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
        return url as NSURL
    }
}

private extension UIView {
    var descendants: [UIView] {
        return subviews + subviews.flatMap { $0.descendants }
    }
}
