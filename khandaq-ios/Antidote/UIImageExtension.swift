// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

extension UIImage {
    class func emptyImage() -> UIImage {
        return imageWithColor(.clear, size: CGSize(width: 1, height: 1))
    }

    class func imageWithColor(_ color: UIColor, size: CGSize) -> UIImage {
        // UIGraphicsBeginImageContext throws on apps linked against the iOS 17+ SDK.
        let rect = CGRect(origin: CGPoint.zero, size: size)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        return UIGraphicsImageRenderer(size: rect.size, format: format).image { ctx in
            color.setFill()
            ctx.fill(rect)
        }
    }

    class func templateNamed(_ named: String) -> UIImage {
        return UIImage(named: named)!.withRenderingMode(.alwaysTemplate)
    }

    func scaleToSize(_ size: CGSize) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            draw(in: CGRect(origin: CGPoint.zero, size: size))
        }
    }

    func cropWithRect(_ rect: CGRect) -> UIImage {
        var rect = rect

        switch imageOrientation {
            case .up:
                fallthrough
            case .upMirrored:
                fallthrough
            case .down:
                fallthrough
            case .downMirrored:
                break

            case .left:
                fallthrough
            case .leftMirrored:
                fallthrough
            case .right:
                fallthrough
            case .rightMirrored:
                var temp = rect.origin.x
                rect.origin.x = rect.origin.y
                rect.origin.y = temp

                temp = rect.size.width
                rect.size.width = rect.size.height
                rect.size.height = rect.size.width
        }

        if (scale > 1.0) {
            rect.origin.x *= scale
            rect.origin.y *= scale
            rect.size.width *= scale
            rect.size.height *= scale
        }

        let imageRef = self.cgImage?.cropping(to: rect)!
        return UIImage(cgImage: imageRef!, scale: scale, orientation: imageOrientation)
    }

    func flippedToCorrectLayout() -> UIImage {
        if #available(iOS 9.0, *) {
            return imageFlippedForRightToLeftLayoutDirection()
        }
        return self
    }
}
