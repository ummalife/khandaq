// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

class SnapshotBaseTest: FBSnapshotTestCase {
    var theme: Theme!

    var image: UIImage {
        get {
            let bundle = Bundle(for: type(of: self))
            return UIImage(named: "icon", in:bundle, compatibleWith: nil)!
        }
    }

    override func setUp() {
        super.setUp()

        // Reference images belong in the repository next to this file, not inside the built test
        // bundle — that default is why every snapshot test failed with "reference image not found".
        // Derived from #file so it holds no matter how the tests are launched (scheme environment
        // variables do not reach the test process when the scheme reuses the launch action's).
        let referenceDir = URL(fileURLWithPath: #file)
                .deletingLastPathComponent()
                .appendingPathComponent("ReferenceImages")
                .path
        setenv("FB_REFERENCE_IMAGE_DIR", referenceDir, 1)

        // Re-record after a deliberate visual change: FB_RECORD_MODE=1 in the scheme's test action.
        recordMode = (ProcessInfo.processInfo.environment["FB_RECORD_MODE"] == "1")

        let filepath = Bundle.main.path(forResource: "default-theme", ofType: "yaml")!
        let yamlString = try! NSString(contentsOfFile:filepath, encoding:String.Encoding.utf8.rawValue) as String

        theme = try! Theme(yamlString: yamlString)
    }

    func verifyView(_ view: UIView) {
        FBSnapshotVerifyView(view, identifier: "normal")

        view.forceRightToLeft()
        FBSnapshotVerifyView(view, identifier: "right-to-left")
    }
}

private extension UIView {
    func forceRightToLeft() {
        if #available(iOS 9.0, *) {
            semanticContentAttribute = .forceRightToLeft
        }

        for view in subviews {
            view.forceRightToLeft()
        }
    }
}
