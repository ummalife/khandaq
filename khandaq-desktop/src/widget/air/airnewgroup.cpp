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

#include "airnewgroup.h"

#include "src/model/air/airmodel.h"
#include "src/widget/air/airgroupviews.h"
#include "src/widget/air/airnewcontact.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/translator.h"

#include <QLabel>
#include <QStyle>
#include <QTextEdit>
#include <QVBoxLayout>

using AirStrings::Text;
using AirTokens::Color;
using AirTokens::TextRole;
namespace Space = AirTokens::Space;

namespace {
const AirStyle& air()
{
    return AirStyle::instance();
}

QLabel* makeLabel(TextRole role, Color color, QWidget* parent)
{
    auto* const label = new QLabel(parent);
    label->setTextFormat(Qt::PlainText);
    label->setWordWrap(true);
    label->setFont(air().font(role));
    AirPaint::textColor(label, air().color(color));
    return label;
}

QVBoxLayout* block(int spacing)
{
    auto* const box = new QVBoxLayout();
    box->setContentsMargins(0, 0, 0, 0);
    box->setSpacing(spacing);
    return box;
}
} // namespace

AirNewGroup::AirNewGroup(Lookup lookup_, QWidget* column_, QWidget* parent)
    : AirDropPanel(column_, parent)
    , lookup{std::move(lookup_)}
{
    setObjectName(QStringLiteral("newGroup"));
    closer()->setObjectName(QStringLiteral("newGroupClose"));
    QWidget* const pageWidget = AirDropPanel::page();
    QVBoxLayout* const sheet = pageLayout();

    // ---- New group ----
    createPage = new QWidget(pageWidget);
    auto* const form = new QVBoxLayout(createPage);
    form->setContentsMargins(0, 0, 0, 0);
    form->setSpacing(Space::s20);
    sheet->addWidget(createPage);

    // The name: one line, what everyone invited sees.
    QVBoxLayout* box = block(Space::s6);
    nameLabel = makeLabel(TextRole::Label, Color::Ink2, createPage);
    nameField = new AirTextField(AirTextField::Kind::Line, createPage);
    nameField->setObjectName(QStringLiteral("newGroupName"));
    nameNote = makeLabel(TextRole::Caption, Color::Ink3, createPage);
    box->addWidget(nameLabel);
    box->addWidget(nameField);
    box->addWidget(nameNote);
    form->addLayout(box);

    // Who can join, each choice with what follows from it.
    box = block(Space::s4);
    privacyLabel = makeLabel(TextRole::Label, Color::Ink2, createPage);
    inviteOnlyRow = new AirRadioRow(createPage);
    inviteOnlyRow->setObjectName(QStringLiteral("newGroupInviteOnly"));
    openRow = new AirRadioRow(createPage);
    openRow->setObjectName(QStringLiteral("newGroupOpen"));
    box->addWidget(privacyLabel);
    box->addSpacing(Space::s2);
    box->addWidget(inviteOnlyRow);
    box->addWidget(openRow);
    form->addLayout(box);

    // The contacts online to invite.
    box = block(Space::s6);
    inviteLabel = makeLabel(TextRole::Label, Color::Ink2, createPage);
    inviteNote = makeLabel(TextRole::Caption, Color::Ink3, createPage);
    inviteList = new AirInviteList(createPage);
    box->addWidget(inviteLabel);
    box->addWidget(inviteNote);
    box->addWidget(inviteList);
    form->addLayout(box);

    // "Create group", and why it waits while it does.
    box = block(Space::s6);
    createError = makeLabel(TextRole::Caption, Color::Danger, createPage);
    createButton = new AirButton(AirButton::Kind::Ink, createPage);
    createButton->setObjectName(QStringLiteral("newGroupCreate"));
    createWhy = makeLabel(TextRole::Caption, Color::Ink3, createPage);
    createWhy->setObjectName(QStringLiteral("newGroupWhy"));
    box->addWidget(createError);
    box->addWidget(createButton, 0, Qt::AlignLeading);
    box->addWidget(createWhy);
    form->addLayout(box);

    joinLink = new AirButton(AirButton::Kind::Quiet, createPage);
    joinLink->setObjectName(QStringLiteral("newGroupJoinLink"));
    form->addWidget(joinLink, 0, Qt::AlignLeading);

    // ---- Join by key ----
    joinPage = new QWidget(pageWidget);
    auto* const joining = new QVBoxLayout(joinPage);
    joining->setContentsMargins(0, 0, 0, 0);
    joining->setSpacing(Space::s20);
    sheet->addWidget(joinPage);

    box = block(Space::s6);
    keyLabel = makeLabel(TextRole::Label, Color::Ink2, joinPage);
    keyField = new AirTextField(AirTextField::Kind::Key, joinPage);
    keyField->setObjectName(QStringLiteral("joinGroupKey"));
    keyNote = makeLabel(TextRole::Caption, Color::Ink3, joinPage);
    alreadyLabel = makeLabel(TextRole::Caption, Color::Ink2, joinPage);
    openButton = new AirButton(AirButton::Kind::Quiet, joinPage);
    openButton->setObjectName(QStringLiteral("joinGroupOpen"));
    box->addWidget(keyLabel);
    box->addWidget(keyField);
    box->addWidget(keyNote);
    box->addWidget(alreadyLabel);
    box->addWidget(openButton, 0, Qt::AlignLeading);
    joining->addLayout(box);

    box = block(Space::s6);
    joinError = makeLabel(TextRole::Caption, Color::Danger, joinPage);
    joinButton = new AirButton(AirButton::Kind::Ink, joinPage);
    joinButton->setObjectName(QStringLiteral("joinGroupJoin"));
    joinWhy = makeLabel(TextRole::Caption, Color::Ink3, joinPage);
    box->addWidget(joinError);
    box->addWidget(joinButton, 0, Qt::AlignLeading);
    box->addWidget(joinWhy);
    joining->addLayout(box);

    createLink = new AirButton(AirButton::Kind::Quiet, joinPage);
    createLink->setObjectName(QStringLiteral("joinGroupCreateLink"));
    joining->addWidget(createLink, 0, Qt::AlignLeading);
    sheet->addStretch(1);

    connect(nameField, &AirTextField::changed, this, [this] {
        createError->hide();
        refreshCreate();
    });
    connect(nameField, &AirTextField::submitted, this, &AirNewGroup::create);
    connect(inviteList, &AirInviteList::choiceChanged, this, [this] { refreshCreate(); });
    connect(createButton, &AirButton::clicked, this, &AirNewGroup::create);
    connect(joinLink, &AirButton::clicked, this, [this] {
        resetJoin();
        showPage(Page::Join);
        focusFirst();
    });
    connect(keyField, &AirTextField::changed, this, [this] {
        joinError->hide();
        refreshJoin();
    });
    connect(keyField, &AirTextField::submitted, this, &AirNewGroup::join);
    connect(joinButton, &AirButton::clicked, this, &AirNewGroup::join);
    connect(openButton, &AirButton::clicked, this, [this] {
        const QString key = groupKey();
        close();
        emit openGroupRequested(key);
    });
    connect(createLink, &AirButton::clicked, this, [this] {
        showPage(Page::Create);
        focusFirst();
    });

    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirNewGroup::restyle);
    Translator::registerHandler(std::bind(&AirNewGroup::retranslate, this), this);
    resetCreate();
    resetJoin();
    showPage(Page::Create);
    restyle();
    retranslate();
}

AirNewGroup::~AirNewGroup()
{
    Translator::unregister(this);
}

void AirNewGroup::openCreate(const QString& inviteKey)
{
    // Every opening starts afresh, with By invitation only chosen.
    if (!isOpen() || page != Page::Create) {
        resetCreate();
    }
    showPage(Page::Create);
    if (!inviteKey.isEmpty()) {
        inviteList->choose(inviteKey.toUpper());
    }
    refreshCreate();
    unfold();
    focusFirst();
}

void AirNewGroup::openJoin(const QString& text)
{
    if (!isOpen() || page != Page::Join) {
        resetJoin();
    }
    showPage(Page::Join);
    if (!text.isEmpty()) {
        keyField->setText(text);
        refreshJoin();
    }
    unfold();
    focusFirst();
}

void AirNewGroup::refreshCandidates()
{
    if (isOpen() && page == Page::Create && lookup.candidates) {
        inviteList->setCandidates(lookup.candidates());
        refreshCreate();
    }
}

void AirNewGroup::createFailed()
{
    createError->setText(AirStrings::text(Text::NewGroupFailed));
    createError->show();
    place();
}

void AirNewGroup::joinFailed()
{
    joinError->setText(AirStrings::text(Text::InviteJoinFailed));
    joinError->show();
    place();
}

void AirNewGroup::showPage(Page page_)
{
    page = page_;
    createPage->setVisible(page == Page::Create);
    joinPage->setVisible(page == Page::Join);
    setTitle(AirStrings::text(page == Page::Create ? Text::NewGroupTitle : Text::JoinTitle));
    place();
}

void AirNewGroup::resetCreate()
{
    nameField->setText(QString());
    inviteOnlyRow->setChecked(true);
    inviteList->clearChoice();
    if (lookup.candidates) {
        inviteList->setCandidates(lookup.candidates());
    }
    createError->hide();
    refreshCreate();
}

void AirNewGroup::resetJoin()
{
    keyField->setText(QString());
    joinError->hide();
    refreshJoin();
}

void AirNewGroup::retranslate()
{
    setTitle(AirStrings::text(page == Page::Create ? Text::NewGroupTitle : Text::JoinTitle));
    nameLabel->setText(AirStrings::text(Text::NewGroupName));
    nameField->setPlaceholder(AirStrings::text(Text::NewGroupNamePlaceholder));
    nameField->edit()->setAccessibleName(AirStrings::text(Text::NewGroupName));
    nameNote->setText(AirStrings::text(Text::NewGroupNameNote));
    privacyLabel->setText(AirStrings::text(Text::NewGroupPrivacy));
    inviteOnlyRow->setText(AirStrings::groupPrivacyText(Air::GroupPrivacy::InviteOnly));
    inviteOnlyRow->setNote(AirStrings::groupPrivacyNote(Air::GroupPrivacy::InviteOnly));
    openRow->setText(AirStrings::groupPrivacyText(Air::GroupPrivacy::Open));
    openRow->setNote(AirStrings::groupPrivacyNote(Air::GroupPrivacy::Open));
    inviteLabel->setText(AirStrings::text(Text::GroupInvite));
    inviteNote->setText(AirStrings::text(Text::NewGroupInviteNote));
    joinLink->setText(AirStrings::text(Text::NewGroupJoinLink));
    keyLabel->setText(AirStrings::text(Text::GroupKey));
    keyField->edit()->setAccessibleName(AirStrings::text(Text::GroupKey));
    keyNote->setText(AirStrings::text(Text::JoinKeyNote));
    alreadyLabel->setText(AirStrings::text(Text::JoinAlready));
    openButton->setText(AirStrings::text(Text::JoinOpen));
    joinButton->setText(AirStrings::text(Text::InviteJoin));
    createLink->setText(AirStrings::text(Text::NewGroupCreateLink));
    if (createError->isVisibleTo(this)) {
        createError->setText(AirStrings::text(Text::NewGroupFailed));
    }
    if (joinError->isVisibleTo(this)) {
        joinError->setText(AirStrings::text(Text::InviteJoinFailed));
    }
    // Every text of the panel stands at the start edge of the interface.
    const Qt::Alignment start =
        QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter);
    for (QLabel* const label : AirDropPanel::page()->findChildren<QLabel*>()) {
        label->setAlignment(start);
    }
    refreshCreate();
    refreshJoin();
}

void AirNewGroup::restyle()
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
        {nameLabel, TextRole::Label, Color::Ink2},
        {nameNote, TextRole::Caption, Color::Ink3},
        {privacyLabel, TextRole::Label, Color::Ink2},
        {inviteLabel, TextRole::Label, Color::Ink2},
        {inviteNote, TextRole::Caption, Color::Ink3},
        {createError, TextRole::Caption, Color::Danger},
        {createWhy, TextRole::Caption, Color::Ink3},
        {keyLabel, TextRole::Label, Color::Ink2},
        {keyNote, TextRole::Caption, Color::Ink3},
        {alreadyLabel, TextRole::Caption, Color::Ink2},
        {joinError, TextRole::Caption, Color::Danger},
        {joinWhy, TextRole::Caption, Color::Ink3},
    };
    for (const auto& text : texts) {
        text.label->setFont(air().font(text.role));
        AirPaint::textColor(text.label, air().color(text.color));
    }
    update();
}

void AirNewGroup::refreshCreate()
{
    const QString name = Air::cleanGroupName(nameField->text());
    const int over = Air::groupNameOverflow(nameField->text());
    const int invited = inviteList->chosen().size();
    createButton->setText(invited > 0 ? AirStrings::text(Text::NewGroupCreateInvite, invited)
                                      : AirStrings::text(Text::NewGroupCreate));
    nameField->setInvalid(over > 0);
    QString why;
    if (name.isEmpty()) {
        why = AirStrings::text(Text::NewGroupNameFirst);
    } else if (over > 0) {
        why = AirStrings::text(Text::NewGroupNameTooLong);
    }
    createButton->setEnabled(why.isEmpty());
    createWhy->setText(why);
    createWhy->setVisible(!why.isEmpty());
}

QString AirNewGroup::groupKey() const
{
    return Air::cleanHex(keyField->text(), Air::toxKeyHexLength);
}

void AirNewGroup::refreshJoin()
{
    const QString key = groupKey();
    const bool whole = key.size() == Air::toxKeyHexLength;
    const bool known = whole && lookup.hasGroup && lookup.hasGroup(key);
    alreadyLabel->setVisible(known);
    openButton->setVisible(known);
    joinButton->setEnabled(whole && !known);
    const bool explain = !whole && !key.isEmpty();
    joinWhy->setText(explain ? AirStrings::text(Text::JoinKeyBad) : QString());
    joinWhy->setVisible(explain);
}

void AirNewGroup::create()
{
    refreshCreate();
    if (!createButton->isEnabled()) {
        return;
    }
    const Air::GroupPrivacy privacy =
        openRow->isChecked() ? Air::GroupPrivacy::Open : Air::GroupPrivacy::InviteOnly;
    emit createRequested(Air::cleanGroupName(nameField->text()), privacy, inviteList->chosen());
}

void AirNewGroup::join()
{
    refreshJoin();
    if (!joinButton->isEnabled()) {
        return;
    }
    emit joinRequested(groupKey());
}

void AirNewGroup::focusFirst()
{
    AirTextField* const field = page == Page::Create ? nameField : keyField;
    field->edit()->setFocus(Qt::OtherFocusReason);
}
