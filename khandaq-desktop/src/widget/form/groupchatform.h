/*
    Copyright © 2014-2019 by The qTox Project Contributors

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

#pragma once

#include "genericchatform.h"
#include "src/core/groupid.h"
#include "src/core/toxpk.h"
#include "src/model/air/airgroup.h"
#include "src/persistence/igroupsettings.h"

#include <QPointer>
#include <QTimer>

namespace Ui {
class MainWindow;
}
class AirGetNowBand;
class AirGroupDossier;
class AirLocal;
class AirSource;
class AirPrivateThread;
class Group;
class TabCompleter;
class IMessageDispatcher;
struct AirGroupInfo;
struct Message;
class Settings;
class DocumentCache;
class SmileyPack;
class Style;
class IMessageBoxManager;
class FriendList;
class GroupList;

/**
 * The conversation of a group (E13 to E15). Its head says how many are in the group and how many
 * are online now, with the members who get a message now under it; the line under the field says
 * the same for the next message, and the field counts down the last tenth of a group message. Who
 * may not write gets a read-only bar with a way to write to a moderator. The title unfolds the
 * group dossier: the roles in words, the members and what the founder and the moderators may do to
 * them, the key, Invite and Leave.
 */
class GroupChatForm : public GenericChatForm
{
    Q_OBJECT
public:
    GroupChatForm(Core& core_, Group* chatGroup, IChatLog& chatLog_,
        IMessageDispatcher& messageDispatcher_, Settings& settings_,
        DocumentCache& documentCache, SmileyPack& smileyPack, Style& style,
            IMessageBoxManager& messageBoxManager, FriendList& friendList,
            GroupList& groupList);
    ~GroupChatForm();

    void peerAudioPlaying(ToxPk peerPk);
    /**
     * The marks kept on this device (the mutes and the private messages with members) and the
     * state of our own node, which the line under the field names while the group is not connected.
     */
    void setAir(AirLocal* local, AirSource* source);
    /** Unfolds the private messages with the member who has this key in the group. */
    void openPrivate(const QString& peerKey);
    /** Whether the private messages with this member are unfolded now. */
    bool isPrivateOpen(const QString& peerKey) const;
#ifdef KHANDAQ_SCREENSHOT_HOOK
    /** Test builds only: asks about an action on the member with this name, as their menu does. */
    void askMemberActionForShots(const QString& name, Air::MemberAction action);
#endif

signals:
    /** Leaving was confirmed in the dossier: the main window leaves the group. */
    void leaveRequested(const GroupId& groupId);

private slots:
    void onScreenshotClicked() override;
    void onAttachClicked() override;
    void onMicMuteToggle();
    void onVolMuteToggle();
    void onCallClicked();
    void onUserJoined(const ToxPk& user, const QString& name);
    void onUserLeft(const ToxPk& user, const QString& name);
    void onPeerNameChanged(const ToxPk& peer, const QString& oldName, const QString& newName);
    void onTitleChanged(const QString& author, const QString& title);

protected:
    void keyPressEvent(QKeyEvent* ev) final;
    void keyReleaseEvent(QKeyEvent* ev) final;
    // drag & drop
    void dragEnterEvent(QDragEnterEvent* ev) final;
    void dropEvent(QDropEvent* ev) final;
    void showEvent(QShowEvent* event) override;
    void hideEvent(QHideEvent* event) override;
    bool canSendNow() const override;

private:
    void retranslateUi();
    /** Asks toxcore again who is in the group and what you may do, and shows it everywhere. */
    void refreshMembers();
    void refreshCounter();
    void toggleDossier();
    AirGroupInfo dossierInfo() const;
    QVector<Air::InviteCandidate> inviteCandidates() const;
    QString groupKey() const;
    /** The peer number the member with this key has now; -1 once they are gone. */
    int peerIdOf(const QString& peerKey) const;
    void applyMemberAction(const Air::GroupMember& member, Air::MemberAction action);
    void undoRole(const Air::GroupMember& member, Air::GroupRole role);
    void inviteContacts(const QVector<Air::InviteCandidate>& chosen);
    void askToWrite();
    void showPrivate(const QString& name, const QString& peerKey);
    /** Whether the private messages may be answered: not while you are an observer. */
    void refreshPrivateRight();
    void sendPrivate(const QString& peerKey, const QString& text);
    void joinGroupCall();
    void leaveGroupCall();
    void sendFilesToGroup(const QStringList& paths);
    QByteArray compressImageForGroup(const QString& path, QString* sendName) const;

private:
    Core& core;
    Group* group;
    TabCompleter* tabber;
    bool inCall;
    Settings& settings;
    Style& style;
    FriendList& friendList;
    QPointer<AirLocal> airLocal;
    QPointer<AirSource> airSource;
    AirGetNowBand* band = nullptr;
    AirGroupDossier* dossier = nullptr;
    AirPrivateThread* privateThread = nullptr;
    /** The roster toxcore gave last, by role, online first. */
    QVector<Air::GroupMember> members;
    Air::GroupReach reach;
    /** The state of our own node when the roster was read last. */
    Air::NetworkState selfNetwork = Air::NetworkState::Direct;
    Air::GroupRole selfRole = Air::GroupRole::Member;
    Air::GroupVoice voice = Air::GroupVoice::All;
    Air::ComposeRight right = Air::ComposeRight::Write;
    /** Changes that come together are shown once. */
    QTimer refreshLater;
    /** Presence and the connection to the group have no signal: asked again while it shows. */
    QTimer refreshTicker;
};
