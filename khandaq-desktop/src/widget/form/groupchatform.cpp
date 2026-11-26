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

#include "groupchatform.h"

#include "tabcompleter.h"
#include "src/core/core.h"
#include "src/core/coreav.h"
#include "src/core/groupid.h"
#include "src/chatlog/chatwidget.h"
#include "src/chatlog/content/text.h"
#include "src/model/friend.h"
#include "src/friendlist.h"
#include "src/model/group.h"
#include "src/model/status.h"
#include "src/model/air/airconversation.h"
#include "src/model/air/airmessagemarks.h"
#include "src/model/air/airmodel.h"
#include "src/widget/air/aircomposer.h"
#include "src/widget/air/airgroupdossier.h"
#include "src/widget/air/airgroupviews.h"
#include "src/widget/air/airlocal.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airsource.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/chatformheader.h"
#include "src/widget/form/chatform.h"
#include "src/widget/groupwidget.h"
#include "src/widget/style.h"
#include "src/widget/tool/chatquotepreview.h"
#include "src/widget/translator.h"
#include "src/persistence/igroupsettings.h"
#include "src/persistence/settings.h"

#include <QAbstractButton>
#include <QBuffer>
#include <QCursor>
#include <QDesktopServices>
#include <QDir>
#include <QDragEnterEvent>
#include <QFileDialog>
#include <QFileInfo>
#include <QImageReader>
#include <QMenu>
#include <QMessageBox>
#include <QMimeData>
#include <QTimer>
#include <QToolButton>
#include <QPushButton>
#include <QUrl>

using AirText = AirStrings::Text;

namespace
{
#include "src/core/khandaqlimits.h"

/** While the conversation shows, presence and the connection are asked again this often. */
constexpr int refreshTickMs = 2000;

Air::GroupRole airRole(Tox_Group_Role role)
{
    switch (role) {
    case TOX_GROUP_ROLE_FOUNDER:
        return Air::GroupRole::Founder;
    case TOX_GROUP_ROLE_MODERATOR:
        return Air::GroupRole::Moderator;
    case TOX_GROUP_ROLE_OBSERVER:
        return Air::GroupRole::Observer;
    case TOX_GROUP_ROLE_USER:
        break;
    }
    return Air::GroupRole::Member;
}

Tox_Group_Role toxRole(Air::GroupRole role)
{
    switch (role) {
    case Air::GroupRole::Founder:
        return TOX_GROUP_ROLE_FOUNDER;
    case Air::GroupRole::Moderator:
        return TOX_GROUP_ROLE_MODERATOR;
    case Air::GroupRole::Observer:
        return TOX_GROUP_ROLE_OBSERVER;
    case Air::GroupRole::Member:
        break;
    }
    return TOX_GROUP_ROLE_USER;
}

Air::GroupVoice airVoice(Tox_Group_Voice_State voice)
{
    switch (voice) {
    case TOX_GROUP_VOICE_STATE_MODERATOR:
        return Air::GroupVoice::Moderators;
    case TOX_GROUP_VOICE_STATE_FOUNDER:
        return Air::GroupVoice::Founder;
    case TOX_GROUP_VOICE_STATE_ALL:
        break;
    }
    return Air::GroupVoice::All;
}
} // namespace

GroupChatForm::GroupChatForm(Core& core_, Group* chatGroup, IChatLog& chatLog_,
    IMessageDispatcher& messageDispatcher_, Settings& settings_, DocumentCache& documentCache_,
        SmileyPack& smileyPack_, Style& style_, IMessageBoxManager& messageBoxManager,
        FriendList& friendList_, GroupList& groupList_)
    : GenericChatForm(core_, chatGroup, chatLog_, messageDispatcher_,
        documentCache_, smileyPack_, settings_, style_, messageBoxManager, friendList_,
        groupList_)
    , core{core_}
    , group(chatGroup)
    , inCall(false)
    , settings(settings_)
    , style{style_}
    , friendList{friendList_}
{
    tabber = new TabCompleter(msgEdit, group);

    fileButton->setEnabled(true);
    fileButton->setProperty("state", "green");
    ChatFormHeader::Mode mode = ChatFormHeader::Mode::None;
    if (group->isAvGroupchat()) {
        mode = ChatFormHeader::Mode::Audio;
    }

    headWidget->setMode(mode);
    setName(group->getName());

    const auto updateTile = [this] {
        headWidget->setTile(group->getDisplayedName(),
                            Air::toneIndex(group->getPersistentId().toString()),
                            AirTile::groupKind(group->getPersistentId().getByteArray()));
    };
    updateTile();
    connect(group, &Chat::displayedNameChanged, this, updateTile);

    // The head (E13): the members and how many are online under the name, then those who get a
    // message now. The title unfolds the dossier; renaming starts from there.
    headWidget->setDossierEnabled(true);
    headWidget->setNameEditable(false);
    headWidget->setGroupDossier(true);
    band = new AirGetNowBand(this);
    band->hide();
    headWidget->addBand(band);
    connect(band, &AirGetNowBand::clicked, this, &GroupChatForm::toggleDossier);

    msgEdit->setObjectName("group");

    dossier = new AirGroupDossier(this);
    privateThread = new AirPrivateThread(this);
    connect(headWidget, &ChatFormHeader::dossierToggled, this, &GroupChatForm::toggleDossier);
    connect(dossier, &AirGroupDossier::openChanged, headWidget, &ChatFormHeader::setDossierOpen);
    connect(dossier, &AirGroupDossier::renameRequested, headWidget, &ChatFormHeader::beginRename);
    connect(dossier, &AirGroupDossier::muteChanged, this, [this](bool muted) {
        if (airLocal) {
            airLocal->setMuted(ToxPk(group->getPersistentId().getByteArray()), muted);
        }
    });
    connect(dossier, &AirGroupDossier::leaveRequested, this,
            [this] { emit leaveRequested(group->getPersistentId()); });
    connect(dossier, &AirGroupDossier::memberActionRequested, this, &GroupChatForm::applyMemberAction);
    connect(dossier, &AirGroupDossier::writePrivatelyRequested, this,
            [this](const Air::GroupMember& member) { showPrivate(member.name, member.publicKey); });
    connect(dossier, &AirGroupDossier::memberMuteRequested, this,
            [this](const Air::GroupMember& member, bool muted) {
                if (!airLocal || member.publicKey.isEmpty()) {
                    return;
                }
                airLocal->setMuted(ToxPk(member.publicKey), muted);
                AirToast::post(this, AirStrings::text(muted ? AirText::GroupMemberMuted
                                                            : AirText::GroupMemberUnmuted)
                                         .arg(member.name));
                refreshMembers();
            });
    connect(dossier, &AirGroupDossier::memberHideRequested, this,
            [this](const Air::GroupMember& member, bool hidden) {
                if (member.publicKey.isEmpty()) {
                    return;
                }
                // The list toxcore's callbacks and the dispatcher read: messages and voice of a
                // key on it are dropped as they come.
                QStringList blackList = settings.getBlackList();
                blackList.removeAll(member.publicKey);
                if (hidden) {
                    blackList.append(member.publicKey);
                }
                settings.setBlackList(blackList);
                AirToast::post(this, AirStrings::text(hidden ? AirText::GroupMemberHidden
                                                             : AirText::GroupMemberShown)
                                         .arg(member.name));
            });
    connect(dossier, &AirGroupDossier::inviteRequested, this, &GroupChatForm::inviteContacts);
    connect(dossier, &AirGroupDossier::privacyChangeRequested, this, &GroupChatForm::setPrivacy);
    connect(dossier, &AirGroupDossier::selfNameRequested, this, &GroupChatForm::setNameInGroup);
    connect(privateThread, &AirPrivateThread::sendRequested, this, &GroupChatForm::sendPrivate);
    connect(composer, &AirComposer::readOnlyActionRequested, this, &GroupChatForm::askToWrite);
    connect(chatWidget, &ChatWidget::heldFileAccepted, this,
            [this](const ToxFile& file) { emit heldFileAccepted(group->getPersistentId(), file); });
    connect(chatWidget, &ChatWidget::authorClicked, this, &GroupChatForm::showAuthorMenu);

    connect(msgEdit, &ChatTextEdit::tabPressed, tabber, &TabCompleter::complete);
    connect(msgEdit, &ChatTextEdit::keyPressed, tabber, &TabCompleter::reset);
    connect(msgEdit, &ChatTextEdit::textChanged, this, &GroupChatForm::refreshCounter);
    // KHANDAQ (0.2.60): a pasted picture is offered as in a chat with a person (it was not taken).
    takePastedImages();
    connect(quotePreview, &ChatQuotePreview::changed, this, &GroupChatForm::refreshCounter);
    connect(headWidget, &ChatFormHeader::callTriggered, this, &GroupChatForm::onCallClicked);
    connect(headWidget, &ChatFormHeader::micMuteToggle, this, &GroupChatForm::onMicMuteToggle);
    connect(headWidget, &ChatFormHeader::volMuteToggle, this, &GroupChatForm::onVolMuteToggle);
    connect(headWidget, &ChatFormHeader::nameChanged, chatGroup, &Group::setName);
    connect(group, &Group::titleChanged, this, &GroupChatForm::onTitleChanged);
    connect(group, &Group::userJoined, this, &GroupChatForm::onUserJoined);
    connect(group, &Group::userLeft, this, &GroupChatForm::onUserLeft);
    connect(group, &Group::peerNameChanged, this, &GroupChatForm::onPeerNameChanged);

    // Who is in the group and what you may do come from toxcore; changes that come together are
    // shown once, and presence, which has no signal, is asked again while the conversation shows.
    refreshLater.setSingleShot(true);
    refreshLater.setInterval(0);
    connect(&refreshLater, &QTimer::timeout, this, &GroupChatForm::refreshMembers);
    refreshTicker.setInterval(refreshTickMs);
    connect(&refreshTicker, &QTimer::timeout, this, &GroupChatForm::refreshMembers);
    const auto later = [this] { refreshLater.start(); };
    connect(group, &Group::numPeersChanged, this, later);
    connect(group, &Chat::displayedNameChanged, this, later);
    connect(&core, &Core::groupModerationChanged, this, [this, later](int groupnumber) {
        if (static_cast<uint32_t>(groupnumber) == group->getId()) {
            later();
        }
    });
    settings.connectTo_blackListChanged(this, [later](QStringList const&) { later(); });

    // KHANDAQ (0.2.57): Report, after the actions of every conversation; prepareChatMenu shows it
    // on a message of someone else only.
    menu.addSeparator();
    reportAction = menu.addAction(QString(), this, [this] { report(); });

    if (settings.getShowGroupJoinLeaveMessages()) {
        addSystemInfoMessage(QDateTime::currentDateTime(), SystemMessageType::selfJoinedGroup, {});
    }

    setAcceptDrops(true);
    retranslateUi();
    Translator::registerHandler(std::bind(&GroupChatForm::retranslateUi, this), this);
}

GroupChatForm::~GroupChatForm()
{
    if (settings.getShowGroupJoinLeaveMessages()) {
        addSystemInfoMessage(QDateTime::currentDateTime(), SystemMessageType::selfLeftGroup, {});
    }
    Translator::unregister(this);
}

void GroupChatForm::setAir(AirLocal* local, AirSource* source)
{
    airLocal = local;
    airSource = source;
    if (airSource) {
        connect(airSource, &AirSource::networkChanged, &refreshLater, QOverload<>::of(&QTimer::start));
        // KHANDAQ (beta ideas 2026-11-20): what the field holds, typed and not sent, shows in the
        // row as a draft; sending empties the field and with it the draft.
        connect(msgEdit, &ChatTextEdit::textChanged, this, [this] {
            if (airSource) {
                airSource->setDraft(AirSource::groupEntryId(group->getPersistentId()),
                                    msgEdit->toPlainText());
            }
        });
    }
    if (airLocal) {
        connect(airLocal, &AirLocal::privateLinesChanged, this,
                [this](const QString& changedGroup, const QString& peerKey) {
                    if (changedGroup == groupKey() && privateThread->isOpen()
                        && privateThread->peerKey() == peerKey) {
                        privateThread->setLines(airLocal->privateLines(changedGroup, peerKey));
                    }
                });
    }
    refreshMembers();
}

void GroupChatForm::openPrivate(const QString& peerKey)
{
    QString name;
    for (const Air::GroupMember& member : members) {
        if (member.publicKey == peerKey) {
            name = member.name;
            break;
        }
    }
    if (name.isEmpty()) {
        // The member left meanwhile: the messages kept stay readable under the name the group had.
        name = group->resolveToxPk(ToxPk(peerKey));
    }
    showPrivate(name, peerKey);
}

bool GroupChatForm::isPrivateOpen(const QString& peerKey) const
{
    return privateThread->isOpen() && privateThread->peerKey() == peerKey;
}

#ifdef KHANDAQ_SCREENSHOT_HOOK
void GroupChatForm::askMemberActionForShots(const QString& name, Air::MemberAction action)
{
    for (const Air::GroupMember& member : members) {
        if (member.name == name && !member.isSelf) {
            dossier->askForShots(member, action);
            return;
        }
    }
    qWarning() << "No member to ask about:" << name;
}
#endif

QString GroupChatForm::groupKey() const
{
    return group->getPersistentId().toString();
}

int GroupChatForm::peerIdOf(const QString& peerKey) const
{
    if (peerKey.isEmpty()) {
        return -1;
    }
    const int groupNumber = static_cast<int>(group->getId());
    for (const uint32_t peerId : core.getGroupPeerList(groupNumber)) {
        if (core.getGroupPeerPk(groupNumber, static_cast<int>(peerId)).toString() == peerKey) {
            return static_cast<int>(peerId);
        }
    }
    return -1;
}

void GroupChatForm::refreshMembers()
{
    const int groupNumber = static_cast<int>(group->getId());
    const int connection = core.getGroupConnection(groupNumber);
    const uint32_t selfPeer = core.getGroupSelfPeerId(groupNumber);
    // Our entries carry the key of the profile or the one the group knows us by; the name over
    // them opens no menu.
    chatWidget->setGroupSelfKey(core.getGroupSelfPublicKey(groupNumber));
    selfRole = airRole(core.getGroupSelfRole(groupNumber));
    voice = airVoice(core.getGroupVoiceState(groupNumber));

    QVector<Air::GroupMember> roster;
    QSet<QString> seenKeys;
    bool sawSelf = false;
    for (const uint32_t peerId : core.getGroupPeerList(groupNumber)) {
        const ToxPk peerPk = core.getGroupPeerPk(groupNumber, static_cast<int>(peerId));
        if (peerPk.isEmpty() || seenKeys.contains(peerPk.toString())) {
            // Gone between the list and the question, or listed twice.
            continue;
        }
        seenKeys.insert(peerPk.toString());
        Air::GroupMember member;
        member.peerId = peerId;
        member.isSelf = selfPeer != std::numeric_limits<uint32_t>::max() && peerId == selfPeer;
        sawSelf = sawSelf || member.isSelf;
        member.publicKey = peerPk.toString();
        const QString name = core.getGroupPeerName(groupNumber, static_cast<int>(peerId));
        member.name = member.isSelf ? (name.isEmpty() ? core.getUsername() : name)
                                    : friendList.decideNickname(peerPk, name);
        member.role = member.isSelf
                          ? selfRole
                          : airRole(core.getGroupPeerRole(groupNumber, static_cast<int>(peerId)));
        member.online = member.isSelf || core.isGroupPeerOnline(groupNumber, static_cast<int>(peerId));
        roster.append(member);
    }
    if (!sawSelf) {
        // toxcore has not listed you yet: you are in the group all the same.
        Air::GroupMember self;
        self.peerId = selfPeer;
        self.isSelf = true;
        self.name = core.getUsername();
        self.role = selfRole;
        self.online = true;
        roster.append(self);
    }
    // Synced, or still connecting with members reached: a founder's group back from the save
    // stays connecting while the members come to it, and messages go to those online all the
    // same. Nobody is online while the group reaches nobody.
    bool reachesSomeone = false;
    for (const Air::GroupMember& member : roster) {
        reachesSomeone = reachesSomeone || (member.online && !member.isSelf);
    }
    const bool connected = connection == 1 || (connection == 0 && reachesSomeone);
    for (Air::GroupMember& member : roster) {
        member.online = connected && member.online;
    }
    members = Air::sortedRoster(roster);

    int online = 0;
    for (const Air::GroupMember& member : members) {
        online += member.online && !member.isSelf;
    }
    const uint32_t peerCount = core.getGroupNumberPeers(groupNumber);
    reach = Air::GroupReach::make(peerCount == std::numeric_limits<uint32_t>::max()
                                      ? 0
                                      : static_cast<int>(peerCount),
                                  members.size(), online, connected);
    right = Air::composeRight(selfRole, voice);
    selfNetwork = airSource ? airSource->networkState() : Air::NetworkState::Direct;

    headWidget->setSubtitle(AirStrings::groupHeaderText(reach));
    headWidget->updateCallButtons(true, inCall);

    // While you may write: who gets it now, over the field and under it.
    const QVector<Air::GroupMember> reachable = Air::reachableNow(members);
    band->setMembers(reachable);
    band->setVisible(right == Air::ComposeRight::Write && !reachable.isEmpty());
    if (right == Air::ComposeRight::Write) {
        composer->setReadOnly(QString(), QString());
        const Air::GroupHint hint = Air::GroupHint::make(reach, selfNetwork);
        composer->setHint(AirStrings::groupHintText(hint, reach), hint.dot);
    } else {
        const bool founderOnly = right == Air::ComposeRight::FounderOnly;
        const bool nobody = Air::askable(members, founderOnly).isEmpty();
        // The button stays and says why it waits: an observer, whose private messages toxcore
        // refuses too, or nobody known to ask.
        composer->setReadOnly(AirStrings::groupReadOnlyText(right), AirStrings::groupAskText(right),
                              AirStrings::groupAskUnavailableText(right, nobody));
    }
    refreshPrivateRight();
    refreshCounter();

    if (dossier->isOpen()) {
        dossier->setInfo(dossierInfo());
    }
}

void GroupChatForm::refreshCounter()
{
    // The room left once a tenth of a group message remains; past the limit the text goes in
    // parts, as to one person: GroupMessageDispatcher cuts it at toxcore's group limit.
    const int bytes = outgoingText().toUtf8().size();
    const Air::ComposeRoom room = Air::composeRoom(bytes, Air::groupMessageLimitBytes);
    composer->setCounter(room.shown && right == Air::ComposeRight::Write
                             ? AirStrings::composeCounterText(room.left)
                             : QString());
    // Send waits while nobody would get the message: see Air::GroupHint::sendWaits.
    composer->setSendBlocked(Air::GroupHint::make(reach, selfNetwork).sendWaits());
}

bool GroupChatForm::canSendNow() const
{
    return right == Air::ComposeRight::Write
           && !Air::GroupHint::make(reach, selfNetwork).sendWaits();
}

void GroupChatForm::showEvent(QShowEvent* event)
{
    GenericChatForm::showEvent(event);
    refreshMembers();
    refreshTicker.start();
}

void GroupChatForm::hideEvent(QHideEvent* event)
{
    refreshTicker.stop();
    GenericChatForm::hideEvent(event);
}

void GroupChatForm::toggleDossier()
{
    if (dossier->isOpen()) {
        dossier->close();
        return;
    }
    privateThread->close();
    dossier->setInfo(dossierInfo());
    dossier->open();
}

AirGroupInfo GroupChatForm::dossierInfo() const
{
    AirGroupInfo info;
    info.name = group->getDisplayedName();
    info.groupKey = groupKey();
    info.tone = Air::toneIndex(groupKey());
    info.reach = reach;
    info.selfRole = selfRole;
    info.voice = voice;
    const int groupNumber = static_cast<int>(group->getId());
    // KHANDAQ (beta feedback 2026-11-22): the name toxcore gives the members, as our row says it.
    info.selfName = core.getGroupSelfName(groupNumber);
    if (info.selfName.isEmpty()) {
        info.selfName = core.getUsername();
    }
    info.privacy = core.getGroupPrivacyState(groupNumber) == TOX_GROUP_PRIVACY_STATE_PRIVATE
                       ? Air::GroupPrivacy::InviteOnly
                       : Air::GroupPrivacy::Open;
    info.members = members;
    const QStringList blackList = settings.getBlackList();
    for (const Air::GroupMember& member : members) {
        if (member.publicKey.isEmpty()) {
            continue;
        }
        if (airLocal && airLocal->isMuted(ToxPk(member.publicKey))) {
            info.mutedMembers.insert(member.publicKey);
        }
        if (blackList.contains(member.publicKey)) {
            info.hiddenMembers.insert(member.publicKey);
        }
    }
    info.muted = airLocal && airLocal->isMuted(ToxPk(group->getPersistentId().getByteArray()));
    info.candidates = inviteCandidates();
    return info;
}

QVector<Air::InviteCandidate> GroupChatForm::inviteCandidates() const
{
    QVector<Air::InviteCandidate> candidates;
    for (Friend* const frnd : friendList.getAllFriends()) {
        Air::InviteCandidate candidate;
        candidate.friendId = static_cast<int>(frnd->getId());
        candidate.publicKey = frnd->getPublicKey().toString();
        candidate.name = frnd->getDisplayedName();
        candidate.online = Status::isOnline(frnd->getStatus());
        candidates.append(candidate);
    }
    return candidates;
}

void GroupChatForm::applyMemberAction(const Air::GroupMember& member, Air::MemberAction action)
{
    // The peer number may have changed since the menu opened: the key says who it was.
    const int peerId = peerIdOf(member.publicKey);
    const int groupNumber = static_cast<int>(group->getId());
    Air::GroupRole role = member.role;
    const bool changesRole = Air::actionRole(action, &role);
    const bool done = peerId >= 0
                      && (changesRole ? core.setGroupPeerRole(groupNumber, peerId, toxRole(role))
                                      : core.kickGroupPeer(groupNumber, peerId));
    refreshMembers();
    if (!done) {
        // An error is never a toast (E21): the operation is the title, what to do under it.
        AirDialog::notice(this,
                          AirStrings::text(changesRole ? AirText::ErrorRoleTitle
                                                       : AirText::ErrorKickTitle),
                          AirStrings::text(AirText::GroupChangeFailed));
        return;
    }
    const QString words = AirStrings::memberActionDone(action, member.name);
    if (!changesRole) {
        // Removing has no undo: the member needs a new invitation.
        AirToast::post(this, words);
        return;
    }
    // A change of role is taken back from the toast for five seconds.
    const Air::GroupMember before = member;
    AirToast::post(this, words, AirStrings::text(AirText::Undo),
                   [this, before] { undoRole(before, before.role); });
}

void GroupChatForm::undoRole(const Air::GroupMember& member, Air::GroupRole role)
{
    const int peerId = peerIdOf(member.publicKey);
    const bool done =
        peerId >= 0 && core.setGroupPeerRole(static_cast<int>(group->getId()), peerId, toxRole(role));
    refreshMembers();
    if (!done) {
        AirDialog::notice(this, AirStrings::text(AirText::ErrorRoleTitle),
                          AirStrings::text(AirText::GroupChangeFailed));
    }
}

void GroupChatForm::setPrivacy(Air::GroupPrivacy privacy)
{
    const Tox_Group_Privacy_State state = privacy == Air::GroupPrivacy::Open
                                              ? TOX_GROUP_PRIVACY_STATE_PUBLIC
                                              : TOX_GROUP_PRIVACY_STATE_PRIVATE;
    const bool done = core.setGroupPrivacyState(static_cast<int>(group->getId()), state);
    // toxcore calls back the other members, not us: the dossier asks it again here.
    refreshMembers();
    if (!done) {
        AirDialog::notice(this, AirStrings::text(AirText::ErrorPrivacyTitle),
                          AirStrings::text(AirText::GroupChangeFailed));
    }
}

void GroupChatForm::setNameInGroup(const QString& name)
{
    const int groupNumber = static_cast<int>(group->getId());
    const bool done = core.setGroupSelfName(groupNumber, name);
    if (done) {
        // Tab completion leaves our own name in the group out (TabCompleter::buildCompletionList).
        group->setSelfName(core.getGroupSelfName(groupNumber));
    }
    // Our row and the field show the name toxcore holds now.
    refreshMembers();
    if (!done) {
        AirDialog::notice(this, AirStrings::text(AirText::ErrorGroupSelfNameTitle),
                          AirStrings::text(AirText::GroupChangeFailed));
        return;
    }
    AirToast::post(this, AirStrings::text(AirText::YouSaved));
}

void GroupChatForm::inviteContacts(const QVector<Air::InviteCandidate>& chosen)
{
    tellInvites(this, this,
                sendInvites(core, airLocal, group->getId(), group->getPersistentId(), chosen));
}

Air::InviteTally GroupChatForm::sendInvites(Core& core, AirLocal* local, uint32_t groupNumber,
                                            const GroupId& groupId,
                                            const QVector<Air::InviteCandidate>& chosen)
{
    Air::InviteTally tally;
    const QString groupKey = groupId.toString();
    for (const Air::InviteCandidate& candidate : chosen) {
        if (!candidate.canInvite()) {
            ++tally.refused;
            continue;
        }
        switch (core.inviteToGroup(static_cast<uint32_t>(candidate.friendId),
                                   static_cast<int>(groupNumber))) {
        case Core::GroupInviteResult::Sent:
            ++tally.sent;
            if (local) {
                // One kept from before goes too: it has just been sent.
                local->dropOutgoingInvite(candidate.publicKey, groupKey);
            }
            break;
        case Core::GroupInviteResult::Later:
            if (local) {
                local->keepOutgoingInvite(candidate.publicKey, groupKey);
                ++tally.later;
            } else {
                ++tally.refused;
            }
            break;
        case Core::GroupInviteResult::Refused:
            ++tally.refused;
            break;
        }
    }
    return tally;
}

void GroupChatForm::tellInvites(QWidget* toastOn, QWidget* dialogOn, const Air::InviteTally& tally)
{
    if (tally.refused > 0) {
        AirDialog::notice(dialogOn,
                          AirStrings::text(tally.sent + tally.later == 0
                                               ? AirText::ErrorInviteTitle
                                               : AirText::ErrorInviteSomeTitle),
                          AirStrings::text(AirText::InviteRetry));
        return;
    }
    QStringList said;
    if (tally.sent > 0) {
        said.append(AirStrings::text(AirText::InviteSent, tally.sent));
    }
    if (tally.later > 0) {
        said.append(AirStrings::text(AirText::InviteLater, tally.later));
    }
    if (!said.isEmpty()) {
        // A line each, which reads the same in every language; the toast wraps.
        AirToast::post(toastOn, said.join(QLatin1Char('\n')));
    }
}

void GroupChatForm::askToWrite()
{
    // The founder alone, or the moderators and the founder, online first.
    const QVector<Air::GroupMember> asked =
        Air::askable(members, right == Air::ComposeRight::FounderOnly);
    if (asked.isEmpty()) {
        return;
    }
    if (asked.size() == 1) {
        showPrivate(asked.first().name, asked.first().publicKey);
        return;
    }
    QMenu chooser(this);
    QAction* const heading = chooser.addAction(AirStrings::text(AirText::GroupAskChoose));
    heading->setEnabled(false);
    chooser.addSeparator();
    QVector<QAction*> actions;
    for (const Air::GroupMember& member : asked) {
        actions.append(
            chooser.addAction(AirStrings::text(AirText::GroupMemberStatus)
                                  .arg(member.name, AirStrings::groupMemberStatusText(member))));
    }
    // Over the button that asked, from its start edge; the keyboard reaches it as well.
    auto* const button =
        composer->findChild<QAbstractButton*>(QStringLiteral("composerReadOnlyAction"));
    QPoint at = QCursor::pos();
    if (button) {
        const QRect rect = button->rect();
        at = button->mapToGlobal(layoutDirection() == Qt::RightToLeft ? rect.topRight()
                                                                      : rect.topLeft());
        at.ry() -= chooser.sizeHint().height();
    }
    QAction* const picked = chooser.exec(at);
    const int index = actions.indexOf(picked);
    if (index >= 0) {
        showPrivate(asked.at(index).name, asked.at(index).publicKey);
    }
}

void GroupChatForm::showPrivate(const QString& name, const QString& peerKey)
{
    if (peerKey.isEmpty()) {
        return;
    }
    dossier->close();
    privateThread->showMember(name, peerKey);
    privateThread->setLines(airLocal ? airLocal->privateLines(groupKey(), peerKey)
                                     : QVector<Air::LocalStore::PrivateLine>());
    refreshPrivateRight();
    privateThread->open();
    privateThread->focusField();
}

void GroupChatForm::refreshPrivateRight()
{
    // An observer still reads what a moderator writes to them, but cannot answer.
    privateThread->setWritable(selfRole != Air::GroupRole::Observer,
                               AirStrings::text(AirText::GroupObserverNoPrivate));
}

void GroupChatForm::sendPrivate(const QString& peerKey, const QString& text)
{
    if (selfRole == Air::GroupRole::Observer) {
        refreshPrivateRight();
        return;
    }
    const int peerId = peerIdOf(peerKey);
    if (peerId < 0 || !core.sendGroupPrivateMessage(static_cast<int>(group->getId()), peerId, text)) {
        privateThread->failed();
        return;
    }
    // toxcore keeps no copy: this device does.
    if (airLocal) {
        airLocal->addPrivateLine(groupKey(), peerKey, true, text);
    }
    privateThread->sent();
}

void GroupChatForm::onTitleChanged(const QString& author, const QString& title)
{
    if (author.isEmpty()) {
        return;
    }

    const QDateTime curTime = QDateTime::currentDateTime();
    addSystemInfoMessage(curTime, SystemMessageType::titleChanged, {author, title});
}

void GroupChatForm::onScreenshotClicked()
{
    // Unsupported
}

void GroupChatForm::onAttachClicked()
{
    const QStringList paths = QFileDialog::getOpenFileNames(window(), tr("Send a file"),
                                                            QDir::homePath(), QString(), nullptr);
    askToSendFiles(paths);
}

void GroupChatForm::onPasteFiles(const QStringList& paths)
{
    askToSendFiles(paths);
}

void GroupChatForm::askToSendFiles(const QStringList& paths)
{
    if (right != Air::ComposeRight::Write) {
        return;
    }
    offerFiles(paths);
}

void GroupChatForm::sendPreparedFiles(const QStringList& paths)
{
    sendFilesToGroup(paths);
}

/**
 * @brief Send local files into the NGC group. NGC file transfers are a single
 * custom packet limited to NGC_GROUP_FILE_MAX_BYTES, so images are recompressed
 * to fit and oversized non-image files are rejected with an error popup.
 */
void GroupChatForm::sendFilesToGroup(const QStringList& paths)
{
    if (right != Air::ComposeRight::Write) {
        // Who may not write may not send files either, and the bar under the conversation says
        // why: the attachment is not offered and a dropped file is not taken.
        return;
    }
    // Every refusal is said, in one dialog for all the files chosen.
    const QString code = settings.getTranslation();
    const QLocale locale = code.isEmpty() ? QLocale::system() : QLocale(code);
    QStringList refusals;
    for (const QString& path : paths) {
        const QFileInfo info(path);
        if (!info.isFile() || !info.isReadable()) {
            refusals.append(AirStrings::text(AirStrings::Text::FileUnreadable).arg(info.fileName()));
            continue;
        }

        if (info.size() <= 0) {
            refusals.append(AirStrings::text(AirStrings::Text::FileEmpty).arg(info.fileName()));
            continue;
        }
        if (static_cast<quint64>(info.size()) > KHANDAQ_MAX_FILE_TRANSFER_BYTES) {
            refusals.append(AirStrings::text(AirStrings::Text::FileTooLarge)
                                .arg(info.fileName(),
                                     AirStrings::byteSizeText(Air::fileLimitShownBytes, locale)));
            continue;
        }

        QString sendName = info.fileName();
        QByteArray payload;

        if (info.size() <= static_cast<qint64>(NGC_SINGLE_PKT_MAX_FILESIZE)) {
            QFile in(path);
            if (!in.open(QIODevice::ReadOnly)) {
                refusals.append(
                    AirStrings::text(AirStrings::Text::FileUnreadable).arg(info.fileName()));
                continue;
            }
            payload = in.readAll();
        } else {
            if (!core.sendGroupFileFromPath(group->getId(), sendName, path)) {
                refusals.append(AirStrings::text(AirStrings::Text::FileGroupNotConnected));
            }
            continue;
        }

        if (payload.isEmpty()) {
            refusals.append(AirStrings::text(AirStrings::Text::FileUnreadable).arg(info.fileName()));
            continue;
        }

        if (!core.sendGroupFile(group->getId(), sendName, path, payload)) {
            refusals.append(AirStrings::text(AirStrings::Text::FileGroupNotConnected));
        }
    }
    if (!refusals.isEmpty()) {
        refusals.removeDuplicates();
        AirDialog::notice(this, AirStrings::text(AirStrings::Text::FileNotSentTitle),
                          refusals.join(QLatin1Char('\n')));
    }
}

/**
 * @brief Recompress an image so the encoded size fits the NGC packet limit.
 * Returns an empty array if the file is not a readable image or cannot be
 * shrunk enough.
 */
QByteArray GroupChatForm::compressImageForGroup(const QString& path, QString* sendName) const
{
    QImageReader reader(path);
    if (!reader.canRead()) {
        return {};
    }
    QImage image = reader.read();
    if (image.isNull()) {
        return {};
    }

    const int dims[] = {1280, 960, 800, 640, 480, 320, 240};
    const int qualities[] = {80, 70, 60, 50, 40, 30};

    for (int dim : dims) {
        QImage scaled = (image.width() > dim || image.height() > dim)
                            ? image.scaled(dim, dim, Qt::KeepAspectRatio, Qt::SmoothTransformation)
                            : image;
        for (int quality : qualities) {
            QByteArray out;
            QBuffer buffer(&out);
            buffer.open(QIODevice::WriteOnly);
            if (!scaled.save(&buffer, "JPEG", quality)) {
                return {};
            }
            if (out.size() <= static_cast<int>(NGC_SINGLE_PKT_MAX_FILESIZE)) {
                if (sendName != nullptr) {
                    *sendName = QFileInfo(path).completeBaseName() + QStringLiteral(".jpg");
                }
                return out;
            }
        }
    }
    return {};
}

void GroupChatForm::onUserJoined(const ToxPk& user, const QString& name)
{
    std::ignore = user;
    if (settings.getShowGroupJoinLeaveMessages()) {
        addSystemInfoMessage(QDateTime::currentDateTime(), SystemMessageType::userJoinedGroup, {name});
    }
    refreshLater.start();
}

void GroupChatForm::onUserLeft(const ToxPk& user, const QString& name)
{
    std::ignore = user;
    if (settings.getShowGroupJoinLeaveMessages()) {
        addSystemInfoMessage(QDateTime::currentDateTime(), SystemMessageType::userLeftGroup, {name});
    }
    refreshLater.start();
}

void GroupChatForm::onPeerNameChanged(const ToxPk& peer, const QString& oldName, const QString& newName)
{
    std::ignore = peer;
    addSystemInfoMessage(QDateTime::currentDateTime(), SystemMessageType::peerNameChanged,
                         {oldName, newName});
    refreshLater.start();
}

void GroupChatForm::peerAudioPlaying(ToxPk peerPk)
{
    // Their tile in the strip is ringed while their voice comes.
    band->setSpeaking(peerPk.toString());
}

void GroupChatForm::dragEnterEvent(QDragEnterEvent* ev)
{
    // A drop that would be refused is not taken: the pointer says so where it is, and the bar
    // under the conversation says why for one who may not write (E21).
    if (ev->mimeData()->hasUrls()) {
        if (right != Air::ComposeRight::Write) {
            return;
        }
        for (const QUrl& url : ev->mimeData()->urls()) {
            // A file, or a web address for the field (beta ideas 2026-11-20).
            if (url.isValid()) {
                ev->acceptProposedAction();
                return;
            }
        }
    }
    if (!ev->mimeData()->hasFormat("toxPk")) {
        return;
    }
    ToxPk toxPk{ev->mimeData()->data("toxPk")};
    Friend* frnd = friendList.findFriend(toxPk);
    // KHANDAQ (0.2.60): a contact offline now is taken too; their invitation waits for them.
    if (frnd)
        ev->acceptProposedAction();
}

void GroupChatForm::dropEvent(QDropEvent* ev)
{
    if (ev->mimeData()->hasUrls()) {
        QStringList paths;
        QStringList addresses;
        for (const QUrl& url : ev->mimeData()->urls()) {
            if (url.isLocalFile()) {
                paths.append(url.toLocalFile());
            } else if (url.isValid()) {
                // KHANDAQ (beta ideas 2026-11-20): a web address goes into the field, as in a
                // chat with a person.
                addresses.append(url.toString());
            }
        }
        if (!paths.isEmpty() || !addresses.isEmpty()) {
            if (right == Air::ComposeRight::Write) {
                putAddressesInField(addresses);
            }
            askToSendFiles(paths);
            return;
        }
    }
    if (!ev->mimeData()->hasFormat("toxPk")) {
        return;
    }
    ToxPk toxPk{ev->mimeData()->data("toxPk")};
    Friend* frnd = friendList.findFriend(toxPk);
    if (!frnd)
        return;

    // A contact dropped on the conversation is invited, and the toast says how it went: sent, or
    // kept until they are online.
    Air::InviteCandidate candidate;
    candidate.friendId = static_cast<int>(frnd->getId());
    candidate.publicKey = frnd->getPublicKey().toString();
    candidate.name = frnd->getDisplayedName();
    candidate.online = Status::isOnline(frnd->getStatus());
    inviteContacts({candidate});
}

void GroupChatForm::onMicMuteToggle()
{
    if (audioInputFlag) {
        CoreAV* av = core.getAv();
        const bool oldMuteState = av->isGroupCallInputMuted(group);
        const bool newMute = !oldMuteState;
        av->muteCallInput(group, newMute);
        headWidget->updateMuteMicButton(inCall, newMute);
    }
}

void GroupChatForm::onVolMuteToggle()
{
    if (audioOutputFlag) {
        CoreAV* av = core.getAv();
        const bool oldMuteState = av->isGroupCallOutputMuted(group);
        const bool newMute = !oldMuteState;
        av->muteCallOutput(group, newMute);
        headWidget->updateMuteVolButton(inCall, newMute);
    }
}

void GroupChatForm::onCallClicked()
{
    CoreAV* av = core.getAv();

    if (!inCall) {
        joinGroupCall();
    } else {
        leaveGroupCall();
    }

    headWidget->updateCallButtons(true, inCall);

    const bool inMute = av->isGroupCallInputMuted(group);
    headWidget->updateMuteMicButton(inCall, inMute);

    const bool outMute = av->isGroupCallOutputMuted(group);
    headWidget->updateMuteVolButton(inCall, outMute);
}

void GroupChatForm::keyPressEvent(QKeyEvent* ev)
{
    // Push to talk (CTRL+P)
    if (ev->key() == Qt::Key_P && (ev->modifiers() & Qt::ControlModifier) && inCall) {
        onMicMuteToggle();
    }

    if (msgEdit->hasFocus())
        return;
}

void GroupChatForm::keyReleaseEvent(QKeyEvent* ev)
{
    // Push to talk (CTRL+P)
    if (ev->key() == Qt::Key_P && (ev->modifiers() & Qt::ControlModifier) && inCall) {
        onMicMuteToggle();
    }

    if (msgEdit->hasFocus())
        return;
}

void GroupChatForm::retranslateUi()
{
    // The limit is said before a file is chosen, in the paperclip and the attach menu.
    const QString code = settings.getTranslation();
    const QString limit =
        AirStrings::groupFileLimitText(code.isEmpty() ? QLocale::system() : QLocale(code));
    fileButton->setToolTip(limit);
    setAttachLimit(limit);
    reportAction->setText(AirStrings::text(AirText::MessageReport));
    refreshMembers();
}

void GroupChatForm::prepareChatMenu(QPoint globalPos)
{
    ChatLogIdx idx{0};
    reportAction->setVisible(chatWidget->entryAtGlobalPos(globalPos, idx) && prepareReport(idx));
}

bool GroupChatForm::isSelfKey(const ToxPk& key) const
{
    if (key.isEmpty()) {
        return false;
    }
    // Our own entries carry the key of our profile; a group knows us by a key of its own too.
    const ToxPk groupSelf = core.getGroupSelfPublicKey(static_cast<int>(group->getId()));
    return key == core.getSelfPublicKey() || (!groupSelf.isEmpty() && key == groupSelf);
}

GenericChatForm::ReplyAuthor GroupChatForm::replyAuthorOf(const ChatLogItem& item) const
{
    const int groupNumber = static_cast<int>(group->getId());
    ReplyAuthor author;
    if (isSelfKey(item.getSender())) {
        // The members know us by the key the group gave us and by our name in it (refreshMembers).
        author.self = true;
        author.key = core.getGroupSelfPublicKey(groupNumber);
        Air::GroupMember self;
        for (const Air::GroupMember& member : members) {
            if (member.isSelf) {
                self = member;
                break;
            }
        }
        author.name = self.name.trimmed().isEmpty() ? core.getUsername() : self.name;
        return author;
    }
    author.key = item.getSender();
    // Their name in the group, which every member knows, not the one this device gives a contact.
    const int peerId = peerIdOf(author.key.toString());
    const QString peerName = peerId >= 0 ? core.getGroupPeerName(groupNumber, peerId) : QString();
    author.name = peerName.trimmed().isEmpty() ? item.getDisplayName() : peerName;
    return author;
}

bool GroupChatForm::canReactTo(ChatLogIdx idx) const
{
    // A text by the 4-byte id toxcore gave it, a file by its 32-byte id.
    constexpr int textIdBytes = 4;
    constexpr int fileIdBytes = 32;
    Air::MessageEffect::Anchor anchor = Air::MessageEffect::Anchor::Text;
    QByteArray id;
    if (selfRole == Air::GroupRole::Observer || !reactionAnchorOf(idx, &anchor, &id)
        || id.size() != (anchor == Air::MessageEffect::Anchor::Text ? textIdBytes : fileIdBytes)) {
        return false;
    }
    return !replyAuthorOf(chatLog.at(idx)).key.isEmpty();
}

void GroupChatForm::reactTo(ChatLogIdx idx, const QString& emoji)
{
    Air::MessageEffect::Anchor anchor = Air::MessageEffect::Anchor::Text;
    QByteArray id;
    if (!canReactTo(idx) || !reactionAnchorOf(idx, &anchor, &id)) {
        return;
    }
    // The members know whose it is by its key in the group, ours too.
    const QByteArray author = replyAuthorOf(chatLog.at(idx)).key.getByteArray();
    QString own;
    if (!chatLog.toggleOwnReaction(idx, emoji, &own)) {
        return;
    }
    const QByteArray packet =
        Air::groupReactionPacket(anchor, id, author,
                                 static_cast<quint32>(QDateTime::currentSecsSinceEpoch()), own,
                                 !own.isEmpty());
    if (packet.isEmpty() || !core.sendGroupCustomPacket(static_cast<int>(group->getId()), packet)) {
        qDebug() << "Our reaction did not go to group" << group->getId();
    }
}

void GroupChatForm::showAuthorMenu(ChatLogIdx idx, const QPoint& globalPos)
{
    if (idx < chatLog.getFirstIdx() || idx >= chatLog.getNextIdx()) {
        return;
    }
    const ChatLogItem& item = chatLog.at(idx);
    const ToxPk sender = item.getSender();
    if (sender.isEmpty() || isSelfKey(sender)
        || item.getContentType() == ChatLogItem::ContentType::systemMessage) {
        return;
    }
    Air::GroupMember member;
    const bool present = Air::memberWithKey(members, sender.toString(), &member);
    if (present && member.isSelf) {
        return;
    }
    AirGroupInfo info = dossierInfo();
    if (!present) {
        // Gone from the group, or not listed yet: the key still takes a mute and a hide.
        member.publicKey = sender.toString();
        // KHANDAQ (0.2.63): named as the transcript names them, a relayed author with no name too.
        member.name = Air::authorUnnamed(item.getDisplayName(), member.publicKey)
                          ? Air::unnamedAuthorName(member.publicKey)
                          : item.getDisplayName();
        if (airLocal && airLocal->isMuted(sender)) {
            info.mutedMembers.insert(member.publicKey);
        }
        if (settings.getBlackList().contains(member.publicKey)) {
            info.hiddenMembers.insert(member.publicKey);
        }
    }
    dossier->showMemberMenu(member, globalPos, info, present);
}

bool GroupChatForm::prepareReport(ChatLogIdx idx)
{
    const ChatLogItem& item = chatLog.at(idx);
    const ToxPk& sender = item.getSender();
    if (isSelfKey(sender)) {
        return false;
    }
    // KHANDAQ (0.2.57, review): the mail names the author only when toxcore vouches for them. An
    // entry without a key, or a file a member relayed, in this launch (ToxFile::relayed) or an
    // earlier one (AirLocal::isRelayed), is still reported, by nobody's name (Air::reportMailUrl).
    // 0.2.63: so is a relayed text, by the mark ChatHistory::onMessageSynced keeps.
    bool vouched = !sender.isEmpty();
    // KHANDAQ (0.2.63): the store is read once, as a plain pointer. A QPointer is read again on
    // each use, and GCC does not carry the check over to the second read; isRelayed is virtual
    // since #174, so the call loads through that read and the Windows build stopped here
    // (-Werror=null-dereference). Nothing below can delete the store: it lives as long as Widget.
    const AirLocal* const marks = airLocal.data();
    QString text;
    switch (item.getContentType()) {
    case ChatLogItem::ContentType::message:
        text = item.getContentAsMessage().message.content;
        // KHANDAQ (0.2.63): nor a text a member relayed from the history (ChatHistory marks it).
        if (marks
            && marks->isRelayed(Air::textEntryKey(groupKey(), sender.toString(),
                                                  item.getTimestamp().toMSecsSinceEpoch()))) {
            vouched = false;
        }
        break;
    case ChatLogItem::ContentType::fileTransfer: {
        // A file says what it is by its name; what is in it stays on this computer.
        const ToxFile& file = item.getContentAsFile().file;
        text = file.fileName;
        const bool relayed =
            file.relayed
            || (marks && !file.resumeFileId.isEmpty()
                && marks->isRelayed(Air::fileEntryKey(groupKey(), file.resumeFileId)));
        vouched = vouched && !relayed;
        break;
    }
    case ChatLogItem::ContentType::systemMessage:
        return false;
    }
    menuReport = Air::GroupReport{group->getName(), vouched ? sender.toString() : QString(),
                                  item.getTimestamp(), text};
    return true;
}

void GroupChatForm::report()
{
    const QUrl mail = Air::reportMailUrl(menuReport);
    // Who and where, never the words of the message.
    qDebug() << "Report: a mail to" << mail.path() << "about a message of"
             << menuReport.authorKey.left(8) << "in a group," << mail.toEncoded().size() << "bytes";
    if (!QDesktopServices::openUrl(mail)) {
        qWarning() << "Report: no mail app took the mail";
        // KHANDAQ (0.2.57, review): as iOS does, the person sees where to write instead: the title
        // of Report and support's address, no new words to translate.
        AirDialog::notice(this, AirStrings::text(AirText::MessageReport),
                          QString::fromLatin1(Air::reportAddress));
    }
}

void GroupChatForm::joinGroupCall()
{
    CoreAV* av = core.getAv();
    av->joinGroupCall(*group);
    audioInputFlag = true;
    audioOutputFlag = true;
    inCall = true;
}

void GroupChatForm::leaveGroupCall()
{
    CoreAV* av = core.getAv();
    av->leaveGroupCall(group->getId());
    audioInputFlag = false;
    audioOutputFlag = false;
    inCall = false;
}
