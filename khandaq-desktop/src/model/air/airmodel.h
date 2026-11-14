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

#pragma once

#include <QPair>
#include <QSet>
#include <QString>
#include <QVector>

#include <functional>

/**
 * The Air screen (design/redesign/02-plan.md, screens 1 and 2) as plain values.
 *
 * A port of khandaq-ios/Antidote/AirListModel.swift and of the Android AirModel.java with the same
 * rules and the same test vectors, so all three clients agree on which section a row stands in,
 * what every filter keeps, every count and empty state, and the small helpers the rows use. Nothing
 * here touches Tox, the database or a widget: AirSource turns friends and groups into these values.
 */
namespace Air {

/** The filter pills. All is the resting state: pressing the active pill again returns to it. */
enum class Filter
{
    All,
    Requests,
    Transit,
    Favorites,
};

/** The pills, in the order they stand on screen. */
QVector<Filter> pillFilters();
/** Stable name for accessibility and the screenshot hook. */
QString filterName(Filter filter);
Filter filterFromName(const QString& name);

// TOX_CONNECTION values, so the model does not need toxcore's header.
constexpr int toxConnectionNone = 0;
constexpr int toxConnectionTcp = 1;
constexpr int toxConnectionUdp = 2;

/** Our own node, for the quiet line under the name in the "you" block. */
enum class NetworkState
{
    /** Online, UDP: peers are reached directly. */
    Direct,
    /** Online through a TCP relay. */
    Relay,
    /** The device has a network, Tox is not connected yet. */
    Connecting,
    /** No network at all: whatever is sent waits on this device. */
    Offline,
};

NetworkState networkState(int toxConnection, bool networkReachable);

/** How the person or group behind a row can be reached right now. */
struct Reach
{
    enum class Kind
    {
        /** Online, the link goes straight to their device. */
        Direct,
        /** Online through a TCP relay. */
        Relay,
        /** Not online; secondsAgo since they were last seen. */
        Away,
        /** Added by our request and never online since: it has not been answered yet. */
        Pending,
        /**
         * Not linked yet, but active within the last day while our own node has only just come
         * online: their link is most likely still being set up, so the row says so instead of a
         * stale "last seen".
         */
        Connecting,
        /** A group and how many of its members are online; zero when nobody is. */
        Group,
        /** Saved messages: never sent anywhere. */
        Local,
    };

    Kind kind = Kind::Away;
    qint64 secondsAgo = 0;
    int online = 0;

    static Reach direct();
    static Reach relay();
    static Reach pending();
    static Reach connecting();
    static Reach local();
    static Reach away(qint64 secondsAgo);
    /** Away for as long as anyone can tell: a contact never seen with a date. */
    static Reach awayLongAgo();
    static Reach group(int online);

    /**
     * lastSeenSeconds is 0 or less when the friend was never online since being added.
     * selfLinkSettling: our own node came online less than a minute ago.
     * askedSeconds: for a contact made by accepting their request, when it came (they were online
     * then); 0 or less for a contact made by our own request.
     */
    static Reach friendReach(int toxConnection, qint64 lastSeenSeconds, qint64 nowSeconds,
                             bool selfLinkSettling, qint64 askedSeconds = 0);

    /** A message sent now would arrive now. */
    bool isLive() const;

    /**
     * How many of a person's undelivered messages wait in transit: all of them while the person
     * is away, none while they are online, when the conversation shows the same messages as
     * leaving or sent (outgoingTextDelivery).
     */
    int messagesInTransit(int undelivered) const;

    bool operator==(const Reach& other) const;
    bool operator!=(const Reach& other) const;
};

/** The state of our own last message in a row, drawn in front of the preview. */
enum class Delivery
{
    /** The last message is not ours, or has no state worth a mark. */
    None,
    Transit,
    Sent,
    Delivered,
    /** Desktop only: the message broke and will not be sent (a broken message in the chat log). */
    Failed,
};

/**
 * Our own last text to one person, by the conversation's rule (Air::outgoingTextStatus): in transit
 * while the person is away, a clock while it is leaving, then sent and delivered.
 */
Delivery outgoingTextDelivery(bool isDelivered, bool handedOver, bool personOnline);

enum class EntryKind
{
    Direct,
    Group,
    Saved,
    /** A contact without a conversation: opening the row starts one. */
    Contact,
};

enum class PreviewStyle
{
    Message,
    Draft,
    Typing,
    /** A quiet explanation instead of a message (a request waiting for an answer, saved messages). */
    Hint,
};

enum class SectionKind
{
    Requests,
    Live,
    Away,
    Saved,
    Messages,
};

/** Request cards carry their own label; every other section has a heading with its count. */
bool sectionHasHeading(SectionKind kind);
QString sectionName(SectionKind kind);

/** One row of the list: a conversation, or a contact who has no conversation yet. */
struct Entry
{
    /** Unique across kinds: "f:" + public key, "c:" + conference id, or "saved". */
    QString id;
    EntryKind kind = EntryKind::Direct;
    QString title;
    Reach reach;
    QString preview;
    PreviewStyle previewStyle = PreviewStyle::Message;
    Delivery lastDelivery = Delivery::None;
    QString timeText;
    /** When the conversation last moved, in milliseconds; zero for a contact without one. */
    qint64 activity = 0;
    int transitCount = 0;
    int unreadCount = 0;
    bool isFavorite = false;
    /** Someone mentioned us in this group since we last looked. */
    bool mentioned = false;
    /** Whose photo the tile shows (a friend's public key), or empty for none. */
    QString photoKey;
    /** Tone index for the initials, from the friend's public key or the group's id. */
    int tone = 0;

    SectionKind section() const;
    bool operator==(const Entry& other) const;
    bool operator!=(const Entry& other) const;
};

/**
 * An incoming friend request or an invitation to a group, drawn as a card above the sections until
 * it is answered (E12).
 */
struct Request
{
    enum class Kind
    {
        Friend,
        /** An invitation to a group from a contact. */
        Group,
    };

    Kind kind = Kind::Friend;
    /**
     * A friend request: the public key, upper case, one card per person however often they ask.
     * An invitation: "invite:" and the invitation's id.
     */
    QString id;
    /** Who asks: the person, or the contact who invites. */
    QString publicKey;
    QString message;
    /** An invitation: the name of the group, and the inviter as the list calls them. */
    QString groupName;
    QString inviterName;
    /** An invitation: Join goes through the inviter, so it can go now only while they are online. */
    bool inviterOnline = false;
    QString timeText;
    /** When it arrived, in milliseconds. */
    qint64 date = 0;

    static Request make(const QString& publicKey, const QString& message, const QString& timeText,
                        qint64 date);
    static Request invitation(const QString& inviteId, const QString& inviterKey,
                              const QString& groupName, const QString& inviterName,
                              bool inviterOnline, const QString& timeText, qint64 date);
    /** The id an invitation's card carries. */
    static QString invitationId(const QString& inviteId);
    bool isInvitation() const;
    bool operator==(const Request& other) const;
};

/** A message that matched the command line, listed under the rows while searching. */
struct MessageHit
{
    QString id;
    /** The entry id of the conversation it belongs to. */
    QString chatId;
    QString title;
    QString snippet;
    QString timeText;

    bool operator==(const MessageHit& other) const;
};

/** One item of a section: exactly one of the payloads is used, the one type names. */
struct Item
{
    enum class Type
    {
        Request,
        Entry,
        Message,
    };

    Type type = Type::Entry;
    Request request;
    Entry entry;
    MessageHit message;

    static Item of(const Request& request);
    static Item of(const Entry& entry);
    static Item of(const MessageHit& hit);

    /** Identity for animated updates: a person keeps the same id when their row changes section. */
    QString id() const;
    bool operator==(const Item& other) const;
};

/** What the list says when it has nothing to show for the current filter or query. */
enum class EmptyState
{
    None,
    /** No contacts and no requests yet: only saved messages. */
    NothingYet,
    /** The command line matched nothing, and it is not the start of an ID, which has its own
     * row over the list. */
    NoMatch,
    NoRequests,
    NoTransit,
    /** Nothing starred: only saved messages. */
    NoFavorites,
};

/** The numbers on the pills, counted over everything whatever the filter or the query. */
struct Counts
{
    int requests = 0;
    int transit = 0;

    bool operator==(const Counts& other) const;
};

struct Section
{
    SectionKind kind = SectionKind::Away;
    QVector<Item> items;
};

/**
 * One line of the list: a section heading or an item. The heading of a section keeps its id when
 * its count changes, so the view rolls the number instead of replacing the line.
 */
struct Row
{
    enum class Type
    {
        Header,
        Request,
        Entry,
        Message,
    };

    Type type = Type::Header;
    SectionKind section = SectionKind::Away;
    /** For a heading: how many items the section holds. */
    int count = 0;
    /** Unused for a heading. */
    Item item;

    QString id() const;
};

struct Snapshot
{
    QVector<Section> sections;
    Counts counts;
    EmptyState emptyState = EmptyState::None;

    int itemCount() const;
    const Section* section(SectionKind kind) const;
    /** Headings and items in the order they are drawn. */
    QVector<Row> rows() const;
};

Snapshot build(const QVector<Entry>& entries, const QVector<Request>& requests,
               const QVector<MessageHit>& messageHits, Filter filter, const QString& query);
bool matches(const Entry& entry, Filter filter);
/** Latest activity first; contacts without a conversation (no activity) last, by name. */
bool rowOrder(const Entry& first, const Entry& second);

struct IndexPath
{
    int section = 0;
    int row = 0;

    IndexPath() = default;
    IndexPath(int sectionIndex, int rowIndex)
        : section{sectionIndex}
        , row{rowIndex}
    {
    }

    bool operator==(const IndexPath& other) const;
    bool operator<(const IndexPath& other) const;
};

struct RowMove
{
    IndexPath from;
    IndexPath to;

    bool operator==(const RowMove& other) const;
};

/**
 * What changed between two snapshots: deletions and moves' sources by old index paths, insertions
 * and moves' destinations by new ones.
 */
struct Changes
{
    QSet<int> deletedSections;
    QSet<int> insertedSections;
    QVector<IndexPath> deletedRows;
    QVector<IndexPath> insertedRows;
    QVector<RowMove> movedRows;

    int count() const;
    bool isEmpty() const;
};

/** Beyond this many operations an animation reads as noise; the caller reloads instead. */
constexpr int maximumAnimatedChanges = 40;

/**
 * The update that turns oldSnapshot into newSnapshot. Returns false when it should not be animated:
 * too many changes, or a set that does not add up.
 *
 * Only rows that really changed place are moved: within a section, the rows kept in their relative
 * order (one longest increasing run) stay put, so a person who comes online is the one row that
 * slides. A row cannot move out of a section that disappears or into one that appears in the same
 * update, so such a row is removed and inserted instead.
 */
bool diff(const Snapshot& oldSnapshot, const Snapshot& newSnapshot, Changes& changes);

/** Positions (into values) of one longest strictly increasing run. */
QSet<int> longestIncreasingRun(const QVector<int>& values);

/** What the command line holds: a search, or a whole Tox ID that opens "new contact". */
struct Command
{
    enum class Type
    {
        Empty,
        Query,
        ContactAddress,
    };

    Type type = Type::Empty;
    /** The trimmed query, the address in upper case, or empty. */
    QString value;

    static Command parse(const QString& raw);
    bool operator==(const Command& other) const;
};

constexpr int toxAddressHexLength = 76;

/**
 * A full 76-character Tox ID typed or pasted on its own, in upper case; a null string for anything
 * else. Spaces, line breaks, invisible formatting characters and one "tox:"-style prefix are
 * allowed, but every other character has to be part of the ID: a search like "cafe bead" stays a
 * search. A 64-character key is not accepted, since it is also the length of a group ID. The
 * checksum is left to New contact, which already validates it.
 */
QString standaloneToxAddress(const QString& raw);

// ---- New contact (E11) and the start of an ID in the command line (E3) ----------------------------

constexpr int toxKeyHexLength = 64;
constexpr int toxAntispamHexLength = 8;
/** toxcore's tox_max_friend_request_length(): the message of a request, in UTF-8 bytes. */
constexpr int friendRequestMaxBytes = 1016;
/** The command line reads a hex run this long, with a digit in it, as the start of an ID. */
constexpr int commandIdMinimum = 12;

/**
 * The hex of an ID in any usual shape: spaces, line breaks, invisible characters, one "tox:"-style
 * prefix, lower case, or a sentence around it ("my ID: ..."), whose words are not taken for hex
 * once a whole ID stands in it. Upper case, at most 76 characters (khandaq-ios AirToxId.clean).
 */
QString cleanToxId(const QString& raw);

/**
 * The same cleaning for hex of another length: a group key is 64 characters, and "khandaq:group:"
 * in front of it must not lend it the hex letters of its words.
 */
QString cleanHex(const QString& raw, int length);

/** Groups of four, the way an ID is read aloud and compared. */
QString groupedHex(const QString& hex);

/**
 * toxcore's check of a whole ID: its last two bytes are the XOR of the even and of the odd bytes of
 * the key and the antispam. False for anything but 76 hex characters.
 */
bool toxIdCheckMatches(const QString& hex);

/**
 * The start of an ID in the command line: 12 to 76 hex characters with at least one digit and
 * nothing else, spaces, line breaks, invisible characters and one prefix aside. Upper case, or a
 * null string for a name, a word or a sentence.
 */
QString commandIdCandidate(const QString& raw);

/** The verdict under the ID field of New contact, decided in the order of the kinds. */
struct IdVerdict
{
    enum class Kind
    {
        Empty,
        /** Characters still missing: left. */
        Partial,
        BadCheck,
        /** Your own ID, with any antispam. */
        Own,
        /** Already a contact, also one whose request is still on its way; value is the key. */
        Contact,
        /** This person already sent you a request: it waits in Requests. */
        TheyAsked,
        /** The key is blocked on this device; sending the request lifts the block. */
        Blocked,
        Ok,
    };

    /** The check part of the ruler: it turns green or red only once the ID is whole. */
    enum class Check
    {
        Filling,
        Good,
        Bad,
    };

    using KeyTest = std::function<bool(const QString& publicKey)>;

    Kind kind = Kind::Empty;
    int left = 0;
    /** The contact's public key (Contact), the whole address (Blocked, Ok), or empty. */
    QString value;

    /** Where "Send request" sends, when it may go; a null string otherwise. */
    QString address() const;
    Check check() const;

    /** The tests get public keys in upper case. */
    static IdVerdict of(const QString& hex, const QString& ownAddress, const KeyTest& isContact,
                        const KeyTest& hasRequest, const KeyTest& isBlocked);
    bool operator==(const IdVerdict& other) const;
    bool operator!=(const IdVerdict& other) const;
};

/** One part of the ruler under the ID field, and how much of it is typed. */
struct RulerPart
{
    int filled = 0;
    int size = 0;

    /** Key 64, antispam 8 and check 4, for count characters typed. */
    static QVector<RulerPart> parts(int count);
    bool operator==(const RulerPart& other) const;
};

/**
 * What the ID field shows after an edit, and where its caret stands: only the hex of an ID, in
 * groups of four, whatever was typed or pasted (khandaq-ios AirNewContactController.edit).
 */
struct IdFieldState
{
    QString text;
    int caret = 0;

    /**
     * The field after any edit: the hex of edited (cleanToxId) in groups of four, with the caret
     * after the same hex character it followed in edited, at the start of the next group when it
     * closed one.
     */
    static IdFieldState regroup(const QString& edited, int caret, int hexLength = toxAddressHexLength);
    /**
     * Backspace, or Delete with forwards, at caret with nothing selected. The space between two
     * groups is never what goes: the character beyond it does, so the key does not stick on it.
     */
    static IdFieldState erase(const QString& text, int caret, bool forwards,
                              int hexLength = toxAddressHexLength);
    bool operator==(const IdFieldState& other) const;
};

/** Case- and diacritic-insensitive form for matching a query. */
QString fold(const QString& text);

/** Names in the order of the language, ignoring case: negative when first comes before second. */
int compareTitles(const QString& first, const QString& second);

/** Where every occurrence of the query starts and ends in text, ignoring case and marks. */
QVector<QPair<int, int>> matchRanges(const QString& text, const QString& query);

/** The first letters of the first two words, or one letter for a single word. */
QString initials(const QString& title);

constexpr int toneCount = 6;

/**
 * A stable tone for a key (a public key or a group id): the same person keeps the same colour on
 * every launch and every client, so the hash is spelled out (FNV-1a, 32 bit, over the upper-cased
 * UTF-8 bytes).
 */
int toneIndex(const QString& key);
int wrapTone(int index);

/**
 * The first twelve characters of a key in groups of four: enough to compare with what the person
 * sent, never mistaken for a whole ID.
 */
QString groupedKey(const QString& publicKey);

/**
 * A message as the transcript shows it (stage 3, item 10), read by the rules of khandaq-ios
 * MessageReplyHelper and GroupMentionHelper: the reply header ("[KQ|id|tail|time|name]" ...
 * "[KQ/end]", or the older "----" quote) becomes the quote, the mention block ("[KQ|m|...][KQ/m]",
 * or the older "[KQ|m]...[|KQ|m]") goes, and other Khandaq markup this build cannot read shows the
 * words after it (DESIGN.md "Unknown markup").
 */
struct MessageText
{
    /** It answers another message: who wrote that one (empty when unknown) and its first words. */
    bool reply = false;
    QString replyName;
    QString replyPreview;
    /** Its own words: control characters other than line breaks dropped, trimmed. */
    QString body;
    /** Markup this build cannot read with nothing after it: the message is said in words instead. */
    bool unreadable = false;
    /** It opens with Khandaq markup (a reply, a mention block, a form this build cannot read). */
    bool marked = false;
};

MessageText readMessageText(const QString& raw);

/**
 * The words an entry of the transcript shows for a message: readMessageText's body when the
 * message opens with Khandaq markup, else the text as it came, so a plain message keeps its spaces
 * and lines. Empty for a message this build cannot read.
 */
QString entryWords(const QString& raw);

/**
 * The visible body of a message for a one-line preview or a search hit: readMessageText's body,
 * empty for a message this build cannot read.
 */
QString plainMessageBody(const QString& raw);

/**
 * Where the words a message's author wrote begin in raw: after a reply's header and quote, after
 * markup this build cannot read, else at 0. A mention block stays in, as it names whom it mentions.
 * Whether a message mentions you is read from here on, so a reply to your message, whose header
 * and quote carry your name and words, is not taken for a mention.
 */
int ownWordsStart(const QString& raw);

/** "Away" words for a row: which of the reach strings applies and the number it carries. */
struct AwayWords
{
    enum class Unit
    {
        JustNow,
        Minutes,
        Hours,
        Days,
        LongAgo,
    };

    Unit unit = Unit::JustNow;
    qint64 amount = 0;

    static AwayWords of(qint64 secondsAgo);
};

} // namespace Air
