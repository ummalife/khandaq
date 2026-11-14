// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

// The Air screen (design/redesign/02-plan.md, screens 1 and 2) as plain values. Nothing here touches
// Realm or a view; UIKit is imported only for IndexPath rows and sections. AirListDataSource turns
// chats, friends and requests into these values, and AirListBuilder decides the sections, their order,
// filtering, empty states and every count on the screen, so the rules can be tested on their own and
// no two numbers come from different places.

/// The filter pills. `all` is the resting state: tapping the active pill again returns to it.
enum AirFilter: Int {
    case all
    case requests
    case transit
    case favorites

    /// The pills, in the order they stand on screen.
    static let pills: [AirFilter] = [.requests, .transit, .favorites]

    /// Order for the sideways swipe on the list: everything first, then the pills from left to right.
    static let swipeOrder: [AirFilter] = [.all, .requests, .transit, .favorites]

    /// Kept for the life of the process: a theme or language rebuild keeps the filter, a cold start
    /// always opens on everything.
    static var current: AirFilter = .all

    /// Stable name for accessibility identifiers, which the UI tests use.
    var accessibilityName: String {
        switch self {
            case .all:
                return "all"
            case .requests:
                return "requests"
            case .transit:
                return "transit"
            case .favorites:
                return "favorites"
        }
    }
}

/// Our own node, for the quiet line under the name in the "you" block.
enum AirNetworkState: Equatable {
    /// Online, UDP: peers are reached directly.
    case direct
    /// Online through a TCP relay.
    case relay
    /// The device has a network, Tox is not connected yet.
    case connecting
    /// No network at all: whatever is sent waits on this device.
    case offline

    init(connection: OCTToxConnectionStatus, networkReachable: Bool) {
        switch connection {
            case .UDP:
                self = .direct
            case .TCP:
                self = .relay
            case .none:
                self = networkReachable ? .connecting : .offline
        }
    }
}

/// Our own node as every screen tells it (redesign stage 3, E4): the session keeps it current, and the
/// line under the name, the Network screen and the hint under the message field read it.
enum AirSelfNetwork {
    static let didChange = Notification.Name("KhandaqAirSelfNetworkDidChange")

    private(set) static var state: AirNetworkState = .connecting

    static func update(_ newState: AirNetworkState) {
        guard newState != state else {
            return
        }
        state = newState
        NotificationCenter.default.post(name: didChange, object: nil)
    }
}

/// How the person or group behind a row can be reached right now.
enum AirReach: Equatable {
    /// Online, the link goes straight to their device.
    case direct
    /// Online through a TCP relay.
    case relay
    /// Not online; seconds since they were last seen.
    case away(secondsAgo: TimeInterval)
    /// Added by our request and never online since: it has not been answered yet.
    case pending
    /// Not linked yet, but active within the last day while our own node has only just come online:
    /// their link is most likely still being set up (KHANDAQ #178), so the row says so instead of a
    /// stale "last seen".
    case connecting
    /// A group and how many of its members are online; zero when nobody is or it is not connected.
    case group(online: Int)
    /// Saved messages: never sent anywhere.
    case local

    /// A message sent now would arrive now.
    var isLive: Bool {
        switch self {
            case .direct, .relay:
                return true
            case .group(let online):
                return online > 0
            case .away, .pending, .connecting, .local:
                return false
        }
    }

    /// `selfLinkSettling`: our own node came online less than a minute ago
    /// (SelfConnectionTracker.friendLinksMayStillBeConnecting). `askedAt`: for a contact made by
    /// accepting their request, when it came (AirLocalStore.askedAt); nil for a contact made by our own
    /// request.
    static func friend(connection: OCTToxConnectionStatus,
                       lastSeenOnlineInterval: TimeInterval,
                       now: TimeInterval,
                       selfLinkSettling: Bool = false,
                       askedAt: Date? = nil) -> AirReach {
        switch connection {
            case .UDP:
                return .direct
            case .TCP:
                return .relay
            case .none:
                // KHANDAQ (QA 2026-11-14, F2): toxcore has no last-online time for anyone who has not
                // connected since being added, also when they asked us and we accepted, and that read as
                // our own request waiting for a reply. They were online when their request came, which
                // stands for the time they were last seen until toxcore has one.
                let lastSeen = lastSeenOnlineInterval > 0 ? lastSeenOnlineInterval : (askedAt?.timeIntervalSince1970 ?? 0)
                guard lastSeen > 0 else {
                    return .pending
                }
                let secondsAgo = max(0, now - lastSeen)
                if selfLinkSettling && secondsAgo < 86400 {
                    return .connecting
                }
                return .away(secondsAgo: secondsAgo)
        }
    }

    /// How many of a person's undelivered messages wait in transit: all of them while the person is
    /// away, none while they are online, when the conversation shows the same messages as leaving or
    /// sent (AirEntryStatus.ofOutgoingText).
    func messagesInTransit(undelivered: Int) -> Int {
        return isLive ? 0 : undelivered
    }
}

/// The state of our own last message in a row, drawn in front of the preview.
enum AirDelivery: Equatable {
    case transit
    case sent
    case delivered

    /// Our own last text to one person, by the conversation's rule (AirEntryStatus.ofOutgoingText):
    /// in transit while the person is away, a clock while it is leaving, then sent and delivered.
    static func ofOutgoingText(isDelivered: Bool, messageId: Int32, personOnline: Bool) -> AirDelivery {
        switch AirEntryStatus.ofOutgoingText(isSaved: false, isDelivered: isDelivered, messageId: messageId, personOnline: personOnline) {
            case .delivered:
                return .delivered
            case .sent:
                return .sent
            default:
                return .transit
        }
    }
}

/// One row of the list: a conversation, or a contact who has no conversation yet.
struct AirEntry: Equatable {
    enum Kind: Equatable {
        case direct
        case group
        case saved
        /// A contact without a conversation: opening the row starts one.
        case contact
    }

    enum PreviewStyle: Equatable {
        case message
        case draft
        case typing
        /// A quiet explanation instead of a message (a request waiting for an answer, saved messages).
        case hint
    }

    /// The chat's unique identifier, or the friend's public key for a contact without a chat.
    let id: String
    let kind: Kind
    let title: String
    let reach: AirReach
    let preview: String
    let previewStyle: PreviewStyle
    let lastDelivery: AirDelivery?
    let timeText: String
    /// When the conversation last moved (lastActivityDateInterval); zero for a contact without one.
    let activity: TimeInterval
    let transitCount: Int
    let unreadCount: Int
    let isFavorite: Bool
    let avatarData: Data?
    /// AirTone index for the initials, from the friend's public key or the group's chat id.
    let tone: Int

    var section: AirSectionKind {
        if kind == .saved {
            return .saved
        }
        return reach.isLive ? .live : .away
    }
}

/// What a card above the sections asks for.
enum AirRequestKind: Equatable {
    /// Someone asks to become a contact; only their key is known.
    case contact
    /// A contact invites you to a group (E12). Joining goes through them, so it works only while
    /// they are online.
    case group(name: String, inviter: String, inviterOnline: Bool)
}

/// An incoming friend request or group invitation, drawn as a card above the sections.
struct AirRequest: Equatable {
    let id: String
    /// The person asking, or for an invitation the contact who sent it.
    let publicKey: String
    let message: String
    let timeText: String
    let date: TimeInterval
    let kind: AirRequestKind

    init(id: String, publicKey: String, message: String, timeText: String, date: TimeInterval, kind: AirRequestKind = .contact) {
        self.id = id
        self.publicKey = publicKey
        self.message = message
        self.timeText = timeText
        self.date = date
        self.kind = kind
    }
}

/// A message that matched the command line, listed under the rows while searching.
struct AirMessageHit: Equatable {
    let id: String
    let chatId: String
    let title: String
    let snippet: String
    let timeText: String
}

enum AirSectionKind: Equatable {
    case requests
    case live
    case away
    case saved
    case messages

    /// Request cards carry their own label; every other section has a heading with its count.
    var hasHeading: Bool {
        return self != .requests
    }

    /// The order sections stand in, top to bottom.
    static let order: [AirSectionKind] = [.requests, .live, .away, .saved, .messages]
}

enum AirItem: Equatable {
    case request(AirRequest)
    case entry(AirEntry)
    case message(AirMessageHit)

    /// Identity for animated updates: a person keeps the same id when their row changes section.
    var id: String {
        switch self {
            case .request(let request):
                return "request:" + request.id
            case .entry(let entry):
                return "entry:" + entry.id
            case .message(let hit):
                return "message:" + hit.id
        }
    }
}

/// What the list says when it has nothing to show for the current filter or query.
enum AirEmptyState: Equatable {
    /// No contacts and no requests yet: only saved messages.
    case nothingYet
    /// The command line matched nothing.
    case noMatch
    case noRequests
    case noTransit
    /// Nothing starred: only saved messages.
    case noFavorites
}

/// The numbers on the pills, counted over everything whatever the filter or the query.
struct AirCounts: Equatable {
    var requests = 0
    var transit = 0
}

struct AirSnapshot: Equatable {
    struct Section: Equatable {
        let kind: AirSectionKind
        let items: [AirItem]
    }

    let sections: [Section]
    let counts: AirCounts
    let emptyState: AirEmptyState?

    static let empty = AirSnapshot(sections: [], counts: AirCounts(), emptyState: nil)

    func item(at indexPath: IndexPath) -> AirItem? {
        guard indexPath.section >= 0, indexPath.section < sections.count else {
            return nil
        }
        let items = sections[indexPath.section].items
        guard indexPath.row >= 0, indexPath.row < items.count else {
            return nil
        }
        return items[indexPath.row]
    }

    func entry(at indexPath: IndexPath) -> AirEntry? {
        if case .entry(let entry)? = item(at: indexPath) {
            return entry
        }
        return nil
    }

    var itemCount: Int {
        return sections.reduce(0) { $0 + $1.items.count }
    }
}

enum AirListBuilder {
    static func build(entries: [AirEntry],
                      requests: [AirRequest],
                      messageHits: [AirMessageHit] = [],
                      filter: AirFilter,
                      query: String) -> AirSnapshot {
        var counts = AirCounts()
        counts.requests = requests.count
        counts.transit = entries.reduce(0) { $0 + $1.transitCount }

        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let searching = !needle.isEmpty

        let visible = entries.filter { entry in
            guard matches(entry, filter: filter) else {
                return false
            }
            return !searching || entry.title.range(of: needle, options: [.caseInsensitive, .diacriticInsensitive]) != nil
        }

        var sections: [AirSnapshot.Section] = []

        if !searching && (filter == .all || filter == .requests) && !requests.isEmpty {
            let newestFirst = requests.sorted { first, second in
                if first.date != second.date {
                    return first.date > second.date
                }
                return first.id < second.id
            }
            sections.append(AirSnapshot.Section(kind: .requests, items: newestFirst.map { .request($0) }))
        }

        for kind in [AirSectionKind.live, .away, .saved] {
            let items = visible.filter { $0.section == kind }.sorted(by: rowOrder)
            if !items.isEmpty {
                sections.append(AirSnapshot.Section(kind: kind, items: items.map { .entry($0) }))
            }
        }

        if searching && !messageHits.isEmpty {
            sections.append(AirSnapshot.Section(kind: .messages, items: messageHits.map { .message($0) }))
        }

        let state = emptyState(sections: sections, entries: entries, visible: visible, requests: requests,
                               filter: filter, searching: searching)
        return AirSnapshot(sections: sections, counts: counts, emptyState: state)
    }

    static func matches(_ entry: AirEntry, filter: AirFilter) -> Bool {
        switch filter {
            case .all:
                return true
            case .requests:
                return entry.reach == .pending
            case .transit:
                return entry.transitCount > 0
            case .favorites:
                return entry.isFavorite || entry.kind == .saved
        }
    }

    /// Latest activity first; contacts without a conversation (no activity) last, by name.
    static func rowOrder(_ first: AirEntry, _ second: AirEntry) -> Bool {
        if first.activity != second.activity {
            return first.activity > second.activity
        }
        let byName = first.title.localizedCaseInsensitiveCompare(second.title)
        if byName != .orderedSame {
            return byName == .orderedAscending
        }
        return first.id < second.id
    }

    private static func emptyState(sections: [AirSnapshot.Section],
                                   entries: [AirEntry],
                                   visible: [AirEntry],
                                   requests: [AirRequest],
                                   filter: AirFilter,
                                   searching: Bool) -> AirEmptyState? {
        if searching {
            return sections.isEmpty ? .noMatch : nil
        }

        switch filter {
            case .all:
                let hasPeople = !requests.isEmpty || entries.contains { $0.kind != .saved }
                return hasPeople ? nil : .nothingYet
            case .requests:
                return sections.isEmpty ? .noRequests : nil
            case .transit:
                return sections.isEmpty ? .noTransit : nil
            case .favorites:
                return visible.contains { $0.kind != .saved } ? nil : .noFavorites
        }
    }
}

/// A row that changed position between two snapshots.
struct AirRowMove: Equatable {
    let from: IndexPath
    let to: IndexPath
}

/// What changed between two snapshots, in the terms UITableView's batch updates take: deletions and
/// moves' sources by old index paths, insertions and moves' destinations by new ones.
struct AirListChanges: Equatable {
    var deletedSections = IndexSet()
    var insertedSections = IndexSet()
    var deletedRows: [IndexPath] = []
    var insertedRows: [IndexPath] = []
    var movedRows: [AirRowMove] = []

    var count: Int {
        return deletedSections.count + insertedSections.count + deletedRows.count + insertedRows.count + movedRows.count
    }

    var isEmpty: Bool {
        return count == 0
    }
}

enum AirListDiff {
    /// Beyond this many operations an animation reads as noise; the caller reloads instead.
    static let maximumAnimatedChanges = 40

    /**
     The batch update that turns `old` into `new`, or nil when it should not be animated: too many
     changes, or a set the table could reject.

     Only rows that really changed place are moved: within a section, the rows kept in their relative
     order (one longest increasing run) stay put and the table shifts them itself, so a person who comes
     online is the one row that slides. A row cannot move out of a section that disappears or into one
     that appears in the same update, so such a row is removed and inserted instead.
     */
    static func changes(from old: AirSnapshot, to new: AirSnapshot) -> AirListChanges? {
        var changes = AirListChanges()

        let oldKinds = old.sections.map { $0.kind }
        let newKinds = new.sections.map { $0.kind }

        for (index, kind) in oldKinds.enumerated() where !newKinds.contains(kind) {
            changes.deletedSections.insert(index)
        }
        for (index, kind) in newKinds.enumerated() where !oldKinds.contains(kind) {
            changes.insertedSections.insert(index)
        }

        var oldPaths: [String: IndexPath] = [:]
        for (section, block) in old.sections.enumerated() {
            for (row, item) in block.items.enumerated() {
                oldPaths[item.id] = IndexPath(row: row, section: section)
            }
        }
        var newPaths: [String: IndexPath] = [:]
        for (section, block) in new.sections.enumerated() {
            for (row, item) in block.items.enumerated() {
                newPaths[item.id] = IndexPath(row: row, section: section)
            }
        }
        // Ids must be unique on both sides, or the paths above lost a row.
        guard oldPaths.count == old.itemCount, newPaths.count == new.itemCount else {
            return nil
        }

        for (id, from) in oldPaths where newPaths[id] == nil {
            if !changes.deletedSections.contains(from.section) {
                changes.deletedRows.append(from)
            }
        }
        for (id, to) in newPaths where oldPaths[id] == nil {
            if !changes.insertedSections.contains(to.section) {
                changes.insertedRows.append(to)
            }
        }

        // Rows on both sides: which keep their place and which move.
        for (newSection, block) in new.sections.enumerated() {
            guard !changes.insertedSections.contains(newSection) else {
                for item in block.items {
                    if let from = oldPaths[item.id], !changes.deletedSections.contains(from.section) {
                        changes.deletedRows.append(from)
                    }
                }
                continue
            }
            guard let oldSection = oldKinds.firstIndex(of: block.kind) else {
                continue
            }

            var stayed: [(row: Int, oldRow: Int)] = []
            for (row, item) in block.items.enumerated() {
                guard let from = oldPaths[item.id] else {
                    continue
                }
                let to = IndexPath(row: row, section: newSection)
                if from.section == oldSection {
                    stayed.append((row: row, oldRow: from.row))
                }
                else if changes.deletedSections.contains(from.section) {
                    changes.insertedRows.append(to)
                }
                else {
                    changes.movedRows.append(AirRowMove(from: from, to: to))
                }
            }

            let kept = longestIncreasingRun(stayed.map { $0.oldRow })
            for (position, pair) in stayed.enumerated() where !kept.contains(position) {
                changes.movedRows.append(AirRowMove(from: IndexPath(row: pair.oldRow, section: oldSection),
                                                    to: IndexPath(row: pair.row, section: newSection)))
            }
        }

        changes.deletedRows.sort()
        changes.insertedRows.sort()
        changes.movedRows.sort { $0.to < $1.to }

        guard changes.count <= maximumAnimatedChanges, isConsistent(changes, old: old, new: new) else {
            return nil
        }
        return changes
    }

    /// Positions (into `values`) of one longest strictly increasing run.
    static func longestIncreasingRun(_ values: [Int]) -> Set<Int> {
        var tails: [Int] = []
        var previous = [Int](repeating: -1, count: values.count)

        for (index, value) in values.enumerated() {
            var low = 0
            var high = tails.count
            while low < high {
                let middle = (low + high) / 2
                if values[tails[middle]] < value {
                    low = middle + 1
                }
                else {
                    high = middle
                }
            }
            if low > 0 {
                previous[index] = tails[low - 1]
            }
            if low == tails.count {
                tails.append(index)
            }
            else {
                tails[low] = index
            }
        }

        var result = Set<Int>()
        var cursor = tails.last ?? -1
        while cursor >= 0 {
            result.insert(cursor)
            cursor = previous[cursor]
        }
        return result
    }

    /// The count rule UITableView enforces, checked before handing the update over: for every section
    /// on both sides, new rows = old rows - removed - moved out + inserted + moved in.
    private static func isConsistent(_ changes: AirListChanges, old: AirSnapshot, new: AirSnapshot) -> Bool {
        for (newSection, block) in new.sections.enumerated() where !changes.insertedSections.contains(newSection) {
            guard let oldSection = old.sections.firstIndex(where: { $0.kind == block.kind }) else {
                return false
            }
            let removed = changes.deletedRows.filter { $0.section == oldSection }.count
            let movedOut = changes.movedRows.filter { $0.from.section == oldSection }.count
            let inserted = changes.insertedRows.filter { $0.section == newSection }.count
            let movedIn = changes.movedRows.filter { $0.to.section == newSection }.count
            if old.sections[oldSection].items.count - removed - movedOut + inserted + movedIn != block.items.count {
                return false
            }
        }
        return true
    }
}

/// What the command line holds: a search, a whole Tox ID that opens "new contact", or the start of one.
enum AirCommand: Equatable {
    case empty
    case query(String)
    /// 76 hex characters: "New contact" opens with them and checks them there (a typo is not repaired).
    case contactAddress(String)
    /// 12 hex characters or more with a digit among them: the row "New contact with this ID" above the list.
    case partialAddress(String)
    /// A group key that says so ("khandaq:group:" or a khandaq://group/ link): "Join a group" opens with it.
    case groupKey(String)

    static func parse(_ raw: String) -> AirCommand {
        if let key = AirGroupKey.command(raw) {
            return .groupKey(key)
        }
        if let hex = AirToxId.commandCandidate(raw), hex.contains(where: { $0.isNumber }) {
            return hex.count == AirToxId.length ? .contactAddress(hex) : .partialAddress(hex)
        }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? .empty : .query(trimmed)
    }
}
