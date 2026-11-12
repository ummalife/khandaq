// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

extension UIAlertController {
    class func showErrorWithMessage(_ message: String, retryBlock: (() -> Void)?) {
        let words = errorWords(message: message)
        showWithTitle(words.title, message: words.message, retryBlock: retryBlock)
    }

    /// The words of an error known only by its sentence (redesign stage 3, E21). The sentence says what
    /// failed, so it is the title rather than a bare "Error" over it; "Internal error" says nothing, so
    /// it gives way to what to do.
    class func errorWords(message: String) -> (title: String, message: String?) {
        let empty = [String(localized: "error_internal_message"), String(localized: "error_general_unknown_message")]
        guard !message.isEmpty, !empty.contains(message) else {
            return (String(localized: "error_failed_generic"), String(localized: "error_try_again_message"))
        }
        return (message, nil)
    }

    /// `details` is the technical code for support: it waits under "Details" and can be copied there.
    class func showWithTitle(_ title: String, message: String? = nil, details: String? = nil, retryBlock: (() -> Void)?) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)


        if let retryBlock = retryBlock {
            alert.addAction(UIAlertAction(title: String(localized: "error_retry_button"), style: .default) { _ in
                retryBlock()
            })

            alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        }
        else {
            alert.addAction(UIAlertAction(title: String(localized: "error_ok_button"), style: .cancel, handler: nil))
        }

        if let details = details {
            alert.addAction(UIAlertAction(title: String(localized: "error_details"), style: .default) { _ in
                showDetails(details)
            })
        }

        guard let visible = visibleViewController() else {
            return
        }

        visible.present(alert, animated: true, completion: nil)
    }

    /// The code under "Details" on its own, with Copy: also for an error that stands under a field.
    class func showDetails(_ details: String) {
        let alert = UIAlertController(title: String(localized: "error_details"),
                                      message: String(localized: "error_details_format", details),
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: String(localized: "error_details_copy"), style: .default) { _ in
            UIPasteboard.general.string = details
        })
        alert.addAction(UIAlertAction(title: String(localized: "error_ok_button"), style: .cancel, handler: nil))

        visibleViewController()?.present(alert, animated: true, completion: nil)
    }

    fileprivate class func visibleViewController(_ rootViewController: UIViewController? = nil) -> UIViewController? {
        var root: UIViewController

        if let rootViewController = rootViewController {
            root = rootViewController
        }
        else {
            let appDelegate = UIApplication.shared.delegate as? AppDelegate

            guard let controller = appDelegate?.window?.rootViewController else {
                return nil
            }

            root = controller
        }

        guard let presented = root.presentedViewController else {
            return root
        }

        if let navigation = presented as? UINavigationController {
            return visibleViewController(navigation.topViewController)
        }
        if let tabBar = presented as? UITabBarController {
            return visibleViewController(tabBar.selectedViewController)
        }

        return presented
    }
}
