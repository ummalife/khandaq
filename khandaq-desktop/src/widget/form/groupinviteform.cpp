/*
    Copyright © 2015-2019 by The qTox Project Contributors

    This file is part of qTox, a Qt-based graphical interface for Tox.

    qTox is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    qTox is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with qTox.  If not, see <http://www.gnu.org/licenses/>.
*/

#include "groupinviteform.h"

#include "ui_mainwindow.h"
#include "src/core/core.h"
#include "src/model/groupinvite.h"
#include "src/persistence/settings.h"
#include "src/widget/contentlayout.h"
#include "src/widget/form/groupinvitewidget.h"
#include "src/widget/translator.h"

#include <QDateTime>
#include <QDebug>
#include <QGroupBox>
#include <QLabel>
#include <QLineEdit>
#include <QPushButton>
#include <QRegularExpression>
#include <QVBoxLayout>
#include <QWindow>

#include <algorithm>
#include <limits>

#include <tox/tox.h>

/**
 * @class GroupInviteForm
 *
 * @brief This form contains all group invites you received
 */

GroupInviteForm::GroupInviteForm(Settings& settings_, Core& core_)
    : headWidget(new QWidget(this))
    , headLabel(new QLabel(this))
    , createButton(new QPushButton(this))
    , joinBox(new QGroupBox(this))
    , joinGroupIdEdit(new QLineEdit(this))
    , joinButton(new QPushButton(this))
    , joinHintLabel(new QLabel(this))
    , inviteBox(new QGroupBox(this))
    , scroll(new QScrollArea(this))
    , settings{settings_}
    , core{core_}
{
    QVBoxLayout* layout = new QVBoxLayout(this);
    connect(createButton, &QPushButton::clicked, this, [this]() { emit groupCreate(); });

    QVBoxLayout* joinLayout = new QVBoxLayout(joinBox);
    joinGroupIdEdit->setPlaceholderText(QStringLiteral("b8a36631e8b2351d5704df1043cf34e95ea7c6d8777bc925a0aa5254d697e41f"));
    joinGroupIdEdit->setClearButtonEnabled(true);
    joinHintLabel->setWordWrap(true);
    joinHintLabel->setStyleSheet(QStringLiteral("color: gray;"));
    joinLayout->addWidget(joinGroupIdEdit);
    joinLayout->addWidget(joinHintLabel);
    joinLayout->addWidget(joinButton);
    connect(joinButton, &QPushButton::clicked, this, &GroupInviteForm::onJoinGroupClicked);
    connect(joinGroupIdEdit, &QLineEdit::textChanged, this, &GroupInviteForm::onJoinGroupIdChanged);

    QWidget* innerWidget = new QWidget(scroll);
    innerWidget->setLayout(new QVBoxLayout());
    innerWidget->layout()->setAlignment(Qt::AlignTop);
    scroll->setWidget(innerWidget);
    scroll->setWidgetResizable(true);

    QVBoxLayout* inviteLayout = new QVBoxLayout(inviteBox);
    inviteLayout->addWidget(scroll);

    layout->addWidget(createButton);
    layout->addWidget(joinBox);
    layout->addWidget(inviteBox);

    QFont bold;
    bold.setBold(true);

    headLabel->setFont(bold);
    QHBoxLayout* headLayout = new QHBoxLayout(headWidget);
    headLayout->addWidget(headLabel);

    retranslateUi();
    updateJoinButtonState();
    Translator::registerHandler(std::bind(&GroupInviteForm::retranslateUi, this), this);
}

GroupInviteForm::~GroupInviteForm()
{
    Translator::unregister(this);
}

void GroupInviteForm::onJoinGroupIdChanged(const QString& text)
{
    std::ignore = text;
    updateJoinButtonState();
}

void GroupInviteForm::updateJoinButtonState()
{
    QString hex = joinGroupIdEdit->text();
    hex.remove(QRegularExpression(QStringLiteral(R"(\s)")));
    const QString cleaned = hex;
    hex.remove(QRegularExpression(QStringLiteral(R"([^a-fA-F0-9])")));

    if (cleaned.isEmpty()) {
        joinHintLabel->clear();
        joinButton->setEnabled(false);
        return;
    }

    if (cleaned.compare(hex, Qt::CaseInsensitive) != 0) {
        joinHintLabel->setText(tr("Group ID may only contain A-F and 0-9."));
        joinButton->setEnabled(false);
        return;
    }

    if (hex.size() != TOX_GROUP_CHAT_ID_SIZE * 2) {
        joinHintLabel->setText(tr("Group ID must be 64 hex characters (%1/64).").arg(hex.size()));
        joinButton->setEnabled(false);
        return;
    }

    joinHintLabel->clear();
    joinButton->setEnabled(true);
}

void GroupInviteForm::onJoinGroupClicked()
{
    const uint32_t groupNum = core.joinGroupByChatIdHex(joinGroupIdEdit->text());
    if (groupNum == std::numeric_limits<uint32_t>::max()) {
        emit groupJoinFailed(tr("Could not join group. Check the Group ID and try again."));
        return;
    }

    joinGroupIdEdit->clear();
    joinHintLabel->clear();
    emit groupJoinSucceeded();
}

/**
 * @brief Detects that form is shown
 * @return True if form is visible
 */
bool GroupInviteForm::isShown() const
{
    bool result = isVisible();
    if (result) {
        headWidget->window()->windowHandle()->alert(0);
    }
    return result;
}

/**
 * @brief Shows the form
 * @param contentLayout Main layout that contains all components of the form
 */
void GroupInviteForm::show(ContentLayout* contentLayout)
{
    contentLayout->mainContent->layout()->addWidget(this);
    contentLayout->mainHead->layout()->addWidget(headWidget);
    QWidget::show();
    headWidget->show();
}

/**
 * @brief Adds group invite
 * @param inviteInfo Object which contains info about group invitation
 * @return true if notification is needed, false otherwise
 */
bool GroupInviteForm::addGroupInvite(const GroupInvite& inviteInfo)
{
    // supress duplicate invite messages
    for (GroupInviteWidget* existing : invites) {
        if (existing->getInviteInfo().getInvite() == inviteInfo.getInvite()) {
            return false;
        }
    }

    GroupInviteWidget* widget = new GroupInviteWidget(this, inviteInfo, settings,
        core);
    scroll->widget()->layout()->addWidget(widget);
    invites.append(widget);
    connect(widget, &GroupInviteWidget::accepted, [this] (const GroupInvite& inviteInfo_) {
        deleteInviteWidget(inviteInfo_);
        emit groupInviteAccepted(inviteInfo_);
    });

    connect(widget, &GroupInviteWidget::rejected, [this] (const GroupInvite& inviteInfo_) {
        deleteInviteWidget(inviteInfo_);
    });
    if (isVisible()) {
        emit groupInvitesSeen();
        return false;
    }
    return true;
}

void GroupInviteForm::showEvent(QShowEvent* event)
{
    QWidget::showEvent(event);
    emit groupInvitesSeen();
}

/**
 * @brief Deletes accepted/declined group invite widget
 * @param inviteInfo Invite information of accepted/declined widget
 */
void GroupInviteForm::deleteInviteWidget(const GroupInvite& inviteInfo)
{
    auto deletingWidget =
        std::find_if(invites.begin(), invites.end(), [=](const GroupInviteWidget* widget) {
            return inviteInfo == widget->getInviteInfo();
        });
    (*deletingWidget)->deleteLater();
    scroll->widget()->layout()->removeWidget(*deletingWidget);
    invites.erase(deletingWidget);
}

void GroupInviteForm::retranslateUi()
{
    headLabel->setText(tr("Groups"));
    if (createButton) {
        createButton->setText(tr("Create new group"));
    }
    if (joinBox) {
        joinBox->setTitle(tr("Join group by ID"));
    }
    if (joinButton) {
        joinButton->setText(tr("Join"));
    }
    if (joinHintLabel && joinHintLabel->text().contains('/')) {
        updateJoinButtonState();
    }
    inviteBox->setTitle(tr("Group invites"));
    for (GroupInviteWidget* invite : invites) {
        invite->retranslateUi();
    }
}
