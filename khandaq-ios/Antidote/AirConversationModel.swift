// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

// The conversation with one person (design/redesign/02-plan.md, screens 5, 6, 7, 10 and 16) as plain
// values. Nothing here touches Realm or a view, so the rules (which state a message is in, where a run
// of one author starts, what the header, the hint under the field and the day strip say) can be tested
// on their own. ChatPrivateController and the Air conversation views read their words from here.

/// The state of one of our own messages, as the transcript and the tray draw it.
enum AirEntryStatus: Equatable {
    /// Incoming messages and notes to yourself: no mark at all.
    case none
    /// Waits on this device until the person is online (an amber clock, in the tray).
    case transit
    /// The person is online and the message is leaving right now (a faint clock).
    case sending
    /// Left this device, no receipt yet (one check).
    case sent
    /// The person's device confirmed it (two checks).
    case delivered
    /// The network refused it; "Retry" sends the same text again (red).
    case failed

    /// An outgoing text as objcTox stores it: `messageId` is -1 until toxcore has taken the message,
    /// `isDelivered` turns true with the receipt. Undelivered while the person is away means it waits
    /// for them: objcTox sends every undelivered text again when they come online.
    static func ofOutgoingText(isSaved: Bool, isDelivered: Bool, messageId: Int32, personOnline: Bool) -> AirEntryStatus {
        if isSaved {
            return .none
        }
        if isDelivered {
            return .delivered
        }
        if !personOnline {
            return .transit
        }
        return messageId == -1 ? .sending : .sent
    }

    /// The icon from the design-system sprite, nil for no mark.
    var iconName: String? {
        switch self {
            case .none:
                return nil
            case .transit, .sending:
                return "clock"
            case .sent:
                return "check"
            case .delivered:
                return "check-double"
            case .failed:
                return "alert"
        }
    }

    /// The word a screen reader says for the mark.
    var spokenText: String? {
        switch self {
            case .none:
                return nil
            case .transit:
                return String(localized: "air_delivery_transit")
            case .sending:
                return String(localized: "air_delivery_sending")
            case .sent:
                return String(localized: "air_delivery_sent")
            case .delivered:
                return String(localized: "air_delivery_delivered")
            case .failed:
                return String(localized: "air_delivery_failed")
        }
    }
}

/// What the transcript needs to know about a neighbouring entry to decide where a run starts.
struct AirRunKey: Equatable {
    /// The author: an empty string for ourselves, the friend's id otherwise.
    let author: String
    let date: Date
    /// Text entries share runs; a call or a file stands on its own row and breaks the run after it.
    let isText: Bool
}

enum AirTranscript {
    /// Two messages of one author further apart than this start a new run, so the name shows again.
    static let runGap: TimeInterval = 300

    /// An entry opens a new run (and shows its author) unless the entry before it in reading order is a
    /// text by the same author on the same day, written less than five minutes earlier.
    static func startsRun(_ entry: AirRunKey, after previous: AirRunKey?, calendar: Calendar = Calendar.current) -> Bool {
        guard let previous = previous else {
            return true
        }
        if !previous.isText || previous.author != entry.author {
            return true
        }
        if !calendar.isDate(previous.date, inSameDayAs: entry.date) {
            return true
        }
        return entry.date.timeIntervalSince(previous.date) > runGap
    }

    /// The first word of a name: the author label and the hints say "Amina", not "Amina Safina".
    static func shortName(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let first = trimmed.components(separatedBy: .whitespacesAndNewlines).first(where: { !$0.isEmpty }) ?? ""
        return first.isEmpty ? trimmed : first
    }
}

/// The day mark above the first entry of a day: "Today", "Yesterday", or the date.
enum AirDayMark {
    static func text(for date: Date, now: Date = Date(), locale: Locale, calendar: Calendar = Calendar.current) -> String {
        if calendar.isDate(date, inSameDayAs: now) {
            return String(localized: "air_day_today")
        }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(date, inSameDayAs: yesterday) {
            return String(localized: "air_day_yesterday")
        }
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        let sameYear = calendar.component(.year, from: date) == calendar.component(.year, from: now)
        formatter.setLocalizedDateFormatFromTemplate(sameYear ? "dMMMM" : "dMMMMy")
        return formatter.string(from: date)
    }
}

/// How the link to the person looks right now, for the mark beside the presence line and the path
/// picture in the dossier.
enum AirLinkState: Equatable {
    case direct
    case relay
    case none

    init(reach: AirReach) {
        switch reach {
            case .direct:
                self = .direct
            case .relay:
                self = .relay
            default:
                self = .none
        }
    }

    var spokenText: String {
        switch self {
            case .direct:
                return String(localized: "air_link_direct")
            case .relay:
                return String(localized: "air_link_relay")
            case .none:
                return String(localized: "air_link_none")
        }
    }
}

enum AirPresenceLine {
    /// The quiet line under the header: "online since 9:40, direct", "offline for 25 minutes", "typing".
    /// `onlineSince` is when this device saw the person come online; without it the time is left out.
    static func text(reach: AirReach, onlineSince: Date?, isTyping: Bool, time: (Date) -> String) -> String {
        if isTyping && reach.isLive {
            return String(localized: "air_presence_typing")
        }
        switch reach {
            case .direct:
                if let since = onlineSince {
                    return String(localized: "air_presence_direct_since_format", time(since))
                }
                return String(localized: "air_presence_direct")
            case .relay:
                if let since = onlineSince {
                    return String(localized: "air_presence_relay_since_format", time(since))
                }
                return String(localized: "air_presence_relay")
            case .pending:
                return String(localized: "air_presence_pending")
            case .connecting:
                return String(localized: "air_reach_connecting")
            case .away(let seconds):
                return awayText(seconds: seconds)
            case .group, .local:
                return ""
        }
    }

    static func awayText(seconds: TimeInterval) -> String {
        if seconds < 60 {
            return String(localized: "air_presence_away_just_now")
        }
        if seconds < 3600 {
            return String(localized: "air_presence_away_minutes", max(1, Int(seconds / 60)))
        }
        if seconds < 86400 {
            return String(localized: "air_presence_away_hours", max(1, Int(seconds / 3600)))
        }
        if seconds < 86400 * 7 {
            return String(localized: "air_presence_away_days", max(1, Int(seconds / 86400)))
        }
        return String(localized: "air_presence_away_long")
    }
}

/// The line under the message field: what happens to a message sent now.
struct AirComposeHint: Equatable {
    enum Dot: Equatable {
        case live
        case transit
        case none
    }

    let text: String
    let dot: Dot

    /// `name` is the person's short name; `contactExists` is false once the contact was removed.
    static func make(isSaved: Bool, contactExists: Bool, name: String, reach: AirReach) -> AirComposeHint {
        if isSaved {
            return AirComposeHint(text: String(localized: "air_compose_hint_saved"), dot: .none)
        }
        guard contactExists else {
            return AirComposeHint(text: "", dot: .none)
        }
        if reach.isLive {
            return AirComposeHint(text: String(localized: "air_compose_hint_live_format", name), dot: .live)
        }
        return AirComposeHint(text: String(localized: "air_compose_hint_wait_format", name), dot: .transit)
    }

    /// While our own node is not connected the hint says so rather than blaming the other side
    /// (E4): nothing is refused, the message waits on its way. Nil when the node is online.
    static func ownNode(_ state: AirNetworkState) -> AirComposeHint? {
        switch state {
            case .direct, .relay:
                return nil
            case .connecting:
                return AirComposeHint(text: String(localized: "air_compose_hint_self_connecting"), dot: .transit)
            case .offline:
                return AirComposeHint(text: String(localized: "air_compose_hint_self_offline"), dot: .transit)
        }
    }
}

/// The length counter under the field. Tox carries at most 1372 bytes in one message and toxcore
/// keeps 38 of them for the message id and time of msgV3, so objcTox cuts a longer text to 1334 bytes
/// without a word; the counter shows the room left before that, once only a tenth of it remains.
enum AirComposeLimit {
    static let bytes = 1334

    /// nil while there is plenty of room; otherwise the bytes left, negative once over the limit.
    static func remaining(forBytes count: Int) -> Int? {
        let left = bytes - count
        return Double(left) < Double(bytes) * 0.1 ? left : nil
    }

    static func counterText(remaining: Int) -> String {
        if remaining >= 0 {
            return String(localized: "air_compose_bytes_left", remaining)
        }
        return String(localized: "air_compose_bytes_over", -remaining)
    }

    /// A text that does not fit one message, in parts that do, in order: a text shared from another app
    /// has no counter, and objcTox would cut it short. A part ends at a line break or a space when one
    /// lies in its second half, otherwise at the last whole character that fits.
    static func parts(of text: String, maxBytes: Int = bytes) -> [String] {
        var parts = [String]()
        var rest = Substring(text)
        while rest.utf8.count > maxBytes {
            var end = rest.startIndex
            var used = 0
            for index in rest.indices {
                let size = rest[index].utf8.count
                if used + size > maxBytes {
                    break
                }
                used += size
                end = rest.index(after: index)
            }
            if end == rest.startIndex {
                // One character wider than a message: it goes alone.
                end = rest.index(after: end)
            }
            let head = rest[..<end]
            let secondHalf = head[head.index(head.startIndex, offsetBy: head.count / 2)...]
            let cut = secondHalf.lastIndex(where: { $0.isNewline })
                ?? secondHalf.lastIndex(where: { $0.isWhitespace })
                ?? end
            let part = rest[..<cut].trimmingCharacters(in: .whitespacesAndNewlines)
            if !part.isEmpty {
                parts.append(part)
            }
            rest = rest[cut...].drop { $0.isWhitespace }
        }
        let last = rest.trimmingCharacters(in: .whitespacesAndNewlines)
        if !last.isEmpty {
            parts.append(last)
        }
        return parts
    }
}

enum AirKeyFormat {
    /// A key in groups of four, so two people can read it to each other: "7F3A 91C0 5D2E ...".
    static func groups(_ key: String, size: Int = 4) -> [String] {
        let characters = Array(key.uppercased().filter { !$0.isWhitespace })
        guard size > 0, !characters.isEmpty else {
            return []
        }
        return stride(from: 0, to: characters.count, by: size).map {
            String(characters[$0..<min($0 + size, characters.count)])
        }
    }

    /// The groups in lines of `perLine`, joined by spaces and line breaks.
    static func lines(_ key: String, perLine: Int = 4) -> String {
        let all = groups(key)
        guard perLine > 0 else {
            return all.joined(separator: " ")
        }
        return stride(from: 0, to: all.count, by: perLine).map {
            all[$0..<min($0 + perLine, all.count)].joined(separator: " ")
        }.joined(separator: "\n")
    }
}

/// The 24 cells of the day strip. Each cell is a clock hour; it shows the most recent time that hour
/// came round, so the hours after now belong to yesterday and the strip always covers the last day.
struct AirDayStrip: Equatable {
    /// Index 0 is the hour from midnight to one o'clock.
    let online: [Bool]
    /// The current clock hour, outlined on the strip.
    let nowHour: Int

    var hasMarks: Bool {
        return online.contains(true)
    }

    /// `hours` holds the start of every hour the person was seen online, in seconds since 1970.
    static func make(hours: Set<Int>, now: Date, calendar: Calendar = Calendar.current) -> AirDayStrip {
        let nowHour = calendar.component(.hour, from: now)
        let today = calendar.startOfDay(for: now)
        var online = [Bool](repeating: false, count: 24)
        for hour in 0..<24 {
            let day = hour <= nowHour ? today : calendar.date(byAdding: .day, value: -1, to: today)
            guard let base = day, let start = calendar.date(bySettingHour: hour, minute: 0, second: 0, of: base) else {
                continue
            }
            online[hour] = hours.contains(Int(start.timeIntervalSince1970))
        }
        return AirDayStrip(online: online, nowHour: nowHour)
    }

    /// Runs of online hours as (from, to) on the clock: 7 to 9 is the hours 7 and 8.
    var ranges: [(from: Int, to: Int)] {
        var result: [(from: Int, to: Int)] = []
        var start: Int?
        for hour in 0...24 {
            let isOn = hour < 24 && online[hour]
            if isOn, start == nil {
                start = hour
            }
            else if !isOn, let from = start {
                result.append((from: from, to: hour))
                start = nil
            }
        }
        return result
    }

    /// What a screen reader says for the strip.
    func spokenText(locale: Locale, nowOnline: Bool) -> String {
        let parts = ranges.map { String(localized: "air_day_range_format", $0.from, $0.to) }
        var text: String
        if parts.isEmpty {
            text = String(localized: "air_day_a11y_none")
        }
        else {
            let formatter = ListFormatter()
            formatter.locale = locale
            text = String(localized: "air_day_a11y_format", formatter.string(from: parts) ?? parts.joined(separator: ", "))
        }
        if nowOnline {
            text += ". " + String(localized: "air_day_now_online")
        }
        return text
    }
}

/// When the person is usually online, from the last week of hourly marks: the longest run of clock
/// hours seen on the most days. Needs marks on at least two different days, or it would only repeat
/// the strip.
enum AirBestHours {
    static func find(hours: Set<Int>, now: Date, calendar: Calendar = Calendar.current) -> (from: Int, to: Int)? {
        let weekAgo = now.timeIntervalSince1970 - 7 * 86400
        var daysPerHour = [Set<Int>](repeating: Set<Int>(), count: 24)
        var allDays = Set<Int>()
        for start in hours where TimeInterval(start) >= weekAgo {
            let date = Date(timeIntervalSince1970: TimeInterval(start))
            let hour = calendar.component(.hour, from: date)
            let day = Int(calendar.startOfDay(for: date).timeIntervalSince1970)
            daysPerHour[hour].insert(day)
            allDays.insert(day)
        }
        guard allDays.count >= 2 else {
            return nil
        }
        let counts = daysPerHour.map { $0.count }
        guard let top = counts.max(), top >= 2 else {
            return nil
        }
        var best: (from: Int, to: Int)?
        var bestLength = 0
        var start: Int?
        for hour in 0...24 {
            let isTop = hour < 24 && counts[hour] == top
            if isTop, start == nil {
                start = hour
            }
            else if !isTop, let from = start {
                if hour - from > bestLength {
                    best = (from: from, to: hour)
                    bestLength = hour - from
                }
                start = nil
            }
        }
        // A person online almost all day has no "best time"; the strip says enough.
        guard let found = best, bestLength < 20 else {
            return nil
        }
        return found
    }
}
