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

#include "src/model/air/airmention.h"

#include "src/model/air/airmodel.h"

#include <QtTest/QtTest>

#include <algorithm>

using namespace Air;

namespace {
/** "Isa Dagestani" in Cyrillic, and the handle it gives. */
const QString isaName = QStringLiteral("\u0418\u0441\u0430 \u0414\u0430\u0433\u0435\u0441\u0442\u0430\u043D\u0438");
const QString isaHandle = QStringLiteral("\u0418\u0441\u0430_\u0414\u0430\u0433\u0435\u0441\u0442\u0430\u043D\u0438");
/** "Isa" alone in Cyrillic. */
const QString isaShort = QStringLiteral("\u0418\u0441\u0430");
/** "Muhammad Ali" in Arabic, and the handle it gives. */
const QString muhammadName = QStringLiteral("\u0645\u062D\u0645\u062F \u0639\u0644\u064A");
const QString muhammadHandle = QStringLiteral("\u0645\u062D\u0645\u062F_\u0639\u0644\u064A");

QString key(char digit)
{
    return QString(64, QLatin1Char(digit));
}

QString key(const QString& head, char digit)
{
    return head + QString(64 - head.size(), QLatin1Char(digit));
}

GroupMember member(const QString& name, const QString& publicKey, bool online = true,
                   bool isSelf = false)
{
    GroupMember found;
    found.name = name;
    found.publicKey = publicKey;
    found.online = online;
    found.isSelf = isSelf;
    return found;
}

Mention mention(const QString& publicKey, const QString& handle)
{
    Mention made;
    made.key = publicKey;
    made.handle = handle;
    return made;
}

QStringList names(const QVector<MentionChoice>& choices)
{
    QStringList list;
    for (const MentionChoice& choice : choices) {
        list.append(choice.member.name);
    }
    return list;
}

/** Bob, Isa Dagestani and Muhammad Ali online, Zaid away, and you. */
QVector<GroupMember> group()
{
    return {member(QStringLiteral("You"), key('F'), true, true),
            member(QStringLiteral("Zaid"), key('D'), false),
            member(QStringLiteral("Bob"), key('A')),
            member(isaName, key('B')),
            member(muhammadName, key('C'))};
}
} // namespace

class TestAirMention : public QObject
{
    Q_OBJECT
private slots:
    void handleKeepsLettersOfAnyScript();
    void handleJoinsWordsWithOneUnderscore();
    void handleDropsTheRest();
    void handleKeepsCombiningMarks();
    void handlesOfMembersWithoutAName();
    void sharedHandlesTakeTheKey();
    void sharedHandlesDoNotDependOnOrder();
    void choicesLeaveYouOutOnlineFirst();
    void choicesFilterByHandleOrName();
    void queryOpensAtTheStartOfAWord();
    void queryClosesOutsideTheWord();
    void tokenEndsAfterItsLetters();
    void collectsHandlesOfAnyScript();
    void collectsWhatOtherClientsWrite();
    void leavesUnknownTokensPlain();
    void neverMentionsYou();
    void collectsEachPairOnce();
    void blockAsTheClientsWriteIt();
    void readsTheBlockBack();
    void readsTheBlockAfterAReply();
    void readsTheOlderBlock();
    void tellsWhomTheBlockNames();
    void spansCoverWholeHandles();
};

void TestAirMention::handleKeepsLettersOfAnyScript()
{
    QCOMPARE(mentionHandle(isaName), isaHandle);
    QCOMPARE(mentionHandle(muhammadName), muhammadHandle);
    QCOMPARE(mentionHandle(QStringLiteral("Bob")), QStringLiteral("Bob"));
    QCOMPARE(mentionHandle(QStringLiteral("Agent 007")), QStringLiteral("Agent_007"));
    // A Chinese name keeps its characters.
    QCOMPARE(mentionHandle(QStringLiteral("\u674E\u660E")), QStringLiteral("\u674E\u660E"));
}

void TestAirMention::handleJoinsWordsWithOneUnderscore()
{
    QCOMPARE(mentionHandle(QStringLiteral("Mary-Jane Watson")), QStringLiteral("Mary_Jane_Watson"));
    QCOMPARE(mentionHandle(QStringLiteral("a . - b")), QStringLiteral("a_b"));
    QCOMPARE(mentionHandle(QStringLiteral("  Lead")), QStringLiteral("Lead"));
    QCOMPARE(mentionHandle(QStringLiteral("Trail. ")), QStringLiteral("Trail"));
    QCOMPARE(mentionHandle(QStringLiteral("snake_case")), QStringLiteral("snakecase"));
}

void TestAirMention::handleDropsTheRest()
{
    QCOMPARE(mentionHandle(QStringLiteral("Bob (Work)")), QStringLiteral("Bob"));
    QCOMPARE(mentionHandle(QStringLiteral("\U0001F319 Moon!")), QStringLiteral("Moon"));
    QCOMPARE(mentionHandle(QStringLiteral("!!!")), QString());
    QCOMPARE(mentionHandle(QString()), QString());
    // " (" at the very start is not a suffix of the name.
    QCOMPARE(mentionHandle(QStringLiteral(" (x)")), QStringLiteral("x"));
}

void TestAirMention::handleKeepsCombiningMarks()
{
    // Amit in Devanagari: its vowel sign is a spacing mark.
    QCOMPARE(mentionHandle(QStringLiteral("\u0905\u092E\u093F\u0924")),
             QStringLiteral("\u0905\u092E\u093F\u0924"));
    // Muhammad in Arabic with its harakat.
    const QString vowelled = QStringLiteral("\u0645\u064F\u062D\u064E\u0645\u0651\u064E\u062F");
    QCOMPARE(mentionHandle(vowelled), vowelled);
    // Jose with a combining acute accent.
    QCOMPARE(mentionHandle(QStringLiteral("Jose\u0301 M.")), QStringLiteral("Jose\u0301_M"));
    // A mark with no letter before it, or after "_", has nothing to belong to.
    QCOMPARE(mentionHandle(QStringLiteral("\u0301Ann \u0301Lee")), QStringLiteral("Ann_Lee"));
}

void TestAirMention::handlesOfMembersWithoutAName()
{
    const QHash<QString, QString> handles =
        mentionHandles({member(QStringLiteral("!!!"), key(QStringLiteral("0A1B2C3D4E"), '9')),
                        member(QString(), QStringLiteral("abc") + key('1').mid(3)),
                        member(QStringLiteral("Nobody"), QString())});
    QCOMPARE(handles.size(), 2);
    QCOMPARE(handles.value(key(QStringLiteral("0A1B2C3D4E"), '9')), QStringLiteral("u0A1B2C3D"));
    QCOMPARE(handles.value(QStringLiteral("ABC") + key('1').mid(3)), QStringLiteral("uABC11111"));
}

void TestAirMention::sharedHandlesTakeTheKey()
{
    const QString first = key(QStringLiteral("ABCD"), '1');
    const QString second = key(QStringLiteral("1234"), '2');
    const QString third = key(QStringLiteral("ABCD"), '3');
    const QHash<QString, QString> handles =
        mentionHandles({member(QStringLiteral("Isa"), first), member(QStringLiteral("isa."), second),
                        member(QStringLiteral("ISA"), third), member(QStringLiteral("Bob"), key('B'))});
    QCOMPARE(handles.value(second), QStringLiteral("isa_1234"));
    // Two of them share the first 4 digits too, so they take 8.
    QCOMPARE(handles.value(first), QStringLiteral("Isa_ABCD1111"));
    QCOMPARE(handles.value(third), QStringLiteral("ISA_ABCD3333"));
    QCOMPARE(handles.value(key('B')), QStringLiteral("Bob"));

    // You count: a member who shares your handle is told apart from you.
    const QHash<QString, QString> withYou = mentionHandles(
        {member(QStringLiteral("Bob"), key('F'), true, true), member(QStringLiteral("Bob"), key('A'))});
    QCOMPARE(withYou.value(key('A')), QStringLiteral("Bob_AAAA"));
    QCOMPARE(withYou.value(key('F')), QStringLiteral("Bob_FFFF"));
}

void TestAirMention::sharedHandlesDoNotDependOnOrder()
{
    QVector<GroupMember> members = {member(QStringLiteral("Isa"), key(QStringLiteral("ABCD"), '1')),
                                    member(QStringLiteral("Isa"), key(QStringLiteral("1234"), '2')),
                                    member(isaName, key('B')), member(QStringLiteral("Bob"), key('A'))};
    const QHash<QString, QString> forward = mentionHandles(members);
    std::reverse(members.begin(), members.end());
    QCOMPARE(mentionHandles(members), forward);
}

void TestAirMention::choicesLeaveYouOutOnlineFirst()
{
    // Names of one script, so that the order does not depend on how the system collates scripts.
    const QVector<GroupMember> members = {member(QStringLiteral("You"), key('F'), true, true),
                                          member(QStringLiteral("Zed"), key('E'), false),
                                          member(QStringLiteral("carl"), key('C')),
                                          member(QStringLiteral("Adam"), key('D'), false),
                                          member(QStringLiteral("Bob"), key('A')),
                                          member(QStringLiteral("NoKey"), QString())};
    const QVector<MentionChoice> choices = mentionChoices(members, QString());
    QCOMPARE(names(choices), QStringList({QStringLiteral("Bob"), QStringLiteral("carl"),
                                          QStringLiteral("Adam"), QStringLiteral("Zed")}));
    QCOMPARE(choices.at(1).handle, QStringLiteral("carl"));
    QCOMPARE(mentionChoices(group(), isaShort).value(0).handle, isaHandle);
}

void TestAirMention::choicesFilterByHandleOrName()
{
    // The start of the handle, case ignored.
    QCOMPARE(names(mentionChoices(group(), isaShort.toUpper() + QStringLiteral("_\u0414"))),
             QStringList({isaName}));
    // Anywhere in the name, in any script.
    QCOMPARE(names(mentionChoices(group(), QStringLiteral("\u0639\u0644\u064A"))),
             QStringList({muhammadName}));
    QCOMPARE(names(mentionChoices(group(), QStringLiteral("ai"))), QStringList({QStringLiteral("Zaid")}));
    // Marks ignored.
    QVector<GroupMember> members = group();
    members.append(member(QStringLiteral("Jos\u00E9"), key('E')));
    QCOMPARE(names(mentionChoices(members, QStringLiteral("JOSE"))),
             QStringList({QStringLiteral("Jos\u00E9")}));
    // You never come up, not even by your own name.
    QVERIFY(mentionChoices(group(), QStringLiteral("You")).isEmpty());
    QVERIFY(mentionChoices(group(), QStringLiteral("xyz")).isEmpty());
}

void TestAirMention::queryOpensAtTheStartOfAWord()
{
    int start = -1;
    QString query;
    QVERIFY(mentionQueryAt(QStringLiteral("@"), 1, &start, &query));
    QCOMPARE(start, 0);
    QCOMPARE(query, QString());

    const QString text = QStringLiteral("hi @") + isaShort;
    QVERIFY(mentionQueryAt(text, text.size(), &start, &query));
    QCOMPARE(start, 3);
    QCOMPARE(query, isaShort);

    QVERIFY(mentionQueryAt(QStringLiteral("line\n@Bo"), 8, &start, &query));
    QCOMPARE(start, 5);
    QCOMPARE(query, QStringLiteral("Bo"));

    // The caret inside the word filters by what stands before it.
    QVERIFY(mentionQueryAt(QStringLiteral("@Bob"), 2, &start, &query));
    QCOMPARE(query, QStringLiteral("B"));
}

void TestAirMention::queryClosesOutsideTheWord()
{
    int start = -1;
    QString query;
    QVERIFY(!mentionQueryAt(QStringLiteral("mail@host"), 9, &start, &query));
    QVERIFY(!mentionQueryAt(QStringLiteral("@Bob "), 5, &start, &query));
    QVERIFY(!mentionQueryAt(QStringLiteral("@Bob-"), 5, &start, &query));
    QVERIFY(!mentionQueryAt(QStringLiteral("Bob"), 3, &start, &query));
    QVERIFY(!mentionQueryAt(QStringLiteral("@Bob"), 0, &start, &query));
    QVERIFY(!mentionQueryAt(QStringLiteral("@Bob"), 9, &start, &query));
}

void TestAirMention::tokenEndsAfterItsLetters()
{
    QCOMPARE(mentionTokenEnd(QStringLiteral("@Bob, hi"), 0), 4);
    QCOMPARE(mentionTokenEnd(QStringLiteral("x @") + isaHandle + QStringLiteral(" y"), 2),
             3 + isaHandle.size());
    QCOMPARE(mentionTokenEnd(QStringLiteral("@"), 0), 1);
}

void TestAirMention::collectsHandlesOfAnyScript()
{
    const QString words = QStringLiteral("@") + isaHandle + QStringLiteral(" and @") + muhammadHandle
                          + QStringLiteral(", look at this @Bob!");
    QCOMPARE(collectMentions(words, group()),
             QVector<Mention>({mention(key('B'), isaHandle), mention(key('C'), muhammadHandle),
                               mention(key('A'), QStringLiteral("Bob"))}));
    // The token goes as written, in any case.
    QCOMPARE(collectMentions(QStringLiteral("@bOB"), group()),
             QVector<Mention>({mention(key('A'), QStringLiteral("bOB"))}));
}

void TestAirMention::collectsWhatOtherClientsWrite()
{
    // Two members named Isa: each has a handle with 4 hex digits of the key here.
    const QVector<GroupMember> members = {member(QStringLiteral("Isa"), key('A')),
                                          member(QStringLiteral("Isa"), key('B')),
                                          member(QStringLiteral("Bob"), key('C'))};
    QCOMPARE(collectMentions(QStringLiteral("@Isa_BBBB"), members),
             QVector<Mention>({mention(key('B'), QStringLiteral("Isa_BBBB"))}));
    // "@Isa" names neither of the two: it stays plain.
    QVERIFY(collectMentions(QStringLiteral("@Isa"), members).isEmpty());
    // Another client kept the plain handle for one of them and the 8 digits are as good as 4.
    QCOMPARE(collectMentions(QStringLiteral("@Isa_AAAAAAAA"), members),
             QVector<Mention>({mention(key('A'), QStringLiteral("Isa_AAAAAAAA"))}));
    QCOMPARE(collectMentions(QStringLiteral("@Bob_CCCC"), members),
             QVector<Mention>({mention(key('C'), QStringLiteral("Bob_CCCC"))}));

    // When only you share a handle with a member, the plain handle still names them.
    const QVector<GroupMember> withYou = {member(QStringLiteral("Isa"), key('F'), true, true),
                                          member(QStringLiteral("Isa"), key('A'))};
    QCOMPARE(collectMentions(QStringLiteral("@Isa"), withYou),
             QVector<Mention>({mention(key('A'), QStringLiteral("Isa"))}));
    // A member without a name is named by "u" and 8 digits of the key.
    QCOMPARE(collectMentions(QStringLiteral("@u12345678"), {member(QString(), key(QStringLiteral("12345678"), '0'))}),
             QVector<Mention>({mention(key(QStringLiteral("12345678"), '0'), QStringLiteral("u12345678"))}));
}

void TestAirMention::leavesUnknownTokensPlain()
{
    QVERIFY(collectMentions(QStringLiteral("@Nobody and @"), group()).isEmpty());
    QVERIFY(collectMentions(QStringLiteral("no at sign Bob"), group()).isEmpty());
    // "@Bobby" is not "@Bob".
    QVERIFY(collectMentions(QStringLiteral("@Bobby"), group()).isEmpty());
    QVERIFY(collectMentions(QStringLiteral("@Bob"), {}).isEmpty());
}

void TestAirMention::neverMentionsYou()
{
    QVERIFY(collectMentions(QStringLiteral("@You"), group()).isEmpty());
}

void TestAirMention::collectsEachPairOnce()
{
    QCOMPARE(collectMentions(QStringLiteral("@Bob @Bob @bob @Bob"), group()),
             QVector<Mention>({mention(key('A'), QStringLiteral("Bob")),
                               mention(key('A'), QStringLiteral("bob"))}));
}

void TestAirMention::blockAsTheClientsWriteIt()
{
    QCOMPARE(mentionBlock({mention(key('B'), isaHandle), mention(key('A'), QStringLiteral("Bob"))}),
             QStringLiteral("[KQ|m|") + key('B') + QStringLiteral(":") + isaHandle + QStringLiteral(";")
                 + key('A') + QStringLiteral(":Bob][KQ/m]\n"));
    QCOMPARE(mentionBlock({}), QString());
    // A field cannot end its pair or the block.
    QCOMPARE(mentionBlock({mention(key('A'), QStringLiteral("B|o;b:\n"))}),
             QStringLiteral("[KQ|m|") + key('A') + QStringLiteral(":Bob][KQ/m]\n"));
    QCOMPARE(mentionBlock({mention(key('A'), QStringLiteral("|;:"))}), QString());
}

void TestAirMention::readsTheBlockBack()
{
    const QVector<Mention> mentions = {mention(key('B'), isaHandle),
                                       mention(key('A'), QStringLiteral("Bob"))};
    const QString raw = mentionBlock(mentions) + QStringLiteral("@") + isaHandle
                        + QStringLiteral(" @Bob hello");
    QCOMPARE(readMentions(raw), mentions);
    // The words the transcript shows have no block.
    QCOMPARE(plainMessageBody(raw), QStringLiteral("@") + isaHandle + QStringLiteral(" @Bob hello"));
    // Keys in lower case come in upper case; a pair without a key or a handle is left out.
    QCOMPARE(readMentions(QStringLiteral("[KQ|m|") + key('a') + QStringLiteral(":Bob;:x;")
                          + key('C') + QStringLiteral(":;junk][KQ/m]\n@Bob")),
             QVector<Mention>({mention(key('A'), QStringLiteral("Bob"))}));
    QVERIFY(readMentions(QStringLiteral("@Bob without a block")).isEmpty());
    QVERIFY(readMentions(QStringLiteral("[KQ|m|") + key('A') + QStringLiteral(":Bob\nno end")).isEmpty());
}

void TestAirMention::readsTheBlockAfterAReply()
{
    ReplyMeta meta;
    meta.localId = 7;
    meta.authorKey = key('C');
    meta.timeMs = 1700000000000;
    meta.authorName = QStringLiteral("Muhammad");
    meta.words = QStringLiteral("@Bob said so");
    const QVector<Mention> mentions = {mention(key('A'), QStringLiteral("Bob"))};
    const QString raw = replyHead(meta) + mentionBlock(mentions) + QStringLiteral("@Bob yes");
    QCOMPARE(readMentions(raw), mentions);
    QVERIFY(mentionsKey(raw, key('A')));
    // A reply whose quote holds a block mentions nobody.
    meta.words = mentionBlock(mentions) + QStringLiteral("@Bob");
    QVERIFY(readMentions(replyHead(meta) + QStringLiteral("plain")).isEmpty());
}

void TestAirMention::readsTheOlderBlock()
{
    QCOMPARE(readMentions(QStringLiteral("[KQ|m]") + key('A') + QStringLiteral(":Bob[|KQ|m]\n@Bob")),
             QVector<Mention>({mention(key('A'), QStringLiteral("Bob"))}));
}

void TestAirMention::tellsWhomTheBlockNames()
{
    const QString raw = mentionBlock({mention(key('A'), QStringLiteral("Bob"))}) + QStringLiteral("@Bob");
    QVERIFY(mentionsKey(raw, key('A')));
    QVERIFY(mentionsKey(raw, key('a')));
    QVERIFY(!mentionsKey(raw, key('B')));
    QVERIFY(!mentionsKey(raw, QString()));
    QVERIFY(!mentionsKey(QStringLiteral("@Bob ") + key('A'), key('A')));
}

void TestAirMention::spansCoverWholeHandles()
{
    const QVector<Mention> mentions = {mention(key('A'), QStringLiteral("Bob")),
                                       mention(key('B'), isaHandle)};
    const QString words = QStringLiteral("@bob, @Bobby @") + isaHandle + QStringLiteral(" mail@Bob");
    const QVector<MentionSpan> spans = mentionSpans(words, mentions);
    QCOMPARE(spans.size(), 3);
    QCOMPARE(spans.at(0).start, 0);
    QCOMPARE(spans.at(0).length, 4);
    QCOMPARE(spans.at(0).key, key('A'));
    QCOMPARE(spans.at(1).start, 13);
    QCOMPARE(spans.at(1).length, 1 + isaHandle.size());
    QCOMPARE(spans.at(1).key, key('B'));
    QCOMPARE(spans.at(2).start, words.size() - 4);
    QVERIFY(mentionSpans(words, {}).isEmpty());
}

QTEST_GUILESS_MAIN(TestAirMention)
#include "airmention_test.moc"
