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
#include "src/model/air/airyou.h"
#include "src/widget/air/airchatparts.h"
#include "src/widget/air/aircomposer.h"
#include "src/widget/air/airgroupdossier.h"
#include "src/widget/air/airgroupviews.h"
#include "src/widget/air/airmediaparts.h"
#include "src/widget/air/airnewcontact.h"
#include "src/widget/air/airnewgroup.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airwidgets.h"

#include <QAbstractButton>
#include <QApplication>
#include <QClipboard>
#include <QLabel>
#include <QMenu>
#include <QRegularExpression>
#include <QSignalSpy>
#include <QTextEdit>
#include <QtTest/QtTest>

#include <functional>
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
    void newGroupStartsOpenToAnyoneWithTheKeyAndWaitsForAName();
    void newGroupCreatesWithTheChoiceAndOfflineContactsToo();
    void joiningTakesAWholeKeyAndKnowsTheGroupsInTheList();
    void inviteListKeepsTheChoiceByKey();
    void readOnlyBarTakesThePlaceOfTheField();
    void composerOffersTheEmojiAtTheEndOfTheField();
    void composerSendSaysSaveWhileANoteIsEdited();
    void composerRecordsAVoiceNoteInPlaceOfTheField();
    void composerPausesAVoiceNoteAndLetsItGoOn();
    void dossierSaysTheRoleAndListsTheMembers();
    void dossierSetsYourNameInTheGroup();
    void memberMenuIsTheSameFromTheRowAndFromTheirName();
    void aGroupByInvitationOnlySharesNoKeyAndItsFounderOpensIt();
    void privateThreadTellsAnObserverWhyItCannotAnswer();
    void getNowBandShowsWhoFitsAndCountsTheRest();
};

/**
 * Every opening starts with Open to anyone with the group key chosen (0.2.56), and Create says why
 * it waits. By invitation only stays to be chosen, and says that the key lets nobody in.
 */
void TestAirGroupViews::newGroupStartsOpenToAnyoneWithTheKeyAndWaitsForAName()
{
    Window window;
    window.panel->openCreate();
    QVERIFY(window.panel->isOpen());
    auto* const inviteOnly = part<QAbstractButton>(window.panel.get(), "newGroupInviteOnly");
    auto* const open = part<QAbstractButton>(window.panel.get(), "newGroupOpen");
    QVERIFY(open->isChecked());
    QVERIFY(!inviteOnly->isChecked());
    QVERIFY(inviteOnly->isEnabled());
    QCOMPARE(inviteOnly->accessibleDescription(),
             AirStrings::groupPrivacyNote(Air::GroupPrivacy::InviteOnly));
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
    inviteOnly->click();
    QVERIFY(inviteOnly->isChecked());
    QVERIFY(!open->isChecked());
    window.panel->close();
    QVERIFY(!window.panel->isOpen());
    window.panel->openCreate();
    QCOMPARE(name->text(), QString());
    QVERIFY(open->isChecked());
    QVERIFY(!inviteOnly->isChecked());
    QVERIFY(!create->isEnabled());
}

/** Create hands over the name, the choice and the contacts chosen, those offline too (0.2.60). */
void TestAirGroupViews::newGroupCreatesWithTheChoiceAndOfflineContactsToo()
{
    Window window;
    // "To new group" in Mariam's menu: she comes chosen.
    window.panel->openCreate(window.contacts.at(0).publicKey.toLower());
    auto* const create = part<AirButton>(window.panel.get(), "newGroupCreate");
    QCOMPARE(create->text(), AirStrings::text(Text::NewGroupCreateInvite, 1));

    // Those online first, then by name. Yusuf is offline: he can be chosen, and his row says
    // that the invitation waits for him.
    QList<QAbstractButton*> rows = window.inviteRows();
    QCOMPARE(rows.size(), 3);
    QCOMPARE(rows.at(0)->accessibleName(), QStringLiteral("Ibrahim Aliev"));
    QCOMPARE(rows.at(1)->accessibleName(), QStringLiteral("Mariam Hasanova"));
    QCOMPARE(rows.at(2)->accessibleName(), QStringLiteral("Yusuf Karimov"));
    QVERIFY(!rows.at(0)->isChecked());
    QVERIFY(rows.at(1)->isChecked());
    QVERIFY(rows.at(2)->isEnabled());
    QVERIFY(rows.at(1)->accessibleDescription().isEmpty());
    QCOMPARE(rows.at(2)->accessibleDescription(), AirStrings::text(Text::InviteWhenOnline));

    rows.at(0)->click();
    QCOMPARE(create->text(), AirStrings::text(Text::NewGroupCreateInvite, 2));
    rows.at(0)->click();
    QCOMPARE(create->text(), AirStrings::text(Text::NewGroupCreateInvite, 1));
    rows.at(2)->click();
    QCOMPARE(create->text(), AirStrings::text(Text::NewGroupCreateInvite, 2));

    QString createdName;
    Air::GroupPrivacy createdPrivacy = Air::GroupPrivacy::Open;
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
    part<QAbstractButton>(window.panel.get(), "newGroupInviteOnly")->click();
    auto* const name = part<AirTextField>(window.panel.get(), "newGroupName");
    name->edit()->setFocus();
    QTest::keyClicks(name->edit(), QStringLiteral("  Book  club "));
    QVERIFY(create->isEnabled());
    create->click();
    QCOMPARE(requests, 1);
    QCOMPARE(createdName, QStringLiteral("Book club"));
    QCOMPARE(createdPrivacy, Air::GroupPrivacy::InviteOnly);
    QCOMPARE(invited.size(), 2);
    QCOMPARE(invited.at(0).name, QStringLiteral("Mariam Hasanova"));
    QCOMPARE(invited.at(1).name, QStringLiteral("Yusuf Karimov"));
    QVERIFY(!invited.at(1).online);

    // toxcore refused it: the form stays as it was, with the reason.
    window.panel->createFailed();
    QVERIFY(window.panel->isOpen());
    QVERIFY(showsText(window.panel.get(), AirStrings::text(Text::NewGroupFailed)));
    QCOMPARE(name->text(), QStringLiteral("  Book  club "));

    // Mariam goes offline before Create: she stays chosen, her invitation will wait too.
    window.contacts[0].online = false;
    window.panel->refreshCandidates();
    QCOMPARE(create->text(), AirStrings::text(Text::NewGroupCreateInvite, 2));
    rows = window.inviteRows();
    QCOMPARE(rows.size(), 3);
    QCOMPARE(rows.at(1)->accessibleName(), QStringLiteral("Mariam Hasanova"));
    QVERIFY(rows.at(1)->isEnabled());
    QVERIFY(rows.at(1)->isChecked());

    // Yusuf is gone from toxcore, with no friend number: nothing to send it over, he is let go.
    window.contacts[1].friendId = -1;
    window.panel->refreshCandidates();
    QCOMPARE(create->text(), AirStrings::text(Text::NewGroupCreateInvite, 1));
    rows = window.inviteRows();
    QCOMPARE(rows.at(2)->accessibleName(), QStringLiteral("Yusuf Karimov"));
    QVERIFY(!rows.at(2)->isEnabled());
    QVERIFY(rows.at(2)->accessibleDescription().isEmpty());
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
void TestAirGroupViews::inviteListKeepsTheChoiceByKey()
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
    // Yusuf is offline: he is chosen as well, his invitation waits for him (0.2.60). Twice is once.
    list->choose(contacts.at(2).publicKey);
    QCOMPARE(list->chosen().size(), 2);
    QCOMPARE(list->chosen().last().name, QStringLiteral("Yusuf"));
    QCOMPARE(choices.count(), 2);
    list->choose(contacts.at(2).publicKey);
    QCOMPARE(choices.count(), 2);

    // The same contacts again: nothing is rebuilt, the choice stays.
    const QList<QAbstractButton*> before =
        list->findChildren<QAbstractButton*>(QStringLiteral("inviteRow"));
    list->setCandidates(contacts);
    QCOMPARE(list->findChildren<QAbstractButton*>(QStringLiteral("inviteRow")), before);
    QCOMPARE(choices.count(), 2);

    // A new session gives Bilal another friend number: the choice follows his key.
    contacts[1].friendId = 7;
    list->setCandidates(contacts);
    QCOMPARE(list->chosen().size(), 2);
    QCOMPARE(list->chosen().first().friendId, 7);

    // Bilal goes offline: he stays chosen, his invitation will wait.
    contacts[1].online = false;
    list->setCandidates(contacts);
    QCOMPARE(list->chosen().size(), 2);
    QCOMPARE(choices.count(), 2);

    // Yusuf is gone from toxcore, with no friend number: the choice lets him go.
    contacts[2].friendId = -1;
    list->setCandidates(contacts);
    QCOMPARE(list->chosen().size(), 1);
    QCOMPARE(list->chosen().first().name, QStringLiteral("Bilal"));
    QCOMPARE(choices.count(), 3);
    QCOMPARE(choices.last().at(0).toInt(), 1);

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

/**
 * KHANDAQ (0.2.60): users could not send emoji from the computer. The button sits between the field
 * and Send, in the Tab order too, and asks for the choice above itself; it goes with the field.
 */
void TestAirGroupViews::composerOffersTheEmojiAtTheEndOfTheField()
{
    QWidget host;
    host.resize(700, 300);
    auto* const field = new QTextEdit();
    auto* const composer = new AirComposer(field, &host);
    composer->setGeometry(0, 0, 700, 300);
    host.show();
    QVERIFY(QTest::qWaitForWindowExposed(&host));
    auto* const emoji = part<QAbstractButton>(composer, "composerEmoji");
    auto* const attach = part<QAbstractButton>(composer, "composerAttach");
    QVERIFY(emoji->isVisible());
    QCOMPARE(emoji->accessibleName(), AirStrings::text(Text::ComposeEmoji));
    QCOMPARE(emoji->toolTip(), AirStrings::text(Text::ComposeEmoji));
    const QRect fieldBox(field->mapTo(composer, QPoint()), field->size());
    const QRect emojiBox(emoji->mapTo(composer, QPoint()), emoji->size());
    const QRect attachBox(attach->mapTo(composer, QPoint()), attach->size());
    QVERIFY(attachBox.right() < fieldBox.left());
    QVERIFY(fieldBox.right() < emojiBox.left());
    // Tab goes from the field (past its own viewport) to the emoji: the order the row shows.
    QWidget* next = field->nextInFocusChain();
    while (next && field->isAncestorOf(next)) {
        next = next->nextInFocusChain();
    }
    QCOMPARE(next, static_cast<QWidget*>(emoji));

    QSignalSpy asks(composer, &AirComposer::emojiRequested);
    emoji->click();
    QCOMPARE(asks.count(), 1);
    QCOMPARE(asks.first().at(0).toPoint(), emoji->mapToGlobal(QPoint(emoji->width() / 2, 0)));

    // A read-only group hides the field and the emoji with it; a build without a set hides it.
    composer->setReadOnly(AirStrings::groupReadOnlyText(Air::ComposeRight::Observer),
                          AirStrings::groupAskText(Air::ComposeRight::Observer));
    QVERIFY(!emoji->isVisible());
    composer->setReadOnly(QString(), QString());
    QVERIFY(emoji->isVisible());
    composer->setEmojiVisible(false);
    QVERIFY(!emoji->isVisible());
}

/** KHANDAQ (beta feedback 2026-11-22): Send reads "Save" while a saved message is edited. */
void TestAirGroupViews::composerSendSaysSaveWhileANoteIsEdited()
{
    QWidget host;
    auto* const composer = new AirComposer(new QTextEdit(), &host);
    auto* const send = part<QAbstractButton>(composer, "composerSend");
    QCOMPARE(send->text(), AirStrings::text(Text::ComposeSend));
    QSignalSpy asks(composer, &AirComposer::sendRequested);
    composer->setSaving(true);
    QCOMPARE(send->text(), AirStrings::text(Text::ComposeSave));
    QVERIFY(send->isEnabled());
    send->click();
    QCOMPARE(asks.count(), 1);
    composer->setSaving(false);
    QCOMPARE(send->text(), AirStrings::text(Text::ComposeSend));
}

/**
 * The microphone follows the emoji (0.2.60). While a note is recorded its bar stands where the
 * field was and the cross where the paperclip was; Send and Enter send the note, the cross and Esc
 * cancel it, and the field comes back with its draft.
 */
void TestAirGroupViews::composerRecordsAVoiceNoteInPlaceOfTheField()
{
    QWidget host;
    host.resize(700, 300);
    auto* const field = new QTextEdit();
    auto* const composer = new AirComposer(field, &host);
    composer->setGeometry(0, 0, 700, 300);
    host.show();
    QVERIFY(QTest::qWaitForWindowExposed(&host));
    auto* const voice = part<QAbstractButton>(composer, "composerVoice");
    auto* const emoji = part<QAbstractButton>(composer, "composerEmoji");
    auto* const attach = part<QAbstractButton>(composer, "composerAttach");
    auto* const cancel = part<QAbstractButton>(composer, "composerVoiceCancel");
    auto* const send = part<QAbstractButton>(composer, "composerSend");
    auto* const recording = part<QWidget>(composer, "composerRecording");
    auto* const time = part<QLabel>(composer, "composerRecordingTime");
    auto* const bar = part<AirProgressBar>(composer, "composerRecordingBar");
    QVERIFY(voice->isVisible());
    QVERIFY(!cancel->isVisible());
    QVERIFY(!recording->isVisible());
    QCOMPARE(voice->accessibleName(), AirStrings::text(Text::ComposeVoice));
    QCOMPARE(voice->toolTip(), AirStrings::text(Text::ComposeVoice));
    const QRect emojiBox(emoji->mapTo(composer, QPoint()), emoji->size());
    const QRect voiceBox(voice->mapTo(composer, QPoint()), voice->size());
    const QRect sendBox(send->mapTo(composer, QPoint()), send->size());
    QVERIFY(emojiBox.right() < voiceBox.left());
    QVERIFY(voiceBox.right() < sendBox.left());
    // Tab: the emoji, the microphone, Send, as the row shows them.
    host.activateWindow();
    QVERIFY(QTest::qWaitForWindowActive(&host));
    emoji->setFocus();
    QVERIFY(emoji->hasFocus());
    QTest::keyClick(emoji, Qt::Key_Tab);
    QCOMPARE(QApplication::focusWidget(), static_cast<QWidget*>(voice));
    QTest::keyClick(voice, Qt::Key_Tab);
    QCOMPARE(QApplication::focusWidget(), static_cast<QWidget*>(send));

    // The microphone asks; the form starts the note.
    QSignalSpy asks(composer, &AirComposer::voiceRequested);
    voice->click();
    QCOMPARE(asks.count(), 1);
    QVERIFY(!composer->isRecording());

    field->setPlainText(QStringLiteral("a draft"));
    field->setFocus();
    composer->setRecording(true);
    QVERIFY(composer->isRecording());
    QVERIFY(recording->isVisible());
    QVERIFY(cancel->isVisible());
    QVERIFY(!field->isVisible());
    QVERIFY(!attach->isVisible());
    QVERIFY(!emoji->isVisible());
    QVERIFY(!voice->isVisible());
    QVERIFY(send->hasFocus());
    QCOMPARE(time->text(), QStringLiteral("0:00"));
    QCOMPARE(cancel->accessibleName(), AirStrings::text(Text::ComposeVoiceCancel));
    const QRect cancelBox(cancel->mapTo(composer, QPoint()), cancel->size());
    const QRect barBox(recording->mapTo(composer, QPoint()), recording->size());
    QVERIFY(cancelBox.right() < barBox.left());
    composer->setRecordingTime(83500, 0.25);
    QCOMPARE(time->text(), QStringLiteral("1:23"));
    QCOMPARE(bar->progress(), 0.25);
    QVERIFY(recording->accessibleName().startsWith(AirStrings::text(Text::ComposeRecording)));
    QVERIFY(recording->accessibleName().endsWith(QStringLiteral("1:23")));

    QSignalSpy texts(composer, &AirComposer::sendRequested);
    QSignalSpy notes(composer, &AirComposer::voiceSendRequested);
    QSignalSpy cancels(composer, &AirComposer::voiceCancelRequested);
    send->click();
    QCOMPARE(notes.count(), 1);
    QCOMPARE(texts.count(), 0);
    QTest::keyClick(send, Qt::Key_Return);
    QCOMPARE(notes.count(), 2);
    QTest::keyClick(send, Qt::Key_Escape);
    QCOMPARE(cancels.count(), 1);
    cancel->click();
    QCOMPARE(cancels.count(), 2);
    // While Send waits (a group where nobody would get it), Enter does not send the note either.
    composer->setSendBlocked(true);
    QTest::keyClick(composer, Qt::Key_Return);
    QCOMPARE(notes.count(), 2);
    composer->setSendBlocked(false);

    composer->setRecording(false);
    QVERIFY(!composer->isRecording());
    QVERIFY(field->isVisible());
    QVERIFY(attach->isVisible());
    QVERIFY(emoji->isVisible());
    QVERIFY(voice->isVisible());
    QVERIFY(!cancel->isVisible());
    QVERIFY(!recording->isVisible());
    QCOMPARE(field->toPlainText(), QStringLiteral("a draft"));
    send->click();
    QCOMPARE(texts.count(), 1);
    QCOMPARE(notes.count(), 2);

    // A form without the paperclip or the microphone (notes) keeps them hidden after a note.
    composer->setAttachVisible(false);
    composer->setVoiceVisible(false);
    QVERIFY(!voice->isVisible());
    composer->setRecording(true);
    composer->setRecording(false);
    QVERIFY(!attach->isVisible());
    QVERIFY(!voice->isVisible());
    QVERIFY(emoji->isVisible());
}

/**
 * KHANDAQ (beta feedback 2026-11-22): while a note is recorded, a voice note's round button stands
 * where the microphone was: two bars to pause, the triangle to go on, named for each, and the bar
 * says that the recording is paused. A new note starts unpaused.
 */
void TestAirGroupViews::composerPausesAVoiceNoteAndLetsItGoOn()
{
    QWidget host;
    host.resize(700, 300);
    auto* const field = new QTextEdit();
    auto* const composer = new AirComposer(field, &host);
    composer->setGeometry(0, 0, 700, 300);
    host.show();
    QVERIFY(QTest::qWaitForWindowExposed(&host));
    auto* const pause = part<AirPlayButton>(composer, "composerVoicePause");
    auto* const send = part<QAbstractButton>(composer, "composerSend");
    auto* const recording = part<QWidget>(composer, "composerRecording");
    QVERIFY(!pause->isVisible());

    composer->setRecording(true);
    QVERIFY(pause->isVisible());
    QVERIFY(pause->isEnabled());
    QVERIFY(pause->isPlaying());
    QCOMPARE(pause->accessibleName(), AirStrings::text(Text::ComposeVoicePause));
    QCOMPARE(pause->toolTip(), AirStrings::text(Text::ComposeVoicePause));
    const QRect barBox(recording->mapTo(composer, QPoint()), recording->size());
    const QRect pauseBox(pause->mapTo(composer, QPoint()), pause->size());
    const QRect sendBox(send->mapTo(composer, QPoint()), send->size());
    QVERIFY(barBox.right() < pauseBox.left());
    QVERIFY(pauseBox.right() < sendBox.left());
    // Tab: the cross, the pause, Send.
    host.activateWindow();
    QVERIFY(QTest::qWaitForWindowActive(&host));
    auto* const cancel = part<QAbstractButton>(composer, "composerVoiceCancel");
    cancel->setFocus();
    QTest::keyClick(cancel, Qt::Key_Tab);
    QCOMPARE(QApplication::focusWidget(), static_cast<QWidget*>(pause));
    QTest::keyClick(pause, Qt::Key_Tab);
    QCOMPARE(QApplication::focusWidget(), static_cast<QWidget*>(send));

    // The button asks; the form pauses the note and says so.
    QSignalSpy asks(composer, &AirComposer::voicePauseRequested);
    pause->click();
    QCOMPARE(asks.count(), 1);
    QCOMPARE(asks.last().at(0).toBool(), true);
    QVERIFY(pause->isPlaying());
    composer->setRecordingPaused(true);
    QVERIFY(!pause->isPlaying());
    QCOMPARE(pause->accessibleName(), AirStrings::text(Text::ComposeVoiceResume));
    QCOMPARE(pause->toolTip(), AirStrings::text(Text::ComposeVoiceResume));
    composer->setRecordingTime(12000, 0.04);
    QVERIFY(recording->accessibleName().startsWith(AirStrings::text(Text::ComposeRecordingPaused)));
    QVERIFY(recording->accessibleName().endsWith(QStringLiteral("0:12")));
    QTest::keyClick(pause, Qt::Key_Space);
    QCOMPARE(asks.count(), 2);
    QCOMPARE(asks.last().at(0).toBool(), false);
    composer->setRecordingPaused(false);
    QCOMPARE(pause->accessibleName(), AirStrings::text(Text::ComposeVoicePause));
    QVERIFY(recording->accessibleName().startsWith(AirStrings::text(Text::ComposeRecording)));
    QVERIFY(!recording->accessibleName().startsWith(
        AirStrings::text(Text::ComposeRecordingPaused)));
    // Enter on the pause sends the note, as from Send.
    QSignalSpy notes(composer, &AirComposer::voiceSendRequested);
    QTest::keyClick(pause, Qt::Key_Return);
    QCOMPARE(notes.count(), 1);
    QCOMPARE(asks.count(), 2);

    // No microphone for the note: nothing to pause.
    composer->setRecordingPaused(false, false);
    QVERIFY(!pause->isEnabled());
    composer->setRecordingPaused(true);
    composer->setRecording(false);
    QVERIFY(!pause->isVisible());
    composer->setRecording(true);
    QVERIFY(pause->isEnabled());
    QVERIFY(pause->isPlaying());
    QCOMPARE(pause->accessibleName(), AirStrings::text(Text::ComposeVoicePause));
    composer->setRecording(false);
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
    info.privacy = Air::GroupPrivacy::Open;
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
             AirStrings::groupPrivacyText(Air::GroupPrivacy::Open));
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
 * KHANDAQ (beta feedback 2026-11-22): the dossier shows our name in the group, with the note that
 * the profile name stays, and asks for a new one on Enter or when the field loses the focus. An
 * emptied field, the same name or Esc ask nothing, and a refresh keeps what is being typed.
 */
void TestAirGroupViews::dossierSetsYourNameInTheGroup()
{
    QWidget host;
    host.resize(1100, 800);
    auto* const dossier = new AirGroupDossier(&host);
    host.show();
    QVERIFY(QTest::qWaitForWindowExposed(&host));
    host.activateWindow();
    QVERIFY(QTest::qWaitForWindowActive(&host));
    AirGroupInfo info;
    info.name = QStringLiteral("Study circle");
    info.groupKey = groupKey;
    info.selfName = QStringLiteral("Amina");
    info.members = {member(1, QStringLiteral("Amina"), Air::GroupRole::Member, true, true)};
    info.reach = Air::GroupReach::make(1, 1, 0, true);
    dossier->setInfo(info);
    dossier->open();

    auto* const field = part<AirTextField>(dossier, "groupSelfName");
    QVERIFY(field);
    QVERIFY(field->isVisibleTo(dossier));
    QCOMPARE(field->text(), QStringLiteral("Amina"));
    QVERIFY(showsText(dossier, AirStrings::text(Text::GroupSelfName)));
    QVERIFY(showsText(dossier, AirStrings::text(Text::GroupSelfNameNote)));
    QCOMPARE(field->edit()->accessibleName(), AirStrings::text(Text::GroupSelfName));

    // Enter asks for the name, trimmed, and the field shows the one the group took.
    QSignalSpy asked(dossier, &AirGroupDossier::selfNameRequested);
    const QMetaObject::Connection answer =
        QObject::connect(dossier, &AirGroupDossier::selfNameRequested, dossier,
                         [&](const QString& name) {
                             info.selfName = name;
                             dossier->setInfo(info);
                         });
    field->edit()->setFocus();
    QVERIFY(field->edit()->hasFocus());
    field->setText(QStringLiteral("  Sister Amina "));
    QTest::keyClick(field->edit(), Qt::Key_Return);
    QCOMPARE(asked.count(), 1);
    QCOMPARE(asked.at(0).at(0).toString(), QStringLiteral("Sister Amina"));
    QCOMPARE(field->text(), QStringLiteral("Sister Amina"));

    // A refresh while it is being edited keeps what is typed; Esc takes it back and asks nothing,
    // and the dossier stays.
    field->setText(QStringLiteral("Umm"));
    dossier->setInfo(info);
    QCOMPARE(field->text(), QStringLiteral("Umm"));
    QTest::keyClick(field->edit(), Qt::Key_Escape);
    QCOMPARE(field->text(), QStringLiteral("Sister Amina"));
    QVERIFY(dossier->isOpen());

    // Typing stops at the bytes toxcore takes for a name.
    QTest::keyClicks(field->edit(), QString(Air::nameLimitBytes, QLatin1Char('a')));
    QCOMPARE(field->text().toUtf8().size(), Air::nameLimitBytes);
    QTest::keyClick(field->edit(), Qt::Key_Escape);

    // An emptied field takes the name back; the same name asks nothing.
    field->setText(QStringLiteral("   "));
    QTest::keyClick(field->edit(), Qt::Key_Return);
    QCOMPARE(field->text(), QStringLiteral("Sister Amina"));
    QTest::keyClick(field->edit(), Qt::Key_Return);
    QCOMPARE(asked.count(), 1);

    // Leaving the field asks as well; a name the group refused leaves the one it has.
    QObject::disconnect(answer);
    field->setText(QStringLiteral("Amina B."));
    part<AirButton>(dossier, "groupLeave")->setFocus();
    QCOMPARE(asked.count(), 2);
    QCOMPARE(asked.at(1).at(0).toString(), QStringLiteral("Amina B."));
    QCOMPARE(field->text(), QStringLiteral("Sister Amina"));
}

/**
 * KHANDAQ (0.2.58): a member's row and their name over a message in the transcript open the same
 * menu. Someone no longer in the group gets what a key still allows: Copy key, Mute and Hide.
 */
void TestAirGroupViews::memberMenuIsTheSameFromTheRowAndFromTheirName()
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
    info.privacy = Air::GroupPrivacy::Open;
    const Air::GroupMember liWei = member(3, QStringLiteral("Li Wei"), Air::GroupRole::Member, true);
    info.members = Air::sortedRoster(
        {member(1, QStringLiteral("Amina"), Air::GroupRole::Founder, true, true),
         member(2, QStringLiteral("Mariam"), Air::GroupRole::Moderator, true), liWei});
    info.mutedMembers.insert(liWei.publicKey);
    info.reach = Air::GroupReach::make(3, 3, 2, true);
    dossier->setInfo(info);
    dossier->open();

    // The texts of the menu that opens next, a separator as "-"; the menu is closed unchosen.
    const auto menuOf = [](const std::function<void()>& open) {
        QStringList texts;
        QTimer::singleShot(0, [&texts] {
            auto* const menu = qobject_cast<QMenu*>(QApplication::activePopupWidget());
            if (!menu) {
                return;
            }
            for (const QAction* action : menu->actions()) {
                texts.append(action->isSeparator() ? QStringLiteral("-") : action->text());
            }
            menu->close();
        });
        open();
        return texts;
    };

    QStringList expected{AirStrings::text(Text::GroupWritePrivately),
                         AirStrings::text(Text::DossierCopyKey),
                         AirStrings::text(Text::GroupMemberUnmute),
                         AirStrings::text(Text::GroupMemberHide), QStringLiteral("-")};
    for (const Air::MemberAction action :
         Air::memberActions(Air::GroupRole::Member, Air::GroupRole::Founder)) {
        expected.append(AirStrings::memberActionText(action));
    }
    QVERIFY(expected.size() > 5);

    // From the row of the member.
    QAbstractButton* row = nullptr;
    for (QAbstractButton* const candidate :
         dossier->findChildren<QAbstractButton*>(QStringLiteral("groupMember"))) {
        if (candidate->accessibleName().startsWith(QStringLiteral("Li Wei"))) {
            row = candidate;
        }
    }
    QVERIFY(row);
    QCOMPARE(menuOf([row] { row->click(); }), expected);

    // From their name in the transcript: the same menu.
    const QPoint underName = host.mapToGlobal(QPoint(200, 300));
    QCOMPARE(menuOf([&] { dossier->showMemberMenu(liWei, underName, info); }), expected);

    // No longer in the group: what a key still allows, nothing that needs them in it.
    info.hiddenMembers.insert(liWei.publicKey);
    QCOMPARE(menuOf([&] { dossier->showMemberMenu(liWei, underName, info, false); }),
             (QStringList{AirStrings::text(Text::DossierCopyKey),
                          AirStrings::text(Text::GroupMemberUnmute),
                          AirStrings::text(Text::GroupMemberShow)}));

    // Mute from the name asks for the same change as from the row.
    int mutes = 0;
    QString mutedKey;
    bool mutedTo = true;
    QObject::connect(dossier, &AirGroupDossier::memberMuteRequested, dossier,
                     [&](const Air::GroupMember& muted, bool on) {
                         ++mutes;
                         mutedKey = muted.publicKey;
                         mutedTo = on;
                     });
    QTimer::singleShot(0, [] {
        auto* const menu = qobject_cast<QMenu*>(QApplication::activePopupWidget());
        if (!menu) {
            return;
        }
        // Chosen as a person chooses it: the action becomes the current one and Return takes it.
        for (QAction* const action : menu->actions()) {
            if (action->text() == AirStrings::text(Text::GroupMemberUnmute)) {
                menu->setActiveAction(action);
            }
        }
        QTest::keyClick(menu, Qt::Key_Return);
    });
    dossier->showMemberMenu(liWei, underName, info, false);
    QCOMPARE(mutes, 1);
    QCOMPARE(mutedKey, liWei.publicKey);
    QVERIFY(!mutedTo);
}

/**
 * A group by invitation only is joined by invitation, never with its key: its dossier shows, copies
 * and shares no key, says so in one line and keeps Invite. Its founder alone has the switch that
 * opens it to anyone with the key, asked once, and that closes it again; the switch shows what
 * toxcore holds, not the click.
 */
void TestAirGroupViews::aGroupByInvitationOnlySharesNoKeyAndItsFounderOpensIt()
{
    QWidget host;
    host.resize(1100, 800);
    auto* const dossier = new AirGroupDossier(&host);
    host.show();
    QVERIFY(QTest::qWaitForWindowExposed(&host));
    const auto openDialog = [&host]() -> AirDialog* {
        for (AirDialog* const dialog : host.findChildren<AirDialog*>()) {
            if (dialog->isVisible()) {
                return dialog;
            }
        }
        return nullptr;
    };
    AirGroupInfo info;
    info.name = QStringLiteral("Study circle");
    info.groupKey = groupKey;
    info.privacy = Air::GroupPrivacy::InviteOnly;
    info.selfRole = Air::GroupRole::Member;
    info.members = Air::sortedRoster(
        {member(1, QStringLiteral("Amina"), Air::GroupRole::Founder, true),
         member(2, QStringLiteral("Mariam"), Air::GroupRole::Member, true, true)});
    info.reach = Air::GroupReach::make(2, 2, 1, true);
    dossier->setInfo(info);
    dossier->open();

    // A member: no key in any form, the line in its place, Invite, and the words.
    auto* const key = part<QLabel>(dossier, "groupKey");
    QVERIFY(!key->isVisibleTo(dossier));
    QVERIFY(key->text().isEmpty());
    QVERIFY(key->accessibleDescription().isEmpty());
    QVERIFY(!part<QWidget>(dossier, "groupCopyKey")->isVisibleTo(dossier));
    for (const AirButton* const button : dossier->findChildren<AirButton*>()) {
        QVERIFY2(!button->isVisibleTo(dossier)
                     || button->text() != AirStrings::text(Text::DossierQr),
                 "a QR button shows");
    }
    const QRegularExpression space(QStringLiteral("\\s"));
    for (const QLabel* const label : dossier->findChildren<QLabel*>()) {
        QVERIFY2(!QString(label->text()).remove(space).contains(groupKey),
                 qPrintable(label->objectName()));
    }
    QVERIFY(showsText(dossier, AirStrings::text(Text::GroupKeyHidden)));
    QVERIFY(part<QWidget>(dossier, "groupInvite")->isVisibleTo(dossier));
    QVERIFY(part<QWidget>(dossier, "groupPrivacy")->isVisibleTo(dossier));
    QVERIFY(showsText(dossier, AirStrings::groupPrivacyNote(Air::GroupPrivacy::InviteOnly)));
    auto* const privacySwitch = part<AirSwitch>(dossier, "groupPrivacySwitch");
    QVERIFY(!privacySwitch->isVisibleTo(dossier));

    // Leaving says that only a new invitation brings you back.
    part<AirButton>(dossier, "groupLeave")->click();
    AirDialog* dialog = openDialog();
    QVERIFY(dialog);
    QVERIFY(showsText(dialog, AirStrings::text(Text::GroupLeavePrivateMessage)));
    part<AirButton>(dialog, "dialogCancel")->click();
    QVERIFY(!openDialog());

    // The founder: the switch in place of the words, off.
    info.selfRole = Air::GroupRole::Founder;
    info.members = Air::sortedRoster(
        {member(1, QStringLiteral("Amina"), Air::GroupRole::Founder, true, true),
         member(2, QStringLiteral("Mariam"), Air::GroupRole::Member, true)});
    dossier->setInfo(info);
    QVERIFY(privacySwitch->isVisibleTo(dossier));
    QVERIFY(!privacySwitch->isChecked());
    QCOMPARE(privacySwitch->accessibleName(), AirStrings::text(Text::PrivacyOpen));
    QVERIFY(!part<QWidget>(dossier, "groupPrivacy")->isVisibleTo(dossier));
    QVERIFY(!key->isVisibleTo(dossier));

    // Opening is asked once; until the group takes it, the switch stays off.
    QVector<Air::GroupPrivacy> changes;
    QObject::connect(dossier, &AirGroupDossier::privacyChangeRequested, dossier,
                     [&changes](Air::GroupPrivacy privacy) { changes.append(privacy); });
    privacySwitch->click();
    QVERIFY(!privacySwitch->isChecked());
    QCOMPARE(changes.size(), 0);
    dialog = openDialog();
    QVERIFY(dialog);
    QVERIFY(showsText(dialog, AirStrings::text(Text::GroupOpenMessage)));
    part<AirButton>(dialog, "dialogCancel")->click();
    QCOMPARE(changes.size(), 0);
    privacySwitch->click();
    dialog = openDialog();
    QVERIFY(dialog);
    part<AirButton>(dialog, "dialogConfirm")->click();
    QCOMPARE(changes.size(), 1);
    QCOMPARE(changes.takeFirst(), Air::GroupPrivacy::Open);
    QVERIFY(!privacySwitch->isChecked());

    // toxcore took it: the key with Copy and QR, and the switch on.
    info.privacy = Air::GroupPrivacy::Open;
    dossier->setInfo(info);
    QVERIFY(privacySwitch->isChecked());
    QVERIFY(key->isVisibleTo(dossier));
    QCOMPARE(key->text(), Air::keyLines(groupKey));
    QVERIFY(part<QWidget>(dossier, "groupCopyKey")->isVisibleTo(dossier));
    QVERIFY(!part<QWidget>(dossier, "groupKeyHidden")->isVisibleTo(dossier));
    QVERIFY(showsText(dossier, AirStrings::groupPrivacyNote(Air::GroupPrivacy::Open)));

    // Closing it again goes without a question, and waits for toxcore as well.
    privacySwitch->click();
    QVERIFY(!openDialog());
    QCOMPARE(changes.size(), 1);
    QCOMPARE(changes.takeFirst(), Air::GroupPrivacy::InviteOnly);
    QVERIFY(privacySwitch->isChecked());
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
