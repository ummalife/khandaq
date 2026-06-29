// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

extension URL {
    func isToxURL() -> Bool {
        guard isFileURL else {
            return false
        }

        // KHANDAQ: a Tox profile save is an opaque binary blob. The previous probe sniffed the
        // MIME type via NSURLConnection.sendSynchronousRequest — deprecated since iOS 9 and it
        // returns nil on modern iOS (26), so isToxURL() always returned false and every
        // share-sheet import was silently rejected before the "Create profile" alert appeared.
        // iOS only routes files matching our declared document type (public.data) here, so accept
        // a `.tox` extension (or an extension-less legacy save) for any readable file.
        let ext = pathExtension.lowercased()
        guard ext == "tox" || ext.isEmpty else {
            return false
        }

        return FileManager.default.isReadableFile(atPath: path)
    }
}
