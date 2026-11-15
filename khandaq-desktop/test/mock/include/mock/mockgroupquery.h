/*
    Copyright © 2020 by The qTox Project Contributors

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

#include "src/core/icoregroupquery.h"

/**
 * Mock 1 peer at group number 0, and as many others as set (one by default)
 */
class MockGroupQuery : public ICoreGroupQuery
{
public:
    MockGroupQuery() = default;
    virtual ~MockGroupQuery();
    MockGroupQuery(const MockGroupQuery&) = default;
    MockGroupQuery& operator=(const MockGroupQuery&) = default;
    MockGroupQuery(MockGroupQuery&&) = default;
    MockGroupQuery& operator=(MockGroupQuery&&) = default;

    GroupId getGroupPersistentId(uint32_t groupNumber) const override
    {
        std::ignore = groupNumber;
        return GroupId(0);
    }

    uint32_t getGroupNumberPeers(int groupId) const override
    {
        std::ignore = groupId;
        if (emptyGroup) {
            return 1;
        }

        return 1 + others;
    }

    QVector<uint32_t> getGroupPeerList(int groupId) const override
    {
        std::ignore = groupId;
        if (emptyGroup) {
            return {0};
        }
        QVector<uint32_t> peers;
        for (uint32_t peer = 0; peer <= others; ++peer) {
            peers.append(peer);
        }
        return peers;
    }

    QString getGroupPeerName(int groupId, int peerId) const override
    {
        std::ignore = groupId;
        return QString("peer") + peerId;
    }

    ToxPk getGroupPeerPk(int groupId, int peerId) const override
    {
        std::ignore = groupId;
        uint8_t id[ToxPk::size] = {static_cast<uint8_t>(peerId)};
        return ToxPk(id);
    }

    QStringList getGroupPeerNames(int groupId) const override
    {
        std::ignore = groupId;
        if (emptyGroup) {
            return QStringList({QString("me")});
        }
        QStringList names({QString("me"), QString("other")});
        for (uint32_t peer = 2; peer <= others; ++peer) {
            names.append(QString("other%1").arg(peer));
        }
        return names;
    }

    bool getGroupAvEnabled(int groupId) const override
    {
        std::ignore = groupId;
        return false;
    }

    void setAsEmptyGroup()
    {
        emptyGroup = true;
    }

    void setAsFunctionalGroup()
    {
        emptyGroup = false;
    }

    /** How many others a functional group has: peers 1 to count. */
    void setOthers(uint32_t count)
    {
        others = count;
    }

private:
    bool emptyGroup = false;
    uint32_t others = 1;
};
