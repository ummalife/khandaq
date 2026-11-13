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

#include "src/model/air/airgroup.h"
#include "src/model/air/airlocalstore.h"
#include "src/model/air/airmodel.h"
#include "src/widget/air/airstrings.h"

#include <QTranslator>
#include <QtTest/QtTest>

#include <memory>

/**
 * The group screens as plain values (E13 to E15), with the vectors of
 * khandaq-ios/AntidoteTests/AirGroupModelTests.swift where the desktop has the same data: the order
 * of the members, who gets a message now and what the header and the hint say about it, who may
 * write, what the founder and a moderator may change, the invitation list, the name of a new
 * group, the length counter, the group key and the private messages kept on this device.
 */
class TestAirGroup : public QObject
{
    Q_OBJECT

private slots:
    void initTestCase();
    void init();
    void cleanup();

    void membersGoByRoleThenOnlineThenName();
    void searchByNameIgnoresCaseAndMarks();
    void memberRowSaysTheRoleAndThePresence();
    void toneFollowsTheGroupKeyWhenKnown();
    void headerAndHintCountTheMembersAndTheOnesOnline();
    void everyoneOnlineNobodyOnlineAloneAndNotConnected();
    void countsNeverContradictEachOther();
    void observersAndTheVoiceStateDecideWhoWrites();
    void founderAndModeratorsChangeRolesWithinTheirRights();
    void everyChangeIsAskedInOneSentenceAndToldOnceDone();
    void invitationListPutsWhoCanBeInvitedFirst();
    void sentInvitationsAreCounted();
    void privacyIsSaidWithItsConsequence();
    void nameIsOneLineAndFitsInFortyEightBytes();
    void counterShowsTheLastTenthOfAGroupMessage();
    void groupKeyIsCleanedLikeAnIdOfItsOwnLength();
    void privateMessagesStayOnThisDeviceUntilTheGroupIsLeft();

private:
    void use(const QString& language);
    static Air::GroupMember member(quint32 peerId, const QString& name, Air::GroupRole role,
                                   bool online = false, bool isSelf = false,
                                   const QString& key = QString());
    static Air::InviteCandidate candidate(int friendId, const QString& name, bool online);
    static QStringList names(const QVector<Air::GroupMember>& members);

    std::unique_ptr<QTranslator> translator;
};

namespace {
const QString groupKey =
    QStringLiteral("8F2A41C07D3B9E6152A4F0C8B7D3E9A1C2F4B6D8E0A1C3E5F7B9D1F3A5C7E9B1");
const QString peerKey =
    QStringLiteral("1B3D5F7A9C1E3B5D7F9A1C3E5B7D9F1A3C5E7B9D1F3A5C7E9B1D3F5A7C9E1B3D");
const QString otherPeerKey =
    QStringLiteral("2C4E6A8B0D2F4A6C8E0B2D4F6A8C0E2B4D6F8A0C2E4B6D8F0A2C4E6B8D0F2A4C");
} // namespace

void TestAirGroup::initTestCase()
{
    Q_INIT_RESOURCE(translations);
}

void TestAirGroup::init()
{
    use(QStringLiteral("en"));
}

void TestAirGroup::cleanup()
{
    if (translator) {
        QCoreApplication::removeTranslator(translator.get());
        translator.reset();
    }
}

void TestAirGroup::use(const QString& language)
{
    cleanup();
    translator.reset(new QTranslator);
    QVERIFY2(translator->load(language, QStringLiteral(":translations/")), qPrintable(language));
    QCoreApplication::installTranslator(translator.get());
}

Air::GroupMember TestAirGroup::member(quint32 peerId, const QString& name, Air::GroupRole role,
                                      bool online, bool isSelf, const QString& key)
{
    Air::GroupMember result;
    result.peerId = peerId;
    result.name = name;
    result.role = role;
    result.online = online;
    result.isSelf = isSelf;
    result.publicKey = key;
    return result;
}

Air::InviteCandidate TestAirGroup::candidate(int friendId, const QString& name, bool online)
{
    Air::InviteCandidate result;
    result.friendId = friendId;
    result.name = name;
    result.online = online;
    result.publicKey = QString::number(friendId) + name;
    return result;
}

QStringList TestAirGroup::names(const QVector<Air::GroupMember>& members)
{
    QStringList result;
    for (const Air::GroupMember& one : members) {
        result.append(one.name);
    }
    return result;
}

void TestAirGroup::membersGoByRoleThenOnlineThenName()
{
    using Air::GroupRole;
    const QVector<Air::GroupMember> members = {
        member(1, QStringLiteral("Yusuf"), GroupRole::Member),
        member(2, QStringLiteral("Ibrahim"), GroupRole::Observer, true),
        member(3, QStringLiteral("Bilal"), GroupRole::Member, true),
        member(4, QStringLiteral("Amina"), GroupRole::Moderator),
        member(5, QStringLiteral("Samir"), GroupRole::Founder, true, true),
        member(6, QStringLiteral("Zaynab"), GroupRole::Member),
        member(7, QStringLiteral("Hamza"), GroupRole::Member),
        member(8, QStringLiteral("Aisha"), GroupRole::Member, true),
    };
    // The desktop learns nothing of when a member was last seen: the offline go by name.
    QCOMPARE(names(Air::sortedRoster(members)),
             QStringList({"Samir", "Amina", "Aisha", "Bilal", "Hamza", "Yusuf", "Zaynab", "Ibrahim"}));
    // Everyone online but you, in the order of the dossier.
    QCOMPARE(names(Air::reachableNow(members)), QStringList({"Aisha", "Bilal", "Ibrahim"}));
    // You are never offered to yourself.
    QCOMPARE(names(Air::askable(members, false)), QStringList({"Amina"}));
    QVERIFY(Air::askable(members, true).isEmpty());

    QVector<Air::GroupMember> founderElsewhere = members;
    founderElsewhere.append(member(9, QStringLiteral("Imam Khalid"), GroupRole::Founder));
    // An observer can ask the founder too; the one online now comes first.
    QCOMPARE(names(Air::askable(founderElsewhere, false)), QStringList({"Imam Khalid", "Amina"}));
    QCOMPARE(names(Air::askable(founderElsewhere, true)), QStringList({"Imam Khalid"}));
    founderElsewhere[3].online = true;
    QCOMPARE(names(Air::askable(founderElsewhere, false)), QStringList({"Amina", "Imam Khalid"}));

    // The same name twice keeps both, in the order of their peer numbers.
    const QVector<Air::GroupMember> twins = {member(12, QStringLiteral("Omar"), GroupRole::Member),
                                             member(11, QStringLiteral("Omar"), GroupRole::Member)};
    const QVector<Air::GroupMember> sortedTwins = Air::sortedRoster(twins);
    QCOMPARE(sortedTwins.at(0).peerId, 11u);
    QCOMPARE(sortedTwins.at(1).peerId, 12u);
}

void TestAirGroup::searchByNameIgnoresCaseAndMarks()
{
    using Air::GroupRole;
    const QVector<Air::GroupMember> members = {member(1, QStringLiteral("Zaynab Alieva"),
                                                      GroupRole::Member),
                                               member(2, QStringLiteral("Ibrahim Gasanov"),
                                                      GroupRole::Member),
                                               member(3, QStringLiteral("Zoë"), GroupRole::Member)};
    QCOMPARE(Air::filteredRoster(members, QStringLiteral("  ")).size(), 3);
    QCOMPARE(names(Air::filteredRoster(members, QStringLiteral("alieva"))),
             QStringList({"Zaynab Alieva"}));
    QCOMPARE(names(Air::filteredRoster(members, QStringLiteral("zoe"))), QStringList({"Zoë"}));
    QVERIFY(Air::filteredRoster(members, QStringLiteral("Omar")).isEmpty());
    QCOMPARE(Air::groupSearchFrom, 20);
}

void TestAirGroup::memberRowSaysTheRoleAndThePresence()
{
    using Air::GroupRole;
    QCOMPARE(AirStrings::groupMemberStatusText(
                 member(1, QStringLiteral("Amina"), GroupRole::Moderator, true)),
             QStringLiteral("moderator, online"));
    QCOMPARE(AirStrings::groupMemberStatusText(member(2, QStringLiteral("Hamza"), GroupRole::Member)),
             QStringLiteral("member, offline"));

    use(QStringLiteral("ru"));
    QCOMPARE(AirStrings::groupMemberStatusText(
                 member(1, QStringLiteral("Амина"), GroupRole::Founder, true)),
             QStringLiteral("основатель, в сети"));
    QCOMPARE(AirStrings::groupMemberStatusText(member(2, QStringLiteral("Хамза"), GroupRole::Member)),
             QStringLiteral("участник, не в сети"));
}

void TestAirGroup::toneFollowsTheGroupKeyWhenKnown()
{
    QCOMPARE(member(1, QStringLiteral("Amina"), Air::GroupRole::Member, false, false,
                    QStringLiteral("ABC"))
                 .toneKey(),
             QStringLiteral("ABC"));
    QCOMPARE(member(1, QStringLiteral("Amina"), Air::GroupRole::Member).toneKey(),
             QStringLiteral("Amina"));
}

void TestAirGroup::headerAndHintCountTheMembersAndTheOnesOnline()
{
    // The plan's group: 9 members with you, 4 others online. A message is for the 8 others.
    const Air::GroupReach reach = Air::GroupReach::make(9, 9, 4, true);
    QCOMPARE(reach.total, 9);
    QCOMPARE(reach.recipients(), 8);
    QCOMPARE(AirStrings::groupHeaderText(reach), QStringLiteral("9 members, 4 online now"));
    const Air::GroupHint hint = Air::GroupHint::make(reach);
    QCOMPARE(hint.dot, Air::ComposeHint::Dot::Live);
    QCOMPARE(AirStrings::groupHintText(hint, reach),
             QStringLiteral("4 of 8 get it now, the rest when they come online"));
    const Air::GroupReach one = Air::GroupReach::make(9, 9, 1, true);
    QCOMPARE(AirStrings::groupHintText(Air::GroupHint::make(one), one),
             QStringLiteral("1 of 8 gets it now, the rest when they come online"));

    use(QStringLiteral("ru"));
    QCOMPARE(AirStrings::groupHeaderText(reach), QStringLiteral("9 участников, 4 в сети сейчас"));
    QCOMPARE(AirStrings::groupHintText(hint, reach),
             QStringLiteral("Сразу получат 4 из 8, остальные, когда появятся в сети"));
    const Air::GroupReach large = Air::GroupReach::make(31, 31, 21, true);
    QCOMPARE(AirStrings::groupHeaderText(large), QStringLiteral("31 участник, 21 в сети сейчас"));
    QCOMPARE(AirStrings::groupHintText(Air::GroupHint::make(large), large),
             QStringLiteral("Сразу получит 21 из 30, остальные, когда появятся в сети"));
    QCOMPARE(AirStrings::groupHeaderText(Air::GroupReach::make(3, 3, 1, true)),
             QStringLiteral("3 участника, 1 в сети сейчас"));
}

void TestAirGroup::everyoneOnlineNobodyOnlineAloneAndNotConnected()
{
    using Kind = Air::GroupHint::Kind;
    const Air::GroupReach all = Air::GroupReach::make(5, 5, 4, true);
    QCOMPARE(Air::GroupHint::make(all).kind, Kind::All);
    QCOMPARE(AirStrings::groupHintText(Air::GroupHint::make(all), all),
             QStringLiteral("All 4 get it now: everyone is online"));
    const Air::GroupReach pair = Air::GroupReach::make(2, 2, 1, true);
    QCOMPARE(Air::GroupHint::make(pair).kind, Kind::OneOther);
    QCOMPARE(AirStrings::groupHintText(Air::GroupHint::make(pair), pair),
             QStringLiteral("The other member is online and gets it now"));

    // NGC keeps no history and the desktop neither keeps group messages for later nor serves them
    // to members who come online (the phones do): with nobody to get it, Send waits and says so,
    // and no amber dot claims something is on its way.
    const Air::GroupReach nobody = Air::GroupReach::make(9, 9, 0, true);
    QCOMPARE(AirStrings::groupHeaderText(nobody), QStringLiteral("9 members, nobody online now"));
    QCOMPARE(Air::GroupHint::make(nobody).dot, Air::ComposeHint::Dot::None);
    QVERIFY(Air::GroupHint::make(nobody).sendWaits());
    QCOMPARE(AirStrings::groupHintText(Air::GroupHint::make(nobody), nobody),
             QStringLiteral("Nobody else is online: you can send once someone comes online"));

    const Air::GroupReach alone = Air::GroupReach::make(1, 1, 0, true);
    QCOMPARE(AirStrings::groupHeaderText(alone), QStringLiteral("1 member, nobody online now"));
    QCOMPARE(Air::GroupHint::make(alone).dot, Air::ComposeHint::Dot::None);
    QVERIFY(Air::GroupHint::make(alone).sendWaits());
    QCOMPARE(AirStrings::groupHintText(Air::GroupHint::make(alone), alone),
             QStringLiteral(
                 "Only you are in the group so far: invite people from the group dossier"));

    // Nobody gets anything while the group is not connected.
    const Air::GroupReach offline = Air::GroupReach::make(9, 9, 4, false);
    QCOMPARE(offline.online, 0);
    QCOMPARE(AirStrings::groupHeaderText(offline),
             QStringLiteral("9 members, not connected to the group"));
    QCOMPARE(Air::GroupHint::make(offline).kind, Kind::Offline);
    QCOMPARE(Air::GroupHint::make(offline).dot, Air::ComposeHint::Dot::None);
    QVERIFY(Air::GroupHint::make(offline).sendWaits());
    QCOMPARE(AirStrings::groupHintText(Air::GroupHint::make(offline), offline),
             QStringLiteral("No connection to the group: you can send once it connects"));
    // Without a connection to the group, the line says when our own node is the reason; Send
    // waits all the same.
    const Air::GroupHint selfConnecting = Air::GroupHint::make(offline, Air::NetworkState::Connecting);
    QCOMPARE(selfConnecting.kind, Kind::SelfConnecting);
    QCOMPARE(selfConnecting.dot, Air::ComposeHint::Dot::None);
    QVERIFY(selfConnecting.sendWaits());
    QCOMPARE(
        AirStrings::groupHintText(selfConnecting, offline),
        QStringLiteral(
            "No connection to the group: the app is connecting. You can send once it connects"));
    const Air::GroupHint selfOffline = Air::GroupHint::make(offline, Air::NetworkState::Offline);
    QCOMPARE(selfOffline.kind, Kind::SelfOffline);
    QVERIFY(selfOffline.sendWaits());
    QCOMPARE(AirStrings::groupHintText(selfOffline, offline),
             QStringLiteral("No connection to the group: this computer has no network. You can "
                            "send once it is back"));
    QCOMPARE(Air::GroupHint::make(offline, Air::NetworkState::Relay).kind, Kind::Offline);
    // A group still connected (members on the same local network) keeps its words: a message
    // sent then reaches them.
    QCOMPARE(Air::GroupHint::make(all, Air::NetworkState::Offline).kind, Kind::All);
    QVERIFY(!Air::GroupHint::make(all, Air::NetworkState::Connecting).sendWaits());
    // Someone gets it now: Send goes.
    QVERIFY(!Air::GroupHint::make(all).sendWaits());
    QVERIFY(!Air::GroupHint::make(pair).sendWaits());
    QVERIFY(!Air::GroupHint::make(Air::GroupReach::make(9, 9, 4, true)).sendWaits());

    use(QStringLiteral("ru"));
    // 21 takes the form of one in Russian; it is still not "the other member".
    const Air::GroupReach twentyOne = Air::GroupReach::make(22, 22, 21, true);
    QCOMPARE(AirStrings::groupHintText(Air::GroupHint::make(twentyOne), twentyOne),
             QStringLiteral("Сразу получат все 21: все в сети"));
    QCOMPARE(AirStrings::groupHintText(Air::GroupHint::make(pair), pair),
             QStringLiteral("Второй участник в сети и получит сразу"));
    QCOMPARE(AirStrings::groupHintText(Air::GroupHint::make(nobody), nobody),
             QStringLiteral(
                 "Больше никого нет в сети: отправить получится, когда кто-нибудь появится"));
    QCOMPARE(AirStrings::groupHintText(Air::GroupHint::make(offline), offline),
             QStringLiteral("Нет связи с группой: отправить получится, когда связь появится"));
    QCOMPARE(AirStrings::groupHintText(selfConnecting, offline),
             QStringLiteral("Нет связи с группой: приложение подключается. Отправить получится, "
                            "когда связь появится"));
    QCOMPARE(
        AirStrings::groupHintText(selfOffline, offline),
        QStringLiteral(
            "Нет связи с группой: у компьютера нет сети. Отправить получится, когда она появится"));
}

void TestAirGroup::countsNeverContradictEachOther()
{
    // The roster arrived before the peer count did: the known members count.
    QCOMPARE(Air::GroupReach::make(0, 4, 2, true).total, 4);
    // Never fewer than you.
    QCOMPARE(Air::GroupReach::make(0, 0, 0, true).total, 1);
    // More online than there are others is capped, so "5 of 3" cannot happen.
    const Air::GroupReach capped = Air::GroupReach::make(4, 4, 9, true);
    QCOMPARE(capped.online, 3);
    QCOMPARE(AirStrings::groupHintText(Air::GroupHint::make(capped), capped),
             QStringLiteral("All 3 get it now: everyone is online"));
    QCOMPARE(Air::GroupReach::make(4, 4, -2, true).online, 0);
}

void TestAirGroup::observersAndTheVoiceStateDecideWhoWrites()
{
    using Air::ComposeRight;
    using Air::GroupRole;
    using Air::GroupVoice;
    for (const GroupVoice voice : {GroupVoice::All, GroupVoice::Moderators, GroupVoice::Founder}) {
        QCOMPARE(Air::composeRight(GroupRole::Observer, voice), ComposeRight::Observer);
    }
    QCOMPARE(Air::composeRight(GroupRole::Member, GroupVoice::All), ComposeRight::Write);
    QCOMPARE(Air::composeRight(GroupRole::Member, GroupVoice::Moderators),
             ComposeRight::ModeratorsOnly);
    QCOMPARE(Air::composeRight(GroupRole::Moderator, GroupVoice::Moderators), ComposeRight::Write);
    QCOMPARE(Air::composeRight(GroupRole::Founder, GroupVoice::Moderators), ComposeRight::Write);
    QCOMPARE(Air::composeRight(GroupRole::Moderator, GroupVoice::Founder), ComposeRight::FounderOnly);
    QCOMPARE(Air::composeRight(GroupRole::Founder, GroupVoice::Founder), ComposeRight::Write);

    QVERIFY(AirStrings::groupReadOnlyText(ComposeRight::Write).isEmpty());
    QVERIFY(AirStrings::groupAskText(ComposeRight::Write).isEmpty());
    QCOMPARE(AirStrings::groupReadOnlyText(ComposeRight::Observer),
             QStringLiteral(
                 "Read only. You are an observer: a moderator can give you the right to write."));
    QCOMPARE(AirStrings::groupAskText(ComposeRight::Observer),
             QStringLiteral("Write to a moderator"));
    QCOMPARE(AirStrings::groupAskText(ComposeRight::ModeratorsOnly),
             QStringLiteral("Write to a moderator"));
    QCOMPARE(AirStrings::groupAskText(ComposeRight::FounderOnly),
             QStringLiteral("Write to the founder"));

    // toxcore refuses an observer's private messages and drops them on the other side, so the
    // button waits for an observer even with moderators online; otherwise only for nobody to ask.
    const QString observerWaits = QStringLiteral(
        "An observer cannot write here, not even privately: ask a moderator outside the group.");
    QCOMPARE(AirStrings::groupAskUnavailableText(ComposeRight::Observer, false), observerWaits);
    QCOMPARE(AirStrings::groupAskUnavailableText(ComposeRight::Observer, true), observerWaits);
    QVERIFY(AirStrings::groupAskUnavailableText(ComposeRight::ModeratorsOnly, false).isEmpty());
    QCOMPARE(AirStrings::groupAskUnavailableText(ComposeRight::ModeratorsOnly, true),
             QStringLiteral("No moderator of this group is known yet"));
    QCOMPARE(AirStrings::groupAskUnavailableText(ComposeRight::FounderOnly, true),
             QStringLiteral("No moderator of this group is known yet"));
    QVERIFY(AirStrings::groupAskUnavailableText(ComposeRight::Write, true).isEmpty());

    use(QStringLiteral("ru"));
    QCOMPARE(AirStrings::groupReadOnlyText(ComposeRight::Observer),
             QStringLiteral("Только чтение. Вы наблюдатель: право писать может дать модератор."));
    QCOMPARE(AirStrings::groupAskText(ComposeRight::Observer),
             QStringLiteral("Написать модератору"));
    QCOMPARE(
        AirStrings::groupAskUnavailableText(ComposeRight::Observer, false),
        QStringLiteral(
            "Наблюдатель не может писать здесь, даже лично: попросите модератора вне группы."));
}

void TestAirGroup::founderAndModeratorsChangeRolesWithinTheirRights()
{
    using Air::GroupRole;
    using Air::MemberAction;
    const QVector<GroupRole> roles = {GroupRole::Founder, GroupRole::Moderator, GroupRole::Member,
                                      GroupRole::Observer};
    for (const GroupRole target : roles) {
        for (const GroupRole role : roles) {
            QVERIFY2(!Air::canSetRole(role, target, GroupRole::Member), "a member manages nothing");
            QVERIFY2(!Air::canSetRole(role, target, GroupRole::Observer),
                     "an observer manages nothing");
            QVERIFY2(!Air::canSetRole(GroupRole::Founder, target, GroupRole::Founder),
                     "nobody is made founder");
            QVERIFY2(!Air::canSetRole(role, GroupRole::Founder, GroupRole::Founder),
                     "the founder's role stays");
        }
        QVERIFY(!Air::canRemove(target, GroupRole::Member));
        QVERIFY(!Air::canRemove(target, GroupRole::Observer));
    }
    QCOMPARE(Air::roleRank(GroupRole::Founder), 0);
    QCOMPARE(Air::roleRank(GroupRole::Observer), 3);

    using Actions = QVector<MemberAction>;
    QCOMPARE(Air::memberActions(GroupRole::Member, GroupRole::Founder),
             Actions({MemberAction::MakeModerator, MemberAction::MakeObserver, MemberAction::Remove}));
    QCOMPARE(Air::memberActions(GroupRole::Moderator, GroupRole::Founder),
             Actions({MemberAction::MakeMember, MemberAction::MakeObserver, MemberAction::Remove}));
    QCOMPARE(Air::memberActions(GroupRole::Observer, GroupRole::Founder),
             Actions({MemberAction::MakeModerator, MemberAction::MakeMember, MemberAction::Remove}));
    QVERIFY(Air::memberActions(GroupRole::Founder, GroupRole::Founder).isEmpty());

    QCOMPARE(Air::memberActions(GroupRole::Member, GroupRole::Moderator),
             Actions({MemberAction::MakeObserver, MemberAction::Remove}));
    QCOMPARE(Air::memberActions(GroupRole::Observer, GroupRole::Moderator),
             Actions({MemberAction::MakeMember, MemberAction::Remove}));
    QVERIFY2(Air::memberActions(GroupRole::Moderator, GroupRole::Moderator).isEmpty(),
             "moderators do not manage each other");
    QVERIFY(Air::memberActions(GroupRole::Founder, GroupRole::Moderator).isEmpty());
    QVERIFY(Air::memberActions(GroupRole::Member, GroupRole::Member).isEmpty());

    GroupRole role = GroupRole::Member;
    QVERIFY(Air::actionRole(MemberAction::MakeObserver, &role));
    QCOMPARE(role, GroupRole::Observer);
    QVERIFY(!Air::actionRole(MemberAction::Remove, &role));
}

void TestAirGroup::everyChangeIsAskedInOneSentenceAndToldOnceDone()
{
    using Air::MemberAction;
    QCOMPARE(AirStrings::memberActionTitle(MemberAction::MakeModerator, QStringLiteral("Amina")),
             QStringLiteral("Make Amina a moderator?"));
    QCOMPARE(AirStrings::memberActionMessage(MemberAction::MakeModerator),
             QStringLiteral("A moderator makes members observers and back and can remove them."));
    QCOMPARE(AirStrings::memberActionDone(MemberAction::MakeModerator, QStringLiteral("Amina")),
             QStringLiteral("Amina is a moderator now"));
    QCOMPARE(AirStrings::memberActionTitle(MemberAction::Remove, QStringLiteral("Omar")),
             QStringLiteral("Remove Omar from the group?"));
    QCOMPARE(AirStrings::memberActionDone(MemberAction::Remove, QStringLiteral("Omar")),
             QStringLiteral("Omar is no longer in the group"));

    use(QStringLiteral("ru"));
    // The name stays as it is written, so it opens the question instead of taking a case ending.
    QCOMPARE(AirStrings::memberActionTitle(MemberAction::MakeObserver, QStringLiteral("Амина")),
             QStringLiteral("Амина: сделать наблюдателем?"));
    QCOMPARE(AirStrings::memberActionDone(MemberAction::MakeMember, QStringLiteral("Омар")),
             QStringLiteral("Омар теперь участник"));
    QCOMPARE(AirStrings::groupRoleExplanation(Air::GroupRole::Observer),
             QStringLiteral(
                 "Вы наблюдатель: читаете группу, но писать не можете. Право писать может "
                 "дать модератор."));
}

void TestAirGroup::invitationListPutsWhoCanBeInvitedFirst()
{
    const QVector<Air::InviteCandidate> candidates = {
        candidate(1, QStringLiteral("Yusuf"), false), candidate(2, QStringLiteral("Bilal"), true),
        candidate(3, QStringLiteral("Amina"), true),  candidate(4, QStringLiteral("Timur"), true),
        candidate(5, QStringLiteral("Aisha"), false), candidate(-1, QStringLiteral("Zaid"), true),
    };
    QStringList order;
    for (const Air::InviteCandidate& one : Air::arrangedInvites(candidates, QString())) {
        order.append(one.name);
    }
    QCOMPARE(order, QStringList({"Amina", "Bilal", "Timur", "Aisha", "Yusuf", "Zaid"}));
    const QVector<Air::InviteCandidate> found =
        Air::arrangedInvites(candidates, QStringLiteral("AMI"));
    QCOMPARE(found.size(), 1);
    QCOMPARE(found.first().name, QStringLiteral("Amina"));

    QVERIFY(candidate(3, QStringLiteral("Amina"), true).canInvite());
    QVERIFY2(!candidate(1, QStringLiteral("Yusuf"), false).canInvite(),
             "an invitation needs the contact online");
    QVERIFY2(!candidate(-1, QStringLiteral("Zaid"), true).canInvite(),
             "no friend number, nothing to send it over");
}

void TestAirGroup::sentInvitationsAreCounted()
{
    using AirStrings::Text;
    QCOMPARE(AirStrings::text(Text::InviteSent, 1), QStringLiteral("Invitation sent"));
    QCOMPARE(AirStrings::text(Text::InviteSent, 3), QStringLiteral("3 invitations sent"));
    use(QStringLiteral("ru"));
    QCOMPARE(AirStrings::text(Text::InviteSent, 1), QStringLiteral("Отправлено 1 приглашение"));
    QCOMPARE(AirStrings::text(Text::InviteSent, 3), QStringLiteral("Отправлено 3 приглашения"));
    QCOMPARE(AirStrings::text(Text::InviteSent, 5), QStringLiteral("Отправлено 5 приглашений"));
}

void TestAirGroup::privacyIsSaidWithItsConsequence()
{
    QCOMPARE(AirStrings::groupPrivacyText(Air::GroupPrivacy::InviteOnly),
             QStringLiteral("By invitation only"));
    QCOMPARE(AirStrings::groupPrivacyNote(Air::GroupPrivacy::Open),
             QStringLiteral("Anyone who has the group key can join, no invitation needed."));
    use(QStringLiteral("ru"));
    QCOMPARE(AirStrings::groupPrivacyText(Air::GroupPrivacy::InviteOnly),
             QStringLiteral("Только по приглашению"));
}

void TestAirGroup::nameIsOneLineAndFitsInFortyEightBytes()
{
    QCOMPARE(Air::cleanGroupName(QStringLiteral("  Book\nclub \r\n2026 ")),
             QStringLiteral("Book club 2026"));
    QCOMPARE(Air::groupNameOverflow(QString(48, QLatin1Char('a'))), 0);
    QCOMPARE(Air::groupNameOverflow(QString(50, QLatin1Char('a'))), 2);
    // Cyrillic takes two bytes a letter: 25 letters are 50 bytes.
    QCOMPARE(Air::groupNameOverflow(QString(25, QChar(0x0436))), 2);
    QCOMPARE(Air::groupNameOverflow(QString(24, QChar(0x0436))), 0);
    // Trimmed before it is counted.
    QCOMPARE(Air::groupNameOverflow(QStringLiteral("  ") + QString(48, QLatin1Char('a'))
                                    + QStringLiteral("  ")),
             0);
    QCOMPARE(Air::groupNameLimitBytes, 48);
}

void TestAirGroup::counterShowsTheLastTenthOfAGroupMessage()
{
    QCOMPARE(Air::groupMessageLimitBytes, 1372);
    QVERIFY(!Air::composeRoom(0, Air::groupMessageLimitBytes).shown);
    QVERIFY(!Air::composeRoom(1234, Air::groupMessageLimitBytes).shown);
    QVERIFY(Air::composeRoom(1235, Air::groupMessageLimitBytes).shown);
    QCOMPARE(Air::composeRoom(1235, Air::groupMessageLimitBytes).left, 137);
    QCOMPARE(Air::composeRoom(1372, Air::groupMessageLimitBytes).left, 0);
    // Over the limit: toxcore would refuse it, so Send waits and the counter says by how much.
    QCOMPARE(Air::composeRoom(1400, Air::groupMessageLimitBytes).left, -28);
    QCOMPARE(AirStrings::groupCounterText(-28),
             QStringLiteral("28 bytes over the limit of a group message"));
    QCOMPARE(AirStrings::groupCounterText(-1),
             QStringLiteral("1 byte over the limit of a group message"));
}

void TestAirGroup::groupKeyIsCleanedLikeAnIdOfItsOwnLength()
{
    // As pasted from the dossier, the QR code or a message: case, spaces, the prefix of the code.
    QCOMPARE(Air::cleanHex(groupKey.toLower(), Air::toxKeyHexLength), groupKey);
    QCOMPARE(Air::cleanHex(Air::keyLines(groupKey), Air::toxKeyHexLength), groupKey);
    QCOMPARE(Air::cleanHex(QStringLiteral("khandaq:group:") + groupKey.toLower(), Air::toxKeyHexLength),
             groupKey);
    QCOMPARE(Air::cleanHex(QStringLiteral("the key: ") + groupKey + QStringLiteral(", join us"),
                           Air::toxKeyHexLength),
             groupKey);
    // Never longer than a key, and a part of one stays a part.
    QCOMPARE(Air::cleanHex(groupKey + QStringLiteral("ABCD"), Air::toxKeyHexLength), groupKey);
    QCOMPARE(Air::cleanHex(groupKey.left(10), Air::toxKeyHexLength), groupKey.left(10));

    // The key field groups it by four, like the ID field, and stops at 64.
    const Air::IdFieldState typed =
        Air::IdFieldState::regroup(groupKey.left(9).toLower(), 9, Air::toxKeyHexLength);
    QCOMPARE(typed.text, groupKey.left(4) + QLatin1Char(' ') + groupKey.mid(4, 4) + QLatin1Char(' ')
                             + groupKey.mid(8, 1));
    QCOMPARE(typed.caret, typed.text.size());
    const Air::IdFieldState whole =
        Air::IdFieldState::regroup(groupKey + QStringLiteral("FFFF"), 68, Air::toxKeyHexLength);
    QCOMPARE(whole.text, Air::keyGroups(groupKey).join(QLatin1Char(' ')));
}

void TestAirGroup::privateMessagesStayOnThisDeviceUntilTheGroupIsLeft()
{
    using Air::LocalStore;
    const QDateTime now = QDateTime::fromMSecsSinceEpoch(1800000000000);
    LocalStore store;
    LocalStore::PrivateLine line;
    line.groupKey = groupKey.toLower();
    line.peerKey = peerKey;
    line.ours = true;
    line.text = QStringLiteral("Assalamu alaikum, may I write in the group?");
    line.date = now.toMSecsSinceEpoch();
    QVERIFY(store.addPrivateLine(line));
    line.ours = false;
    line.text = QStringLiteral("Wa alaikum assalam, you can now.");
    line.date += 60000;
    QVERIFY(store.addPrivateLine(line));
    LocalStore::PrivateLine other = line;
    other.peerKey = otherPeerKey;
    other.text = QStringLiteral("Another member");
    QVERIFY(store.addPrivateLine(other));

    // Keys that are not keys, and an empty text, are not kept.
    LocalStore::PrivateLine bad = line;
    bad.peerKey = QStringLiteral("1B3D");
    QVERIFY(!store.addPrivateLine(bad));
    bad = line;
    bad.text.clear();
    QVERIFY(!store.addPrivateLine(bad));

    // Kept with the profile: the order, the side and the words come back in the next session.
    LocalStore reloaded;
    reloaded.load(store.save(), now);
    const QVector<LocalStore::PrivateLine> lines = reloaded.privateLines(groupKey, peerKey);
    QCOMPARE(lines.size(), 2);
    QCOMPARE(lines.at(0).groupKey, groupKey);
    QVERIFY(lines.at(0).ours);
    QVERIFY(!lines.at(1).ours);
    QCOMPARE(lines.at(1).text, QStringLiteral("Wa alaikum assalam, you can now."));
    QCOMPARE(reloaded.privateLines(groupKey, otherPeerKey).size(), 1);

    // Each conversation keeps its last privateLimit messages, the oldest go first.
    for (int index = 0; index < LocalStore::privateLimit + 5; ++index) {
        LocalStore::PrivateLine many = line;
        many.text = QString::number(index);
        QVERIFY(reloaded.addPrivateLine(many));
    }
    const QVector<LocalStore::PrivateLine> kept = reloaded.privateLines(groupKey, peerKey);
    QCOMPARE(kept.size(), LocalStore::privateLimit);
    QCOMPARE(kept.first().text, QStringLiteral("5"));
    QCOMPARE(reloaded.privateLines(groupKey, otherPeerKey).size(), 1);

    // Leaving the group takes its private messages and its mute along.
    QVERIFY(reloaded.setMuted(true, groupKey));
    QVERIFY(reloaded.isMuted(groupKey));
    QVERIFY(reloaded.forgetGroup(groupKey));
    QVERIFY(reloaded.privateLines(groupKey, peerKey).isEmpty());
    QVERIFY(reloaded.privateLines(groupKey, otherPeerKey).isEmpty());
    QVERIFY(!reloaded.isMuted(groupKey));
    QVERIFY(!reloaded.forgetGroup(groupKey));
}

QTEST_GUILESS_MAIN(TestAirGroup)
#include "airgroup_test.moc"
