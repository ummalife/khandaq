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

#include "src/model/air/airgroup.h"
#include "src/model/air/airlocalstore.h"
#include "src/widget/air/airpanels.h"

#include <QSet>
#include <QVector>

class AirButton;
class AirInviteList;
class AirMemberRow;
class AirSwitch;
class AirTextField;
class AirTile;
class QBoxLayout;
class QLabel;
class QVBoxLayout;

/** Everything the group dossier says about one group. */
struct AirGroupInfo
{
    QString name;
    /** The group's key, its chat id: 64 hexadecimal characters in upper case. */
    QString groupKey;
    int tone = 0;
    Air::GroupReach reach;
    Air::GroupRole selfRole = Air::GroupRole::Member;
    Air::GroupVoice voice = Air::GroupVoice::All;
    Air::GroupPrivacy privacy = Air::GroupPrivacy::InviteOnly;
    /** Everyone in the group, you included, in the order of the roster. */
    QVector<Air::GroupMember> members;
    /** The keys of the members whose messages come without a notification. */
    QSet<QString> mutedMembers;
    /** The keys of the members whose messages and voice this device does not show. */
    QSet<QString> hiddenMembers;
    bool muted = false;
    /** The contacts the Invite list offers. */
    QVector<Air::InviteCandidate> candidates;
};

/**
 * The dossier of a group (E14): a fold panel over the conversation, as high as the column lets it
 * be, so the members have room. In order: the group with its counts; your role in words; who gets
 * a message now (or the members, while you may not write), by role and online first, with a search
 * from 20 members; the group key with Copy, Invite and QR and who can join; notifications; Leave
 * the group with its consequence. A member's row opens what may be done to them, each asked once.
 */
class AirGroupDossier : public AirFoldPanel
{
    Q_OBJECT
public:
    explicit AirGroupDossier(QWidget* parent);
    ~AirGroupDossier() override;

    void setInfo(const AirGroupInfo& info);
    const AirGroupInfo& groupInfo() const;
#ifdef KHANDAQ_SCREENSHOT_HOOK
    /** Test builds only: the question before an action on a member, as their menu asks it. */
    void askForShots(const Air::GroupMember& member, Air::MemberAction action);
#endif

signals:
    void renameRequested();
    void muteChanged(bool muted);
    /** Leaving was confirmed. */
    void leaveRequested();
    /** A change of role or a removal was confirmed. */
    void memberActionRequested(const Air::GroupMember& member, Air::MemberAction action);
    void writePrivatelyRequested(const Air::GroupMember& member);
    void memberMuteRequested(const Air::GroupMember& member, bool muted);
    void memberHideRequested(const Air::GroupMember& member, bool hidden);
    void inviteRequested(const QVector<Air::InviteCandidate>& chosen);

protected:
    void arrange(int width) override;
    void aboutToFold() override;

private:
    QVBoxLayout* section(QBoxLayout* column, QLabel** title);
    void retranslate();
    void restyle();
    void refreshMembers();
    void refreshInvite();
    void showMemberMenu(AirMemberRow* row);
    void confirm(const Air::GroupMember& member, Air::MemberAction action);

    AirGroupInfo info;
    QString query;
    QBoxLayout* columns = nullptr;

    AirTile* tile = nullptr;
    QLabel* nameLabel = nullptr;
    QLabel* countsLabel = nullptr;
    AirButton* renameButton = nullptr;

    QLabel* roleTitle = nullptr;
    QLabel* roleText = nullptr;

    QLabel* membersTitle = nullptr;
    QLabel* membersNote = nullptr;
    AirTextField* search = nullptr;
    QWidget* membersHolder = nullptr;
    QVBoxLayout* membersList = nullptr;
    /** The roster the rows show now, so a refresh that changes nothing keeps them. */
    QVector<Air::GroupMember> shownMembers;

    QLabel* keyTitle = nullptr;
    QLabel* keyText = nullptr;
    QLabel* privacyTitle = nullptr;
    QLabel* privacyText = nullptr;
    QLabel* privacyNote = nullptr;
    AirButton* copyButton = nullptr;
    AirButton* inviteButton = nullptr;
    AirButton* qrButton = nullptr;

    QWidget* inviteBox = nullptr;
    QLabel* inviteNote = nullptr;
    AirInviteList* inviteList = nullptr;
    AirButton* sendInvites = nullptr;
    AirButton* cancelInvites = nullptr;
    QLabel* inviteWhy = nullptr;

    QLabel* notifyTitle = nullptr;
    QLabel* muteLabel = nullptr;
    AirSwitch* muteSwitch = nullptr;
    QLabel* mutedNote = nullptr;

    AirButton* leaveButton = nullptr;
    QLabel* leaveNote = nullptr;
};

/**
 * Private messages with one member of a group (E13): what "Write to a moderator" and "Write
 * privately" open. toxcore keeps none of them, so this device keeps the last ones
 * (Air::LocalStore::privateLimit) and the panel says so. It folds over the conversation like the
 * dossier.
 */
class AirPrivateThread : public AirFoldPanel
{
    Q_OBJECT
public:
    explicit AirPrivateThread(QWidget* parent);
    ~AirPrivateThread() override;

    /** Shows the conversation with a member: their name and their key in this group. */
    void showMember(const QString& name, const QString& peerKey);
    QString peerKey() const;
    QString peerName() const;
    /** The messages kept so far, oldest first. */
    void setLines(const QVector<Air::LocalStore::PrivateLine>& lines);
    /** The message went: the field empties. */
    void sent();
    /** toxcore refused the message: the text stays and the panel says why. */
    void failed();
    /**
     * Whether you may write here. An observer may not, not even privately: toxcore refuses the
     * message and the other side drops it. The field then gives way to the reason; the messages
     * of the member still come in.
     */
    void setWritable(bool writable, const QString& reason = QString());
    bool isWritable() const;
    /** Puts the keyboard in the field. */
    void focusField();

signals:
    void sendRequested(const QString& peerKey, const QString& text);

protected:
    void aboutToFold() override;

private:
    void retranslate();
    void restyle();
    void rebuildLines();
    void refreshSend();
    void send();

    QString name;
    QString key;
    QVector<Air::LocalStore::PrivateLine> lines;

    QLabel* titleLabel = nullptr;
    QLabel* noteLabel = nullptr;
    QLabel* emptyLabel = nullptr;
    QWidget* linesHolder = nullptr;
    QVBoxLayout* linesList = nullptr;
    AirTextField* field = nullptr;
    QLabel* counter = nullptr;
    QLabel* errorLabel = nullptr;
    QLabel* blockedLabel = nullptr;
    AirButton* sendButton = nullptr;
    bool writable = true;
};
