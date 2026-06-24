// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

/// KHANDAQ (#107b): outgoing message delivery indicator. Tox reports DELIVERY only (there is no
/// "read" receipt), so a SINGLE check means sent-not-yet-delivered and a DOUBLE check means delivered
/// (WhatsApp-style). The returned image is an `.alwaysTemplate` so the cell tints it (grey = sent,
/// accent = delivered).
enum MessageStatusIcon {
    static func image(delivered: Bool) -> UIImage? {
        guard let base = UIImage(named: "chat-delivered-checkmark") else {
            return nil
        }

        let height: CGFloat = 14.0
        let width = (base.size.height > 0) ? height * base.size.width / base.size.height : height

        if !delivered {
            return render(CGSize(width: width, height: height)) {
                base.draw(in: CGRect(x: 0, y: 0, width: width, height: height))
            }
        }

        // Double check: the single glyph drawn twice, the second offset to the right.
        let offset = width * 0.55
        return render(CGSize(width: width + offset, height: height)) {
            base.draw(in: CGRect(x: 0, y: 0, width: width, height: height))
            base.draw(in: CGRect(x: offset, y: 0, width: width, height: height))
        }
    }

    private static func render(_ size: CGSize, _ draw: () -> Void) -> UIImage {
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { _ in draw() }.withRenderingMode(.alwaysTemplate)
    }
}
