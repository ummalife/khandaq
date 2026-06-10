// Khandaq 0.2 — silent FCM token apply (mirrors Android HelperRelay.apply_notification_token_auto)

import Foundation
import Firebase

final class KhandaqPushManager {
    static let shared = KhandaqPushManager()
    static let appliedTokenKey = "khandaq/fcm-token-applied"

    private weak var toxManager: OCTManager?

    private init() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleFCMToken(_:)),
            name: Notification.Name("FCMToken"),
            object: nil
        )
    }

    func bind(toxManager: OCTManager) {
        self.toxManager = toxManager
        guard FirebaseApp.app() != nil else {
            return
        }
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let token = Messaging.messaging().fcmToken
            guard let token = token, token.count >= 10 else {
                return
            }
            DispatchQueue.main.async {
                self?.applyNotificationTokenAuto(token)
            }
        }
    }

    func unbind() {
        toxManager = nil
    }

    @objc private func handleFCMToken(_ notification: Notification) {
        guard let token = notification.userInfo?["token"] as? String else {
            return
        }
        applyNotificationTokenAuto(token)
    }

    func applyNotificationTokenAuto(_ token: String) {
        guard token.count >= 10 else {
            return
        }

        let stored = UserDefaults.standard.string(forKey: KhandaqPushManager.appliedTokenKey)
        if token == stored {
            return
        }

        UserDefaults.standard.set(token, forKey: KhandaqPushManager.appliedTokenKey)

        guard let manager = toxManager else {
            return
        }

        manager.chats.sendOwnPush()
        manager.chats.broadcastOwnPushURLToConnectedFriends()
    }
}
