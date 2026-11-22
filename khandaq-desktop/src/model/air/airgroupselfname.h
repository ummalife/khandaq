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

#include <QByteArray>
#include <QMap>
#include <QString>

namespace Air {

/**
 * KHANDAQ (beta feedback 2026-11-22): our name in each group. toxcore keeps one name of ours per
 * group (tox_group_self_set_name) and saves it with the group, but gives the profile name to
 * friends only: a rename of the profile reached no group, and every join took the profile name.
 * A group has a name of its own once one is set in its dossier, and keeps it; every other group
 * follows the profile: a rename passes to it, and a join or a rejoin gives it the profile name
 * (Core::setUsername, Core::applyGroupSelfName). This is what the profile keeps of the groups with
 * a name of their own, by their chat id, as iOS and Android do.
 */
class GroupSelfNames
{
public:
    /** The name we should have in the group: its own, else the profile name. */
    QString nameFor(const QByteArray& chatId, const QString& profileName) const;
    /** Whether the group has a name of its own, which a rename of the profile leaves as it is. */
    bool hasOwn(const QByteArray& chatId) const;
    /**
     * Our name in the group, set in its dossier: kept as the group's own, unless it is the profile
     * name, with which the group follows the profile again. False when nothing changed.
     */
    bool choose(const QByteArray& chatId, const QString& name, const QString& profileName);
    /**
     * We left the group: a later join takes the profile name. False when it had no name of its
     * own.
     */
    bool forget(const QByteArray& chatId);
    bool isEmpty() const;

    /** What the profile keeps (Settings::getGroupSelfNames): a JSON object, by chat id in hex. */
    QString store() const;
    /** What store() wrote; an entry without a chat id or a name is skipped. */
    static GroupSelfNames parseStore(const QString& stored);

private:
    QMap<QByteArray, QString> names;
};

/**
 * Whether toxcore should be given wanted as our name in a group where it holds current: a name it
 * takes (one to nameLimitBytes of UTF-8) that is not the one there.
 */
bool needsGroupSelfName(const QString& current, const QString& wanted);

} // namespace Air
