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

#include "src/model/air/airconversation.h"

#include <QString>
#include <QVector>

/**
 * What the group screens (E13 to E15) say and allow, apart from any widget: who is in a group and
 * in what order, who gets a message right now, who may write, and what the founder and the
 * moderators may change. A port of khandaq-ios/Antidote/AirGroupModel.swift with the same rules:
 * the protocol is NGC as it is, the client asks toxcore before acting, and these values only decide
 * what to show.
 */
namespace Air {

/** The roles of an NGC group, founder first. */
enum class GroupRole
{
    Founder,
    Moderator,
    Member,
    Observer,
};

/** Who may write: everyone above observer, the moderators and the founder, or the founder alone. */
enum class GroupVoice
{
    All,
    Moderators,
    Founder,
};

/** Who can join: only people a member invites, or anyone who has the group key. */
enum class GroupPrivacy
{
    InviteOnly,
    Open,
};

/** Founder 0, moderator 1, member 2, observer 3. */
int roleRank(GroupRole role);

/**
 * Whether actor may give target the role role: the founder gives any role but founder to anyone
 * else; a moderator moves members and observers between those two roles.
 */
bool canSetRole(GroupRole role, GroupRole target, GroupRole actor);

/** Whether actor may remove a member with the role target from the group. */
bool canRemove(GroupRole target, GroupRole actor);

/** A member of a group as the group screens show them. */
struct GroupMember
{
    quint32 peerId = 0;
    QString name;
    /** The member's key in this group (NGC keys are per group), upper-case hex; empty if unknown. */
    QString publicKey;
    GroupRole role = GroupRole::Member;
    bool online = false;
    bool isSelf = false;

    /** Where the tone and the initials come from: the key when it is known, else the name. */
    QString toneKey() const;
    bool operator==(const GroupMember& other) const;
};

/** From this many members on, the dossier offers a search by name. */
constexpr int groupSearchFrom = 20;

/** By role, then the ones online now, then by name, then by peer number. */
QVector<GroupMember> sortedRoster(QVector<GroupMember> members);

/** The members whose name holds the query, ignoring case and marks; all of them for no query. */
QVector<GroupMember> filteredRoster(const QVector<GroupMember>& members, const QString& query);

/** Who gets a message the moment it is sent: everyone online but you, in the order of the strip. */
QVector<GroupMember> reachableNow(const QVector<GroupMember>& members);

/**
 * Whom someone who may not write can ask for the right to: the founder alone when only the
 * founder writes, otherwise the moderators and the founder; online first.
 */
QVector<GroupMember> askable(const QVector<GroupMember>& members, bool founderOnly);

/**
 * How many are in the group and how many get a message now: the line under the header and the
 * hint under the field. The count is toxcore's peer count, which includes you, or the members
 * listed when that is larger; online counts the others, and nobody is online while the group is not
 * connected.
 */
struct GroupReach
{
    int total = 1;
    int online = 0;
    bool connected = false;

    static GroupReach make(int peerCount, int knownMembers, int online, bool connected);
    /** Everyone a message is for: the members but you. */
    int recipients() const;
    bool operator==(const GroupReach& other) const;
};

/** The line under the field of a group: who gets a message sent now. */
struct GroupHint
{
    enum class Kind
    {
        /** No connection to the group. */
        Offline,
        /** No connection to the group while our own node looks for the network. */
        SelfConnecting,
        /** No connection to the group: this computer has no network. */
        SelfOffline,
        /** Nobody else is in the group yet. */
        Alone,
        /** Nobody else is online. */
        Nobody,
        /** The one other member is online. */
        OneOther,
        /** Every other member is online. */
        All,
        /** Some of the others are online. */
        Some,
    };

    Kind kind = Kind::Offline;
    ComposeHint::Dot dot = ComposeHint::Dot::None;

    /**
     * Without a connection to the group while our own node is not linked (self), the line says
     * that our node is the reason. A group still connected (members on the same local network)
     * keeps its own words: a message sent then reaches them.
     */
    static GroupHint make(const GroupReach& reach, NetworkState self = NetworkState::Direct);
    /**
     * Whether Send waits, for want of anyone to get the message: no connection, nobody else in the
     * group, or nobody else online. NGC keeps no history, and this client neither keeps group
     * messages for later nor serves them to members who come online, so a message sent then would
     * reach nobody (the phones differ here: they keep and sync group history).
     */
    bool sendWaits() const;
    bool operator==(const GroupHint& other) const;
};

/** Whether you may write in the group now. Observers never may; the voice state can narrow it. */
enum class ComposeRight
{
    Write,
    Observer,
    ModeratorsOnly,
    FounderOnly,
};

ComposeRight composeRight(GroupRole self, GroupVoice voice);

/** What the founder or a moderator can do to one member, in the order of the menu. */
enum class MemberAction
{
    MakeModerator,
    MakeMember,
    MakeObserver,
    Remove,
};

/** The actions on target that actor may take, by the rules above. */
QVector<MemberAction> memberActions(GroupRole target, GroupRole actor);

/** The role a role action gives; false for Remove. */
bool actionRole(MemberAction action, GroupRole* role);

/**
 * NGC carries at most 1372 bytes in one group message (TOX_GROUP_MAX_MESSAGE_LENGTH) and toxcore
 * refuses a longer one instead of cutting it: the field counts down its last tenth (composeRoom)
 * and Send waits over the limit.
 */
constexpr int groupMessageLimitBytes = 1372;

/** NGC takes at most 48 bytes of a group name (TOX_GROUP_MAX_GROUP_NAME_LENGTH). */
constexpr int groupNameLimitBytes = 48;

/** One line: a pasted line break or a run of spaces becomes one space, and the ends are trimmed. */
QString cleanGroupName(const QString& text);

/** 0 while the cleaned name fits; otherwise the bytes over the limit. */
int groupNameOverflow(const QString& text);

/** A contact on the invitation lists of New group and the group dossier. */
struct InviteCandidate
{
    /** Tox's friend number; negative when there is none. */
    int friendId = -1;
    QString publicKey;
    QString name;
    bool online = false;

    /**
     * tox_group_invite_friend goes over the friend connection, so only a contact online now can be
     * invited; the others wait in the list.
     */
    bool canInvite() const;
    bool operator==(const InviteCandidate& other) const;
};

/** Those who can be invited first, then by name; a query keeps the names that hold it. */
QVector<InviteCandidate> arrangedInvites(QVector<InviteCandidate> candidates, const QString& query);

} // namespace Air
