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

#include "airnetwork.h"

namespace Air {

int Nodes::total() const
{
    return relays + directContacts;
}

bool Nodes::operator==(const Nodes& other) const
{
    return relays == other.relays && directContacts == other.directContacts;
}

bool Nodes::operator!=(const Nodes& other) const
{
    return !(*this == other);
}

NetworkReport NetworkReport::make(NetworkState state)
{
    NetworkReport report;
    report.state = state;
    switch (state) {
    case NetworkState::Direct:
        report.link = Link::Direct;
        break;
    case NetworkState::Relay:
        report.link = Link::Relay;
        break;
    case NetworkState::Connecting:
    case NetworkState::Offline:
        report.link = Link::None;
        break;
    }
    report.internet = state != NetworkState::Offline;
    report.live = state == NetworkState::Direct || state == NetworkState::Relay;
    report.offersReconnect = state == NetworkState::Connecting;
    report.alarm = state == NetworkState::Offline;
    return report;
}

bool NetworkReport::operator==(const NetworkReport& other) const
{
    return state == other.state && internet == other.internet && link == other.link
           && live == other.live && offersReconnect == other.offersReconnect && alarm == other.alarm;
}

} // namespace Air
