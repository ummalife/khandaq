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
#include "src/widget/air/aircomposer.h"
#include "src/widget/air/airgroupdossier.h"
#include "src/widget/air/airgroupviews.h"
#include "src/widget/air/airnewcontact.h"
#include "src/widget/air/airnewgroup.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airwidgets.h"

#include <QAbstractButton>
#include <QApplication>
#include <QClipboard>
#include <QLabel>
#include <QSignalSpy>
#include <QTextEdit>
#include <QtTest/QtTest>

#include <memory>

using AirStrings::Text;

namespace {

const QString groupKey =
    QStringLiteral("8F2A41C07D3B9E6152A4F0C8B7D3E9A1C2F4B6D8E0A1C3E5F7B9D1F3A5C7E9B1");

Air::InviteCandidate candidate(int friendId, const QString& name, bool online)
{
    Air::InviteCandidate result;
    result.friendId = friendId;
    result.name = name;
    result.online = online;
    result.publicKey = QString(64, QLatin1Char(static_cast<char>('A' + friendId % 6)));
    return result;
}

Air::GroupMember member(quint32 peerId, const QString& name, Air::GroupRole role, bool online,
                        bool isSelf = false)
{
    Air::GroupMember result;
    result.peerId = peerId;
    result.name = name;
    result.role = role;
    result.online = online;
    result.isSelf = isSelf;
    result.publicKey = QString::number(peerId).repeated(64).left(64);
    return result;
}

template <typename T>
T* part(const QObject* parent, const char* name)
{
    T* const found = parent->findChild<T*>(QLatin1String(name));
    if (!found) {
        qWarning() << "No part called" << name;
    }
    return found;
}

/** Whether some label under parent shows exactly this text. */
bool showsText(const QWidget* parent, const QString& text)
{
    for (const QLabel* label : parent->findChildren<QLabel*>()) {
        if (label->text() == text && label->isVisibleTo(parent)) {
            return true;
        }
    }
    return false;
}

/** Whether the widget paints anything by itself, its children and background left out. */
bool paintsOnItsOwn(QWidget* widget)
{
    QImage image(widget->size(), QImage::Format_ARGB32_Premultiplied);
    image.fill(Qt::transparent);
    widget->render(&image, QPoint(), QRegion(), QWidget::RenderFlags());
    for (int y = 0; y < image.height(); ++y) {
        const auto* const line = reinterpret_cast<const QRgb*>(image.constScanLine(y));
        for (int x = 0; x < image.width(); ++x) {
            if (qAlpha(line[x]) != 0) {
                return true;
            }
        }
    }
    return false;
}

/** A window with a list column on its left, which New group covers, as in the main window. */
struct Window
{
    QWidget host;
    QWidget* column = nullptr;
    std::unique_ptr<AirNewGroup> panel;
    QVector<Air::InviteCandidate> contacts;
    QStringList groups;

    Window()
    {
        host.resize(1000, 760);
        column = new QWidget(&host);
        column->setGeometry(0, 60, 352, 700);
        contacts = {candidate(1, QStringLiteral("Mariam Hasanova"), true),
                    candidate(2, QStringLiteral("Yusuf Karimov"), false),
                    candidate(3, QStringLiteral("Ibrahim Aliev"), true)};
        AirNewGroup::Lookup lookup;
        lookup.candidates = [this] { return contacts; };
        lookup.hasGroup = [this](const QString& key) { return groups.contains(key); };
        panel.reset(new AirNewGroup(lookup, column, &host));
        host.show();
    }

    bool shows(const char* name) const
    {
        return part<QWidget>(panel.get(), name)->isVisibleTo(panel.get());
    }

    /** The invitation rows in the order of the list. */
    QList<QAbstractButton*> inviteRows() const
    {
        return panel->findChildren<QAbstractButton*>(QStringLiteral("inviteRow"));
    }
};

} // namespace

class TestAirGroupViews : public QObject
{
    Q_OBJECT
private slots:
    void newGroupStartsByInvitationOnlyAndWaitsForAName();
    void newGroupCreatesWithTheChoiceAndTheContactsOnline();
    void joiningTakesAWholeKeyAndKnowsTheGroupsInTheList();
    void inviteListKeepsTheChoiceByKeyWhileTheContactIsOnline();
    void readOnlyBarTakesThePlaceOfTheField();
    void dossierSaysTheRoleAndListsTheMembers();
    void privateThreadTellsAnObserverWhyItCannotAnswer();
    void getNowBandShowsWhoFitsAndCountsTheRest();
};

/** Every opening starts with By invitation only chosen, and Create says why it waits. */
void TestAirGroupViews::newGroupStartsByInvitationOnlyAndWaitsForAName()
{
    Window window;
    window.panel->openCreate();
    QVERIFY(window.panel->isOpen());
    auto* const inviteOnly = part<QAbstractButton>(window.panel.get(), "newGroupInviteOnly");
    auto* const open = part<QAbstractButton>(window.panel.get(), "newGroupOpen");
    QVERIFY(inviteOnly->isChecked());
    QVERIFY(!open->isChecked());
    auto* const create = part<AirButton>(window.panel.get(), "newGroupCreate");
    auto* const why = part<QLabel>(window.panel.get(), "newGroupWhy");
    QVERIFY(!create->isEnabled());
    QCOMPARE(why->text(), AirStrings::text(Text::NewGroupNameFirst));
    QVERIFY(window.shows("newGroupWhy"));

    // A pasted line break becomes a space: the name is one line.
    auto* const name = part<AirTextField>(window.panel.get(), "newGroupName");
    QVERIFY(name->edit()->hasFocus() || !QApplication::focusWidget());
    name->edit()->setFocus();
    QApplication::clipboard()->setText(QStringLiteral("Book\nclub"));
    name->edit()->paste();
    QCOMPARE(name->text(), QStringLiteral("Book club"));
    QVERIFY(create->isEnabled());
    QVERIFY(!window.shows("newGroupWhy"));

    // Past 48 bytes the name cannot go, and Enter does not create it.
    name->edit()->insertPlainText(QString(40, QLatin1Char('a')));
    QVERIFY(!create->isEnabled());
    QCOMPARE(why->text(), AirStrings::text(Text::NewGroupNameTooLong));
    int requests = 0;
    QObject::connect(window.panel.get(), &AirNewGroup::createRequested, window.panel.get(),
                     [&requests] { ++requests; });
    QTest::keyClick(name->edit(), Qt::Key_Return);
    QCOMPARE(requests, 0);

    // The other choice, then closed and opened again: the panel is fresh.
    open->click();
    QVERIFY(open->isChecked());
    QVERIFY(!inviteOnly->isChecked());
    window.panel->close();
    QVERIFY(!window.panel->isOpen());
    window.panel->openCreate();
    QCOMPARE(name->text(), QString());
    QVERIFY(inviteOnly->isChecked());
    QVERIFY(!create->isEnabled());
}

/** Create hands over the name, the choice and the contacts chosen; only the online can be. */
void TestAirGroupViews::newGroupCreatesWithTheChoiceAndTheContactsOnline()
{
    Window window;
    // "To new group" in Mariam's menu: she comes chosen.
    window.panel->openCreate(window.contacts.at(0).publicKey.toLower());
    auto* const create = part<AirButton>(window.panel.get(), "newGroupCreate");
    QCOMPARE(create->text(), AirStrings::text(Text::NewGroupCreateInvite, 1));

    // Those who can be invited first, then by name; Yusuf is offline and cannot be chosen.
    QList<QAbstractButton*> rows = window.inviteRows();
    QCOMPARE(rows.size(), 3);
    QCOMPARE(rows.at(0)->accessibleName(), QStringLiteral("Ibrahim Aliev"));
    QCOMPARE(rows.at(1)->accessibleName(), QStringLiteral("Mariam Hasanova"));
    QCOMPARE(rows.at(2)->accessibleName(), QStringLiteral("Yusuf Karimov"));
    QVERIFY(!rows.at(0)->isChecked());
    QVERIFY(rows.at(1)->isChecked());
    QVERIFY(!rows.at(2)->isEnabled());

    rows.at(0)->click();
    QCOMPARE(create->text(), AirStrings::text(Text::NewGroupCreateInvite, 2));
    rows.at(0)->click();
    QCOMPARE(create->text(), AirStrings::text(Text::NewGroupCreateInvite, 1));

    QString createdName;
    Air::GroupPrivacy createdPrivacy = Air::GroupPrivacy::InviteOnly;
    QVector<Air::InviteCandidate> invited;
    int requests = 0;
    QObject::connect(window.panel.get(), &AirNewGroup::createRequested, window.panel.get(),
                     [&](const QString& name, Air::GroupPrivacy privacy,
                         const QVector<Air::InviteCandidate>& chosen) {
                         ++requests;
                         createdName = name;
                         createdPrivacy = privacy;
                         invited = chosen;
                     });
    part<QAbstractButton>(window.panel.get(), "newGroupOpen")->click();
    auto* const name = part<AirTextField>(window.panel.get(), "newGroupName");
    name->edit()->setFocus();
    QTest::keyClicks(name->edit(), QStringLiteral("  Book  club "));
    QVERIFY(create->isEnabled());
    create->click();
    QCOMPARE(requests, 1);
    QCOMPARE(createdName, QStringLiteral("Book club"));
    QCOMPARE(createdPrivacy, Air::GroupPrivacy::Open);
    QCOMPARE(invited.size(), 1);
    QCOMPARE(invited.first().name, QStringLiteral("Mariam Hasanova"));

    // toxcore refused it: the form stays as it was, with the reason.
    window.panel->createFailed();
    QVERIFY(window.panel->isOpen());
    QVERIFY(showsText(window.panel.get(), AirStrings::text(Text::NewGroupFailed)));
    QCOMPARE(name->text(), QStringLiteral("  Book  club "));

    // Mariam goes offline before Create: she is no longer chosen.
    window.contacts[0].online = false;
    window.panel->refreshCandidates();
    QCOMPARE(create->text(), AirStrings::text(Text::NewGroupCreate));
    rows = window.inviteRows();
    QCOMPARE(rows.size(), 3);
    QVERIFY(!rows.at(1)->isEnabled());
    QVERIFY(!rows.at(1)->isChecked());
}

/** The key field takes a key in any usual shape; a group in the list opens instead of joining. */
void TestAirGroupViews::joiningTakesAWholeKeyAndKnowsTheGroupsInTheList()
{
    Window window;
    window.panel->openJoin(QStringLiteral("khandaq:group:") + groupKey.toLower());
    QVERIFY(window.panel->isOpen());
    auto* const key = part<AirTextField>(window.panel.get(), "joinGroupKey");
    QCOMPARE(key->text(), Air::keyGroups(groupKey).join(QLatin1Char(' ')));
    auto* const join = part<AirButton>(window.panel.get(), "joinGroupJoin");
    QVERIFY(join->isEnabled());
    QVERIFY(!window.shows("joinGroupOpen"));
    QStringList joins;
    QObject::connect(window.panel.get(), &AirNewGroup::joinRequested, window.panel.get(),
                     [&joins](const QString& joined) { joins.append(joined); });
    join->click();
    QCOMPARE(joins, QStringList{groupKey});

    // A part of a key waits, and says what a key is.
    window.panel->openJoin(groupKey.left(20));
    QVERIFY(!join->isEnabled());
    QVERIFY(showsText(window.panel.get(), AirStrings::text(Text::JoinKeyBad)));

    // Creating and joining are one panel: the links go between the two pages.
    part<AirButton>(window.panel.get(), "joinGroupCreateLink")->click();
    QVERIFY(window.shows("newGroupName"));
    QVERIFY(!window.shows("joinGroupKey"));
    part<AirButton>(window.panel.get(), "newGroupJoinLink")->click();
    QVERIFY(window.shows("joinGroupKey"));
    QCOMPARE(key->text(), QString());

    // The group is in the list already: it opens instead.
    window.groups.append(groupKey);
    window.panel->openJoin(groupKey);
    QVERIFY(!join->isEnabled());
    QVERIFY(window.shows("joinGroupOpen"));
    QVERIFY(showsText(window.panel.get(), AirStrings::text(Text::JoinAlready)));
    QStringList opens;
    QObject::connect(window.panel.get(), &AirNewGroup::openGroupRequested, window.panel.get(),
                     [&opens](const QString& opened) { opens.append(opened); });
    part<AirButton>(window.panel.get(), "joinGroupOpen")->click();
    QCOMPARE(opens, QStringList{groupKey});
    QVERIFY(!window.panel->isOpen());
}

/** A friend number is a slot of one session: the choice goes by key, and only while online. */
void TestAirGroupViews::inviteListKeepsTheChoiceByKeyWhileTheContactIsOnline()
{
    QWidget host;
    host.resize(400, 600);
    auto* const list = new AirInviteList(&host);
    list->setGeometry(0, 0, 352, 600);
    host.show();
    QVector<Air::InviteCandidate> contacts = {candidate(0, QStringLiteral("Amina"), true),
                                              candidate(1, QStringLiteral("Bilal"), true),
                                              candidate(2, QStringLiteral("Yusuf"), false)};
    list->setCandidates(contacts);
    QSignalSpy choices(list, &AirInviteList::choiceChanged);
    list->choose(contacts.at(1).publicKey);
    QCOMPARE(list->chosen().size(), 1);
    QCOMPARE(list->chosen().first().name, QStringLiteral("Bilal"));
    // Yusuf is offline: choosing him does nothing.
    list->choose(contacts.at(2).publicKey);
    QCOMPARE(list->chosen().size(), 1);
    QCOMPARE(choices.count(), 1);

    // The same contacts again: nothing is rebuilt, the choice stays.
    const QList<QAbstractButton*> before =
        list->findChildren<QAbstractButton*>(QStringLiteral("inviteRow"));
    list->setCandidates(contacts);
    QCOMPARE(list->findChildren<QAbstractButton*>(QStringLiteral("inviteRow")), before);
    QCOMPARE(choices.count(), 1);

    // A new session gives Bilal another friend number: the choice follows his key.
    contacts[1].friendId = 7;
    list->setCandidates(contacts);
    QCOMPARE(list->chosen().size(), 1);
    QCOMPARE(list->chosen().first().friendId, 7);

    // Bilal goes offline: he is no longer chosen, as he cannot be invited now.
    contacts[1].online = false;
    list->setCandidates(contacts);
    QVERIFY(list->chosen().isEmpty());
    QCOMPARE(choices.count(), 2);
    QCOMPARE(choices.last().at(0).toInt(), 0);

    // From eight contacts on, a search by name.
    auto* const search = part<AirTextField>(list, "inviteSearch");
    QVERIFY(!search->isVisibleTo(list));
    for (int index = 3; index < 8; ++index) {
        Air::InviteCandidate more = candidate(index, QStringLiteral("Contact %1").arg(index), true);
        more.publicKey = QString::number(index).repeated(64);
        contacts.append(more);
    }
    list->setCandidates(contacts);
    QVERIFY(search->isVisibleTo(list));
    search->edit()->setFocus();
    QTest::keyClicks(search->edit(), QStringLiteral("amina"));
    const QList<QAbstractButton*> found =
        list->findChildren<QAbstractButton*>(QStringLiteral("inviteRow"));
    QCOMPARE(found.size(), 1);
    QCOMPARE(found.first()->accessibleName(), QStringLiteral("Amina"));
}

/** An observer sees why they cannot write and whom to ask; the field comes back with the right. */
void TestAirGroupViews::readOnlyBarTakesThePlaceOfTheField()
{
    QWidget host;
    host.resize(700, 300);
    auto* const field = new QTextEdit();
    auto* const composer = new AirComposer(field, &host);
    composer->setGeometry(0, 0, 700, 300);
    host.show();
    QVERIFY(QTest::qWaitForWindowExposed(&host));
    auto* const bar = part<QWidget>(composer, "composerReadOnly");
    auto* const ask = part<AirButton>(composer, "composerReadOnlyAction");
    QVERIFY(!composer->isReadOnly());
    QVERIFY(!bar->isVisible());
    QVERIFY(field->isVisible());
    // The composer draws the field's frame itself.
    QVERIFY(paintsOnItsOwn(composer));

    const QString why = AirStrings::groupReadOnlyText(Air::ComposeRight::Observer);
    QVERIFY(!why.isEmpty());
    composer->setReadOnly(why, AirStrings::groupAskText(Air::ComposeRight::Observer));
    QVERIFY(composer->isReadOnly());
    QVERIFY(bar->isVisible());
    QVERIFY(!field->isVisible());
    // No frame of the hidden field under the bar, whose edge would show around its corners.
    QVERIFY(!paintsOnItsOwn(composer));
    QCOMPARE(bar->accessibleName(), why);
    QCOMPARE(ask->text(), AirStrings::text(Text::GroupAskModerator));
    QVERIFY(ask->isEnabled());
    auto* const waitReason = part<QLabel>(composer, "composerReadOnlyWhy");
    QVERIFY(!waitReason->isVisible());
    QSignalSpy asks(composer, &AirComposer::readOnlyActionRequested);
    ask->click();
    QCOMPARE(asks.count(), 1);

    // Nobody known to ask: the button stays and says why it waits, under it.
    composer->setReadOnly(why, AirStrings::groupAskText(Air::ComposeRight::ModeratorsOnly),
                          AirStrings::text(Text::GroupAskNobody));
    QVERIFY(ask->isVisible());
    QVERIFY(!ask->isEnabled());
    QCOMPARE(ask->toolTip(), AirStrings::text(Text::GroupAskNobody));
    QVERIFY(waitReason->isVisible());
    QCOMPARE(waitReason->text(), AirStrings::text(Text::GroupAskNobody));
    QVERIFY(waitReason->geometry().top() > ask->geometry().bottom());

    // An observer: toxcore refuses their private messages too.
    composer->setReadOnly(why, AirStrings::groupAskText(Air::ComposeRight::Observer),
                          AirStrings::groupAskUnavailableText(Air::ComposeRight::Observer, false));
    QVERIFY(!ask->isEnabled());
    QCOMPARE(waitReason->text(), AirStrings::text(Text::GroupObserverNoPrivate));

    // Only the founder writes: the founder is the one to ask.
    composer->setReadOnly(AirStrings::groupReadOnlyText(Air::ComposeRight::FounderOnly),
                          AirStrings::groupAskText(Air::ComposeRight::FounderOnly));
    QCOMPARE(ask->text(), AirStrings::text(Text::GroupAskFounder));
    QVERIFY(ask->isEnabled());
    QVERIFY(!waitReason->isVisible());

    composer->setReadOnly(QString(), QString());
    QVERIFY(!composer->isReadOnly());
    QVERIFY(!bar->isVisible());
    QVERIFY(field->isVisible());
    QVERIFY(paintsOnItsOwn(composer));
}

/** The dossier says your role in words and lists everyone, you included, by role. */
void TestAirGroupViews::dossierSaysTheRoleAndListsTheMembers()
{
    QWidget host;
    host.resize(1100, 800);
    auto* const dossier = new AirGroupDossier(&host);
    host.show();
    QVERIFY(QTest::qWaitForWindowExposed(&host));
    AirGroupInfo info;
    info.name = QStringLiteral("Study circle");
    info.groupKey = groupKey;
    info.selfRole = Air::GroupRole::Founder;
    info.members =
        Air::sortedRoster({member(1, QStringLiteral("Amina"), Air::GroupRole::Founder, true, true),
                           member(2, QStringLiteral("Mariam"), Air::GroupRole::Moderator, true),
                           member(3, QStringLiteral("Li Wei"), Air::GroupRole::Member, false),
                           member(4, QStringLiteral("Ibrahim"), Air::GroupRole::Observer, true)});
    info.reach = Air::GroupReach::make(4, 4, 2, true);
    dossier->setInfo(info);
    dossier->open();
    QVERIFY(dossier->isOpen());

    QCOMPARE(part<QLabel>(dossier, "groupRole")->text(),
             AirStrings::groupRoleExplanation(Air::GroupRole::Founder));
    QCOMPARE(part<QLabel>(dossier, "groupKey")->text(), Air::keyLines(groupKey));
    QCOMPARE(part<QLabel>(dossier, "groupPrivacy")->text(),
             AirStrings::groupPrivacyText(Air::GroupPrivacy::InviteOnly));
    QVERIFY(showsText(dossier, AirStrings::text(Text::GroupWhoGetsNow)));
    QVERIFY(showsText(dossier, AirStrings::text(Text::GroupWhoGetsNowNote).arg(2).arg(3)));

    // Your own row says who you are and opens nothing; the others open their actions.
    const QList<QAbstractButton*> self =
        dossier->findChildren<QAbstractButton*>(QStringLiteral("groupMemberSelf"));
    QCOMPARE(self.size(), 1);
    QCOMPARE(self.first()->focusPolicy(), Qt::NoFocus);
    QVERIFY(self.first()->accessibleName().startsWith(
        AirStrings::text(Text::GroupMemberYou).arg(QStringLiteral("Amina"))));
    QList<QAbstractButton*> rows =
        dossier->findChildren<QAbstractButton*>(QStringLiteral("groupMember"));
    QCOMPARE(rows.size(), 3);
    QCOMPARE(rows.first()->accessibleName(),
             AirStrings::text(Text::GroupMemberStatus)
                 .arg(QStringLiteral("Mariam"), AirStrings::groupMemberStatusText(info.members.at(1))));
    // No search for four members.
    QVERIFY(!part<QWidget>(dossier, "groupMemberSearch")->isVisibleTo(dossier));

    // An observer reads the members, not who gets a message.
    info.selfRole = Air::GroupRole::Observer;
    dossier->setInfo(info);
    QCOMPARE(part<QLabel>(dossier, "groupRole")->text(),
             AirStrings::groupRoleExplanation(Air::GroupRole::Observer));
    QVERIFY(showsText(dossier, AirStrings::text(Text::GroupMembers)));
    QVERIFY(!showsText(dossier, AirStrings::text(Text::GroupWhoGetsNow)));

    // Leave asks once, over the window, and only the verb leaves.
    QSignalSpy leaves(dossier, &AirGroupDossier::leaveRequested);
    part<AirButton>(dossier, "groupLeave")->click();
    QCOMPARE(leaves.count(), 0);
    auto* const dialog = host.findChild<AirDialog*>();
    QVERIFY(dialog);
    part<AirButton>(dialog, "dialogConfirm")->click();
    QCOMPARE(leaves.count(), 1);
    QVERIFY(!dossier->isOpen());
}

/**
 * toxcore lets an observer send nothing in the group, private messages included: the thread keeps
 * what the member wrote and says why there is no field, and the text typed before stays.
 */
void TestAirGroupViews::privateThreadTellsAnObserverWhyItCannotAnswer()
{
    QWidget host;
    host.resize(1100, 800);
    auto* const thread = new AirPrivateThread(&host);
    host.show();
    QVERIFY(QTest::qWaitForWindowExposed(&host));
    const QString peerKey(64, QLatin1Char('A'));
    Air::LocalStore::PrivateLine line;
    line.groupKey = groupKey;
    line.peerKey = peerKey;
    line.text = QStringLiteral("Wa alaykum as-salam! I will give you the word after Friday.");
    line.date = QDateTime::currentMSecsSinceEpoch();
    thread->showMember(QStringLiteral("Mariam"), peerKey);
    thread->setLines({line});
    thread->open();

    auto* const field = part<AirTextField>(thread, "groupPrivateField");
    auto* const send = part<AirButton>(thread, "groupPrivateSend");
    auto* const blocked = part<QLabel>(thread, "groupPrivateBlocked");
    QVERIFY(thread->isWritable());
    QVERIFY(field->isVisibleTo(thread));
    QVERIFY(!blocked->isVisibleTo(thread));
    QSignalSpy sends(thread, &AirPrivateThread::sendRequested);
    field->edit()->setFocus();
    QTest::keyClicks(field->edit(), QStringLiteral("Salam"));
    QVERIFY(send->isEnabled());

    const QString reason = AirStrings::text(Text::GroupObserverNoPrivate);
    thread->setWritable(false, reason);
    QVERIFY(!thread->isWritable());
    QVERIFY(!field->isVisibleTo(thread));
    QVERIFY(!send->isVisibleTo(thread));
    QVERIFY(blocked->isVisibleTo(thread));
    QCOMPARE(blocked->text(), reason);
    QVERIFY(showsText(thread, line.text));
    // Enter reaches nothing that sends.
    QTest::keyClick(field->edit(), Qt::Key_Return);
    QCOMPARE(sends.count(), 0);

    // The role comes back: the field with what was typed.
    thread->setWritable(true);
    QVERIFY(field->isVisibleTo(thread));
    QVERIFY(!blocked->isVisibleTo(thread));
    QCOMPARE(field->text(), QStringLiteral("Salam"));
    QTest::keyClick(field->edit(), Qt::Key_Return);
    QCOMPARE(sends.count(), 1);
    QCOMPARE(sends.at(0).at(1).toString(), QStringLiteral("Salam"));
}

/** The strip shows as many tiles as the width takes, then how many more get it now. */
void TestAirGroupViews::getNowBandShowsWhoFitsAndCountsTheRest()
{
    QWidget host;
    auto* const band = new AirGetNowBand(&host);
    host.resize(1000, 100);
    QVector<Air::GroupMember> online;
    QStringList names;
    for (quint32 peer = 1; peer <= 12; ++peer) {
        online.append(member(peer, QStringLiteral("Member %1").arg(peer), Air::GroupRole::Member, true));
        names.append(QStringLiteral("Member %1").arg(peer));
    }
    band->setMembers(online);
    band->setGeometry(0, 0, 220, band->sizeHint().height());
    host.show();
    QVERIFY(QTest::qWaitForWindowExposed(&host));
    QLabel* more = nullptr;
    for (QLabel* label : band->findChildren<QLabel*>()) {
        if (label->text().startsWith(QLatin1Char('+'))) {
            more = label;
        }
    }
    QVERIFY(more);
    QVERIFY(more->isVisible());
    QVERIFY(more->geometry().right() < band->width());
    QCOMPARE(band->accessibleName(),
             AirStrings::text(Text::GroupGetNowSpoken).arg(QLocale().createSeparatedList(names)));

    // Wide enough for everyone: no count.
    band->setGeometry(0, 0, 900, band->sizeHint().height());
    QVERIFY(!more->isVisible());

    // A click, Enter or Space opens the dossier.
    QSignalSpy clicks(band, &AirGetNowBand::clicked);
    band->setFocus();
    QTest::keyClick(band, Qt::Key_Return);
    QTest::keyClick(band, Qt::Key_Space);
    QTest::mouseClick(band, Qt::LeftButton);
    QCOMPARE(clicks.count(), 3);
}

int main(int argc, char* argv[])
{
    // The widgets need a platform, not a screen: CI runs the tests without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    QApplication app(argc, argv);
    TestAirGroupViews test;
    return QTest::qExec(&test, argc, argv);
}

#include "airgroupviews_test.moc"
