// Network event ring buffer, connection quality, debug screen support.

import Foundation

enum ConnectionQualityLevel: String {
    case strong
    case medium
    case weak
    case offline
}

final class ConnectionQualityMonitor {
    static let shared = ConnectionQualityMonitor()

    private(set) var level: ConnectionQualityLevel = .strong
    private(set) var estimatedRttMs: Int = 80
    private var bootstrapStart: Date?

    func onBootstrapStarted() {
        bootstrapStart = Date()
    }

    func onBootstrapFinished(connected: Bool) {
        if let start = bootstrapStart {
            let elapsed = Int(Date().timeIntervalSince(start) * 1000)
            estimatedRttMs = (estimatedRttMs * 3 + elapsed) / 4
            bootstrapStart = nil
        }

        if !connected {
            level = .offline
            return
        }

        if estimatedRttMs < 300 {
            level = .strong
        } else if estimatedRttMs < 1500 {
            level = .medium
        } else {
            level = .weak
        }

        NetworkDiagnosticsLog.log("connection_quality", detail: "\(level.rawValue) rtt_ms=\(estimatedRttMs)")
    }

    func onInternetLost() {
        level = .offline
    }

    var adaptiveChunkPayloadBytes: Int {
        switch level {
        case .weak: return 256
        case .medium: return 512
        case .strong, .offline: return 1024
        }
    }
}

final class NetworkDiagnosticsLog {
    private static let maxLines = 500
    private static var lines: [String] = []
    private static let lock = NSLock()

    static func log(_ event: String, detail: String = "") {
        let ts = ISO8601DateFormatter().string(from: Date())
        let line = "\(ts) [\(event)] \(detail)"
        lock.lock()
        lines.append(line)
        if lines.count > maxLines {
            lines.removeFirst(lines.count - maxLines)
        }
        lock.unlock()
    }

    static func snapshot() -> String {
        lock.lock()
        defer { lock.unlock() }
        return lines.joined(separator: "\n")
    }
}
