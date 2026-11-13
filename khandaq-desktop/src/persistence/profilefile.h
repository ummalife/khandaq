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
#include <QString>

/**
 * A profile file to be moved in (E20), read before it is copied to this device: whether it takes a
 * password, then the name and the ID it holds, which the person checks before confirming. The file
 * is only read: toxcore loads it without network, and nothing of it is written anywhere.
 */
namespace ProfileFile {

/** No profile file comes near this size: thousands of contacts and groups take a few megabytes. */
constexpr qint64 maxBytes = 64 * 1024 * 1024;

struct Contents
{
    enum class Problem
    {
        None,
        /** The file is encrypted and no password was given to open it. */
        NeedsPassword,
        /** The password given does not open the file. */
        WrongPassword,
        /** Neither an encrypted save nor a plain one: not a profile file at all. */
        NotProfile,
        /** It is a save, but cut short or one toxcore does not load. */
        Damaged,
        /** The file could not be read, or it is far larger than any profile. */
        Unreadable,
    };

    Problem problem = Problem::None;
    bool encrypted = false;
    /** The name the profile's contacts see; empty when it has none. */
    QString name;
    /** The ID, 76 hexadecimal characters in upper case. */
    QString id;
    /** For support, under Details: what failed and its code. */
    QString code;
};

/** Reads the bytes of a profile file, with password when the file is encrypted. */
Contents read(const QByteArray& data, const QString& password);

/** Reads the file at path, refusing one larger than maxBytes. */
Contents readFile(const QString& path, const QString& password);

} // namespace ProfileFile
