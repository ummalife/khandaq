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

#include "airgroupdossier.h"

#include "src/model/air/airconversation.h"
#include "src/model/air/airmodel.h"
#include "src/widget/air/airchatparts.h"
#include "src/widget/air/airgroupviews.h"
#include "src/widget/air/airnewcontact.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/translator.h"

#include <QApplication>
#include <QBoxLayout>
#include <QClipboard>
#include <QDateTime>
#include <QLabel>
#include <QLocale>
#include <QMenu>
#include <QStyle>
#include <QTextEdit>

#include <limits>

using AirStrings::Text;
using AirTokens::Color;
using AirTokens::TextRole;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;

namespace {
const AirStyle& air()
{
    return AirStyle::instance();
}

QLabel* makeLabel(TextRole role, Color color, QWidget* parent, bool wrap = true)
{
    auto* const label = new QLabel(parent);
    label->setTextFormat(Qt::PlainText);
    label->setWordWrap(wrap);
    label->setFont(air().font(role));
    AirPaint::textColor(label, air().color(color));
    return label;
}

/** The two columns of sections stack once the dossier is narrower than this. */
constexpr int twoColumnWidth = 720;

/** Every text stands at the start edge of the interface, whatever script it is in. */
void alignToStart(QWidget* page, Qt::LayoutDirection direction)
{
    const Qt::Alignment start = QStyle::visualAlignment(direction, Qt::AlignLeft | Qt::AlignVCenter);
    for (QLabel* const label : page->findChildren<QLabel*>()) {
        label->setAlignment(start);
    }
}

/** Hours and minutes in the Latin digits of the Plex type, with the day when it is not today. */
QString lineTime(qint64 date)
{
    const QDateTime time = QDateTime::fromMSecsSinceEpoch(date).toLocalTime();
    const QString clock = QLocale::c().toString(time.time(), QStringLiteral("H:mm"));
    const QDate today = QDate::currentDate();
    if (time.date() == today) {
        return clock;
    }
    return AirStrings::dayMarkText(time.date(), today, QLocale()) + QLatin1Char(' ') + clock;
}
} // namespace

// ---- AirGroupDossier ----

AirGroupDossier::AirGroupDossier(QWidget* parent)
    : AirFoldPanel(parent)
{
    setObjectName(QStringLiteral("groupDossier"));
    QWidget* const pageWidget = page();
    QVBoxLayout* const sections = pageLayout();

    // The group: tile, name, the counts and "Rename".
    auto* const top = new QHBoxLayout();
    top->setSpacing(Space::s12);
    tile = new AirTile(Size::field, pageWidget);
    tile->setKind(AirTile::Kind::Group);
    top->addWidget(tile, 0, Qt::AlignTop);
    auto* const names = new QVBoxLayout();
    names->setSpacing(Space::s2);
    nameLabel = makeLabel(TextRole::Heading, Color::Ink, pageWidget);
    countsLabel = makeLabel(TextRole::Caption, Color::Ink2, pageWidget);
    names->addWidget(nameLabel);
    names->addWidget(countsLabel);
    top->addLayout(names, 1);
    renameButton = new AirButton(AirButton::Kind::Quiet, pageWidget);
    renameButton->setObjectName(QStringLiteral("groupRename"));
    top->addWidget(renameButton, 0, Qt::AlignTop);
    sections->addLayout(top);

    columns = new QBoxLayout(QBoxLayout::LeftToRight);
    columns->setSpacing(Space::s32);
    auto* const left = new QVBoxLayout();
    left->setSpacing(Space::s16);
    auto* const right = new QVBoxLayout();
    right->setSpacing(Space::s16);
    columns->addLayout(left, 1);
    columns->addLayout(right, 1);
    sections->addLayout(columns);

    // Your role, in one or two sentences.
    QVBoxLayout* body = section(left, &roleTitle);
    roleText = makeLabel(TextRole::Body, Color::Ink2, pageWidget);
    roleText->setObjectName(QStringLiteral("groupRole"));
    body->addWidget(roleText);

    // Who gets a message now, or the members: by role, online first.
    body = section(left, &membersTitle);
    membersNote = makeLabel(TextRole::Caption, Color::Ink2, pageWidget);
    body->addWidget(membersNote);
    search = new AirTextField(AirTextField::Kind::Line, pageWidget);
    search->setObjectName(QStringLiteral("groupMemberSearch"));
    body->addWidget(search);
    membersHolder = new QWidget(pageWidget);
    membersList = new QVBoxLayout(membersHolder);
    membersList->setContentsMargins(0, 0, 0, 0);
    membersList->setSpacing(0);
    body->addWidget(membersHolder);
    left->addStretch();

    // The group key: in groups of four, who can join with it, Copy, Invite and the QR code.
    body = section(right, &keyTitle);
    keyText = makeLabel(TextRole::Mono, Color::Ink, pageWidget);
    keyText->setTextInteractionFlags(Qt::TextSelectableByMouse);
    keyText->setObjectName(QStringLiteral("groupKey"));
    body->addWidget(keyText);
    auto* const keyActions = new QHBoxLayout();
    keyActions->setContentsMargins(0, Space::s4, 0, 0);
    keyActions->setSpacing(Space::s8);
    copyButton = new AirButton(AirButton::Kind::Plain, pageWidget);
    copyButton->setObjectName(QStringLiteral("groupCopyKey"));
    inviteButton = new AirButton(AirButton::Kind::Plain, pageWidget);
    inviteButton->setObjectName(QStringLiteral("groupInvite"));
    qrButton = new AirButton(AirButton::Kind::Plain, pageWidget);
    keyActions->addWidget(copyButton);
    keyActions->addWidget(inviteButton);
    keyActions->addWidget(qrButton);
    keyActions->addStretch();
    body->addLayout(keyActions);

    // Invite: the contacts, those online first, and the button that sends the invitations.
    inviteBox = new QWidget(pageWidget);
    auto* const inviteColumn = new QVBoxLayout(inviteBox);
    inviteColumn->setContentsMargins(0, Space::s8, 0, 0);
    inviteColumn->setSpacing(Space::s8);
    inviteNote = makeLabel(TextRole::Caption, Color::Ink2, inviteBox);
    inviteColumn->addWidget(inviteNote);
    inviteList = new AirInviteList(inviteBox);
    inviteColumn->addWidget(inviteList);
    auto* const inviteFoot = new QHBoxLayout();
    inviteFoot->setSpacing(Space::s8);
    sendInvites = new AirButton(AirButton::Kind::Ink, inviteBox);
    sendInvites->setObjectName(QStringLiteral("groupSendInvites"));
    cancelInvites = new AirButton(AirButton::Kind::Quiet, inviteBox);
    inviteFoot->addWidget(sendInvites);
    inviteFoot->addWidget(cancelInvites);
    inviteFoot->addStretch();
    inviteColumn->addLayout(inviteFoot);
    inviteWhy = makeLabel(TextRole::Caption, Color::Ink3, inviteBox);
    inviteColumn->addWidget(inviteWhy);
    inviteBox->hide();
    body->addWidget(inviteBox);

    // Who can join with the key, and what follows from it.
    body = section(right, &privacyTitle);
    privacyText = makeLabel(TextRole::Body, Color::Ink, pageWidget);
    privacyText->setObjectName(QStringLiteral("groupPrivacy"));
    body->addWidget(privacyText);
    privacyNote = makeLabel(TextRole::Caption, Color::Ink2, pageWidget);
    body->addWidget(privacyNote);

    // Notifications.
    body = section(right, &notifyTitle);
    auto* const muteRow = new QHBoxLayout();
    muteLabel = makeLabel(TextRole::Body, Color::Ink, pageWidget, false);
    muteSwitch = new AirSwitch(pageWidget);
    muteSwitch->setObjectName(QStringLiteral("groupMute"));
    muteRow->addWidget(muteLabel, 1);
    muteRow->addWidget(muteSwitch);
    body->addLayout(muteRow);
    mutedNote = makeLabel(TextRole::Caption, Color::Ink2, pageWidget);
    body->addWidget(mutedNote);
    right->addStretch();

    // Leave the group, with what follows from it.
    auto* const foot = new QVBoxLayout();
    foot->setSpacing(Space::s6);
    leaveButton = new AirButton(AirButton::Kind::Danger, pageWidget);
    leaveButton->setObjectName(QStringLiteral("groupLeave"));
    foot->addWidget(leaveButton, 0, Qt::AlignLeading);
    leaveNote = makeLabel(TextRole::Caption, Color::Ink2, pageWidget);
    foot->addWidget(leaveNote);
    sections->addLayout(foot);
    sections->addStretch();

    connect(renameButton, &AirButton::clicked, this, [this] {
        close();
        emit renameRequested();
    });
    connect(search, &AirTextField::changed, this, [this] {
        query = search->text();
        refreshMembers();
    });
    connect(copyButton, &AirButton::clicked, this, [this] {
        QApplication::clipboard()->setText(info.groupKey);
        AirToast::post(this, AirStrings::text(Text::GroupKeyCopied));
    });
    connect(qrButton, &AirButton::clicked, this, [this] {
        showAirQr(this, AirStrings::text(Text::GroupQrHeading).arg(info.name),
                  QStringLiteral("khandaq:group:") + info.groupKey,
                  AirStrings::text(Text::GroupQrSubtitle));
    });
    connect(inviteButton, &AirButton::clicked, this, [this] {
        inviteBox->setVisible(!inviteBox->isVisible());
        if (inviteBox->isVisible()) {
            inviteList->clearChoice();
            refreshInvite();
        }
        place();
    });
    connect(inviteList, &AirInviteList::choiceChanged, this, [this] { refreshInvite(); });
    connect(sendInvites, &AirButton::clicked, this, [this] {
        const QVector<Air::InviteCandidate> chosen = inviteList->chosen();
        if (chosen.isEmpty()) {
            return;
        }
        inviteBox->hide();
        place();
        emit inviteRequested(chosen);
    });
    connect(cancelInvites, &AirButton::clicked, this, [this] {
        inviteBox->hide();
        place();
    });
    connect(muteSwitch, &AirSwitch::toggled, this, [this](bool muted) {
        info.muted = muted;
        mutedNote->setText(AirStrings::text(muted ? Text::GroupMutedNote : Text::GroupUnmutedNote));
        emit muteChanged(muted);
    });
    connect(leaveButton, &AirButton::clicked, this, [this] {
        AirDialog::confirm(this, AirStrings::text(Text::GroupLeaveTitle).arg(info.name),
                           AirStrings::text(Text::GroupLeaveMessage),
                           AirStrings::text(Text::GroupLeaveVerb), AirDialog::Verb::Danger, [this] {
                               close();
                               emit leaveRequested();
                           });
    });

    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirGroupDossier::restyle);
    Translator::registerHandler(std::bind(&AirGroupDossier::retranslate, this), this);
    restyle();
    retranslate();
}

AirGroupDossier::~AirGroupDossier()
{
    Translator::unregister(this);
}

QVBoxLayout* AirGroupDossier::section(QBoxLayout* column, QLabel** title)
{
    auto* const box = new QVBoxLayout();
    box->setSpacing(Space::s4);
    *title = makeLabel(TextRole::Label, Color::Ink3, page(), false);
    box->addWidget(*title);
    auto* const body = new QVBoxLayout();
    body->setSpacing(Space::s6);
    box->addLayout(body);
    column->addLayout(box);
    return body;
}

const AirGroupInfo& AirGroupDossier::groupInfo() const
{
    return info;
}

void AirGroupDossier::setInfo(const AirGroupInfo& info_)
{
    info = info_;
    tile->setPerson(info.name, info.tone);
    nameLabel->setText(info.name);
    countsLabel->setText(AirStrings::groupHeaderText(info.reach));
    roleText->setText(AirStrings::groupRoleExplanation(info.selfRole));
    keyText->setText(Air::keyLines(info.groupKey));
    keyText->setAccessibleName(AirStrings::text(Text::GroupKey));
    keyText->setAccessibleDescription(Air::keyGroups(info.groupKey).join(QLatin1Char(' ')));
    privacyText->setText(AirStrings::groupPrivacyText(info.privacy));
    privacyNote->setText(AirStrings::groupPrivacyNote(info.privacy));
    const QSignalBlocker blocker(muteSwitch);
    muteSwitch->setChecked(info.muted);
    mutedNote->setText(AirStrings::text(info.muted ? Text::GroupMutedNote : Text::GroupUnmutedNote));
    refreshMembers();
    if (inviteBox->isVisible()) {
        refreshInvite();
    }
    place();
}

void AirGroupDossier::retranslate()
{
    renameButton->setText(AirStrings::text(Text::DossierRename));
    roleTitle->setText(AirStrings::text(Text::GroupYourRole));
    search->setPlaceholder(AirStrings::text(Text::GroupSearchMembers));
    search->edit()->setAccessibleName(AirStrings::text(Text::GroupSearchMembers));
    keyTitle->setText(AirStrings::text(Text::GroupKey));
    copyButton->setText(AirStrings::text(Text::GroupCopyKey));
    inviteButton->setText(AirStrings::text(Text::GroupInvite));
    qrButton->setText(AirStrings::text(Text::DossierQr));
    privacyTitle->setText(AirStrings::text(Text::NewGroupPrivacy));
    inviteNote->setText(AirStrings::text(Text::InviteOnlineNote));
    cancelInvites->setText(AirStrings::text(Text::Cancel));
    notifyTitle->setText(AirStrings::text(Text::DossierNotificationsTitle));
    muteLabel->setText(AirStrings::text(Text::DossierMute));
    muteSwitch->setAccessibleName(muteLabel->text());
    leaveButton->setText(AirStrings::text(Text::GroupLeave));
    leaveNote->setText(AirStrings::text(Text::GroupLeaveNote));
    alignToStart(page(), layoutDirection());
    // The member rows say their role and presence in the interface's words: made afresh.
    shownMembers.clear();
    setInfo(info);
}

void AirGroupDossier::restyle()
{
    const struct
    {
        // Every member is set by the braces that make it: an aggregate, which an initializer here
        // would stop it being in C++11.
        // cppcheck-suppress uninitMemberVarNoCtor
        QLabel* label;
        TextRole role;
        Color color;
    } texts[] = {
        {nameLabel, TextRole::Heading, Color::Ink},
        {countsLabel, TextRole::Caption, Color::Ink2},
        {roleTitle, TextRole::Label, Color::Ink3},
        {roleText, TextRole::Body, Color::Ink2},
        {membersTitle, TextRole::Label, Color::Ink3},
        {membersNote, TextRole::Caption, Color::Ink2},
        {keyTitle, TextRole::Label, Color::Ink3},
        {keyText, TextRole::Mono, Color::Ink},
        {privacyTitle, TextRole::Label, Color::Ink3},
        {privacyText, TextRole::Body, Color::Ink},
        {privacyNote, TextRole::Caption, Color::Ink2},
        {inviteNote, TextRole::Caption, Color::Ink2},
        {inviteWhy, TextRole::Caption, Color::Ink3},
        {notifyTitle, TextRole::Label, Color::Ink3},
        {muteLabel, TextRole::Body, Color::Ink},
        {mutedNote, TextRole::Caption, Color::Ink2},
        {leaveNote, TextRole::Caption, Color::Ink2},
    };
    for (const auto& text : texts) {
        text.label->setFont(air().font(text.role));
        AirPaint::textColor(text.label, air().color(text.color));
    }
    update();
}

void AirGroupDossier::refreshMembers()
{
    const Air::ComposeRight right = Air::composeRight(info.selfRole, info.voice);
    const bool writes = right == Air::ComposeRight::Write;
    membersTitle->setText(AirStrings::text(writes ? Text::GroupWhoGetsNow : Text::GroupMembers));
    search->setVisible(info.members.size() >= Air::groupSearchFrom);
    const QVector<Air::GroupMember> shown = Air::filteredRoster(info.members, query);

    QString note;
    if (shown.isEmpty()) {
        note = AirStrings::text(Text::GroupNobodyFound);
    } else if (writes) {
        note = info.reach.connected && info.reach.recipients() > 0
                   ? AirStrings::text(Text::GroupWhoGetsNowNote)
                         .arg(info.reach.online)
                         .arg(info.reach.recipients())
                   : AirStrings::groupHintText(Air::GroupHint::make(info.reach), info.reach);
    }
    membersNote->setText(note);
    membersNote->setVisible(!note.isEmpty());

    if (shown == shownMembers) {
        return;
    }
    shownMembers = shown;
    QWidget* const focused = membersHolder->focusWidget();
    const quint32 focusedPeer = focused && focused->parentWidget() == membersHolder
                                    ? static_cast<AirMemberRow*>(focused)->member().peerId
                                    : std::numeric_limits<quint32>::max();
    while (QLayoutItem* item = membersList->takeAt(0)) {
        delete item->widget();
        delete item;
    }
    for (const Air::GroupMember& member : shown) {
        auto* const row = new AirMemberRow(member, membersHolder);
        if (!member.isSelf) {
            connect(row, &AirMemberRow::clicked, this, [this, row] { showMemberMenu(row); });
        }
        membersList->addWidget(row);
        if (member.peerId == focusedPeer && !member.isSelf) {
            row->setFocus(Qt::OtherFocusReason);
        }
    }
    // Tab reaches the members where they stand, before the key and Leave.
    AirFocus::inPlace(membersHolder);
    membersHolder->setVisible(!shown.isEmpty());
}

void AirGroupDossier::refreshInvite()
{
    inviteList->setCandidates(info.candidates);
    const int count = inviteList->chosen().size();
    sendInvites->setText(count > 0 ? AirStrings::text(Text::InviteSendCount, count)
                                   : AirStrings::text(Text::GroupInvite));
    sendInvites->setEnabled(count > 0);
    inviteWhy->setText(count > 0 ? QString() : AirStrings::text(Text::InviteChooseFirst));
    inviteWhy->setVisible(count == 0 && !info.candidates.isEmpty());
}

void AirGroupDossier::showMemberMenu(AirMemberRow* row)
{
    const Air::GroupMember member = row->member();
    QMenu menu(this);
    QAction* const write = menu.addAction(AirStrings::text(Text::GroupWritePrivately));
    QAction* const copy =
        member.publicKey.isEmpty() ? nullptr : menu.addAction(AirStrings::text(Text::DossierCopyKey));
    const bool muted = info.mutedMembers.contains(member.publicKey);
    QAction* const mute = member.publicKey.isEmpty()
                              ? nullptr
                              : menu.addAction(AirStrings::text(muted ? Text::GroupMemberUnmute
                                                                      : Text::GroupMemberMute));
    const bool hidden = info.hiddenMembers.contains(member.publicKey);
    QAction* const hide =
        member.publicKey.isEmpty()
            ? nullptr
            : menu.addAction(AirStrings::text(hidden ? Text::GroupMemberShow : Text::GroupMemberHide));
    const QVector<Air::MemberAction> actions = Air::memberActions(member.role, info.selfRole);
    QVector<QAction*> roleActions;
    if (!actions.isEmpty()) {
        menu.addSeparator();
    }
    for (const Air::MemberAction action : actions) {
        roleActions.append(menu.addAction(AirStrings::memberActionText(action)));
    }

    // Under the row, from its start edge. The menu runs its own loop: the rows may be made afresh
    // meanwhile, so only the copy of the member is used after it.
    const QPoint start =
        layoutDirection() == Qt::RightToLeft ? row->rect().bottomRight() : row->rect().bottomLeft();
    QAction* const chosen = menu.exec(row->mapToGlobal(start));
    if (!chosen) {
        return;
    }
    if (chosen == write) {
        emit writePrivatelyRequested(member);
    } else if (copy && chosen == copy) {
        QApplication::clipboard()->setText(member.publicKey);
        AirToast::post(this, AirStrings::text(Text::DossierKeyCopied));
    } else if (mute && chosen == mute) {
        emit memberMuteRequested(member, !muted);
    } else if (hide && chosen == hide) {
        emit memberHideRequested(member, !hidden);
    } else {
        const int index = roleActions.indexOf(chosen);
        if (index >= 0) {
            confirm(member, actions.at(index));
        }
    }
}

void AirGroupDossier::confirm(const Air::GroupMember& member, Air::MemberAction action)
{
    // Asked once, in one sentence that starts with the name; the button is the verb.
    const bool removes = action == Air::MemberAction::Remove;
    AirDialog::confirm(this, AirStrings::memberActionTitle(action, member.name),
                       AirStrings::memberActionMessage(action), AirStrings::memberActionText(action),
                       removes ? AirDialog::Verb::Danger : AirDialog::Verb::Ink,
                       [this, member, action] { emit memberActionRequested(member, action); });
}

#ifdef KHANDAQ_SCREENSHOT_HOOK
void AirGroupDossier::askForShots(const Air::GroupMember& member, Air::MemberAction action)
{
    confirm(member, action);
}
#endif

void AirGroupDossier::arrange(int width)
{
    columns->setDirection(width < twoColumnWidth ? QBoxLayout::TopToBottom : QBoxLayout::LeftToRight);
}

void AirGroupDossier::aboutToFold()
{
    inviteBox->hide();
}

// ---- AirPrivateThread ----

AirPrivateThread::AirPrivateThread(QWidget* parent)
    : AirFoldPanel(parent)
{
    setObjectName(QStringLiteral("groupPrivate"));
    QWidget* const pageWidget = page();
    QVBoxLayout* const sections = pageLayout();

    auto* const head = new QVBoxLayout();
    head->setSpacing(Space::s4);
    titleLabel = makeLabel(TextRole::Heading, Color::Ink, pageWidget);
    noteLabel = makeLabel(TextRole::Caption, Color::Ink2, pageWidget);
    head->addWidget(titleLabel);
    head->addWidget(noteLabel);
    sections->addLayout(head);

    emptyLabel = makeLabel(TextRole::Caption, Color::Ink3, pageWidget);
    sections->addWidget(emptyLabel);
    linesHolder = new QWidget(pageWidget);
    linesList = new QVBoxLayout(linesHolder);
    linesList->setContentsMargins(0, 0, 0, 0);
    linesList->setSpacing(Space::s12);
    sections->addWidget(linesHolder);

    auto* const writeRow = new QHBoxLayout();
    writeRow->setSpacing(Space::s8);
    field = new AirTextField(AirTextField::Kind::Message, pageWidget);
    field->setObjectName(QStringLiteral("groupPrivateField"));
    sendButton = new AirButton(AirButton::Kind::Ink, pageWidget);
    sendButton->setObjectName(QStringLiteral("groupPrivateSend"));
    writeRow->addWidget(field, 1);
    writeRow->addWidget(sendButton, 0, Qt::AlignBottom);
    sections->addLayout(writeRow);
    counter = makeLabel(TextRole::Caption, Color::Ink2, pageWidget, false);
    counter->hide();
    sections->addWidget(counter);
    errorLabel = makeLabel(TextRole::Caption, Color::Danger, pageWidget);
    errorLabel->hide();
    sections->addWidget(errorLabel);
    blockedLabel = makeLabel(TextRole::Body, Color::Ink2, pageWidget);
    blockedLabel->setObjectName(QStringLiteral("groupPrivateBlocked"));
    blockedLabel->hide();
    sections->addWidget(blockedLabel);
    sections->addStretch();

    connect(field, &AirTextField::changed, this, [this] {
        errorLabel->hide();
        refreshSend();
    });
    connect(field, &AirTextField::submitted, this, &AirPrivateThread::send);
    connect(sendButton, &AirButton::clicked, this, &AirPrivateThread::send);
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirPrivateThread::restyle);
    Translator::registerHandler(std::bind(&AirPrivateThread::retranslate, this), this);
    restyle();
    retranslate();
}

AirPrivateThread::~AirPrivateThread()
{
    Translator::unregister(this);
}

void AirPrivateThread::showMember(const QString& name_, const QString& peerKey_)
{
    const bool other = key != peerKey_;
    name = name_;
    key = peerKey_;
    if (other) {
        field->setText(QString());
        errorLabel->hide();
    }
    retranslate();
}

QString AirPrivateThread::peerKey() const
{
    return key;
}

QString AirPrivateThread::peerName() const
{
    return name;
}

void AirPrivateThread::setLines(const QVector<Air::LocalStore::PrivateLine>& lines_)
{
    lines = lines_;
    rebuildLines();
    place();
    scrollToEnd();
}

void AirPrivateThread::sent()
{
    field->setText(QString());
    errorLabel->hide();
    refreshSend();
}

void AirPrivateThread::failed()
{
    errorLabel->setText(AirStrings::text(Text::PrivateFailed));
    errorLabel->show();
    place();
    scrollToEnd();
}

void AirPrivateThread::setWritable(bool writable_, const QString& reason)
{
    if (writable == writable_ && blockedLabel->text() == (writable_ ? QString() : reason)) {
        return;
    }
    writable = writable_;
    field->setVisible(writable);
    sendButton->setVisible(writable);
    blockedLabel->setText(writable ? QString() : reason);
    blockedLabel->setVisible(!writable);
    if (!writable) {
        errorLabel->hide();
    }
    refreshSend();
    place();
}

bool AirPrivateThread::isWritable() const
{
    return writable;
}

void AirPrivateThread::focusField()
{
    if (writable) {
        field->edit()->setFocus(Qt::OtherFocusReason);
    }
}

void AirPrivateThread::aboutToFold()
{
    errorLabel->hide();
}

void AirPrivateThread::retranslate()
{
    titleLabel->setText(AirStrings::text(Text::PrivateTitle).arg(name));
    noteLabel->setText(AirStrings::text(Text::PrivateNote).arg(name));
    emptyLabel->setText(AirStrings::text(Text::PrivateEmpty));
    field->setPlaceholder(AirStrings::text(Text::GroupWritePrivately));
    field->edit()->setAccessibleName(AirStrings::text(Text::GroupWritePrivately));
    sendButton->setText(AirStrings::text(Text::ComposeSend));
    if (errorLabel->isVisibleTo(this)) {
        errorLabel->setText(AirStrings::text(Text::PrivateFailed));
    }
    rebuildLines();
    refreshSend();
    alignToStart(page(), layoutDirection());
}

void AirPrivateThread::restyle()
{
    titleLabel->setFont(air().font(TextRole::Heading));
    AirPaint::textColor(titleLabel, air().color(Color::Ink));
    for (QLabel* label : {noteLabel, counter}) {
        label->setFont(air().font(TextRole::Caption));
        AirPaint::textColor(label, air().color(Color::Ink2));
    }
    emptyLabel->setFont(air().font(TextRole::Caption));
    AirPaint::textColor(emptyLabel, air().color(Color::Ink3));
    errorLabel->setFont(air().font(TextRole::Caption));
    AirPaint::textColor(errorLabel, air().color(Color::Danger));
    rebuildLines();
}

void AirPrivateThread::rebuildLines()
{
    while (QLayoutItem* item = linesList->takeAt(0)) {
        delete item->widget();
        delete item;
    }
    for (const Air::LocalStore::PrivateLine& line : lines) {
        auto* const entry = new QWidget(linesHolder);
        auto* const column = new QVBoxLayout(entry);
        column->setContentsMargins(0, 0, 0, 0);
        column->setSpacing(Space::s2);
        auto* const head = new QHBoxLayout();
        head->setSpacing(Space::s8);
        QLabel* const author =
            makeLabel(TextRole::Label, line.ours ? Color::Ink2 : Color::Ink, entry, false);
        author->setText(line.ours ? AirStrings::text(Text::You) : name);
        QLabel* const time = makeLabel(TextRole::Caption, Color::Ink3, entry, false);
        time->setText(lineTime(line.date));
        head->addWidget(author);
        head->addWidget(time);
        head->addStretch();
        column->addLayout(head);
        QLabel* const words = makeLabel(TextRole::Body, Color::Ink, entry);
        // Without the markup of a reply, as the transcript shows a message (stage 3, item 10).
        const QString shown = AirStrings::messageWords(line.text);
        words->setText(shown);
        words->setTextInteractionFlags(Qt::TextSelectableByMouse);
        // A person's own words keep their own direction, whatever the interface is.
        words->setLayoutDirection(AirPaint::ownDirection(shown));
        words->setAlignment(QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignTop));
        column->addWidget(words);
        entry->setAccessibleName(
            AirStrings::text(Text::GroupMemberStatus).arg(author->text(), time->text()));
        entry->setAccessibleDescription(shown);
        linesList->addWidget(entry);
    }
    emptyLabel->setVisible(lines.isEmpty());
    linesHolder->setVisible(!lines.isEmpty());
}

void AirPrivateThread::refreshSend()
{
    const QString text = field->text();
    const int bytes = text.toUtf8().size();
    const Air::ComposeRoom room = Air::composeRoom(bytes, Air::groupMessageLimitBytes);
    counter->setText(room.shown ? AirStrings::groupCounterText(room.left) : QString());
    counter->setVisible(room.shown && writable);
    sendButton->setEnabled(writable && !text.trimmed().isEmpty() && room.left >= 0 && !key.isEmpty());
}

void AirPrivateThread::send()
{
    refreshSend();
    if (!sendButton->isEnabled()) {
        return;
    }
    emit sendRequested(key, field->text());
}
