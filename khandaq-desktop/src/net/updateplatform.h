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
#include <QStringList>

/**
 * KHANDAQ (0.2.66): how this copy of Khandaq was installed, and how a new one takes its place.
 *
 * The Windows installer is run again, silently and elevated, over the installed copy. A portable
 * Windows folder and a macOS bundle are swapped by a small script that waits for Khandaq to quit,
 * moves the old copy aside, moves the new one in, keeps the old one if anything fails and starts
 * whichever copy is in place. Linux is left to the package manager or to the person: the verified
 * file is kept and shown. Nothing here downloads or decides; it only names and writes.
 */
namespace UpdatePlatform {

enum class Kind
{
    None,
    WindowsInstaller,
    WindowsZip,
    MacZip,
    LinuxDeb,
    LinuxPortable,
};

struct Layout
{
    Kind kind = Kind::None;
    /** The copy that is replaced: the install folder (over bin), the portable folder or the .app
     * bundle; the folder the program is in on Linux. */
    QString root;
};

/** The manifest's platform key for a kind; empty for None. */
QString key(Kind kind);

/**
 * The layout of the copy whose executable lies in applicationDir. Only the kinds a release is
 * published for count: x86-64 Windows and Linux, and arm64 macOS, which is what khandaq-macos.zip
 * holds. Flatpak, Snap and AppImage copies are updated by what installed them.
 */
Layout detect(const QString& applicationDir);

/** The silent installer's arguments: /D comes last and unquoted, as NSIS requires. */
QString installerArguments(qint64 pid, const QString& installDir);

/** The paths of one swap of a folder or bundle, all next to it, so that every move is a rename. */
struct Swap
{
    /** The copy that is replaced. */
    QString root;
    /** Where the new copy is unpacked. */
    QString staging;
    /** The new copy inside staging: staging itself on Windows, the bundle in it on macOS. */
    QString staged;
    /** Where the current copy goes. */
    QString old;
    /** Windows: the program file the helper starts. */
    QString executable;
    /** Windows: what a portable folder holds that the new copy does not (the settings, the
     * profiles), moved into the new folder by name. */
    QStringList carried;
};

/** The paths for replacing root, told apart from earlier swaps by stamp. */
Swap plan(Kind kind, const QString& root, qint64 stamp);

/**
 * The names in root that a swap carries into the new copy: everything the new copy (staged) has no
 * entry of the same name for, hidden entries included, except programs and libraries (.exe and
 * .dll), which belong to the old copy, and the locks of profiles (.lock), which Khandaq removes as it
 * quits.
 */
QStringList carriedNames(const QString& root, const QString& staged);

/** The file the helper leaves in the old copy once the new one is in place. */
constexpr char oldMarker[] = "khandaq-update-old";

/** The Windows helper (cmd.exe). Pure ASCII with CRLF line ends; the paths reach it only through
 * the environment (windowsEnvironment). */
QString windowsScript();
/** "NAME=value" for each variable the Windows helper reads; note is the text file it opens when the
 * person's files may not all be back in place (windowsNote), none when empty. */
QStringList windowsEnvironment(const Swap& swap, qint64 pid, const QString& note = QString());
/**
 * The text file the Windows helper opens when a rollback could not put everything of the person's
 * back, or the old copy had to stay under another name: intro, the three folders of the swap, one
 * a line, and action. UTF-8 with a byte order mark and CRLF line ends, which every Notepad reads.
 */
QByteArray windowsNote(const Swap& swap, const QString& intro, const QString& action);
/** The macOS helper (/bin/sh). The paths reach it only as arguments (macArguments). */
QString macScript();
/** The arguments of /bin/sh: the script, then what it reads; opener is /usr/bin/open but in tests. */
QStringList macArguments(const QString& scriptPath, const Swap& swap, qint64 pid,
                         const QString& executableName, const QString& opener);

/**
 * What earlier swaps of root left next to it and can go: old copies the helper marked once the new
 * one was in place, and unpacked copies a helper never used. An old Windows folder that still holds
 * anything but programs, libraries, locks and names the current folder has too is kept, and so is
 * an unpacked Windows folder that holds anything but programs, libraries and locks: a rollback that
 * could not put the person's files back leaves them there.
 */
QStringList leftovers(Kind kind, const QString& root);

} // namespace UpdatePlatform
