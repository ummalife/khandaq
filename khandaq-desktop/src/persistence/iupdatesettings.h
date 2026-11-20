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

#include <QNetworkProxy>
#include <QString>

/** KHANDAQ (0.2.65): what the update check reads and keeps (General in khandaq.ini). */
class IUpdateSettings
{
public:
    IUpdateSettings() = default;
    virtual ~IUpdateSettings();
    IUpdateSettings(const IUpdateSettings&) = default;
    IUpdateSettings& operator=(const IUpdateSettings&) = default;
    IUpdateSettings(IUpdateSettings&&) = default;
    IUpdateSettings& operator=(IUpdateSettings&&) = default;

    /** The switch in About: whether Khandaq checks by itself. */
    virtual bool getCheckUpdates() const = 0;
    /** The proxy of the profile, which every request of the update goes through. */
    virtual QNetworkProxy getProxy() const = 0;
    /** When a check last went through, in seconds since the epoch; 0 for never. */
    virtual qint64 getUpdateLastCheck() const = 0;
    virtual void setUpdateLastCheck(qint64 secondsSinceEpoch) = 0;
    /** The highest version a verified manifest has shown ("0.2.65"); empty for none. */
    virtual QString getUpdateHighestSeen() const = 0;
    virtual void setUpdateHighestSeen(const QString& version) = 0;
    /** Writes the settings to disk. */
    virtual void saveGlobal() = 0;
};
