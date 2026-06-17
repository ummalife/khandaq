// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

enum GroupImagePreparer {
    private static let maxDimension: CGFloat = 1600
    private static let maxBytes = 900_000

    static func jpegDataForGroupUpload(from image: UIImage) -> Data? {
        let scaled = scaleDown(image, maxDimension: maxDimension)
        var quality: CGFloat = 0.85
        var data = UIImageJPEGRepresentation(scaled, quality)

        while let current = data, current.count > maxBytes, quality > 0.35 {
            quality -= 0.1
            data = UIImageJPEGRepresentation(scaled, quality)
        }

        return data
    }

    private static func scaleDown(_ image: UIImage, maxDimension: CGFloat) -> UIImage {
        let size = image.size
        let longest = max(size.width, size.height)

        guard longest > maxDimension else {
            return image
        }

        let scale = maxDimension / longest
        let targetSize = CGSize(width: floor(size.width * scale), height: floor(size.height * scale))

        UIGraphicsBeginImageContextWithOptions(targetSize, true, 1.0)
        image.draw(in: CGRect(origin: .zero, size: targetSize))
        let scaled = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()

        return scaled ?? image
    }
}
