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

#include "airgroup.h"

#include "src/model/air/airmodel.h"

#include <QRegularExpression>

#include <algorithm>

namespace Air {

int roleRank(GroupRole role)
{
    switch (role) {
    case GroupRole::Founder:
        return 0;
    case GroupRole::Moderator:
        return 1;
    case GroupRole::Member:
        return 2;
    case GroupRole::Observer:
        return 3;
    }
    return 2;
}

bool canSetRole(GroupRole role, GroupRole target, GroupRole actor)
{
    if (role == target || role == GroupRole::Founder || target == GroupRole::Founder) {
        return false;
    }
    switch (actor) {
    case GroupRole::Founder:
        return true;
    case GroupRole::Moderator:
        return target != GroupRole::Moderator && role != GroupRole::Moderator;
    case GroupRole::Member:
    case GroupRole::Observer:
        return false;
    }
    return false;
}

bool canRemove(GroupRole target, GroupRole actor)
{
    switch (actor) {
    case GroupRole::Founder:
        return target != GroupRole::Founder;
    case GroupRole::Moderator:
        return target == GroupRole::Member || target == GroupRole::Observer;
    case GroupRole::Member:
    case GroupRole::Observer:
        return false;
    }
    return false;
}

QString GroupMember::toneKey() const
{
    return publicKey.isEmpty() ? name : publicKey;
}

bool GroupMember::operator==(const GroupMember& other) const
{
    return peerId == other.peerId && name == other.name && publicKey == other.publicKey
           && role == other.role && online == other.online && isSelf == other.isSelf;
}

QVector<GroupMember> sortedRoster(QVector<GroupMember> members)
{
    std::stable_sort(members.begin(), members.end(),
                     [](const GroupMember& left, const GroupMember& right) {
                         const int leftRank = roleRank(left.role);
                         const int rightRank = roleRank(right.role);
                         if (leftRank != rightRank) {
                             return leftRank < rightRank;
                         }
                         if (left.online != right.online) {
                             return left.online;
                         }
                         const int order = compareTitles(left.name, right.name);
                         if (order != 0) {
                             return order < 0;
                         }
                         return left.peerId < right.peerId;
                     });
    return members;
}

QVector<GroupMember> filteredRoster(const QVector<GroupMember>& members, const QString& query)
{
    const QString folded = fold(query.trimmed());
    if (folded.isEmpty()) {
        return members;
    }
    QVector<GroupMember> kept;
    for (const GroupMember& member : members) {
        if (fold(member.name).contains(folded)) {
            kept.append(member);
        }
    }
    return kept;
}

QVector<GroupMember> reachableNow(const QVector<GroupMember>& members)
{
    QVector<GroupMember> online;
    for (const GroupMember& member : members) {
        if (member.online && !member.isSelf) {
            online.append(member);
        }
    }
    return sortedRoster(online);
}

QVector<GroupMember> askable(const QVector<GroupMember>& members, bool founderOnly)
{
    QVector<GroupMember> kept;
    for (const GroupMember& member : members) {
        if (member.isSelf) {
            continue;
        }
        if (member.role == GroupRole::Founder || (!founderOnly && member.role == GroupRole::Moderator)) {
            kept.append(member);
        }
    }
    // Online first: the one who reads it now comes before the founder who is away.
    std::stable_sort(kept.begin(), kept.end(), [](const GroupMember& left, const GroupMember& right) {
        if (left.online != right.online) {
            return left.online;
        }
        return roleRank(left.role) < roleRank(right.role);
    });
    return kept;
}

GroupReach GroupReach::make(int peerCount, int knownMembers, int online, bool connected)
{
    GroupReach reach;
    reach.total = std::max({peerCount, knownMembers, 1});
    reach.connected = connected;
    reach.online = connected ? std::min(std::max(online, 0), reach.total - 1) : 0;
    return reach;
}

int GroupReach::recipients() const
{
    return total - 1;
}

bool GroupReach::operator==(const GroupReach& other) const
{
    return total == other.total && online == other.online && connected == other.connected;
}

GroupHint GroupHint::make(const GroupReach& reach, NetworkState self)
{
    // No amber dot: nothing waits in transit, Send waits instead (sendWaits).
    GroupHint hint;
    if (!reach.connected) {
        // Without a connection to the group, the line says why when the reason is our own node.
        hint.kind = self == NetworkState::Connecting ? Kind::SelfConnecting
                    : self == NetworkState::Offline  ? Kind::SelfOffline
                                                     : Kind::Offline;
        hint.dot = ComposeHint::Dot::None;
    } else if (reach.recipients() == 0) {
        hint.kind = Kind::Alone;
        hint.dot = ComposeHint::Dot::None;
    } else if (reach.online == 0) {
        hint.kind = Kind::Nobody;
        hint.dot = ComposeHint::Dot::None;
    } else if (reach.online == reach.recipients()) {
        // One other member is a case of its own, not a plural form: Russian takes 21 as "one" too.
        hint.kind = reach.recipients() == 1 ? Kind::OneOther : Kind::All;
        hint.dot = ComposeHint::Dot::Live;
    } else {
        hint.kind = Kind::Some;
        hint.dot = ComposeHint::Dot::Live;
    }
    return hint;
}

bool GroupHint::sendWaits() const
{
    return kind == Kind::Offline || kind == Kind::SelfConnecting || kind == Kind::SelfOffline
           || kind == Kind::Alone || kind == Kind::Nobody;
}

bool GroupHint::operator==(const GroupHint& other) const
{
    return kind == other.kind && dot == other.dot;
}

ComposeRight composeRight(GroupRole self, GroupVoice voice)
{
    if (self == GroupRole::Observer) {
        return ComposeRight::Observer;
    }
    switch (voice) {
    case GroupVoice::All:
        return ComposeRight::Write;
    case GroupVoice::Moderators:
        return self == GroupRole::Founder || self == GroupRole::Moderator
                   ? ComposeRight::Write
                   : ComposeRight::ModeratorsOnly;
    case GroupVoice::Founder:
        return self == GroupRole::Founder ? ComposeRight::Write : ComposeRight::FounderOnly;
    }
    return ComposeRight::Write;
}

bool actionRole(MemberAction action, GroupRole* role)
{
    switch (action) {
    case MemberAction::MakeModerator:
        *role = GroupRole::Moderator;
        return true;
    case MemberAction::MakeMember:
        *role = GroupRole::Member;
        return true;
    case MemberAction::MakeObserver:
        *role = GroupRole::Observer;
        return true;
    case MemberAction::Remove:
        break;
    }
    return false;
}

QVector<MemberAction> memberActions(GroupRole target, GroupRole actor)
{
    QVector<MemberAction> actions;
    for (const MemberAction action :
         {MemberAction::MakeModerator, MemberAction::MakeMember, MemberAction::MakeObserver}) {
        GroupRole role = GroupRole::Member;
        if (actionRole(action, &role) && canSetRole(role, target, actor)) {
            actions.append(action);
        }
    }
    if (canRemove(target, actor)) {
        actions.append(MemberAction::Remove);
    }
    return actions;
}

QString cleanGroupName(const QString& text)
{
    static const QRegularExpression space(QStringLiteral("\\s+"));
    return QString(text).replace(space, QStringLiteral(" ")).trimmed();
}

int groupNameOverflow(const QString& text)
{
    const int bytes = cleanGroupName(text).toUtf8().size();
    return bytes > groupNameLimitBytes ? bytes - groupNameLimitBytes : 0;
}

bool InviteCandidate::canInvite() const
{
    return online && friendId >= 0;
}

bool InviteCandidate::operator==(const InviteCandidate& other) const
{
    return friendId == other.friendId && publicKey == other.publicKey && name == other.name
           && online == other.online;
}

QVector<InviteCandidate> arrangedInvites(QVector<InviteCandidate> candidates, const QString& query)
{
    const QString folded = fold(query.trimmed());
    if (!folded.isEmpty()) {
        QVector<InviteCandidate> kept;
        for (const InviteCandidate& candidate : candidates) {
            if (fold(candidate.name).contains(folded)) {
                kept.append(candidate);
            }
        }
        candidates = kept;
    }
    std::stable_sort(candidates.begin(), candidates.end(),
                     [](const InviteCandidate& left, const InviteCandidate& right) {
                         if (left.canInvite() != right.canInvite()) {
                             return left.canInvite();
                         }
                         const int order = compareTitles(left.name, right.name);
                         if (order != 0) {
                             return order < 0;
                         }
                         return left.publicKey < right.publicKey;
                     });
    return candidates;
}

} // namespace Air
