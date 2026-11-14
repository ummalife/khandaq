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

#include "src/model/air/airmodel.h"
#include "src/widget/air/airstrings.h"

#include <QRegularExpression>
#include <QTranslator>
#include <QtTest/QtTest>

#include <functional>

using namespace Air;

namespace {

Entry entry(const QString& id, Reach reach = Reach::away(3600), qint64 activity = 0, int transit = 0)
{
    Entry value;
    value.id = id;
    value.kind = EntryKind::Direct;
    value.title = id;
    value.reach = reach;
    value.activity = activity;
    value.transitCount = transit;
    value.tone = toneIndex(id);
    return value;
}

Entry titled(const QString& id, const QString& title, EntryKind kind,
             Reach reach = Reach::away(3600), qint64 activity = 0)
{
    Entry value = entry(id, reach, activity);
    value.title = title;
    value.kind = kind;
    return value;
}

Entry favorite(const QString& id, Reach reach = Reach::direct())
{
    Entry value = entry(id, reach);
    value.isFavorite = true;
    return value;
}

Entry saved()
{
    return titled(QStringLiteral("saved"), QStringLiteral("Saved"), EntryKind::Saved,
                  Reach::local(), 1);
}

Request request(const QString& id, qint64 date)
{
    Request value = Request::make(QString(64, QLatin1Char('A')), QString{}, QString{}, date);
    value.id = id;
    return value;
}

QVector<SectionKind> kinds(const Snapshot& snapshot)
{
    QVector<SectionKind> result;
    for (const Section& block : snapshot.sections) {
        result.append(block.kind);
    }
    return result;
}

QStringList ids(const Snapshot& snapshot, SectionKind kind)
{
    QStringList result;
    const Section* block = snapshot.section(kind);
    if (!block) {
        return result;
    }
    for (const Item& item : block->items) {
        switch (item.type) {
        case Item::Type::Request:
            result.append(item.request.id);
            break;
        case Item::Type::Entry:
            result.append(item.entry.id);
            break;
        case Item::Type::Message:
            result.append(item.message.id);
            break;
        }
    }
    return result;
}

Snapshot buildAll(const QVector<Entry>& entries, const QVector<Request>& requests = {},
                  Filter filter = Filter::All, const QString& query = QString{})
{
    return build(entries, requests, {}, filter, query);
}

/** SplitMix64: the same random lists on every run. */
class SeededGenerator
{
public:
    explicit SeededGenerator(quint64 seed)
        : state{seed}
    {
    }

    quint64 next()
    {
        state += 0x9E3779B97F4A7C15ull;
        quint64 value = state;
        value = (value ^ (value >> 30)) * 0xBF58476D1CE4E5B9ull;
        value = (value ^ (value >> 27)) * 0x94D049BB133111EBull;
        return value ^ (value >> 31);
    }

    int below(int bound)
    {
        return static_cast<int>(next() % static_cast<quint64>(bound));
    }

private:
    quint64 state;
};

Reach randomReach(SeededGenerator& generator)
{
    switch (generator.below(5)) {
    case 0:
        return Reach::direct();
    case 1:
        return Reach::relay();
    case 2:
        return Reach::pending();
    case 3:
        return Reach::connecting();
    default:
        return Reach::away(generator.below(100000));
    }
}

qint64 randomActivity(SeededGenerator& generator)
{
    // Few distinct values, so equal activity (and the tie-break by name) comes up often.
    return generator.below(6);
}

/**
 * The new list as a list view builds it from the old one and the update: removed sections and rows
 * go, rows moved or inserted take their new places, and every row the update does not mention keeps
 * its order and fills the places left, section by section.
 */
bool replay(const Changes& changes, const Snapshot& oldSnapshot, const Snapshot& newSnapshot,
            QVector<QStringList>& result)
{
    QHash<int, QStringList> survivors; // by section kind
    for (int index = 0; index < oldSnapshot.sections.size(); ++index) {
        if (changes.deletedSections.contains(index)) {
            continue;
        }
        QSet<int> gone;
        for (const IndexPath& path : changes.deletedRows) {
            if (path.section == index) {
                gone.insert(path.row);
            }
        }
        for (const RowMove& move : changes.movedRows) {
            if (move.from.section == index) {
                gone.insert(move.from.row);
            }
        }
        QStringList kept;
        const QVector<Item>& items = oldSnapshot.sections[index].items;
        for (int row = 0; row < items.size(); ++row) {
            if (!gone.contains(row)) {
                kept.append(items[row].id());
            }
        }
        survivors.insert(static_cast<int>(oldSnapshot.sections[index].kind), kept);
    }

    result.clear();
    for (int index = 0; index < newSnapshot.sections.size(); ++index) {
        const Section& block = newSnapshot.sections[index];
        QStringList all;
        for (const Item& item : block.items) {
            all.append(item.id());
        }
        if (changes.insertedSections.contains(index)) {
            result.append(all);
            continue;
        }
        QVector<QString> places(block.items.size());
        for (const IndexPath& path : changes.insertedRows) {
            if (path.section == index) {
                places[path.row] = block.items[path.row].id();
            }
        }
        for (const RowMove& move : changes.movedRows) {
            if (move.to.section == index) {
                places[move.to.row] =
                    oldSnapshot.sections[move.from.section].items[move.from.row].id();
            }
        }
        const int kind = static_cast<int>(block.kind);
        if (!survivors.contains(kind)) {
            return false;
        }
        QStringList rest = survivors.value(kind);
        for (QString& place : places) {
            if (place.isNull()) {
                if (rest.isEmpty()) {
                    return false;
                }
                place = rest.takeFirst();
            }
        }
        if (!rest.isEmpty()) {
            return false;
        }
        result.append(QStringList::fromVector(places));
    }
    return true;
}

QVector<QStringList> sectionIds(const Snapshot& snapshot)
{
    QVector<QStringList> result;
    for (const Section& block : snapshot.sections) {
        QStringList list;
        for (const Item& item : block.items) {
            list.append(item.id());
        }
        result.append(list);
    }
    return result;
}

QStringList formatArguments(const QString& text)
{
    QStringList found;
    QRegularExpressionMatchIterator it = QRegularExpression(QStringLiteral("%\\d")).globalMatch(text);
    while (it.hasNext()) {
        found.append(it.next().captured(0));
    }
    found.sort();
    return found;
}

/** The glyphs the redesign keeps out of every text (scripts/check-forbidden-glyphs.py). */
bool hasForbiddenGlyph(const QString& text)
{
    static const QSet<uint> single{0x2012, 0x2013, 0x2014, 0x2015, 0x2026, 0x00D7, 0x00B7, 0x2022,
                                   0x2219, 0x22C5, 0x30FB, 0x2610, 0x2611, 0x2612, 0x2705, 0x2713,
                                   0x2714, 0x2715, 0x2716, 0x2717, 0x2718, 0x274C, 0x274E};
    for (uint codePoint : text.toUcs4()) {
        if (single.contains(codePoint) || (codePoint >= 0x2190 && codePoint <= 0x21FF)
            || (codePoint >= 0x27F0 && codePoint <= 0x27FF)
            || (codePoint >= 0x2900 && codePoint <= 0x297F)
            || (codePoint >= 0x2794 && codePoint <= 0x27BF)
            || (codePoint >= 0x2B00 && codePoint <= 0x2B4F)
            || (codePoint >= 0x2500 && codePoint <= 0x25FF)) {
            return true;
        }
    }
    return false;
}

} // namespace

/**
 * The Air screen's rules as plain values: which section a row stands in and in what order, what
 * each filter and the command line keep, every count and empty state, the update between two lists,
 * and the small helpers the rows use. The vectors are the iOS (AirListModelTests.swift) and Android
 * (AirModelTest.java) ones, so the three clients agree.
 */
class TestAirModel : public QObject
{
    Q_OBJECT
private slots:
    void sectionsStandInTheirOrderAndRowsSortByActivity();
    void contactsWithoutAConversationComeLastByName();
    void missingSectionsAreNull();
    void requestsFilterKeepsCardsAndUnansweredContacts();
    void invitationsAreCardsAmongTheRequests();
    void transitFilterKeepsRowsWithWaitingMessages();
    void undeliveredMessagesWaitInTransitOnlyWhileThePersonIsAway();
    void rowMarkOfALastTextFollowsTheConversation();
    void favoritesFilterKeepsStarredRowsAndSavedMessages();
    void favoritesFilterDoesNotMatchEveryRow();
    void filterNamesRoundTrip();
    void queryMatchesTitlesIgnoringCaseAndMarksAndHidesRequests();
    void messageHitsAreIgnoredWithoutAQuery();
    void highlightsCoverEveryMatchInTheOriginalText();
    void countsIgnoreTheFilterAndTheQuery();
    void onlySavedMessagesMeansNothingYet();
    void aRequestAloneIsNotNothing();
    void emptyStateForEachFilterAndForTheQuery();
    void flatRowsCarryHeadingsWithCounts();
    void aPersonComingOnlineIsTheOneRowThatMoves();
    void unchangedListsNeedNoUpdate();
    void aRowCannotMoveIntoASectionThatAppears();
    void tooManyChangesFallBackToAReload();
    void longestIncreasingRun();
    void randomUpdatesReplayIntoTheNewList();
    void commandLineTellsASearchFromAnId();
    void theIdFieldTakesAnIdInAnyUsualShape();
    void theCheckIsReallyChecked();
    void theVerdictsComeInTheirOrder();
    void theRulerCountsEachPart();
    void theIdFieldKeepsItsGroupsAndTheCaret();
    void theCommandLineReadsTheStartOfAnId();
    void requestKeyShowsTheFirstTwelveDigitsInFours();
    void oneCardPerPersonWhateverCaseTheKeyArrivesIn();
    void previewsShowTheBodyWithoutReplyAndMentionMarkup();
    void markupThisBuildCannotReadIsShownInWords();
    void anEntryShowsAPlainMessageAsItCame();
    void ownWordsBeginAfterTheQuote();
    void friendReach();
    void friendSeenTodayIsStillLinkingRightAfterWeComeOnline();
    void aContactWhoseRequestWeAcceptedIsNotWaitingForAReply();
    void onlyAWorkingLinkCountsAsLive();
    void networkStateFromTheToxLinkAndTheDevice();
    void awayWordsChangeUnitAtTheSameSecondsAsOnIos();
    void toneIsTheSameOnEveryLaunchAndEveryClient();
    void tonesSpreadOverAllSix();
    void initialsTakeTheFirstLettersOfTwoWords();
    void everyAirStringExistsInEveryLanguageWithTheSameArguments();
    void aScreenReaderHearsWhatARowShows();
    void aScreenReaderHearsACardAMessageAndAnEntry();
};

void TestAirModel::sectionsStandInTheirOrderAndRowsSortByActivity()
{
    const QVector<Entry> entries{
        entry("away-old", Reach::away(7200), 10),
        entry("live-new", Reach::direct(), 300),
        entry("relay", Reach::relay(), 200),
        titled("group-live", "group-live", EntryKind::Group, Reach::group(2), 100),
        titled("group-empty", "group-empty", EntryKind::Group, Reach::group(0), 50),
        entry("linking", Reach::connecting(), 20),
        entry("pending", Reach::pending()),
        saved(),
    };
    const QVector<Request> requests{request("older", 1), request("newer", 2)};

    const Snapshot snapshot = buildAll(entries, requests);

    QCOMPARE(kinds(snapshot), (QVector<SectionKind>{SectionKind::Requests, SectionKind::Live,
                                                    SectionKind::Away, SectionKind::Saved}));
    QCOMPARE(ids(snapshot, SectionKind::Requests), (QStringList{"newer", "older"}));
    QCOMPARE(ids(snapshot, SectionKind::Live), (QStringList{"live-new", "relay", "group-live"}));
    QCOMPARE(ids(snapshot, SectionKind::Away),
             (QStringList{"group-empty", "linking", "away-old", "pending"}));
    QCOMPARE(ids(snapshot, SectionKind::Saved), (QStringList{"saved"}));
    QCOMPARE(snapshot.emptyState, EmptyState::None);
    QCOMPARE(snapshot.itemCount(), 10);
}

void TestAirModel::contactsWithoutAConversationComeLastByName()
{
    const QVector<Entry> entries{
        titled("k2", "zed", EntryKind::Contact),
        titled("k1", "Anna", EntryKind::Contact),
        titled("chat", "Bob", EntryKind::Direct, Reach::away(3600), 5),
        titled("k3", "anna", EntryKind::Contact),
    };

    const Snapshot snapshot = buildAll(entries);

    QCOMPARE(ids(snapshot, SectionKind::Away), (QStringList{"chat", "k1", "k3", "k2"}));
}

void TestAirModel::missingSectionsAreNull()
{
    const Snapshot snapshot = buildAll({entry("a")});

    QVERIFY(snapshot.section(SectionKind::Away) != nullptr);
    QVERIFY(snapshot.section(SectionKind::Live) == nullptr);
    QCOMPARE(snapshot.section(SectionKind::Away)->items.first().entry.id, QStringLiteral("a"));
}

void TestAirModel::requestsFilterKeepsCardsAndUnansweredContacts()
{
    const QVector<Entry> entries{entry("pending", Reach::pending()),
                                 entry("friend", Reach::direct()), saved()};

    const Snapshot snapshot = buildAll(entries, {request("r", 1)}, Filter::Requests);

    QCOMPARE(kinds(snapshot), (QVector<SectionKind>{SectionKind::Requests, SectionKind::Away}));
    QCOMPARE(ids(snapshot, SectionKind::Away), (QStringList{"pending"}));
    QCOMPARE(snapshot.emptyState, EmptyState::None);
}

/** A group invitation is a card in Requests like a friend request, newest first, and counted. */
void TestAirModel::invitationsAreCardsAmongTheRequests()
{
    const Request invitation =
        Request::invitation(QStringLiteral("ab12"), QString(64, QLatin1Char('b')),
                            QStringLiteral(" Family "), QStringLiteral("Yusuf"), false, QString(), 3);
    QCOMPARE(invitation.id, QStringLiteral("invite:ab12"));
    QCOMPARE(invitation.publicKey, QString(64, QLatin1Char('B')));
    QCOMPARE(invitation.groupName, QStringLiteral("Family"));
    QVERIFY(invitation.isInvitation());
    QVERIFY(!request("r", 1).isInvitation());

    const Snapshot snapshot =
        buildAll({entry("friend", Reach::direct())}, {request("r", 1), invitation}, Filter::Requests);
    QCOMPARE(ids(snapshot, SectionKind::Requests),
             (QStringList{QStringLiteral("invite:ab12"), QStringLiteral("r")}));
    QCOMPARE(snapshot.counts.requests, 2);
}

void TestAirModel::transitFilterKeepsRowsWithWaitingMessages()
{
    const QVector<Entry> entries{entry("waiting", Reach::away(3600), 0, 2), entry("clear"), saved()};

    const Snapshot snapshot = buildAll(entries, {request("r", 1)}, Filter::Transit);

    QCOMPARE(kinds(snapshot), (QVector<SectionKind>{SectionKind::Away}));
    QCOMPARE(ids(snapshot, SectionKind::Away), (QStringList{"waiting"}));
    QCOMPARE(snapshot.emptyState, EmptyState::None);
}

void TestAirModel::undeliveredMessagesWaitInTransitOnlyWhileThePersonIsAway()
{
    QCOMPARE(Reach::away(60).messagesInTransit(3), 3);
    QCOMPARE(Reach::connecting().messagesInTransit(2), 2);
    QCOMPARE(Reach::pending().messagesInTransit(1), 1);
    QCOMPARE(Reach::awayLongAgo().messagesInTransit(4), 4);
    QCOMPARE(Reach::direct().messagesInTransit(3), 0);
    QCOMPARE(Reach::relay().messagesInTransit(3), 0);
}

void TestAirModel::rowMarkOfALastTextFollowsTheConversation()
{
    // isDelivered, handedOver (toxcore took it), personOnline.
    QCOMPARE(outgoingTextDelivery(false, true, false), Delivery::Transit);
    QCOMPARE(outgoingTextDelivery(false, false, true), Delivery::Transit);
    QCOMPARE(outgoingTextDelivery(false, true, true), Delivery::Sent);
    QCOMPARE(outgoingTextDelivery(true, true, false), Delivery::Delivered);
    QCOMPARE(outgoingTextDelivery(true, true, true), Delivery::Delivered);
}

void TestAirModel::favoritesFilterKeepsStarredRowsAndSavedMessages()
{
    const QVector<Entry> entries{favorite("star"), entry("plain", Reach::direct()), saved()};

    const Snapshot snapshot = buildAll(entries, {}, Filter::Favorites);

    QCOMPARE(kinds(snapshot), (QVector<SectionKind>{SectionKind::Live, SectionKind::Saved}));
    QCOMPARE(ids(snapshot, SectionKind::Live), (QStringList{"star"}));
    QCOMPARE(snapshot.emptyState, EmptyState::None);
}

void TestAirModel::favoritesFilterDoesNotMatchEveryRow()
{
    const QVector<Entry> entries{entry("a", Reach::direct(), 1), entry("b", Reach::away(9), 2),
                                 saved()};

    const Snapshot snapshot = buildAll(entries, {}, Filter::Favorites);

    QCOMPARE(kinds(snapshot), (QVector<SectionKind>{SectionKind::Saved}));
    QCOMPARE(snapshot.emptyState, EmptyState::NoFavorites);
}

void TestAirModel::filterNamesRoundTrip()
{
    QCOMPARE(pillFilters(), (QVector<Filter>{Filter::Requests, Filter::Transit, Filter::Favorites}));
    QCOMPARE(filterFromName(QStringLiteral("transit")), Filter::Transit);
    QCOMPARE(filterFromName(QStringLiteral("nonsense")), Filter::All);
    for (Filter filter : {Filter::All, Filter::Requests, Filter::Transit, Filter::Favorites}) {
        QCOMPARE(filterFromName(filterName(filter)), filter);
    }
}

void TestAirModel::queryMatchesTitlesIgnoringCaseAndMarksAndHidesRequests()
{
    const QVector<Entry> entries{titled("a", QStringLiteral("Zoë Adams"), EntryKind::Direct,
                                        Reach::direct()),
                                 titled("b", "Bob", EntryKind::Direct), saved()};
    MessageHit hit;
    hit.id = QStringLiteral("m1");
    hit.chatId = QStringLiteral("b");
    hit.title = QStringLiteral("Bob");
    hit.snippet = QStringLiteral("zoe said hi");

    const Snapshot snapshot =
        build(entries, {request("r", 1)}, {hit}, Filter::All, QStringLiteral("  zoe "));

    QCOMPARE(kinds(snapshot), (QVector<SectionKind>{SectionKind::Live, SectionKind::Messages}));
    QCOMPARE(ids(snapshot, SectionKind::Live), (QStringList{"a"}));
    QCOMPARE(ids(snapshot, SectionKind::Messages), (QStringList{"m1"}));
    QCOMPARE(snapshot.emptyState, EmptyState::None);
}

void TestAirModel::messageHitsAreIgnoredWithoutAQuery()
{
    MessageHit hit;
    hit.id = QStringLiteral("m1");
    hit.chatId = QStringLiteral("b");
    hit.title = QStringLiteral("Bob");

    const Snapshot snapshot =
        build({titled("b", "Bob", EntryKind::Direct)}, {}, {hit}, Filter::All, QStringLiteral(" "));

    QCOMPARE(kinds(snapshot), (QVector<SectionKind>{SectionKind::Away}));
}

void TestAirModel::highlightsCoverEveryMatchInTheOriginalText()
{
    using Ranges = QVector<QPair<int, int>>;
    QCOMPARE(matchRanges(QStringLiteral("Zoë and ZOE"), QStringLiteral("zoe")),
             (Ranges{qMakePair(0, 3), qMakePair(8, 11)}));
    // A decomposed letter keeps its mark inside the highlight.
    QCOMPARE(matchRanges(QStringLiteral("Zoë!"), QStringLiteral("zoe")), (Ranges{qMakePair(0, 4)}));
    QCOMPARE(matchRanges(QStringLiteral("Привет мир"), QStringLiteral("МИР")),
             (Ranges{qMakePair(7, 10)}));
    QVERIFY(matchRanges(QStringLiteral("abc"), QStringLiteral("  ")).isEmpty());
    QVERIFY(matchRanges(QString{}, QStringLiteral("a")).isEmpty());
    QVERIFY(matchRanges(QStringLiteral("abc"), QStringLiteral("x")).isEmpty());
}

void TestAirModel::countsIgnoreTheFilterAndTheQuery()
{
    const QVector<Entry> entries{entry("a", Reach::away(3600), 0, 2),
                                 entry("b", Reach::away(3600), 0, 1), saved()};
    const QVector<Request> requests{request("r1", 1), request("r2", 2)};

    for (Filter filter : {Filter::All, Filter::Requests, Filter::Transit, Filter::Favorites}) {
        for (const QString& query : {QString{}, QStringLiteral("nothing like it")}) {
            const Snapshot snapshot = buildAll(entries, requests, filter, query);
            QCOMPARE(snapshot.counts.requests, 2);
            QCOMPARE(snapshot.counts.transit, 3);
        }
    }
}

void TestAirModel::onlySavedMessagesMeansNothingYet()
{
    const Snapshot snapshot = buildAll({saved()});

    QCOMPARE(snapshot.emptyState, EmptyState::NothingYet);
    QCOMPARE(kinds(snapshot), (QVector<SectionKind>{SectionKind::Saved}));
    QCOMPARE(buildAll({}).emptyState, EmptyState::NothingYet);
}

void TestAirModel::aRequestAloneIsNotNothing()
{
    QCOMPARE(buildAll({saved()}, {request("r", 1)}).emptyState, EmptyState::None);
}

void TestAirModel::emptyStateForEachFilterAndForTheQuery()
{
    const QVector<Entry> entries{entry("a"), saved()};

    QCOMPARE(buildAll(entries, {}, Filter::Requests).emptyState, EmptyState::NoRequests);
    QCOMPARE(buildAll(entries, {}, Filter::Transit).emptyState, EmptyState::NoTransit);
    QCOMPARE(buildAll(entries, {}, Filter::Favorites).emptyState, EmptyState::NoFavorites);
    QCOMPARE(buildAll(entries, {}, Filter::All, QStringLiteral("qqq")).emptyState, EmptyState::NoMatch);
    QCOMPARE(buildAll(entries, {}, Filter::All).emptyState, EmptyState::None);
    // The start of an ID gets the row "New contact with this ID" instead of "nothing found".
    QCOMPARE(buildAll(entries, {}, Filter::All, QStringLiteral("56a1 ade4 b65b")).emptyState,
             EmptyState::None);
}

void TestAirModel::flatRowsCarryHeadingsWithCounts()
{
    const Snapshot snapshot =
        buildAll({entry("a", Reach::direct(), 2), entry("b", Reach::direct(), 1), entry("c")},
                 {request("r", 1)});
    const QVector<Row> rows = snapshot.rows();

    QStringList rowIds;
    for (const Row& row : rows) {
        rowIds.append(row.id());
    }
    QCOMPARE(rowIds, (QStringList{"request:r", "header:live", "entry:a", "entry:b", "header:away",
                                  "entry:c"}));
    QCOMPARE(rows[1].count, 2);
    QCOMPARE(rows[4].count, 1);
}

void TestAirModel::aPersonComingOnlineIsTheOneRowThatMoves()
{
    const QVector<Entry> before{entry("a", Reach::direct(), 30), entry("b", Reach::away(3600), 20),
                                entry("c", Reach::away(3600), 10)};
    const QVector<Entry> after{entry("a", Reach::direct(), 30), entry("b", Reach::away(3600), 20),
                               entry("c", Reach::direct(), 10)};

    Changes changes;
    QVERIFY(diff(buildAll(before), buildAll(after), changes));

    QCOMPARE(changes.movedRows, (QVector<RowMove>{RowMove{IndexPath{1, 1}, IndexPath{0, 1}}}));
    QVERIFY(changes.deletedRows.isEmpty());
    QVERIFY(changes.insertedRows.isEmpty());
    QVERIFY(changes.deletedSections.isEmpty());
    QVERIFY(changes.insertedSections.isEmpty());
}

void TestAirModel::unchangedListsNeedNoUpdate()
{
    const Snapshot snapshot =
        buildAll({entry("a", Reach::direct(), 3), entry("b", Reach::away(3600), 2), saved()},
                 {request("r", 1)});

    Changes changes;
    QVERIFY(diff(snapshot, snapshot, changes));
    QVERIFY(changes.isEmpty());
}

void TestAirModel::aRowCannotMoveIntoASectionThatAppears()
{
    const QVector<Entry> before{entry("a", Reach::away(3600), 2), entry("b", Reach::away(3600), 1)};
    const QVector<Entry> after{entry("a", Reach::direct(), 2), entry("b", Reach::away(3600), 1)};

    Changes changes;
    QVERIFY(diff(buildAll(before), buildAll(after), changes));

    QCOMPARE(changes.insertedSections, (QSet<int>{0}));
    QCOMPARE(changes.deletedRows, (QVector<IndexPath>{IndexPath{0, 0}}));
    QVERIFY(changes.insertedRows.isEmpty());
    QVERIFY(changes.movedRows.isEmpty());
}

void TestAirModel::tooManyChangesFallBackToAReload()
{
    QVector<Entry> before;
    QVector<Entry> after;
    for (int index = 0; index < 50; ++index) {
        before.append(entry(QStringLiteral("p%1").arg(index), Reach::away(3600), index));
        after.append(entry(QStringLiteral("p%1").arg(index + 50), Reach::away(3600), index + 50));
    }

    Changes changes;
    QVERIFY(!diff(buildAll(before), buildAll(after), changes));
}

void TestAirModel::longestIncreasingRun()
{
    QCOMPARE(Air::longestIncreasingRun({}), QSet<int>{});
    QCOMPARE(Air::longestIncreasingRun({0, 1, 2}), (QSet<int>{0, 1, 2}));
    QCOMPARE(Air::longestIncreasingRun({2, 1, 0}).size(), 1);
    QCOMPARE(Air::longestIncreasingRun({0, 3, 1, 2, 4}), (QSet<int>{0, 2, 3, 4}));
}

void TestAirModel::randomUpdatesReplayIntoTheNewList()
{
    SeededGenerator generator(20261111);
    int animated = 0;
    const int rounds = 300;

    for (int round = 0; round < rounds; ++round) {
        QVector<Entry> before;
        const int count = generator.below(14);
        for (int index = 0; index < count; ++index) {
            before.append(entry(QStringLiteral("p%1").arg(index), randomReach(generator),
                                randomActivity(generator)));
        }
        QVector<Entry> after;
        for (const Entry& old : before) {
            switch (generator.below(6)) {
            case 0:
                continue;
            case 1:
                after.append(entry(old.id, randomReach(generator), old.activity));
                break;
            case 2:
                after.append(entry(old.id, old.reach, randomActivity(generator)));
                break;
            default:
                after.append(old);
                break;
            }
        }
        const int added = generator.below(3);
        for (int index = 0; index < added; ++index) {
            after.append(entry(QStringLiteral("n%1").arg(index), randomReach(generator),
                               randomActivity(generator)));
        }
        QVector<Request> oldRequests;
        const int requestCount = generator.below(3);
        for (int index = 0; index < requestCount; ++index) {
            oldRequests.append(request(QStringLiteral("r%1").arg(index), index));
        }
        QVector<Request> newRequests = oldRequests;
        if (generator.below(2) == 0 && !newRequests.isEmpty()) {
            newRequests.removeFirst();
        }
        if (generator.below(2) == 0) {
            before.append(saved());
            after.append(saved());
        }

        const Snapshot oldSnapshot = buildAll(before, oldRequests);
        const Snapshot newSnapshot = buildAll(after, newRequests);

        Changes changes;
        if (!diff(oldSnapshot, newSnapshot, changes)) {
            continue;
        }
        ++animated;
        QVector<QStringList> replayed;
        QVERIFY(replay(changes, oldSnapshot, newSnapshot, replayed));
        QCOMPARE(replayed, sectionIds(newSnapshot));
    }

    // Lists this small stay far below the animation limit, so almost every round must animate.
    QVERIFY(animated > rounds * 9 / 10);
}

void TestAirModel::commandLineTellsASearchFromAnId()
{
    const QString address = QStringLiteral(
        "56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855D34D34D37CB5");
    const QString publicKey = address.left(64);

    auto check = [](const QString& raw, Command::Type type, const QString& value) {
        const Command command = Command::parse(raw);
        return command.type == type && command.value == value;
    };

    QVERIFY(check(QString{}, Command::Type::Empty, QString{}));
    QVERIFY(check(QStringLiteral("  \n "), Command::Type::Empty, QString{}));
    QVERIFY(check(QStringLiteral("  Alice  "), Command::Type::Query, QStringLiteral("Alice")));
    // Hex letters in ordinary words are still a search.
    QVERIFY(check(QStringLiteral("cafe bead"), Command::Type::Query, QStringLiteral("cafe bead")));
    // A bare key has the length of a group ID: never taken for a person's ID.
    QVERIFY(check(publicKey, Command::Type::Query, publicKey));
    QVERIFY(check(address + "0", Command::Type::Query, address + "0"));

    const QString pasted = address.left(30).toLower() + " \n" + address.mid(30);
    QVERIFY(check(pasted, Command::Type::ContactAddress, address));
    QVERIFY(check(QStringLiteral("tox://") + address, Command::Type::ContactAddress, address));
    QVERIFY(check(QStringLiteral("Tox:") + address, Command::Type::ContactAddress, address));
    QVERIFY(check(QStringLiteral("​") + address + QStringLiteral("﻿"),
                  Command::Type::ContactAddress, address));
    QVERIFY(check(address, Command::Type::ContactAddress, address));
    // Only one prefix comes off.
    QVERIFY(check(QStringLiteral("tox:tox:") + address, Command::Type::Query,
                  QStringLiteral("tox:tox:") + address));
    QCOMPARE(standaloneToxAddress(address).size(), toxAddressHexLength);
}

namespace {
/** A whole ID with a correct check (the one of the iOS and Android tests). */
const QString goodAddress =
    QStringLiteral("56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855D34D34D37CB5");
/** The profile's own ID, also with a correct check, since toxcore makes it. */
const QString ownAddress =
    QStringLiteral("7F3A91C05D2E8B4416A9C3E07D5F2B8164E0A3C9D7B25F1E8A4C6D0B3F9E2A7100000000062A");

/** The check toxcore puts at the end of an ID, for building one in a test. */
QString checkFor(const QString& keyAndAntispam)
{
    const QByteArray bytes = QByteArray::fromHex(keyAndAntispam.toLatin1());
    unsigned char check[2] = {0, 0};
    for (int i = 0; i < bytes.size(); ++i) {
        check[i % 2] ^= static_cast<unsigned char>(bytes.at(i));
    }
    return QString::fromLatin1(QByteArray(reinterpret_cast<const char*>(check), 2).toHex().toUpper());
}

/**
 * The verdict on hex with these contacts, open requests and blocked keys. A function, since a
 * lambda takes no default arguments in C++11.
 */
IdVerdict verdict(const QString& hex, const QSet<QString>& contacts = {},
                  const QSet<QString>& requests = {}, const QSet<QString>& blocked = {})
{
    return IdVerdict::of(
        hex, ownAddress, [&contacts](const QString& k) { return contacts.contains(k); },
        [&requests](const QString& k) { return requests.contains(k); },
        [&blocked](const QString& k) { return blocked.contains(k); });
}
} // namespace

void TestAirModel::theIdFieldTakesAnIdInAnyUsualShape()
{
    const QString address = goodAddress;
    QCOMPARE(cleanToxId(address.toLower()), address);
    QCOMPARE(cleanToxId(QStringLiteral("tox:") + address), address);
    QCOMPARE(cleanToxId(QStringLiteral("TOX://") + address.left(40) + QStringLiteral("\n  ")
                        + address.mid(40)),
             address);
    QCOMPARE(cleanToxId(QStringLiteral("\u200B") + groupedHex(address) + QStringLiteral("\uFEFF")),
             address);
    // A sentence around a whole ID: the ID, not the hex letters of the words.
    QCOMPARE(cleanToxId(QStringLiteral("My ID is ") + address + QStringLiteral(", add me")), address);
    // Anything longer is cut at 76 characters.
    QCOMPARE(cleanToxId(address + QStringLiteral("ABCD")).size(), toxAddressHexLength);
    QCOMPARE(cleanToxId(QStringLiteral("12 34 ab")), QStringLiteral("1234AB"));
    QCOMPARE(cleanToxId(QString{}), QString{""});
    QCOMPARE(groupedHex(QStringLiteral("1234567890")), QStringLiteral("1234 5678 90"));
    QCOMPARE(groupedHex(QString{}), QString{""});
}

void TestAirModel::theCheckIsReallyChecked()
{
    const QString address = goodAddress;
    QVERIFY(toxIdCheckMatches(address));
    QString lastWrong = address;
    lastWrong[75] = address.endsWith(QLatin1Char('5')) ? QLatin1Char('6') : QLatin1Char('5');
    QVERIFY(!toxIdCheckMatches(lastWrong));
    // A typo in the key is caught too: the old field recomputed the check and hid it.
    QString keyTypo = address;
    keyTypo[10] = keyTypo.at(10) == QLatin1Char('A') ? QLatin1Char('B') : QLatin1Char('A');
    QVERIFY(!toxIdCheckMatches(keyTypo));
    QVERIFY(!toxIdCheckMatches(address.left(64)));
    QVERIFY(!toxIdCheckMatches(address.left(75) + QLatin1Char('G')));
    QCOMPARE(checkFor(address.left(72)), address.right(4));
}

void TestAirModel::theVerdictsComeInTheirOrder()
{
    const QString address = goodAddress;
    const QString key = address.left(64);

    QCOMPARE(verdict(QString{}).kind, IdVerdict::Kind::Empty);
    const IdVerdict partial = verdict(key);
    QCOMPARE(partial.kind, IdVerdict::Kind::Partial);
    QCOMPARE(partial.left, 12);
    QCOMPARE(verdict(address.left(75) + QLatin1Char('0')).kind, IdVerdict::Kind::BadCheck);
    const IdVerdict ok = verdict(address);
    QCOMPARE(ok.kind, IdVerdict::Kind::Ok);
    QCOMPARE(ok.address(), address);

    const IdVerdict contact = verdict(address, {key});
    QCOMPARE(contact.kind, IdVerdict::Kind::Contact);
    QCOMPARE(contact.value, key);
    QVERIFY(contact.address().isNull());
    // A contact wins over a request and a block, a request over a block.
    QCOMPARE(verdict(address, {key}, {key}, {key}).kind, IdVerdict::Kind::Contact);
    QCOMPARE(verdict(address, {}, {key}, {key}).kind, IdVerdict::Kind::TheyAsked);
    QVERIFY(verdict(address, {}, {key}).address().isNull());

    const IdVerdict blocked = verdict(address, {}, {}, {key});
    QCOMPARE(blocked.kind, IdVerdict::Kind::Blocked);
    // A blocked key may be asked again: that lifts the block.
    QCOMPARE(blocked.address(), address);

    // Your own ID with another antispam is still yours, and a bad check comes before it.
    const QString ownOtherAntispam = ownAddress.left(64) + QStringLiteral("12345678");
    QCOMPARE(verdict(ownOtherAntispam + checkFor(ownOtherAntispam)).kind, IdVerdict::Kind::Own);
    QCOMPARE(verdict(ownOtherAntispam + QStringLiteral("0000")).kind, IdVerdict::Kind::BadCheck);
    QCOMPARE(verdict(ownAddress).kind, IdVerdict::Kind::Own);
}

void TestAirModel::theRulerCountsEachPart()
{
    const auto filled = [](int count) {
        QVector<int> result;
        for (const RulerPart& part : RulerPart::parts(count)) {
            result << part.filled;
        }
        return result;
    };
    QCOMPARE(filled(0), QVector<int>({0, 0, 0}));
    QCOMPARE(filled(30), QVector<int>({30, 0, 0}));
    QCOMPARE(filled(70), QVector<int>({64, 6, 0}));
    QCOMPARE(filled(76), QVector<int>({64, 8, 4}));
    QCOMPARE(filled(90), QVector<int>({64, 8, 4}));
    QVector<int> sizes;
    for (const RulerPart& part : RulerPart::parts(76)) {
        sizes << part.size;
    }
    QCOMPARE(sizes, QVector<int>({64, 8, 4}));

    IdVerdict partial;
    partial.kind = IdVerdict::Kind::Partial;
    partial.left = 3;
    QCOMPARE(partial.check(), IdVerdict::Check::Filling);
    IdVerdict bad;
    bad.kind = IdVerdict::Kind::BadCheck;
    QCOMPARE(bad.check(), IdVerdict::Check::Bad);
    IdVerdict own;
    own.kind = IdVerdict::Kind::Own;
    QCOMPARE(own.check(), IdVerdict::Check::Good);
}

void TestAirModel::theIdFieldKeepsItsGroupsAndTheCaret()
{
    using State = IdFieldState;
    const auto state = [](const QString& text, int caret) {
        State result;
        result.text = text;
        result.caret = caret;
        return result;
    };
    // Typing at the end: a closed group keeps the caret at its end until the next character.
    QCOMPARE(State::regroup(QStringLiteral("abcd"), 4), state(QStringLiteral("ABCD"), 4));
    QCOMPARE(State::regroup(QStringLiteral("ABCDE"), 5), state(QStringLiteral("ABCD E"), 6));
    // Typing inside: the caret stays after the character typed, past the space of a full group.
    QCOMPARE(State::regroup(QStringLiteral("ABC9D EF"), 4), state(QStringLiteral("ABC9 DEF"), 5));
    QCOMPARE(State::regroup(QStringLiteral("A9BCD EF"), 2), state(QStringLiteral("A9BC DEF"), 2));
    // Letters that are not hex and spaces typed by hand do not stay.
    QCOMPARE(State::regroup(QStringLiteral("ABCD Ex"), 7), state(QStringLiteral("ABCD E"), 6));
    QCOMPARE(State::regroup(QStringLiteral("ABCD  "), 6), state(QStringLiteral("ABCD"), 4));
    // A paste lands whole, cleaned, with the caret at its end.
    const QString pasted = QStringLiteral("tox:") + goodAddress.toLower();
    const State whole = State::regroup(pasted, pasted.size());
    QCOMPARE(whole.text, groupedHex(goodAddress));
    QCOMPARE(whole.caret, whole.text.size());
    QCOMPARE(State::regroup(QString{}, 0), state(QString{""}, 0));

    // Backspace after a group space takes the character before it, not only the space.
    QCOMPARE(State::erase(QStringLiteral("ABCD EF"), 5, false), state(QStringLiteral("ABCE F"), 3));
    QCOMPARE(State::erase(QStringLiteral("ABCD EF"), 7, false), state(QStringLiteral("ABCD E"), 6));
    QCOMPARE(State::erase(QStringLiteral("ABCD E"), 6, false), state(QStringLiteral("ABCD"), 4));
    QCOMPARE(State::erase(QStringLiteral("ABCD"), 0, false), state(QStringLiteral("ABCD"), 0));
    // Delete before a group space takes the character after it.
    QCOMPARE(State::erase(QStringLiteral("ABCD EF01"), 4, true), state(QStringLiteral("ABCD F01"), 5));
    QCOMPARE(State::erase(QStringLiteral("ABCD EF01"), 9, true), state(QStringLiteral("ABCD EF01"), 9));
}

void TestAirModel::theCommandLineReadsTheStartOfAnId()
{
    const QString address = goodAddress;
    QCOMPARE(commandIdCandidate(address.left(12)), address.left(12));
    QVERIFY(commandIdCandidate(address.left(11)).isNull());
    QVERIFY(commandIdCandidate(QStringLiteral("Amina Safina")).isNull());
    QCOMPARE(commandIdCandidate(QStringLiteral("tox:") + address.toLower()), address);
    QCOMPARE(commandIdCandidate(QStringLiteral(" 56A1 ADE4\nB65B ")),
             QStringLiteral("56A1ADE4B65B"));
    QVERIFY(commandIdCandidate(address + QLatin1Char('0')).isNull());
    // Twelve hex letters without a digit are a word, not the start of an ID.
    QVERIFY(commandIdCandidate(QStringLiteral("DEADBEEFCAFE")).isNull());
    QVERIFY(commandIdCandidate(QStringLiteral("56A1 ADE4 B65G")).isNull());
}

void TestAirModel::requestKeyShowsTheFirstTwelveDigitsInFours()
{
    QCOMPARE(groupedKey(QStringLiteral("56a1ade4b65b86bcd51cc73e")),
             QStringLiteral("56A1 ADE4 B65B"));
    QCOMPARE(groupedKey(QStringLiteral("56A1AD")), QStringLiteral("56A1 AD"));
    QCOMPARE(groupedKey(QString{}), QString{""});
}

void TestAirModel::oneCardPerPersonWhateverCaseTheKeyArrivesIn()
{
    const Request lower =
        Request::make(QStringLiteral("abcd"), QStringLiteral("  hello  "), QString{}, 1);
    QCOMPARE(lower.id, QStringLiteral("ABCD"));
    QCOMPARE(lower.publicKey, QStringLiteral("ABCD"));
    QCOMPARE(lower.message, QStringLiteral("hello"));
}

void TestAirModel::previewsShowTheBodyWithoutReplyAndMentionMarkup()
{
    // The wire forms are the ones khandaq-ios MessageReplyHelper.encode and GroupMentionHelper write.
    QCOMPARE(plainMessageBody(QStringLiteral("  plain text \n")), QStringLiteral("plain text"));
    QCOMPARE(plainMessageBody(QStringLiteral("[KQ|42|56A1ADE4|1762800000000|Amina]\nwhat time?\n"
                                             "[KQ/end]\nat five")),
             QStringLiteral("at five"));
    // A name with a bar in it stays part of the header.
    QCOMPARE(plainMessageBody(QStringLiteral("[KQ|42|56A1|1762800000000|A|B]\nq\n[KQ/end]\nbody")),
             QStringLiteral("body"));
    // Without the footer the first line after the header is the quote.
    QCOMPARE(plainMessageBody(QStringLiteral("[KQ|7|AB|1|Yusuf]\n\nquoted line\nthe answer")),
             QStringLiteral("the answer"));
    QCOMPARE(plainMessageBody(QStringLiteral("[KQ|7|AB|1|Yusuf]\nquoted only")), QString());
    // Not a number where the id and the time go: no reply, but markup this build cannot read,
    // which shows the words after it (DESIGN.md "Unknown markup").
    QCOMPARE(plainMessageBody(QStringLiteral("[KQ|x|AB|1|Yusuf] hi")), QStringLiteral("hi"));
    QCOMPARE(plainMessageBody(QStringLiteral("----\nold quote\n----\n  the reply")),
             QStringLiteral("the reply"));
    QCOMPARE(plainMessageBody(QStringLiteral("[KQ|m|56A1ADE4:amina][KQ/m] @amina look")),
             QStringLiteral("@amina look"));
    QCOMPARE(plainMessageBody(QStringLiteral("[KQ|m]56A1ADE4,amina[|KQ|m]@amina look")),
             QStringLiteral("@amina look"));
    QCOMPARE(plainMessageBody(QStringLiteral("[KQ|1|AB|2|N]\nq\n[KQ/end]\n[KQ|m|AB:n][KQ/m]both")),
             QStringLiteral("both"));
    // Control characters other than line breaks go (a trailing NUL from older builds).
    QCOMPARE(plainMessageBody(QStringLiteral("[KQ|1|AB|2|N]\nq\n[KQ/end]\nok") + QChar(0)),
             QStringLiteral("ok"));
}

void TestAirModel::markupThisBuildCannotReadIsShownInWords()
{
    // The vectors of khandaq-ios AirPacketTests: a newer reply keeps its words, without a quote.
    const MessageText newerReply =
        readMessageText(QStringLiteral("[KQ|r2|ABCDEF12|Amina]\nquote\n[KQ/end]\nСалам"));
    QVERIFY(!newerReply.reply);
    QVERIFY(newerReply.marked);
    QVERIFY(!newerReply.unreadable);
    QCOMPARE(newerReply.body, QStringLiteral("Салам"));

    // Nothing after the markup: the message is said in words, in the transcript and elsewhere.
    const QString poll = QStringLiteral("[KQ|poll|1|2|3]");
    QVERIFY(readMessageText(poll).unreadable);
    QCOMPARE(readMessageText(poll).body, QString());
    QCOMPARE(AirStrings::messageWords(poll),
             QStringLiteral("This message is in a form this app cannot show yet"));
    QCOMPARE(AirStrings::messageWords(QStringLiteral("[KQ|m|ABCDEF12:amina][KQ/m]")),
             QStringLiteral("This message is in a form this app cannot show yet"));

    // A reply in the current form: its quote, then its words.
    const MessageText reply = readMessageText(
        QStringLiteral("[KQ|42|ABCDEF12|1700000000000|Amina]\nquote\n[KQ/end]\nСалам"));
    QVERIFY(reply.reply);
    QVERIFY(reply.marked);
    QCOMPARE(reply.replyName, QStringLiteral("Amina"));
    QCOMPARE(reply.replyPreview, QStringLiteral("quote"));
    QCOMPARE(reply.body, QStringLiteral("Салам"));
    QCOMPARE(AirStrings::messageWords(reply.body), QStringLiteral("Салам"));
    // A reply that quotes words but says nothing more is still a reply, not an unreadable form.
    const MessageText quoteOnly = readMessageText(QStringLiteral("[KQ|7|AB|1|Yusuf]\nquoted only"));
    QVERIFY(quoteOnly.reply);
    QVERIFY(!quoteOnly.unreadable);
    // The older quote names nobody: the quote says Unknown.
    QCOMPARE(readMessageText(QStringLiteral("----\nold quote\n----\nthe reply")).replyName, QString());
    QCOMPARE(AirStrings::replyName(QString()), QStringLiteral("Unknown"));
    QCOMPARE(AirStrings::replyName(QStringLiteral(" Amina ")), QStringLiteral("Amina"));

    // A mention keeps its words.
    QCOMPARE(AirStrings::messageWords(QStringLiteral("[KQ|m|ABCDEF12:amina][KQ/m]Салам")),
             QStringLiteral("Салам"));
}

void TestAirModel::anEntryShowsAPlainMessageAsItCame()
{
    // Plain text that merely contains [KQ stays as it is, spaces and lines included.
    for (const QString& plain :
         {QStringLiteral("[KQ is a word here]"), QStringLiteral("see [KQ|x] inside"),
          QStringLiteral("  indented\n  code"), QStringLiteral(" [KQ|x] after a space")}) {
        const MessageText text = readMessageText(plain);
        QVERIFY(!text.marked);
        QVERIFY(!text.unreadable);
        QCOMPARE(entryWords(plain), plain);
        QCOMPARE(AirStrings::messageWords(plain), plain);
    }
    // A message with markup shows its body only.
    QCOMPARE(entryWords(QStringLiteral("[KQ|1|AB|2|N]\nq\n[KQ/end]\n  ok  ")),
             QStringLiteral("ok"));
}

void TestAirModel::ownWordsBeginAfterTheQuote()
{
    // A plain message and a mention block are the author's words from the first character.
    QCOMPARE(ownWordsStart(QStringLiteral("Салам")), 0);
    QCOMPARE(ownWordsStart(QStringLiteral("[KQ|m|ABCDEF12:amina][KQ/m]@amina, салам")), 0);
    QCOMPARE(ownWordsStart(QString()), 0);

    // A reply: after the footer, or after the quote line when there is none.
    const QString reply =
        QStringLiteral("[KQ|42|ABCDEF12|1700000000000|Amina]\nquote\n[KQ/end]\nok");
    QCOMPARE(reply.mid(ownWordsStart(reply)), QStringLiteral("\nok"));
    const QString shortReply = QStringLiteral("[KQ|7|AB|1|Yusuf]\nquoted line\nmy words");
    QCOMPARE(shortReply.mid(ownWordsStart(shortReply)), QStringLiteral("my words"));
    const QString quoteOnly = QStringLiteral("[KQ|7|AB|1|Yusuf]\nquoted only");
    QCOMPARE(ownWordsStart(quoteOnly), quoteOnly.size());
    const QString legacy = QStringLiteral("----\nold quote\n----\n the reply");
    QCOMPARE(legacy.mid(ownWordsStart(legacy)), QStringLiteral("the reply"));

    // Markup this build cannot read: after it, and nothing when nothing follows.
    const QString place = QStringLiteral("[KQ|loc|55.79|49.10]\nу входа");
    QCOMPARE(place.mid(ownWordsStart(place)), QStringLiteral("\nу входа"));
    const QString poll = QStringLiteral("[KQ|poll|1|Amina?|2]");
    QCOMPARE(ownWordsStart(poll), poll.size());
}

void TestAirModel::friendReach()
{
    const qint64 now = 1000000;

    QCOMPARE(Reach::friendReach(toxConnectionUdp, 0, now, false), Reach::direct());
    QCOMPARE(Reach::friendReach(toxConnectionTcp, 0, now, false), Reach::relay());
    QCOMPARE(Reach::friendReach(toxConnectionNone, 0, now, false), Reach::pending());
    QCOMPARE(Reach::friendReach(toxConnectionNone, now - 90, now, false), Reach::away(90));
    // A clock that runs behind never gives a negative age.
    QCOMPARE(Reach::friendReach(toxConnectionNone, now + 30, now, false), Reach::away(0));
}

void TestAirModel::friendSeenTodayIsStillLinkingRightAfterWeComeOnline()
{
    const qint64 now = 1000000;

    QCOMPARE(Reach::friendReach(toxConnectionNone, now - 90, now, true), Reach::connecting());
    QCOMPARE(Reach::friendReach(toxConnectionNone, now - 86400, now, true), Reach::away(86400));
    QCOMPARE(Reach::friendReach(toxConnectionNone, 0, now, true), Reach::pending());
    QCOMPARE(Reach::friendReach(toxConnectionUdp, now - 90, now, true), Reach::direct());
}

/**
 * KHANDAQ (QA 2026-11-14, F2): toxcore has no last-online time for a contact made by accepting
 * their request until they first connect. They were online when the request came, and that time
 * stands for it: the row is not "waiting for reply", the tile is not dashed and the contact is not
 * among the requests. Our own request, unanswered, still is.
 */
void TestAirModel::aContactWhoseRequestWeAcceptedIsNotWaitingForAReply()
{
    const qint64 now = 1000000;
    const qint64 day = 86400;

    QCOMPARE(Reach::friendReach(toxConnectionNone, 0, now, false, now - 30), Reach::away(30));
    QCOMPARE(Reach::friendReach(toxConnectionNone, 0, now, false, now - 3 * day), Reach::away(3 * day));
    // Right after our own node comes online they are linking, as anyone seen within the day.
    QCOMPARE(Reach::friendReach(toxConnectionNone, 0, now, true, now - 90), Reach::connecting());
    QCOMPARE(Reach::friendReach(toxConnectionNone, 0, now, true, now - day), Reach::away(day));
    // Once toxcore has a time of its own, that time counts.
    QCOMPARE(Reach::friendReach(toxConnectionNone, now - 60, now, false, now - 2 * 3600),
             Reach::away(60));
    QCOMPARE(Reach::friendReach(toxConnectionUdp, 0, now, false, now - 30), Reach::direct());
    QCOMPARE(Reach::friendReach(toxConnectionTcp, 0, now, false, now - 30), Reach::relay());
    QCOMPARE(Reach::friendReach(toxConnectionNone, 0, now, false, 0), Reach::pending());
    QCOMPARE(Reach::friendReach(toxConnectionNone, 0, now, true, 0), Reach::pending());

    const Entry accepted =
        entry("accepted", Reach::friendReach(toxConnectionNone, 0, now, false, now - 30));
    const Entry ours = entry("ours", Reach::friendReach(toxConnectionNone, 0, now, false, 0));
    QVERIFY(!matches(accepted, Filter::Requests));
    QVERIFY(matches(ours, Filter::Requests));
    QCOMPARE(buildAll({accepted, saved()}, {}, Filter::Requests).emptyState, EmptyState::NoRequests);
    QCOMPARE(ids(buildAll({accepted, ours, saved()}, {}, Filter::Requests), SectionKind::Away),
             (QStringList{"ours"}));
}

void TestAirModel::onlyAWorkingLinkCountsAsLive()
{
    QVERIFY(Reach::direct().isLive());
    QVERIFY(Reach::relay().isLive());
    QVERIFY(Reach::group(1).isLive());
    QVERIFY(!Reach::group(0).isLive());
    QVERIFY(!Reach::away(1).isLive());
    QVERIFY(!Reach::pending().isLive());
    QVERIFY(!Reach::connecting().isLive());
    QVERIFY(!Reach::local().isLive());
    QVERIFY(!Reach::awayLongAgo().isLive());
}

void TestAirModel::networkStateFromTheToxLinkAndTheDevice()
{
    QCOMPARE(networkState(toxConnectionUdp, false), NetworkState::Direct);
    QCOMPARE(networkState(toxConnectionTcp, true), NetworkState::Relay);
    QCOMPARE(networkState(toxConnectionNone, true), NetworkState::Connecting);
    QCOMPARE(networkState(toxConnectionNone, false), NetworkState::Offline);
}

void TestAirModel::awayWordsChangeUnitAtTheSameSecondsAsOnIos()
{
    using Unit = AwayWords::Unit;
    const QVector<QPair<qint64, QPair<Unit, qint64>>> vectors{
        {59, {Unit::JustNow, 0}},        {60, {Unit::Minutes, 1}},
        {3599, {Unit::Minutes, 59}},     {3600, {Unit::Hours, 1}},
        {86399, {Unit::Hours, 23}},      {86400, {Unit::Days, 1}},
        {86400 * 7, {Unit::LongAgo, 0}}, {std::numeric_limits<qint64>::max(), {Unit::LongAgo, 0}},
    };
    for (const auto& vector : vectors) {
        const AwayWords words = AwayWords::of(vector.first);
        QCOMPARE(words.unit, vector.second.first);
        QCOMPARE(words.amount, vector.second.second);
    }
}

void TestAirModel::toneIsTheSameOnEveryLaunchAndEveryClient()
{
    // FNV-1a over the upper-cased UTF-8 bytes, then mod 6: the iOS and Android vectors.
    QCOMPARE(toneIndex(QString{}), 1);
    QCOMPARE(toneIndex(QStringLiteral("A")), 0);
    QCOMPARE(toneIndex(QStringLiteral("abc")), 3);
    QCOMPARE(toneIndex(QStringLiteral("ABC")), 3);
    QCOMPARE(toneIndex(QStringLiteral(
                 "56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855")),
             0);
    QCOMPARE(toneIndex(QStringLiteral("group-chat-1")), 5);
}

void TestAirModel::tonesSpreadOverAllSix()
{
    QSet<int> seen;
    for (int index = 0; index < 120; ++index) {
        seen.insert(toneIndex(QStringLiteral("key-%1").arg(index)));
    }
    QCOMPARE(seen, (QSet<int>{0, 1, 2, 3, 4, 5}));
    QCOMPARE(wrapTone(-1), 5);
    QCOMPARE(wrapTone(6), 0);
}

void TestAirModel::initialsTakeTheFirstLettersOfTwoWords()
{
    QCOMPARE(initials(QStringLiteral("anna maria lopez")), QStringLiteral("A‌M"));
    QCOMPARE(initials(QStringLiteral("  Bob ")), QStringLiteral("B"));
    QCOMPARE(initials(QString{}), QString{});
    QCOMPARE(initials(QStringLiteral("علي محمد")), QStringLiteral("ع‌م"));
    // A letter with its combining mark stays one letter.
    QCOMPARE(initials(QStringLiteral("émile")), QStringLiteral("É"));
    QCOMPARE(initials(QStringLiteral("émile")), QStringLiteral("É"));
}

void TestAirModel::everyAirStringExistsInEveryLanguageWithTheSameArguments()
{
    Q_INIT_RESOURCE(translations);
    const QVector<AirStrings::Text> texts = AirStrings::all();
    QVERIFY(texts.size() > 40);

    for (AirStrings::Text text : texts) {
        const QString english = QString::fromUtf8(AirStrings::source(text));
        QVERIFY2(!hasForbiddenGlyph(english), qPrintable(english));
    }

    for (const QString& language :
         {QStringLiteral("ru"), QStringLiteral("ar"), QStringLiteral("zh_CN")}) {
        QTranslator translator;
        QVERIFY2(translator.load(language, QStringLiteral(":translations/")), qPrintable(language));
        QCoreApplication::installTranslator(&translator);
        for (AirStrings::Text text : texts) {
            const QString english = QString::fromUtf8(AirStrings::source(text));
            // A text in plural forms says its number: %n is not an argument of its own.
            const bool numerus = AirStrings::isNumerus(text);
            const QString translated = numerus ? AirStrings::text(text, 5) : AirStrings::text(text);
            const QString where = language + QStringLiteral(": ") + english;
            QVERIFY2(!translated.trimmed().isEmpty(), qPrintable(where));
            QVERIFY2(!numerus || translated.contains(QLatin1Char('5')), qPrintable(where));
            QVERIFY2(!translated.contains(QStringLiteral("%n")), qPrintable(where));
            // Chinese writes the units of size the Latin way, as the phones do; Russian joins
            // two parts of a line with the same comma as English.
            const bool sameByConvention =
                (language == QStringLiteral("zh_CN")
                 && (text == AirStrings::Text::SizeKilobytes || text == AirStrings::Text::SizeMegabytes
                     || text == AirStrings::Text::SizeGigabytes))
                || (language == QStringLiteral("ru")
                    && (text == AirStrings::Text::GroupMemberStatus
                        || text == AirStrings::Text::FileNameAndSize));
            QVERIFY2(sameByConvention || translated != english, qPrintable(where));
            QCOMPARE(formatArguments(translated), formatArguments(english));
            QVERIFY2(!hasForbiddenGlyph(translated), qPrintable(where));
        }
        QCoreApplication::removeTranslator(&translator);
    }
}

/** In the order of iOS AirRowCell: the name, the reach, the mark and words of the last message, the
 * flags, the time; what the row does not show is not said. */
void TestAirModel::aScreenReaderHearsWhatARowShows()
{
    Entry entry;
    entry.id = QStringLiteral("f:1");
    entry.title = QStringLiteral("Марьям Хасанова");
    entry.reach = Reach::direct();
    entry.preview = QStringLiteral("  Напиши,\n когда освободишься ");
    entry.previewStyle = PreviewStyle::Message;
    entry.lastDelivery = Delivery::Delivered;
    entry.timeText = QStringLiteral("12:05");
    entry.transitCount = 1;
    entry.unreadCount = 2;
    entry.mentioned = true;
    QCOMPARE(AirStrings::spokenRow(entry),
             QStringLiteral(
                 "Марьям Хасанова, online, delivered, Напиши, когда освободишься, You were "
                 "mentioned, 1 in transit, unread: 2, 12:05"));

    // A draft has no mark in front of it.
    entry.previewStyle = PreviewStyle::Draft;
    entry.transitCount = 0;
    entry.unreadCount = 0;
    entry.mentioned = false;
    entry.timeText.clear();
    QCOMPARE(AirStrings::spokenRow(entry),
             QStringLiteral("Марьям Хасанова, online, Напиши, когда освободишься"));
}

void TestAirModel::aScreenReaderHearsACardAMessageAndAnEntry()
{
    const QString key =
        QStringLiteral("56a1ade4b65b86bcd51cc73e2cd4e542179f47959fe3e0e21b4b0acdade51855");
    // The start of the key in one run, as iOS says it.
    QCOMPARE(AirStrings::spokenRequest(
                 Request::make(key, QStringLiteral(" Мир тебе! "), QStringLiteral("12:05"), 2000)),
             QStringLiteral(
                 "Incoming request, 12:05, Мир тебе!, key 56A1ADE4B65B. You will see the "
                 "name once you accept the request"));
    // A request without words says none.
    QCOMPARE(AirStrings::spokenRequest(Request::make(key, QString(), QStringLiteral("12:05"), 2000)),
             QStringLiteral(
                 "Incoming request, 12:05, key 56A1ADE4B65B. You will see the name once you "
                 "accept the request"));
    QCOMPARE(AirStrings::spokenRequest(Request::invitation(QStringLiteral("ab12"), key,
                                                           QStringLiteral("Книжный клуб"),
                                                           QStringLiteral("Марьям Хасанова"), true,
                                                           QStringLiteral("11:52"), 1000)),
             QStringLiteral("Group invitation, 11:52, Книжный клуб, Марьям Хасанова invites you."));

    MessageHit hit;
    hit.title = QStringLiteral("Учебный кружок");
    hit.snippet = QStringLiteral("материалы к четвергу в закрепе");
    hit.timeText = QStringLiteral("вчера");
    QCOMPARE(AirStrings::spokenMessageHit(hit),
             QStringLiteral("Учебный кружок, материалы к четвергу в закрепе, вчера"));
    QCOMPARE(AirStrings::spokenSection(SectionKind::Away, 3), QStringLiteral("Offline, 3"));

    // An entry of a transcript: who, when, the words, then what is shown beside them.
    QCOMPARE(AirStrings::spokenEntry(QStringLiteral("Ибрагим Алиев"), QStringLiteral("12:07"),
                                     QStringLiteral("@Амина, материалы в закрепе"),
                                     EntryStatus::None, true),
             QStringLiteral("Ибрагим Алиев, 12:07, @Амина, материалы в закрепе, Mentions you"));
    QCOMPARE(AirStrings::spokenEntry(QStringLiteral("You"), QStringLiteral("12:08"),
                                     QStringLiteral("Спасибо"), EntryStatus::Transit, false),
             QStringLiteral("You, 12:08, Спасибо, in transit"));
    // A note in Favorites has no author.
    QCOMPARE(AirStrings::spokenEntry(QString(), QStringLiteral("12:09"),
                                     QStringLiteral("Купить хлеб"), EntryStatus::None, false),
             QStringLiteral("12:09, Купить хлеб"));
    // A reply says what it answers before its words; its notes come after them.
    QCOMPARE(AirStrings::spokenEntry(QStringLiteral("Марьям Хасанова"), QStringLiteral("12:10"),
                                     QStringLiteral("В пятницу"), EntryStatus::None, false,
                                     AirStrings::spokenQuote(QStringLiteral("Ибрагим Алиев"),
                                                             QStringLiteral("Когда встреча?")),
                                     AirStrings::spokenNotes(true, QStringLiteral("👍 2"))),
             QStringLiteral("Марьям Хасанова, 12:10, In reply to Ибрагим Алиев, Когда встреча?, "
                            "В пятницу, edited, 👍 2"));
    QCOMPARE(AirStrings::spokenQuote(QString(), QStringLiteral("old quote")),
             QStringLiteral("In reply to Unknown, old quote"));
    QCOMPARE(AirStrings::spokenNotes(false, QString()), QString());
}

QTEST_GUILESS_MAIN(TestAirModel)
#include "airmodel_test.moc"
