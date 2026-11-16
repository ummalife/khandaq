/*
    Copyright © 2013 by Maxim Biro <nurupo.contributions@gmail.com>
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

#include "groupid.h"
#include "icorefriendmessagesender.h"
#include "icoregroupmessagesender.h"
#include "icoregroupquery.h"
#include "icoreidhandler.h"
#include "receiptnum.h"
#include "toxfile.h"
#include "toxid.h"
#include "toxpk.h"

#include "util/strongtype.h"
#include "util/compatiblerecursivemutex.h"
#include "src/model/air/airgroupfilequeue.h"
#include "src/model/air/airgroupfilerepair.h"
#include "src/model/air/airgroupleave.h"
#include "src/model/status.h"
#include <tox/tox.h>

#include <QElapsedTimer>
#include <QHash>
#include <QMutex>
#include <QObject>
#include <QSet>
#include <QThread>
#include <QTimer>

#include <functional>
#include <memory>
#include <vector>

class CoreAV;
class CoreFile;
class CoreExt;
class QFile;
class QNetworkConfigurationManager;
class IAudioControl;
class ICoreSettings;
class QNetworkConfigurationManager;
class GroupInvite;
class Profile;
class Core;
class IBootstrapListGenerator;
struct DhtServer;

using ToxCorePtr = std::unique_ptr<Core>;

class Core : public QObject,
             public ICoreFriendMessageSender,
             public ICoreIdHandler,
             public ICoreGroupMessageSender,
             public ICoreGroupQuery
{
    Q_OBJECT
public:
    enum class ToxCoreErrors
    {
        BAD_PROXY,
        INVALID_SAVE,
        FAILED_TO_START,
        ERROR_ALLOC
    };

    static ToxCorePtr makeToxCore(const QByteArray& savedata, const ICoreSettings& settings,
                                  IBootstrapListGenerator& bootstrapNodes, ToxCoreErrors* err = nullptr);
    const CoreAV* getAv() const;
    CoreAV* getAv();
    void setAv(CoreAV* coreAv);

    CoreFile* getCoreFile() const;
    Tox* getTox() const;
    CompatibleRecursiveMutex& getCoreLoopLock() const;

    const CoreExt* getExt() const;
    CoreExt* getExt();
    ~Core();

    static const QString TOX_EXT;
    uint64_t getMaxMessageSize() const;
    QString getPeerName(const ToxPk& id) const;
    QVector<uint32_t> getFriendList() const;
    GroupId getGroupPersistentId(uint32_t groupNumber) const override;
    uint32_t getGroupNumberPeers(int groupId) const override;
    QVector<uint32_t> getGroupPeerList(int groupId) const override;
    QString getGroupPeerName(int groupId, int peerId) const override;
    ToxPk getGroupPeerPk(int groupId, int peerId) const override;
    /** Our own key in a group, which the members know us by; empty when the group is unknown. */
    ToxPk getGroupSelfPublicKey(int groupId) const;
    bool isGroupPeerPresent(int groupId, const ToxPk& peerPk) const;
    QStringList getGroupPeerNames(int groupId) const override;
    QString getGroupName(int groupId) const;
    bool getGroupAvEnabled(int groupId) const override;
    // What the group screens ask toxcore before they act (E13 to E15).
    /** Our own peer number in the group; UINT32_MAX when toxcore does not know it. */
    uint32_t getGroupSelfPeerId(int groupId) const;
    Tox_Group_Role getGroupSelfRole(int groupId) const;
    Tox_Group_Role getGroupPeerRole(int groupId, int peerId) const;
    /** Whether the peer is reachable now: a direct path, or a relay of the groups (E13). */
    bool isGroupPeerOnline(int groupId, int peerId) const;
    /**
     * tox_group_is_connected of this fork: 1 once the group is synced, 0 while it connects (a
     * founder's group back from the save stays there while members come to it), -1 when it is
     * not connected or unknown.
     */
    int getGroupConnection(int groupId) const;
    Tox_Group_Voice_State getGroupVoiceState(int groupId) const;
    Tox_Group_Privacy_State getGroupPrivacyState(int groupId) const;
    /** The longest group message toxcore takes, in bytes: it refuses a longer one. */
    static uint32_t getMaxGroupMessageSize();
    ToxPk getFriendPublicKey(uint32_t friendNumber) const;
    QString getFriendUsername(uint32_t friendNumber) const;

    bool isFriendOnline(uint32_t friendId) const;
    bool hasFriendWithPublicKey(const ToxPk& publicKey) const;
    uint32_t joinGroupchat(const GroupInvite& inviteInfo);
    uint32_t joinGroupByChatId(const QByteArray& chatId);
    uint32_t joinGroupByChatIdHex(const QString& hexId);
    void quitGroupChat(int groupId) const;
    /**
     * QA live F11: the groups whose leave the profile kept (getPendingGroupLeaves), left now and
     * without a notice: the app closed or crashed before their time came. Called right after
     * makeToxCore, before start(), so that none of them is loaded.
     */
    void leaveStoredGroups(const QString& stored);
    /** QA live F11: what the profile keeps of our leaves that are not done and saved yet. */
    QString getPendingGroupLeaves() const;
    /**
     * 0.2.56: the groups joined by their ID that had not synced when the profile last kept them
     * (getUnsyncedGroupJoins). toxcore saves a group only once it synced, so they are joined again
     * now; those it loaded synced meanwhile and are forgotten. Called after leaveStoredGroups,
     * before start(), so that the groups load with them.
     */
    void rejoinStoredGroups(const QString& stored);
    /** 0.2.56: what the profile keeps of the groups joined by their ID that did not sync yet. */
    QString getUnsyncedGroupJoins() const;
    /**
     * QA live F11: every leave that waits for its notice is done now, and toxcore lets go of the
     * groups: the profile closes. Returns once that is done on the thread of the node.
     */
    void leavePendingGroupsNow();
    /**
     * A file packet a member of the group sent to all (isPrivate false) or to us alone (true), as
     * toxcore's custom packet callbacks hand it over on the thread of the node. Public so that the
     * tests can hand packets over without a network.
     */
    void handleNgcFileTransferPacket(uint32_t groupId, uint32_t peerId, const uint8_t* data,
                                     size_t length, bool isPrivate);
    /**
     * 0.2.57: the files of more than one packet that come, at nowMs of a steady clock: one whose
     * chunks stopped asks its sender for those it misses, and one that does not come is given up
     * (Air::FileArrival). process() calls it; public so that the tests can move the clock.
     */
    void checkGroupFileArrivals(qint64 nowMs);

    QString getUsername() const override;
    Status::Status getStatus() const;
    QString getStatusMessage() const;
    ToxId getSelfId() const override;
    ToxPk getSelfPublicKey() const override;

    QByteArray getSelfDhtId() const;
    int getSelfUdpPort() const;

public slots:
    void start();

    QByteArray getToxSaveData();

    void acceptFriendRequest(const ToxPk& friendPk);
    void requestFriendship(const ToxId& friendId, const QString& message);
    /** False when toxcore refused the invitation (the contact went offline, the group is gone). */
    bool groupInviteFriend(uint32_t friendId, int groupId);
    /** A new group with this name and privacy, founded by us; -1 when toxcore refused it. */
    int createGroup(const QString& name, Tox_Group_Privacy_State privacyState);
    /** The founder or a moderator gives a member a role; false when toxcore refused it. */
    bool setGroupPeerRole(int groupId, int peerId, Tox_Group_Role role);
    /** The founder or a moderator removes a member from the group; false when toxcore refused it. */
    bool kickGroupPeer(int groupId, int peerId);
    /**
     * The founder sets who can join; false when toxcore refused it. toxcore calls no privacy
     * callback for this change of our own: the caller shows it.
     */
    bool setGroupPrivacyState(int groupId, Tox_Group_Privacy_State privacyState);
    /** A message only that peer of the group gets; false when toxcore refused it. */
    bool sendGroupPrivateMessage(int groupId, int peerId, const QString& message);

    void removeFriend(uint32_t friendId);
    void removeGroup(int groupId);

    void setStatus(Status::Status status);
    void setUsername(const QString& username);
    void setStatusMessage(const QString& message);

    bool sendMessage(uint32_t friendId, const QString& message, const QByteArray& msgV3Id,
                     quint32 msgV3Time, ReceiptNum& receipt) override;
    SendResult sendGroupMessage(int groupId, const QString& message, uint32_t& wireId) override;
    SendResult sendGroupAction(int groupId, const QString& message, uint32_t& wireId) override;
    void sendGroupDeliveryReceipt(int groupId, const ToxPk& author, uint32_t messageId) override;
    bool sendGroupFile(int groupId, const QString& fileName, const QString& localPath,
                       const QByteArray& fileData);
    /**
     * A file of more than one packet goes as its BEGIN and then its chunks, which the loop of the
     * node sends at their pace (sendGroupFileChunks): groupFileSendStarted, then
     * groupFileSendProgress, then groupFileSent or groupFileSendFailed. False when the file cannot
     * be read, or the group would refuse its packets now (it is not connected, or we only observe
     * there): nothing is sent then.
     */
    bool sendGroupFileFromPath(int groupId, const QString& fileName, const QString& localPath);
    void changeGroupTitle(int groupId, const QString& title);
    bool sendAction(uint32_t friendId, const QString& action, const QByteArray& msgV3Id,
                    quint32 msgV3Time, ReceiptNum& receipt) override;
    void sendTyping(uint32_t friendId, bool typing);
    /**
     * KHANDAQ (0.2.57): sends a friend a read receipt, the lossless packet 189 as Air::seenPacket
     * builds it. False when toxcore refused it, the friend being offline, say.
     */
    bool sendSeenReceipt(uint32_t friendId, const QByteArray& packet);

    void setNospam(uint32_t nospam);

    /**
     * Asks the network again at once, as Connect again in Settings does. It runs on the thread of
     * the node: call it through a queued connection from elsewhere.
     */
    void reconnect();

signals:
    void connected();
    void disconnected();
    /** Our own link changed: a TOX_CONNECTION value (none, a TCP relay, or direct UDP). */
    void selfLinkChanged(int toxConnection);
    /** How many relays the node holds a connection to; looked at every few seconds. */
    void relaysChanged(int relays);
    /** A friend's link changed: a TOX_CONNECTION value, so a row can tell direct from relayed. */
    void friendLinkChanged(uint32_t friendId, int toxConnection);

    void friendRequestReceived(const ToxPk& friendPk, const QString& message);
    void friendAvatarChanged(const ToxPk& friendPk, const QByteArray& pic);
    void friendAvatarRemoved(const ToxPk& friendPk);

    void requestSent(const ToxPk& friendPk, const QString& message);
    void failedToAddFriend(const ToxPk& friendPk, const QString& errorInfo = QString());

    void usernameSet(const QString& username);
    void statusMessageSet(const QString& message);
    void statusSet(Status::Status status);
    void idSet(const ToxId& id);

    void failedToSetUsername(const QString& username);
    void failedToSetStatusMessage(const QString& message);
    void failedToSetStatus(Status::Status status);
    void failedToSetTyping(bool typing);

    void saveRequest();
    /** QA live F11: what the profile keeps of our leaves changed (getPendingGroupLeaves). */
    void pendingGroupLeavesChanged(const QString& stored);
    /** 0.2.56: what the profile keeps of our unsynced joins changed (getUnsyncedGroupJoins). */
    void unsyncedGroupJoinsChanged(const QString& stored);

    /**
     * @deprecated prefer signals using ToxPk
     */

    void fileAvatarOfferReceived(uint32_t friendId, uint32_t fileId, const QByteArray& avatarHash, uint64_t filesize);

    /**
     * msgV3Id: KHANDAQ (0.2.57) the 32-byte id of a text that came with the msgV3 trailer, which
     * read receipts, edits, deletions and reactions point at; empty for a plain text.
     */
    void friendMessageReceived(uint32_t friendId, const QString& message, bool isAction,
                               const QByteArray& msgV3Id);
    void friendAdded(uint32_t friendId, const ToxPk& friendPk);

    void friendStatusChanged(uint32_t friendId, Status::Status status);
    void friendStatusMessageChanged(uint32_t friendId, const QString& message);
    void friendUsernameChanged(uint32_t friendId, const QString& username);
    void friendTypingChanged(uint32_t friendId, bool isTyping);

    void friendRemoved(uint32_t friendId);
    void friendLastSeenChanged(uint32_t friendId, const QDateTime& dateTime);

    void emptyGroupCreated(int groupnumber, const GroupId groupId, const QString& title = QString());
    void groupInviteReceived(const GroupInvite& inviteInfo);
    /**
     * messageId: what the edits, deletions and reactions of the group point at (stage 3, item 10).
     * author: the member's key in the group, read as the message came (onGroupMessage).
     */
    void groupMessageReceived(int groupnumber, const ToxPk& author, const QString& message,
                              bool isAction, uint32_t messageId);
    /** An edit, a deletion or a reaction a friend sent: lossless packet 186, 187 or 188, as it came. */
    void friendMessageEffect(uint32_t friendId, const QByteArray& packet);
    /** KHANDAQ (0.2.57): a read receipt a friend sent, lossless packet 189, as it came. */
    void friendSeenReceived(uint32_t friendId, const QByteArray& packet);
    /** An edit, a deletion or a reaction a member sent to the group: 0x41, 0x42 or 0x43, as it came. */
    void groupMessageEffect(int groupnumber, const ToxPk& sender, const QByteArray& packet);
    void groupNamelistChanged(int groupnumber, int peernumber, uint8_t change);
    void groupPeerlistChanged(int groupnumber);
    void groupPeerNameChanged(int groupnumber, const ToxPk& peerPk, const QString& newName);
    void groupTitleChanged(int groupnumber, const QString& author, const QString& title);
    void groupPeerAudioPlaying(int groupnumber, ToxPk peerPk);
    void groupSentFailed(int groupId);
    void groupJoined(int groupnumber, GroupId groupId);
    /**
     * 0.2.56: an invite into a group that toxcore still held (one we leave, or one joined by its ID
     * that never synced) is accepted once toxcore let go of that instance, and joinGroupchat
     * returned that instance's number; toxcore refused the invite then. A group we leave is gone;
     * one joined by its ID is joined by its ID again, and groupJoined gives its new number.
     */
    void groupInviteAcceptFailed(const QByteArray& chatId);
    /**
     * 0.2.56: that group joined by its ID could not be joined again either: toxcore holds no
     * instance of it now, and may give groupNumber, the one it had, to another group. The profile
     * keeps its chat id, and the next start joins it by its ID again (rejoinStoredGroups).
     */
    void groupRejoinFailed(const QByteArray& chatId, uint32_t groupNumber);
    /** A role in the group changed, someone was removed, or the voice or privacy state changed. */
    void groupModerationChanged(int groupnumber);
    /** A peer of the group wrote to us alone. */
    void groupPrivateMessageReceived(int groupnumber, const ToxPk& peerPk, const QString& message,
                                     bool isAction);
    // KHANDAQ (external audit 2026-08-21, K-01): `wasSynced` says whether this row arrived through
    // HISTORY SYNC rather than live. It matters because the two carry completely different
    // guarantees: a live packet is authenticated by toxcore, while a synced one names an author that
    // the relaying peer simply wrote into the packet. Desktop has no signed-history consumer (0x02
    // dies at the version gate), so every synced row is unverified by construction — and until now
    // one was indistinguishable from a live one at the point where a notification is raised.
    // fileId: the 32-byte id of the file in its packets, which a deletion or a reaction points at.
    void groupFileReceived(int groupnumber, ToxPk sender, const QString& fileName,
                           const QByteArray& fileData, const QDateTime& timestamp, bool wasSynced,
                           const QByteArray& fileId);
    /**
     * 0.2.57: a file of more than one packet comes into the group: its BEGIN came, and its chunks
     * come from now on (groupFileArrivalProgress). It ends as groupFileReceived with the same id,
     * or as groupFileArrivalFailed once it is given up. sender is empty while toxcore cannot say
     * whose the BEGIN is.
     */
    void groupFileArrivalStarted(int groupnumber, const ToxPk& sender, const QString& fileName,
                                 qint64 fileSize, const QByteArray& fileId);
    /** How many bytes of the file came so far, a few times a second. */
    void groupFileArrivalProgress(const QByteArray& fileId, qint64 bytesReceived);
    /** The file did not come: its chunks stopped, and asking for them did not bring them. */
    void groupFileArrivalFailed(const QByteArray& fileId);
    /** A file of more than one packet that we send into the group: its chunks go from now on. */
    void groupFileSendStarted(int groupnumber, const QString& fileName, const QString& localPath,
                              qint64 fileSize, const QByteArray& fileId);
    /** How many bytes of the file have gone so far, a few times a second. */
    void groupFileSendProgress(const QByteArray& fileId, qint64 bytesSent);
    /**
     * The group of the file has nobody to send it to (waiting), and nothing of it goes; or has
     * again, and the file goes from its start. After Air::GroupFileQueue::aloneLimitMs of
     * waiting it fails. Not waiting is said too of a file that starts again for the members that
     * came (Air::GroupFileQueue::restartMs).
     */
    void groupFileSendWaiting(const QByteArray& fileId, bool waiting);
    /** The file stopped before its last chunk: the group is gone, or refused it, or the file. */
    void groupFileSendFailed(const QByteArray& fileId);
    /** Every packet of the file went to toxcore. */
    void groupFileSent(int groupnumber, const QString& fileName, const QString& localPath,
                       qint64 fileSize, const QByteArray& fileId);
    void actionSentResult(uint32_t friendId, const QString& action, int success);

    void receiptRecieved(int friedId, ReceiptNum receipt);

    void failedToRemoveFriend(uint32_t friendId);

private:
    Core(QThread* coreThread_, IBootstrapListGenerator& bootstrapListGenerator_, const ICoreSettings& settings_);

    static void onFriendRequest(Tox* tox, const uint8_t* cFriendPk, const uint8_t* cMessage,
                                size_t cMessageSize, void* core);
    static void onFriendMessage(Tox* tox, uint32_t friendId, Tox_Message_Type type,
                                const uint8_t* cMessage, size_t cMessageSize, void* core);
    static void onFriendNameChange(Tox* tox, uint32_t friendId, const uint8_t* cName,
                                   size_t cNameSize, void* core);
    static void onFriendTypingChange(Tox* tox, uint32_t friendId, bool isTyping, void* core);
    static void onStatusMessageChanged(Tox* tox, uint32_t friendId, const uint8_t* cMessage,
                                       size_t cMessageSize, void* core);
    static void onUserStatusChanged(Tox* tox, uint32_t friendId, Tox_User_Status userstatus,
                                    void* core);
    static void onConnectionStatusChanged(Tox* tox, uint32_t friendId, Tox_Connection status,
                                          void* vCore);
    static void onGroupInvite(Tox* tox, uint32_t friendId, const uint8_t* inviteData, size_t length,
                              const uint8_t* groupName, size_t groupNameLength, void* vCore);
    static void onGroupMessage(Tox* tox, uint32_t groupId, uint32_t peerId, Tox_Message_Type type,
                               const uint8_t* cMessage, size_t length, uint32_t messageId, void* vCore);
    static void onGroupPeerJoin(Tox* tox, uint32_t groupId, uint32_t peerId, void* vCore);
    static void onGroupPeerExit(Tox* tox, uint32_t groupId, uint32_t peerId, Tox_Group_Exit_Type exitType,
                                const uint8_t* name, size_t nameLength, const uint8_t* partMessage,
                                size_t partMessageLength, void* vCore);
    static void onGroupPeerNameChange(Tox* tox, uint32_t groupId, uint32_t peerId, const uint8_t* name,
                                      size_t length, void* core);
    static void onGroupTopicChange(Tox* tox, uint32_t groupId, uint32_t peerId, const uint8_t* topic,
                                   size_t length, void* vCore);
    static void onGroupSelfJoin(Tox* tox, uint32_t groupId, void* vCore);
    static void onGroupModeration(Tox* tox, uint32_t groupId, uint32_t sourcePeerId,
                                  uint32_t targetPeerId, Tox_Group_Mod_Event modType, void* vCore);
    static void onGroupVoiceState(Tox* tox, uint32_t groupId, Tox_Group_Voice_State voiceState,
                                  void* vCore);
    static void onGroupPrivacyState(Tox* tox, uint32_t groupId,
                                    Tox_Group_Privacy_State privacyState, void* vCore);
    static void onGroupPrivateMessage(Tox* tox, uint32_t groupId, uint32_t peerId,
                                      Tox_Message_Type type, const uint8_t* message, size_t length,
                                      void* vCore);
    static void onGroupJoinFail(Tox* tox, uint32_t groupId, Tox_Group_Join_Fail failType, void* vCore);
    static void onGroupCustomPacket(Tox* tox, uint32_t groupId, uint32_t peerId, const uint8_t* data,
                                    size_t length, void* vCore);
    static void onGroupCustomPrivatePacket(Tox* tox, uint32_t groupId, uint32_t peerId,
                                           const uint8_t* data, size_t length, void* vCore);
    bool sendGroupLeaveNotice(int groupId) const;
    void leaveGroupAfterNotice(int groupId);
    void leaveRecordedGroup(const Air::PendingGroupLeaves::Leave& leave, const char* event);
    uint32_t replaceLeavingGroup(const QByteArray& chatId, std::function<void()> join);
    void acceptDeferredGroupInvite(const GroupInvite& inviteInfo, uint32_t joinedByIdNum);
    void iterateTox();
    bool ignoresGroup(uint32_t groupNumber) const;
    struct OutgoingGroupFile;
    bool sendsGroupFileTo(const OutgoingGroupFile& send) const;
    bool sendGroupFileBegin(OutgoingGroupFile& send, const QByteArray& fileId);
    bool sendGroupFileChunk(OutgoingGroupFile& send, const QByteArray& fileId, int chunk);
    void sendGroupFileChunks();
    bool sendQueuedGroupFilePacket(const Air::GroupFileQueue::Packet& packet, qint64 nowMs);
    void tellGroupFilesWaiting();
    int groupFileMembers(int groupId) const;
    void failGroupFileSend(const QByteArray& fileId, const char* reason);
    void keepSentGroupFile(const QByteArray& fileId, const OutgoingGroupFile& send, qint64 nowMs);
    void answerGroupFileRequest(uint32_t groupId, uint32_t peerId, const uint8_t* data,
                                size_t length);
    int nextGroupFileResend(qint64 nowMs) const;
    void sendGroupFileResend(int index, qint64 nowMs);
    void askForMissingChunks(uint32_t groupId, uint32_t senderPeer, const QByteArray& msgId,
                             const QVector<bool>& received);

    static void onLosslessPacket(Tox* tox, uint32_t friendId,
                                   const uint8_t* data, size_t length, void* core);
    static void onReadReceiptCallback(Tox* tox, uint32_t friendId, uint32_t receipt, void* vCore);

    SendResult sendGroupMessageWithType(int groupId, const QString& message,
                                        Tox_Message_Type type, uint32_t& wireId);
    bool sendMessageWithType(uint32_t friendId, const QString& message, Tox_Message_Type type,
                             const QByteArray& msgV3Id, quint32 msgV3Time, ReceiptNum& receipt);
    void sendHighLevelAck(uint32_t friendId, const QByteArray& msgV3Id);
    bool checkConnection();
    /** Counts the relays the node holds a connection to, at most every few seconds. */
    void countRelays();

    void makeTox(QByteArray savedata, ICoreSettings* s);
    void loadFriends();
    void loadGroups();
    void bootstrapDht();
    void performKhandaqBootstrapBurst();

    void checkLastOnline(uint32_t friendId);
    void reconnectGroupIfDisconnected(uint32_t groupNum) const;
    void kickstartGroupConnection(uint32_t groupNum) const;
    void maintainGroups();
    void requestGroupInviteFromFriends(const QByteArray& chatId);
    void forgetUnsyncedGroupJoin(const QByteArray& chatId);
    void resendPendingGroupInviteRequests(uint32_t friendId);
    void handleGroupInviteRequestPacket(uint32_t friendId, const uint8_t* data, size_t length);
    bool autoAcceptRequestedGroupInvite(const GroupInvite& inviteInfo);

    QString getFriendRequestErrorMessage(const ToxId& friendId, const QString& message) const;
    static void registerCallbacks(Tox* tox);

private slots:
    void process();
    void onStarted();
    void onNetworkChanged();

private:
    struct ToxDeleter
    {
        void operator()(Tox* tox_)
        {
            tox_kill(tox_);
        }
    };
    /**
     * While our node stays offline, the bootstrap nodes are asked again this often (DESIGN.md,
     * "Network state on the phone": every 15 seconds, as Android's OFFLINE_PERIODIC). The start, a
     * change of network and Connect again ask at once; a node that drops off waits this long first,
     * while toxcore tries the nodes it knows.
     */
    static constexpr qint64 offlineBootstrapIntervalMs = 15000;

    using ToxPtr = std::unique_ptr<Tox, ToxDeleter>;
    ToxPtr tox;

    std::unique_ptr<CoreFile> file;
    CoreAV* av = nullptr;
    std::unique_ptr<CoreExt> ext;
    QTimer* toxTimer = nullptr;
    // recursive, since we might call our own functions
    mutable CompatibleRecursiveMutex coreLoopLock;
    // QA live F11: members that said they are leaving a group. Under coreLoopLock: the toxcore
    // callbacks run in process(), which holds it. Mutable: quitGroupChat() is const.
    mutable Air::GroupLeaveNotices groupLeaveNotices;
    // QA live F11: our leaves that wait for their notice, under coreLoopLock.
    Air::PendingGroupLeaves pendingGroupLeaves;
    // QA live F11: the groups we called tox_group_leave on, which toxcore deletes at the next
    // tox_iterate (iterateTox); with the chat id while the profile keeps it, and an empty one when
    // it does not. Under coreLoopLock.
    QHash<int, QByteArray> exitingGroups;
    // QA live F11: the joins of groups we were leaving, which wait for toxcore to delete the old
    // instance: it refuses to join a chat id it still has. Under coreLoopLock.
    std::vector<std::function<void()>> joinsAfterGroupLeaves;
    // KHANDAQ (0.2.57): the toxcore numbers of our msgV3 receipts (sendHighLevelAck) whose own
    // toxcore receipt is yet to come, by friend. None is a message of ours, so their receipts stop
    // in onReadReceiptCallback and never reach the message dispatcher. Dropped when the friend goes
    // offline or is removed, as toxcore drops the receipts it waits for then. Under coreLoopLock.
    QHash<uint32_t, QSet<uint32_t>> highLevelAcks;
    // 0.2.56: the chat ids of the groups joined by their ID that did not sync yet: toxcore saves
    // a group only once it has its shared state (gc_group_is_valid), so the profile keeps them
    // until then. Under coreLoopLock.
    QList<QByteArray> unsyncedGroupJoins;
    // 0.2.56: a file we send into a group whose chunks have yet to go (Air::GroupFileQueue).
    struct OutgoingGroupFile
    {
        int group = -1;
        // The group's chat id when the file was chosen: a group we leave meanwhile can give its
        // number to another one.
        QByteArray chatId;
        QString fileName;
        QString localPath;
        qint64 size = 0;
        // Open from its BEGIN to its last chunk.
        std::shared_ptr<QFile> file;
        // When its progress was last told (monotonicMs).
        qint64 toldMs = 0;
        // Whether it was last told to wait for a member of its group (groupFileSendWaiting).
        bool waiting = false;
        // 0.2.57: when its last chunk went (monotonicMs), once it is in sentGroupFiles.
        qint64 doneMs = 0;
    };
    // 0.2.56: the files we send into groups, by file id; their order and their pace. Under
    // coreLoopLock: sendGroupFileFromPath adds them, process() sends their packets.
    QHash<QByteArray, OutgoingGroupFile> outgoingGroupFiles;
    Air::GroupFileQueue groupFileQueue;
    Air::UploadPace groupFilePace;
    // 0.2.57: the files whose chunks all went, kept a while for the members that ask for chunks
    // they missed (answerGroupFileRequest), by file id. Under coreLoopLock.
    QHash<QByteArray, OutgoingGroupFile> sentGroupFiles;
    // 0.2.57: chunks of our files that members asked for again, to send to them alone, at the
    // pace of the others. Under coreLoopLock.
    struct GroupFileResend
    {
        QByteArray fileId;
        // The member, by its key in the group: its number changes when it joins again.
        ToxPk member;
        int group = -1;
        QByteArray chatId;
        QString fileName;
        QString localPath;
        qint64 size = 0;
        std::shared_ptr<QFile> file;
        // The BEGIN goes first, for a member that holds nothing of the file any more.
        bool begin = true;
        // The chunks to send, in order, and the next of them.
        QVector<int> chunks;
        int next = 0;
        // When it may go again after toxcore refused it (monotonicMs), and since when it does.
        qint64 dueMs = 0;
        qint64 refusedSinceMs = -1;
    };
    QList<GroupFileResend> groupFileResends;
    Air::ResendBudget groupFileResendBudget;
    // 0.2.57: when the files that come were last looked at (checkGroupFileArrivals).
    qint64 groupFileArrivalsCheckedMs = 0;
    // 0.2.57: whether the last packet of the pace was a resend, so that the two take turns.
    bool groupFileResendWent = false;

    std::unique_ptr<QThread> coreThread;
    const IBootstrapListGenerator& bootstrapListGenerator;
    const ICoreSettings& settings;
    bool isConnected = false;
    int lastSelfConnection = -1;
    /** When the relays were last counted, and how many there were (-1 before the first count). */
    QElapsedTimer relaysCounted;
    int lastRelays = -1;
    /** Of those, the relays the groups use (NetworkDiagnostics::groupRelayCount). */
    int lastGroupRelays = 0;
    /** Since the bootstrap nodes were last asked, or since the node went offline; invalid before. */
    QElapsedTimer bootstrapClock;
    class QNetworkConfigurationManager* networkManager = nullptr;
    qint64 lastNetworkRebootstrapMs = 0;
    int groupMaintenanceTick = 0;
};
