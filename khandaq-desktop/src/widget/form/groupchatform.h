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
#include "src/model/air/aircommunity.h"
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
    /**
     * KHANDAQ (0.2.60): invites the chosen contacts to the group; the invitation of a contact
     * offline now is kept in local, which sends it once they are online (Widget::sendOutgoingInvites).
     */
    static Air::InviteTally sendInvites(Core& core, AirLocal* local, uint32_t groupNumber,
                                        const GroupId& groupId,
                                        const QVector<Air::InviteCandidate>& chosen);
    /** Says how the invitations went: a toast, or a notice over dialogOn when any was refused. */
    static void tellInvites(QWidget* toastOn, QWidget* dialogOn, const Air::InviteTally& tally);
#ifdef KHANDAQ_SCREENSHOT_HOOK
    /** Test builds only: asks about an action on the member with this name, as their menu does. */
    void askMemberActionForShots(const QString& name, Air::MemberAction action);
#endif

signals:
    /** Leaving was confirmed in the dossier: the main window leaves the group. */
    void leaveRequested(const GroupId& groupId);
    /** KHANDAQ (0.2.57): Accept on a file of the group held for a tap: the main window writes it. */
    void heldFileAccepted(const GroupId& groupId, const ToxFile& file);

private slots:
    void onScreenshotClicked() override;
    void onAttachClicked() override;
    /**
     * KHANDAQ (0.2.60): files pasted into the field go to the group (they were dropped), once
     * Send is pressed over their list (beta ideas 2026-11-20).
     */
    void onPasteFiles(const QStringList& paths) override;
    void onMicMuteToggle();
    void onVolMuteToggle();
    void onCallClicked();
    void onUserJoined(const ToxPk& user, const QString& name);
    void onUserLeft(const ToxPk& user, const QString& name);
    void onPeerNameChanged(const ToxPk& peer, const QString& oldName, const QString& newName);
    void onTitleChanged(const QString& author, const QString& title);

protected:
    void sendPreparedFiles(const QStringList& paths) override;
    void keyPressEvent(QKeyEvent* ev) final;
    void keyReleaseEvent(QKeyEvent* ev) final;
    // drag & drop
    void dragEnterEvent(QDragEnterEvent* ev) final;
    void dropEvent(QDropEvent* ev) final;
    void showEvent(QShowEvent* event) override;
    void hideEvent(QHideEvent* event) override;
    bool canSendNow() const override;
    /** Report shows on a message or a file someone else sent (SPEC-community, item 5). */
    void prepareChatMenu(QPoint globalPos) override;
    /**
     * KHANDAQ (0.2.58): a member by their name in the group and their key in it; us by ours, as
     * the members know us.
     */
    ReplyAuthor replyAuthorOf(const ChatLogItem& item) const override;
    /**
     * KHANDAQ (0.2.63): a message or a file with its id and a known author, while we are more
     * than an observer: toxcore carries no custom packet of an observer.
     */
    bool canReactTo(ChatLogIdx idx) const override;
    /**
     * KHANDAQ (0.2.63): the entry shows it, and it goes to the group once (packet 0x43), naming
     * whose the entry is (replyAuthorOf): one that does not go is not sent again, as on Android.
     */
    void reactTo(ChatLogIdx idx, const QString& emoji) override;

private:
    void retranslateUi();
    /** Whether the entry is someone else's message or file; if so it is the one Report mails. */
    bool prepareReport(ChatLogIdx idx);
    /** Whether the key is ours: the key of our profile, or the one this group knows us by. */
    bool isSelfKey(const ToxPk& key) const;
    /**
     * KHANDAQ (0.2.58): a click on the name over someone else's entry opens the menu their row in
     * the dossier opens, under the name; for someone no longer in the group, what their key allows.
     */
    void showAuthorMenu(ChatLogIdx idx, const QPoint& globalPos);
    /** Opens the mail to support about menuReport in the person's mail app. */
    void report();
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
    /** The founder's switch: toxcore sets who can join, or the reason shows. */
    void setPrivacy(Air::GroupPrivacy privacy);
    /**
     * KHANDAQ (beta feedback 2026-11-22): our name in this group only, from the dossier: toxcore
     * takes it and the profile keeps it (Core::setGroupSelfName), or the reason shows.
     */
    void setNameInGroup(const QString& name);
    void inviteContacts(const QVector<Air::InviteCandidate>& chosen);
    void askToWrite();
    void showPrivate(const QString& name, const QString& peerKey);
    /** Whether the private messages may be answered: not while you are an observer. */
    void refreshPrivateRight();
    void sendPrivate(const QString& peerKey, const QString& text);
    void joinGroupCall();
    void leaveGroupCall();
    void sendFilesToGroup(const QStringList& paths);
    /**
     * KHANDAQ (beta ideas 2026-11-20): files chosen, pasted or dropped are listed in a dialog and
     * go on Send (offerFiles); who may not write is not asked, as no file of theirs goes.
     */
    void askToSendFiles(const QStringList& paths);
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
    QAction* reportAction = nullptr;
    /** The entry the menu was opened on, as Report mails it. */
    Air::GroupReport menuReport;
};
