/*
    Copyright © 2026 by The Khandaq Project

    This file is part of Khandaq, a Qt-based graphical interface for Tox.

    Khandaq is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Khandaq is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Khandaq.  If not, see <http://www.gnu.org/licenses/>.
*/

#include "airmodel.h"

#include "src/core/toxid.h"

#include <QCollator>
#include <QHash>
#include <QTextBoundaryFinder>

#include <algorithm>
#include <limits>

namespace Air {

namespace {
constexpr qint64 oneDaySeconds = 86400;

const QChar zeroWidthNonJoiner{0x200C};

/** The "tox:"-style prefixes a pasted ID may carry; only one comes off. */
const QStringList& addressPrefixes()
{
    static const QStringList prefixes{QStringLiteral("TOX://"),  QStringLiteral("KHANDAQ://"),
                                      QStringLiteral("TOX:"),    QStringLiteral("KHANDAQ:"),
                                      QStringLiteral("TOX-ID:"), QStringLiteral("TOXID:")};
    return prefixes;
}

bool isInvisibleOrSpace(uint codePoint)
{
    if ((codePoint >= 0x200B && codePoint <= 0x200D) || codePoint == 0xFEFF || codePoint == 0x2060
        || codePoint == 0x00AD || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x00A0
        || codePoint == 0x202F || codePoint == 0x3000) {
        return true;
    }
    // ISO controls, then every Unicode space: what Java's isWhitespace, isISOControl and
    // isSpaceChar cover together on Android.
    if (codePoint <= 0x1F || (codePoint >= 0x7F && codePoint <= 0x9F)) {
        return true;
    }
    return QChar::isSpace(codePoint);
}

bool isMark(uint codePoint)
{
    const QChar::Category category = QChar::category(codePoint);
    return category == QChar::Mark_NonSpacing || category == QChar::Mark_SpacingCombining
           || category == QChar::Mark_Enclosing;
}

QVector<uint> codePoints(const QString& text)
{
    return text.toUcs4();
}

QString fromCodePoint(uint codePoint)
{
    return QString::fromUcs4(&codePoint, 1);
}

/** The words of a title: runs of characters between Unicode spaces. */
QStringList splitWords(const QString& text)
{
    QStringList words;
    QString current;
    for (uint codePoint : codePoints(text)) {
        if (QChar::isSpace(codePoint)) {
            if (!current.isEmpty()) {
                words.append(current);
                current.clear();
            }
            continue;
        }
        current.append(fromCodePoint(codePoint));
    }
    if (!current.isEmpty()) {
        words.append(current);
    }
    return words;
}

QString emptyIfNull(const QString& text)
{
    return text.isNull() ? QString{""} : text;
}
} // namespace

int compareTitles(const QString& first, const QString& second)
{
    static thread_local QCollator collator = [] {
        QCollator c;
        c.setCaseSensitivity(Qt::CaseInsensitive);
        return c;
    }();
    return collator.compare(first, second);
}

// ---- filters and network ----------------------------------------------------------------------

QVector<Filter> pillFilters()
{
    return {Filter::Requests, Filter::Transit, Filter::Favorites};
}

QString filterName(Filter filter)
{
    switch (filter) {
    case Filter::Requests:
        return QStringLiteral("requests");
    case Filter::Transit:
        return QStringLiteral("transit");
    case Filter::Favorites:
        return QStringLiteral("favorites");
    case Filter::All:
        break;
    }
    return QStringLiteral("all");
}

Filter filterFromName(const QString& name)
{
    for (Filter filter : {Filter::Requests, Filter::Transit, Filter::Favorites}) {
        if (filterName(filter) == name) {
            return filter;
        }
    }
    return Filter::All;
}

NetworkState networkState(int toxConnection, bool networkReachable)
{
    if (toxConnection == toxConnectionUdp) {
        return NetworkState::Direct;
    }
    if (toxConnection == toxConnectionTcp) {
        return NetworkState::Relay;
    }
    return networkReachable ? NetworkState::Connecting : NetworkState::Offline;
}

// ---- reach --------------------------------------------------------------------------------------

namespace {
Reach makeReach(Reach::Kind kind, qint64 secondsAgo, int online)
{
    Reach reach;
    reach.kind = kind;
    reach.secondsAgo = secondsAgo;
    reach.online = online;
    return reach;
}
} // namespace

Reach Reach::direct()
{
    return makeReach(Kind::Direct, 0, 0);
}

Reach Reach::relay()
{
    return makeReach(Kind::Relay, 0, 0);
}

Reach Reach::pending()
{
    return makeReach(Kind::Pending, 0, 0);
}

Reach Reach::connecting()
{
    return makeReach(Kind::Connecting, 0, 0);
}

Reach Reach::local()
{
    return makeReach(Kind::Local, 0, 0);
}

Reach Reach::away(qint64 secondsAgo)
{
    return makeReach(Kind::Away, std::max<qint64>(0, secondsAgo), 0);
}

Reach Reach::awayLongAgo()
{
    return makeReach(Kind::Away, std::numeric_limits<qint64>::max(), 0);
}

Reach Reach::group(int online)
{
    return makeReach(Kind::Group, 0, std::max(0, online));
}

Reach Reach::friendReach(int toxConnection, qint64 lastSeenSeconds, qint64 nowSeconds,
                         bool selfLinkSettling)
{
    if (toxConnection == toxConnectionUdp) {
        return direct();
    }
    if (toxConnection == toxConnectionTcp) {
        return relay();
    }
    if (lastSeenSeconds <= 0) {
        return pending();
    }
    const qint64 secondsAgo = std::max<qint64>(0, nowSeconds - lastSeenSeconds);
    if (selfLinkSettling && secondsAgo < oneDaySeconds) {
        return connecting();
    }
    return away(secondsAgo);
}

bool Reach::isLive() const
{
    switch (kind) {
    case Kind::Direct:
    case Kind::Relay:
        return true;
    case Kind::Group:
        return online > 0;
    case Kind::Away:
    case Kind::Pending:
    case Kind::Connecting:
    case Kind::Local:
        break;
    }
    return false;
}

int Reach::messagesInTransit(int undelivered) const
{
    return isLive() ? 0 : undelivered;
}

Delivery outgoingTextDelivery(bool isDelivered, bool handedOver, bool personOnline)
{
    if (isDelivered) {
        return Delivery::Delivered;
    }
    return personOnline && handedOver ? Delivery::Sent : Delivery::Transit;
}

bool Reach::operator==(const Reach& other) const
{
    return kind == other.kind && secondsAgo == other.secondsAgo && online == other.online;
}

bool Reach::operator!=(const Reach& other) const
{
    return !(*this == other);
}

// ---- entries, requests, hits, items -------------------------------------------------------------

bool sectionHasHeading(SectionKind kind)
{
    return kind != SectionKind::Requests;
}

QString sectionName(SectionKind kind)
{
    switch (kind) {
    case SectionKind::Requests:
        return QStringLiteral("requests");
    case SectionKind::Live:
        return QStringLiteral("live");
    case SectionKind::Away:
        return QStringLiteral("away");
    case SectionKind::Saved:
        return QStringLiteral("saved");
    case SectionKind::Messages:
        return QStringLiteral("messages");
    }
    return QString{};
}

SectionKind Entry::section() const
{
    if (kind == EntryKind::Saved) {
        return SectionKind::Saved;
    }
    return reach.isLive() ? SectionKind::Live : SectionKind::Away;
}

bool Entry::operator==(const Entry& other) const
{
    return id == other.id && kind == other.kind && title == other.title && reach == other.reach
           && preview == other.preview && previewStyle == other.previewStyle
           && lastDelivery == other.lastDelivery && timeText == other.timeText
           && activity == other.activity && transitCount == other.transitCount
           && unreadCount == other.unreadCount && isFavorite == other.isFavorite
           && mentioned == other.mentioned && photoKey == other.photoKey && tone == other.tone;
}

bool Entry::operator!=(const Entry& other) const
{
    return !(*this == other);
}

Request Request::make(const QString& publicKey, const QString& message, const QString& timeText,
                      qint64 date)
{
    Request request;
    request.publicKey = emptyIfNull(publicKey).toUpper();
    request.id = request.publicKey;
    request.message = emptyIfNull(message).trimmed();
    request.timeText = emptyIfNull(timeText);
    request.date = date;
    return request;
}

Request Request::invitation(const QString& inviteId, const QString& inviterKey,
                            const QString& groupName, const QString& inviterName,
                            bool inviterOnline, const QString& timeText, qint64 date)
{
    Request request;
    request.kind = Kind::Group;
    request.id = invitationId(inviteId);
    request.publicKey = emptyIfNull(inviterKey).toUpper();
    request.groupName = emptyIfNull(groupName).trimmed();
    request.inviterName = emptyIfNull(inviterName).trimmed();
    request.inviterOnline = inviterOnline;
    request.timeText = emptyIfNull(timeText);
    request.date = date;
    return request;
}

QString Request::invitationId(const QString& inviteId)
{
    return QStringLiteral("invite:") + inviteId;
}

bool Request::isInvitation() const
{
    return kind == Kind::Group;
}

bool Request::operator==(const Request& other) const
{
    return kind == other.kind && id == other.id && publicKey == other.publicKey
           && message == other.message && groupName == other.groupName
           && inviterName == other.inviterName && inviterOnline == other.inviterOnline
           && timeText == other.timeText && date == other.date;
}

bool MessageHit::operator==(const MessageHit& other) const
{
    return id == other.id && chatId == other.chatId && title == other.title
           && snippet == other.snippet && timeText == other.timeText;
}

Item Item::of(const Request& request)
{
    Item item;
    item.type = Type::Request;
    item.request = request;
    return item;
}

Item Item::of(const Entry& entry)
{
    Item item;
    item.type = Type::Entry;
    item.entry = entry;
    return item;
}

Item Item::of(const MessageHit& hit)
{
    Item item;
    item.type = Type::Message;
    item.message = hit;
    return item;
}

QString Item::id() const
{
    switch (type) {
    case Type::Request:
        return QStringLiteral("request:") + request.id;
    case Type::Entry:
        return QStringLiteral("entry:") + entry.id;
    case Type::Message:
        return QStringLiteral("message:") + message.id;
    }
    return QString{};
}

bool Item::operator==(const Item& other) const
{
    if (type != other.type) {
        return false;
    }
    switch (type) {
    case Type::Request:
        return request == other.request;
    case Type::Entry:
        return entry == other.entry;
    case Type::Message:
        return message == other.message;
    }
    return false;
}

bool Counts::operator==(const Counts& other) const
{
    return requests == other.requests && transit == other.transit;
}

QString Row::id() const
{
    if (type == Type::Header) {
        return QStringLiteral("header:") + sectionName(section);
    }
    return item.id();
}

int Snapshot::itemCount() const
{
    int total = 0;
    for (const Section& block : sections) {
        total += block.items.size();
    }
    return total;
}

const Section* Snapshot::section(SectionKind kind) const
{
    for (const Section& block : sections) {
        if (block.kind == kind) {
            return &block;
        }
    }
    return nullptr;
}

QVector<Row> Snapshot::rows() const
{
    QVector<Row> flat;
    for (const Section& block : sections) {
        if (sectionHasHeading(block.kind)) {
            Row heading;
            heading.type = Row::Type::Header;
            heading.section = block.kind;
            heading.count = block.items.size();
            flat.append(heading);
        }
        for (const Item& item : block.items) {
            Row row;
            row.section = block.kind;
            row.item = item;
            switch (item.type) {
            case Item::Type::Request:
                row.type = Row::Type::Request;
                break;
            case Item::Type::Entry:
                row.type = Row::Type::Entry;
                break;
            case Item::Type::Message:
                row.type = Row::Type::Message;
                break;
            }
            flat.append(row);
        }
    }
    return flat;
}

// ---- building ---------------------------------------------------------------------------------

bool matches(const Entry& entry, Filter filter)
{
    switch (filter) {
    case Filter::Requests:
        return entry.reach.kind == Reach::Kind::Pending;
    case Filter::Transit:
        return entry.transitCount > 0;
    case Filter::Favorites:
        return entry.isFavorite || entry.kind == EntryKind::Saved;
    case Filter::All:
        break;
    }
    return true;
}

bool rowOrder(const Entry& first, const Entry& second)
{
    if (first.activity != second.activity) {
        return first.activity > second.activity;
    }
    const int byName = compareTitles(first.title, second.title);
    if (byName != 0) {
        return byName < 0;
    }
    return first.id < second.id;
}

namespace {
EmptyState emptyStateFor(const QVector<Section>& sections, const QVector<Entry>& entries,
                         const QVector<Entry>& visible, const QVector<Request>& requests,
                         Filter filter, bool searching, bool idStart)
{
    if (searching) {
        // While the row "New contact with this ID" stands over the list, "nothing found" says
        // nothing new.
        return sections.isEmpty() && !idStart ? EmptyState::NoMatch : EmptyState::None;
    }

    switch (filter) {
    case Filter::Requests:
        return sections.isEmpty() ? EmptyState::NoRequests : EmptyState::None;
    case Filter::Transit:
        return sections.isEmpty() ? EmptyState::NoTransit : EmptyState::None;
    case Filter::Favorites:
        for (const Entry& entry : visible) {
            if (entry.kind != EntryKind::Saved) {
                return EmptyState::None;
            }
        }
        return EmptyState::NoFavorites;
    case Filter::All:
        break;
    }

    if (!requests.isEmpty()) {
        return EmptyState::None;
    }
    for (const Entry& entry : entries) {
        if (entry.kind != EntryKind::Saved) {
            return EmptyState::None;
        }
    }
    return EmptyState::NothingYet;
}
} // namespace

Snapshot build(const QVector<Entry>& entries, const QVector<Request>& requests,
               const QVector<MessageHit>& messageHits, Filter filter, const QString& query)
{
    Snapshot snapshot;
    snapshot.counts.requests = requests.size();
    for (const Entry& entry : entries) {
        snapshot.counts.transit += entry.transitCount;
    }

    const QString needle = query.trimmed();
    const bool searching = !needle.isEmpty();
    const QString foldedNeedle = fold(needle);

    QVector<Entry> visible;
    for (const Entry& entry : entries) {
        if (!matches(entry, filter)) {
            continue;
        }
        if (searching && !fold(entry.title).contains(foldedNeedle)) {
            continue;
        }
        visible.append(entry);
    }

    if (!searching && (filter == Filter::All || filter == Filter::Requests) && !requests.isEmpty()) {
        QVector<Request> newestFirst = requests;
        std::stable_sort(newestFirst.begin(), newestFirst.end(),
                         [](const Request& first, const Request& second) {
                             if (first.date != second.date) {
                                 return first.date > second.date;
                             }
                             return first.id < second.id;
                         });
        Section block;
        block.kind = SectionKind::Requests;
        for (const Request& request : newestFirst) {
            block.items.append(Item::of(request));
        }
        snapshot.sections.append(block);
    }

    for (SectionKind kind : {SectionKind::Live, SectionKind::Away, SectionKind::Saved}) {
        QVector<Entry> inSection;
        for (const Entry& entry : visible) {
            if (entry.section() == kind) {
                inSection.append(entry);
            }
        }
        if (inSection.isEmpty()) {
            continue;
        }
        std::stable_sort(inSection.begin(), inSection.end(), rowOrder);
        Section block;
        block.kind = kind;
        for (const Entry& entry : inSection) {
            block.items.append(Item::of(entry));
        }
        snapshot.sections.append(block);
    }

    if (searching && !messageHits.isEmpty()) {
        Section block;
        block.kind = SectionKind::Messages;
        for (const MessageHit& hit : messageHits) {
            block.items.append(Item::of(hit));
        }
        snapshot.sections.append(block);
    }

    snapshot.emptyState = emptyStateFor(snapshot.sections, entries, visible, requests, filter,
                                        searching, !commandIdCandidate(needle).isEmpty());
    return snapshot;
}

// ---- diff ---------------------------------------------------------------------------------------

bool IndexPath::operator==(const IndexPath& other) const
{
    return section == other.section && row == other.row;
}

bool IndexPath::operator<(const IndexPath& other) const
{
    if (section != other.section) {
        return section < other.section;
    }
    return row < other.row;
}

bool RowMove::operator==(const RowMove& other) const
{
    return from == other.from && to == other.to;
}

int Changes::count() const
{
    return deletedSections.size() + insertedSections.size() + deletedRows.size()
           + insertedRows.size() + movedRows.size();
}

bool Changes::isEmpty() const
{
    return count() == 0;
}

QSet<int> longestIncreasingRun(const QVector<int>& values)
{
    QVector<int> tails;
    QVector<int> previous(values.size(), -1);

    for (int index = 0; index < values.size(); ++index) {
        const int value = values[index];
        int low = 0;
        int high = tails.size();
        while (low < high) {
            const int middle = (low + high) / 2;
            if (values[tails[middle]] < value) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        if (low > 0) {
            previous[index] = tails[low - 1];
        }
        if (low == tails.size()) {
            tails.append(index);
        } else {
            tails[low] = index;
        }
    }

    QSet<int> result;
    int cursor = tails.isEmpty() ? -1 : tails.last();
    while (cursor >= 0) {
        result.insert(cursor);
        cursor = previous[cursor];
    }
    return result;
}

namespace {
int sectionIndex(const Snapshot& snapshot, SectionKind kind)
{
    for (int index = 0; index < snapshot.sections.size(); ++index) {
        if (snapshot.sections[index].kind == kind) {
            return index;
        }
    }
    return -1;
}

/**
 * The count rule a list view enforces, checked before handing the update over: for every section on
 * both sides, new rows = old rows - removed - moved out + inserted + moved in.
 */
bool isConsistent(const Changes& changes, const Snapshot& oldSnapshot, const Snapshot& newSnapshot)
{
    for (int newSection = 0; newSection < newSnapshot.sections.size(); ++newSection) {
        if (changes.insertedSections.contains(newSection)) {
            continue;
        }
        const Section& block = newSnapshot.sections[newSection];
        const int oldSection = sectionIndex(oldSnapshot, block.kind);
        if (oldSection < 0) {
            return false;
        }
        int removed = 0;
        for (const IndexPath& path : changes.deletedRows) {
            removed += path.section == oldSection ? 1 : 0;
        }
        int movedOut = 0;
        int movedIn = 0;
        for (const RowMove& move : changes.movedRows) {
            movedOut += move.from.section == oldSection ? 1 : 0;
            movedIn += move.to.section == newSection ? 1 : 0;
        }
        int inserted = 0;
        for (const IndexPath& path : changes.insertedRows) {
            inserted += path.section == newSection ? 1 : 0;
        }
        if (oldSnapshot.sections[oldSection].items.size() - removed - movedOut + inserted + movedIn
            != block.items.size()) {
            return false;
        }
    }
    return true;
}
} // namespace

bool diff(const Snapshot& oldSnapshot, const Snapshot& newSnapshot, Changes& changes)
{
    changes = Changes{};

    QVector<SectionKind> oldKinds;
    for (const Section& block : oldSnapshot.sections) {
        oldKinds.append(block.kind);
    }
    QVector<SectionKind> newKinds;
    for (const Section& block : newSnapshot.sections) {
        newKinds.append(block.kind);
    }

    for (int index = 0; index < oldKinds.size(); ++index) {
        if (!newKinds.contains(oldKinds[index])) {
            changes.deletedSections.insert(index);
        }
    }
    for (int index = 0; index < newKinds.size(); ++index) {
        if (!oldKinds.contains(newKinds[index])) {
            changes.insertedSections.insert(index);
        }
    }

    QHash<QString, IndexPath> oldPaths;
    for (int section = 0; section < oldSnapshot.sections.size(); ++section) {
        const QVector<Item>& items = oldSnapshot.sections[section].items;
        for (int row = 0; row < items.size(); ++row) {
            oldPaths.insert(items[row].id(), IndexPath{section, row});
        }
    }
    QHash<QString, IndexPath> newPaths;
    for (int section = 0; section < newSnapshot.sections.size(); ++section) {
        const QVector<Item>& items = newSnapshot.sections[section].items;
        for (int row = 0; row < items.size(); ++row) {
            newPaths.insert(items[row].id(), IndexPath{section, row});
        }
    }
    // Ids must be unique on both sides, or the paths above lost a row.
    if (oldPaths.size() != oldSnapshot.itemCount() || newPaths.size() != newSnapshot.itemCount()) {
        return false;
    }

    for (auto it = oldPaths.constBegin(); it != oldPaths.constEnd(); ++it) {
        if (!newPaths.contains(it.key()) && !changes.deletedSections.contains(it.value().section)) {
            changes.deletedRows.append(it.value());
        }
    }
    for (auto it = newPaths.constBegin(); it != newPaths.constEnd(); ++it) {
        if (!oldPaths.contains(it.key()) && !changes.insertedSections.contains(it.value().section)) {
            changes.insertedRows.append(it.value());
        }
    }

    // Rows on both sides: which keep their place and which move.
    for (int newSection = 0; newSection < newSnapshot.sections.size(); ++newSection) {
        const Section& block = newSnapshot.sections[newSection];
        if (changes.insertedSections.contains(newSection)) {
            for (const Item& item : block.items) {
                const auto from = oldPaths.constFind(item.id());
                if (from != oldPaths.constEnd()
                    && !changes.deletedSections.contains(from.value().section)) {
                    changes.deletedRows.append(from.value());
                }
            }
            continue;
        }
        const int oldSection = oldKinds.indexOf(block.kind);
        if (oldSection < 0) {
            continue;
        }

        QVector<QPair<int, int>> stayed; // (new row, old row)
        for (int row = 0; row < block.items.size(); ++row) {
            const auto from = oldPaths.constFind(block.items[row].id());
            if (from == oldPaths.constEnd()) {
                continue;
            }
            const IndexPath to{newSection, row};
            if (from.value().section == oldSection) {
                stayed.append(qMakePair(row, from.value().row));
            } else if (changes.deletedSections.contains(from.value().section)) {
                changes.insertedRows.append(to);
            } else {
                changes.movedRows.append(RowMove{from.value(), to});
            }
        }

        QVector<int> oldRows;
        for (const auto& pair : stayed) {
            oldRows.append(pair.second);
        }
        const QSet<int> kept = longestIncreasingRun(oldRows);
        for (int position = 0; position < stayed.size(); ++position) {
            if (!kept.contains(position)) {
                changes.movedRows.append(RowMove{IndexPath{oldSection, stayed[position].second},
                                                 IndexPath{newSection, stayed[position].first}});
            }
        }
    }

    std::sort(changes.deletedRows.begin(), changes.deletedRows.end());
    std::sort(changes.insertedRows.begin(), changes.insertedRows.end());
    std::sort(changes.movedRows.begin(), changes.movedRows.end(),
              [](const RowMove& first, const RowMove& second) { return first.to < second.to; });

    return changes.count() <= maximumAnimatedChanges
           && isConsistent(changes, oldSnapshot, newSnapshot);
}

// ---- command line -------------------------------------------------------------------------------

namespace {
/** An upper-case hex digit: the text is upper-cased first. */
bool isHexDigit(QChar c)
{
    return (c >= QLatin1Char('0') && c <= QLatin1Char('9'))
           || (c >= QLatin1Char('A') && c <= QLatin1Char('F'));
}

/** Upper case, without spaces, line breaks and invisible characters, and without one prefix. */
QString compactAddress(const QString& raw)
{
    QString compact;
    compact.reserve(raw.size());
    for (uint codePoint : codePoints(raw.toUpper())) {
        if (!isInvisibleOrSpace(codePoint)) {
            compact.append(fromCodePoint(codePoint));
        }
    }

    for (const QString& prefix : addressPrefixes()) {
        if (compact.startsWith(prefix)) {
            compact.remove(0, prefix.size());
            break;
        }
    }
    return compact;
}

bool isAllHex(const QString& text)
{
    return std::all_of(text.cbegin(), text.cend(), isHexDigit);
}
} // namespace

QString standaloneToxAddress(const QString& raw)
{
    if (raw.isEmpty()) {
        return QString{};
    }

    const QString compact = compactAddress(raw);
    if (compact.size() != toxAddressHexLength || !isAllHex(compact)) {
        return QString{};
    }
    return compact;
}

QString cleanToxId(const QString& raw)
{
    return cleanHex(raw, toxAddressHexLength);
}

QString cleanHex(const QString& raw, int length)
{
    const QString text = compactAddress(raw);
    // A whole ID somewhere in a sentence ("my ID: ..."): that run, not the hex letters of the words.
    int bestStart = 0;
    int bestLength = 0;
    int run = 0;
    for (int i = 0; i < text.size(); ++i) {
        run = isHexDigit(text.at(i)) ? run + 1 : 0;
        if (run > bestLength) {
            bestLength = run;
            bestStart = i - run + 1;
        }
    }
    if (bestLength >= length) {
        return text.mid(bestStart, length);
    }

    QString hex;
    hex.reserve(length);
    for (const QChar c : text) {
        if (hex.size() == length) {
            break;
        }
        if (isHexDigit(c)) {
            hex.append(c);
        }
    }
    return hex;
}

QString groupedHex(const QString& hex)
{
    QStringList groups;
    for (int start = 0; start < hex.size(); start += 4) {
        groups << hex.mid(start, 4);
    }
    return groups.join(QLatin1Char(' '));
}

bool toxIdCheckMatches(const QString& hex)
{
    // ToxId checks the two bytes the way toxcore does.
    return hex.size() == toxAddressHexLength && isAllHex(hex) && ToxId(hex).isValid();
}

QString commandIdCandidate(const QString& raw)
{
    const QString text = compactAddress(raw);
    if (text.size() < commandIdMinimum || text.size() > toxAddressHexLength || !isAllHex(text)) {
        return QString{};
    }
    // "DEADBEEFCAFE" is a word as much as a key: a digit makes it read as an ID.
    const bool hasDigit = std::any_of(text.cbegin(), text.cend(), [](QChar c) { return c.isDigit(); });
    return hasDigit ? text : QString{};
}

QString IdVerdict::address() const
{
    return kind == Kind::Ok || kind == Kind::Blocked ? value : QString{};
}

IdVerdict::Check IdVerdict::check() const
{
    switch (kind) {
    case Kind::Empty:
    case Kind::Partial:
        return Check::Filling;
    case Kind::BadCheck:
        return Check::Bad;
    case Kind::Own:
    case Kind::Contact:
    case Kind::TheyAsked:
    case Kind::Blocked:
    case Kind::Ok:
        break;
    }
    return Check::Good;
}

IdVerdict IdVerdict::of(const QString& hex, const QString& ownAddress, const KeyTest& isContact,
                        const KeyTest& hasRequest, const KeyTest& isBlocked)
{
    IdVerdict verdict;
    if (hex.isEmpty()) {
        return verdict;
    }
    if (hex.size() < toxAddressHexLength) {
        verdict.kind = Kind::Partial;
        verdict.left = toxAddressHexLength - hex.size();
        return verdict;
    }
    if (!toxIdCheckMatches(hex)) {
        verdict.kind = Kind::BadCheck;
        return verdict;
    }
    const QString key = hex.left(toxKeyHexLength);
    if (key == cleanToxId(ownAddress).left(toxKeyHexLength)) {
        verdict.kind = Kind::Own;
    } else if (isContact && isContact(key)) {
        verdict.kind = Kind::Contact;
        verdict.value = key;
    } else if (hasRequest && hasRequest(key)) {
        verdict.kind = Kind::TheyAsked;
    } else if (isBlocked && isBlocked(key)) {
        verdict.kind = Kind::Blocked;
        verdict.value = hex;
    } else {
        verdict.kind = Kind::Ok;
        verdict.value = hex;
    }
    return verdict;
}

bool IdVerdict::operator==(const IdVerdict& other) const
{
    return kind == other.kind && left == other.left && value == other.value;
}

bool IdVerdict::operator!=(const IdVerdict& other) const
{
    return !(*this == other);
}

QVector<RulerPart> RulerPart::parts(int count)
{
    const int bounds[] = {0, toxKeyHexLength, toxKeyHexLength + toxAntispamHexLength,
                          toxAddressHexLength};
    QVector<RulerPart> parts;
    for (int i = 0; i < 3; ++i) {
        RulerPart part;
        part.size = bounds[i + 1] - bounds[i];
        part.filled = qBound(0, count - bounds[i], part.size);
        parts << part;
    }
    return parts;
}

bool RulerPart::operator==(const RulerPart& other) const
{
    return filled == other.filled && size == other.size;
}

IdFieldState IdFieldState::regroup(const QString& edited, int caret, int hexLength)
{
    const QString hex = cleanHex(edited, hexLength);
    const int hexBefore =
        std::min(cleanHex(edited.left(qBound(0, caret, edited.size())), hexLength).size(), hex.size());
    IdFieldState state;
    state.text = groupedHex(hex);
    // One space before every group but the first; past the end of a group the caret goes on to
    // the start of the next one, where typing continues.
    int position = hexBefore + std::max(0, (hexBefore - 1) / 4);
    if (hexBefore > 0 && hexBefore % 4 == 0 && hexBefore < hex.size()) {
        ++position;
    }
    state.caret = std::min(position, state.text.size());
    return state;
}

IdFieldState IdFieldState::erase(const QString& text, int caret, bool forwards, int hexLength)
{
    int from = qBound(0, caret, text.size());
    int to = from;
    if (forwards) {
        if (to < text.size() && text.at(to) == QLatin1Char(' ')) {
            ++to;
        }
        to = std::min(to + 1, text.size());
    } else {
        if (from > 0 && text.at(from - 1) == QLatin1Char(' ')) {
            --from;
        }
        from = std::max(from - 1, 0);
    }
    QString edited = text;
    edited.remove(from, to - from);
    return regroup(edited, from, hexLength);
}

bool IdFieldState::operator==(const IdFieldState& other) const
{
    return text == other.text && caret == other.caret;
}

Command Command::parse(const QString& raw)
{
    Command command;
    const QString address = standaloneToxAddress(raw);
    if (!address.isNull()) {
        command.type = Type::ContactAddress;
        command.value = address;
        return command;
    }
    const QString trimmed = raw.trimmed();
    if (trimmed.isEmpty()) {
        command.type = Type::Empty;
        command.value = QString{""};
    } else {
        command.type = Type::Query;
        command.value = trimmed;
    }
    return command;
}

bool Command::operator==(const Command& other) const
{
    return type == other.type && value == other.value;
}

// ---- text helpers -------------------------------------------------------------------------------

QString fold(const QString& text)
{
    if (text.isEmpty()) {
        return QString{""};
    }
    const QString decomposed = text.normalized(QString::NormalizationForm_D);
    QString stripped;
    stripped.reserve(decomposed.size());
    for (uint codePoint : codePoints(decomposed)) {
        if (!isMark(codePoint)) {
            stripped.append(fromCodePoint(codePoint));
        }
    }
    return stripped.toLower();
}

QVector<QPair<int, int>> matchRanges(const QString& text, const QString& query)
{
    QVector<QPair<int, int>> ranges;
    const QString needle = query.trimmed();
    if (text.isEmpty() || needle.isEmpty()) {
        return ranges;
    }

    // Fold character by character so positions in the folded text map back to the original.
    QString folded;
    QVector<int> origin;
    int index = 0;
    while (index < text.size()) {
        const int length = (text.at(index).isHighSurrogate() && index + 1 < text.size()
                            && text.at(index + 1).isLowSurrogate())
                               ? 2
                               : 1;
        const QString piece = fold(text.mid(index, length));
        for (const QChar c : piece) {
            folded.append(c);
            origin.append(index);
        }
        index += length;
    }
    origin.append(text.size());

    const QString foldedNeedle = fold(needle);
    if (foldedNeedle.isEmpty()) {
        return ranges;
    }
    int from = 0;
    while (from <= folded.size() - foldedNeedle.size()) {
        const int found = folded.indexOf(foldedNeedle, from);
        if (found < 0) {
            break;
        }
        const int end = found + foldedNeedle.size();
        const int start = origin[found];
        int stop = origin[end];
        // A folded end inside one original character still covers the whole character.
        if (stop <= start) {
            stop = std::min(text.size(), start + 1);
        }
        ranges.append(qMakePair(start, stop));
        from = end;
    }
    return ranges;
}

QString initials(const QString& title)
{
    QString letters;
    int words = 0;
    for (const QString& word : splitWords(title)) {
        if (words == 2) {
            break;
        }
        QTextBoundaryFinder characters(QTextBoundaryFinder::Grapheme, word);
        const int end = characters.toNextBoundary();
        const QString first = word.left(end > 0 ? end : word.size());
        if (words > 0) {
            // A zero-width non-joiner keeps two Arabic letters in their separate forms.
            letters.append(zeroWidthNonJoiner);
        }
        letters.append(first.toUpper());
        ++words;
    }
    return letters;
}

int toneIndex(const QString& key)
{
    quint32 hash = 0x811C9DC5u;
    const QByteArray bytes = key.toUpper().toUtf8();
    for (const char value : bytes) {
        hash ^= static_cast<quint8>(value);
        hash *= 0x01000193u;
    }
    return static_cast<int>(hash % static_cast<quint32>(toneCount));
}

int wrapTone(int index)
{
    return ((index % toneCount) + toneCount) % toneCount;
}

QString groupedKey(const QString& publicKey)
{
    const QString head = publicKey.toUpper().left(12);
    QString grouped;
    for (int i = 0; i < head.size(); i += 4) {
        if (i > 0) {
            grouped.append(QLatin1Char(' '));
        }
        grouped.append(head.mid(i, 4));
    }
    return grouped;
}

namespace {
const QString replyHeaderPrefix = QStringLiteral("[KQ|");
const QString replyFooter = QStringLiteral("[KQ/end]");
const QString legacyQuotePrefix = QStringLiteral("----\n");
const QString legacyQuoteSuffix = QStringLiteral("\n----");
const QString mentionPrefix = QStringLiteral("[KQ|m|");
const QString mentionSuffix = QStringLiteral("][KQ/m]");
const QString legacyMentionPrefix = QStringLiteral("[KQ|m]");
const QString legacyMentionSuffix = QStringLiteral("[|KQ|m]");

/** Drops control characters except line breaks and trims, like the iOS trimLeading. */
QString cleanBody(const QString& text)
{
    QString clean;
    clean.reserve(text.size());
    for (const QChar character : text) {
        const bool lineBreak = character == QLatin1Char('\n') || character == QLatin1Char('\r');
        if (lineBreak || character.category() != QChar::Other_Control) {
            clean.append(character);
        }
    }
    return clean.trimmed();
}

bool isWholeNumber(const QString& text)
{
    bool ok = false;
    text.trimmed().toLongLong(&ok);
    return ok;
}

/** A quoted line as the quote shows it: control characters dropped, breaks as spaces, trimmed. */
QString quoteLine(const QString& text)
{
    QString line;
    line.reserve(text.size());
    for (const QChar character : text) {
        if (character == QLatin1Char('\n') || character == QLatin1Char('\r')) {
            line.append(QLatin1Char(' '));
        } else if (character.category() != QChar::Other_Control) {
            line.append(character);
        }
    }
    return line.trimmed();
}

bool isLineBreak(QChar character)
{
    return character == QLatin1Char('\n') || character == QLatin1Char('\r');
}

/**
 * Reads a reply header at the start of raw, as khandaq-ios MessageReplyHelper.parse does: true for
 * "[KQ|id|tail|time|name]" with whole numbers for the id and the time, the name keeping any further
 * bars. With the "[KQ/end]" footer the quote stands between the header and the footer; without it
 * the first line after the header is the quote and the rest the body.
 */
bool readReply(const QString& raw, MessageText* text)
{
    if (!raw.startsWith(replyHeaderPrefix)) {
        return false;
    }
    const int headerEnd = raw.indexOf(QLatin1Char(']'));
    if (headerEnd < 0) {
        return false;
    }
    const QString header = raw.mid(replyHeaderPrefix.size(), headerEnd - replyHeaderPrefix.size());
    const int idEnd = header.indexOf(QLatin1Char('|'));
    const int tailEnd = idEnd < 0 ? -1 : header.indexOf(QLatin1Char('|'), idEnd + 1);
    const int timeEnd = tailEnd < 0 ? -1 : header.indexOf(QLatin1Char('|'), tailEnd + 1);
    if (timeEnd < 0 || !isWholeNumber(header.left(idEnd))
        || !isWholeNumber(header.mid(tailEnd + 1, timeEnd - tailEnd - 1))) {
        return false;
    }

    text->reply = true;
    text->replyName = header.mid(timeEnd + 1).trimmed();
    const int quoteStart = headerEnd + 1;
    const int footer = raw.indexOf(replyFooter, quoteStart);
    if (footer >= 0) {
        text->replyPreview = quoteLine(raw.mid(quoteStart, footer - quoteStart));
        text->body = raw.mid(footer + replyFooter.size());
        return true;
    }
    int start = quoteStart;
    while (start < raw.size() && isLineBreak(raw[start])) {
        ++start;
    }
    int lineEnd = start;
    while (lineEnd < raw.size() && !isLineBreak(raw[lineEnd])) {
        ++lineEnd;
    }
    text->replyPreview = quoteLine(raw.mid(start, lineEnd - start));
    text->body = lineEnd < raw.size() ? raw.mid(lineEnd + 1) : QString();
    return true;
}

/** The older reply: "----", the quote, "----", then the body. */
bool readLegacyReply(const QString& raw, MessageText* text)
{
    if (!raw.startsWith(legacyQuotePrefix)) {
        return false;
    }
    const int end = raw.indexOf(legacyQuoteSuffix, legacyQuotePrefix.size() - 1);
    if (end < 0) {
        return false;
    }
    text->reply = true;
    text->replyName.clear();
    text->replyPreview =
        end > legacyQuotePrefix.size()
            ? quoteLine(raw.mid(legacyQuotePrefix.size(), end - legacyQuotePrefix.size()))
            : QString();
    int start = end + legacyQuoteSuffix.size();
    while (start < raw.size() && raw[start].isSpace()) {
        ++start;
    }
    text->body = raw.mid(start);
    return true;
}

bool startsWithMentions(const QString& text)
{
    return text.startsWith(mentionPrefix) || text.startsWith(legacyMentionPrefix);
}

/**
 * Markup this build cannot read (stage 3, item 10, DESIGN.md "Unknown markup"): a text that opens
 * with "[KQ|" and is neither a reply nor a mention shows what follows its markup, after the footer
 * when there is one, else after the first "]". Plain text that merely contains "[KQ" is left as it
 * is. Sets unreadable when nothing follows.
 */
QString readableBody(const QString& raw, bool* unreadable)
{
    if (!raw.startsWith(replyHeaderPrefix) || startsWithMentions(raw)) {
        return raw;
    }
    QString rest;
    const int footer = raw.indexOf(replyFooter);
    if (footer >= 0) {
        rest = raw.mid(footer + replyFooter.size());
    } else {
        const int headerEnd = raw.indexOf(QLatin1Char(']'));
        if (headerEnd >= 0) {
            rest = raw.mid(headerEnd + 1);
        }
    }
    const QString body = cleanBody(rest);
    if (body.isEmpty()) {
        *unreadable = true;
    }
    return body;
}

/** The text after a mention block, or the text itself when it carries none. */
QString afterMentions(const QString& body)
{
    if (body.startsWith(mentionPrefix)) {
        const int blockEnd = body.indexOf(mentionSuffix);
        if (blockEnd >= 0) {
            return cleanBody(body.mid(blockEnd + mentionSuffix.size()));
        }
        const int lineEnd = body.indexOf(QLatin1Char('\n'));
        if (lineEnd >= 0) {
            return cleanBody(body.mid(lineEnd + 1));
        }
    } else if (body.startsWith(legacyMentionPrefix)) {
        const int blockEnd = body.indexOf(legacyMentionSuffix);
        if (blockEnd >= 0) {
            return cleanBody(body.mid(blockEnd + legacyMentionSuffix.size()));
        }
    }
    return body;
}
} // namespace

MessageText readMessageText(const QString& raw)
{
    MessageText text;
    bool unreadable = false;
    if (readReply(raw, &text) || readLegacyReply(raw, &text)) {
        // A reply's own words may open with markup again; they are read by the same rule.
        text.body = readableBody(cleanBody(text.body), &unreadable);
    } else {
        text.body = readableBody(raw, &unreadable);
    }
    // A mention block with nothing after it is as unreadable as unknown markup.
    const bool mentions = startsWithMentions(text.body);
    text.body = cleanBody(afterMentions(text.body));
    text.unreadable = !text.reply && (unreadable || (mentions && text.body.isEmpty()));
    // The mention blocks open with the reply's own prefix.
    text.marked = text.reply || raw.startsWith(replyHeaderPrefix);
    return text;
}

QString entryWords(const QString& raw)
{
    const MessageText text = readMessageText(raw);
    return text.marked ? text.body : raw;
}

QString plainMessageBody(const QString& raw)
{
    return readMessageText(raw).body;
}

int ownWordsStart(const QString& raw)
{
    // The body a reply reader gives is always the end of raw, from where the quote ends.
    MessageText text;
    if (readReply(raw, &text) || readLegacyReply(raw, &text)) {
        return raw.size() - text.body.size();
    }
    if (!raw.startsWith(replyHeaderPrefix) || startsWithMentions(raw)) {
        return 0;
    }
    const int footer = raw.indexOf(replyFooter);
    if (footer >= 0) {
        return footer + replyFooter.size();
    }
    const int headerEnd = raw.indexOf(QLatin1Char(']'));
    return headerEnd >= 0 ? headerEnd + 1 : raw.size();
}

AwayWords AwayWords::of(qint64 secondsAgo)
{
    AwayWords words;
    if (secondsAgo < 60) {
        words.unit = Unit::JustNow;
    } else if (secondsAgo < 3600) {
        words.unit = Unit::Minutes;
        words.amount = std::max<qint64>(1, secondsAgo / 60);
    } else if (secondsAgo < oneDaySeconds) {
        words.unit = Unit::Hours;
        words.amount = std::max<qint64>(1, secondsAgo / 3600);
    } else if (secondsAgo < oneDaySeconds * 7) {
        words.unit = Unit::Days;
        words.amount = std::max<qint64>(1, secondsAgo / oneDaySeconds);
    } else {
        words.unit = Unit::LongAgo;
    }
    return words;
}

} // namespace Air
