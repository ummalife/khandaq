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

#include "updateplatform.h"

#include <QDir>
#include <QFileInfo>
#include <QRegularExpression>

#include <tuple>

namespace {
constexpr char windowsExecutable[] = "Khandaq.exe";

/**
 * Waits for Khandaq to quit, moves the folder aside, moves the new one in and carries the user's
 * things (a portable copy keeps its settings and profiles in its folder) across by name. Any failure
 * puts everything back and starts the old copy. Delayed expansion is on and paths are only ever
 * written !NAME! inside quotes: the value of a variable is not parsed again, so a folder name with
 * &, ^, %, ! or letters of any language is only ever a name. Numbers alone use %NAME%.
 */
constexpr char windowsHelper[] = R"script(@echo off
rem Khandaq update helper for a portable copy. Khandaq writes this file, sets the KHQ_ variables
rem and starts it as it quits. Every path comes from those variables. The tools are named by their
rem full path, so that another find or ping earlier on PATH is never run.
setlocal EnableExtensions EnableDelayedExpansion
set /a waited=0
:wait_for_quit
"%SystemRoot%\System32\tasklist.exe" /FI "PID eq %KHQ_PID%" /NH 2>nul | "%SystemRoot%\System32\find.exe" " %KHQ_PID% " >nul
if errorlevel 1 goto move_old
set /a waited+=1
if !waited! geq 120 goto give_up
"%SystemRoot%\System32\PING.EXE" -n 2 127.0.0.1 >nul
goto wait_for_quit

:give_up
rem Khandaq did not quit: nothing was touched.
rd /s /q "!KHQ_STAGING!" >nul 2>nul
exit /b 1

:move_old
rem A folder of that name would take the copy inside it rather than be its new name.
if exist "!KHQ_OLD!" goto keep_old
set /a tries=0
:move_old_again
move "!KHQ_ROOT!" "!KHQ_OLD!" >nul 2>nul
if not errorlevel 1 goto move_new
set /a tries+=1
if !tries! geq 30 goto keep_old
"%SystemRoot%\System32\PING.EXE" -n 2 127.0.0.1 >nul
goto move_old_again

:keep_old
rem A file in the folder is still open: the copy stays as it is.
rd /s /q "!KHQ_STAGING!" >nul 2>nul
start "" /D "!KHQ_ROOT!" "!KHQ_ROOT!\!KHQ_EXE!"
exit /b 1

:move_new
if exist "!KHQ_ROOT!" goto old_elsewhere
move "!KHQ_STAGING!" "!KHQ_ROOT!" >nul 2>nul
if errorlevel 1 goto restore_old
set /a carried=0
:carry
if !carried! geq %KHQ_CARRY_COUNT% goto done
set /a carried+=1
move "!KHQ_OLD!\!KHQ_CARRY_%carried%!" "!KHQ_ROOT!\!KHQ_CARRY_%carried%!" >nul 2>nul
if errorlevel 1 goto carry_back
goto carry

:carry_back
rem Rollback: what was carried goes back, then the old folder takes its place again.
set /a back=0
:carry_back_next
if !back! geq %KHQ_CARRY_COUNT% goto remove_new
set /a back+=1
if exist "!KHQ_ROOT!\!KHQ_CARRY_%back%!" if not exist "!KHQ_OLD!\!KHQ_CARRY_%back%!" move "!KHQ_ROOT!\!KHQ_CARRY_%back%!" "!KHQ_OLD!\!KHQ_CARRY_%back%!" >nul 2>nul
goto carry_back_next

:remove_new
move "!KHQ_ROOT!" "!KHQ_STAGING!" >nul 2>nul
:restore_old
rem Rollback: the old folder takes its place again and starts.
move "!KHQ_OLD!" "!KHQ_ROOT!" >nul 2>nul
if errorlevel 1 goto old_elsewhere
rd /s /q "!KHQ_STAGING!" >nul 2>nul
start "" /D "!KHQ_ROOT!" "!KHQ_ROOT!\!KHQ_EXE!"
exit /b 1

:old_elsewhere
rem The old copy could not go back under its name; it still works where it is.
start "" /D "!KHQ_OLD!" "!KHQ_OLD!\!KHQ_EXE!"
exit /b 1

:done
copy /y nul "!KHQ_OLD!\khandaq-update-old" >nul 2>nul
start "" /D "!KHQ_ROOT!" "!KHQ_ROOT!\!KHQ_EXE!"
exit /b 0
)script";

/** The same for the macOS bundle: the paths come as arguments and only ever stand in double quotes. */
constexpr char macHelper[] = R"script(#!/bin/sh
# Khandaq update helper for macOS. Khandaq writes this file and starts it as it quits:
# $1 its process, $2 the bundle, $3 the new bundle, $4 where the bundle goes aside,
# $5 the folder the new bundle was unpacked into, $6 the name of the program in the bundle,
# $7 what opens a bundle (/usr/bin/open).
PATH=/usr/bin:/bin:/usr/sbin:/sbin
export PATH
pid="$1"
app="$2"
staged="$3"
old="$4"
staging="$5"
program="$6"
opener="$7"
waited=0
while kill -0 "$pid" 2>/dev/null; do
    waited=$((waited + 1))
    if [ "$waited" -ge 240 ]; then
        # Khandaq did not quit: nothing was touched.
        rm -rf "$staging"
        exit 1
    fi
    sleep 0.5
done
# mv into a folder that is there would put the bundle inside it rather than rename it.
if [ -e "$old" ] || ! mv "$app" "$old"; then
    rm -rf "$staging"
    exec "$opener" "$app"
fi
if [ -e "$app" ] || ! mv "$staged" "$app"; then
    # Rollback: the old bundle takes its place again and starts.
    if [ ! -e "$app" ] && mv "$old" "$app"; then
        rm -rf "$staging"
        exec "$opener" "$app"
    fi
    exec "$old/Contents/MacOS/$program"
fi
rm -rf "$staging"
: > "$old/khandaq-update-old"
exec "$opener" "$app"
)script";

bool isProgram(const QFileInfo& entry)
{
    const QString suffix = entry.suffix();
    return entry.isFile()
           && (suffix.compare(QLatin1String("exe"), Qt::CaseInsensitive) == 0
               || suffix.compare(QLatin1String("dll"), Qt::CaseInsensitive) == 0);
}

QFileInfoList everything(const QString& dir)
{
    return QDir(dir).entryInfoList(QDir::AllEntries | QDir::Hidden | QDir::System
                                       | QDir::NoDotAndDotDot,
                                   QDir::Name);
}

bool present(const QString& dir, const QString& name)
{
    const QFileInfo entry(QDir(dir).filePath(name));
    return entry.exists() || entry.isSymLink();
}

/** Nothing of the person's is left in an old Windows folder: only programs, libraries, the marker
 * and names the current folder has as well. */
bool onlyProgramsLeft(const QString& old, const QString& root)
{
    for (const QFileInfo& entry : everything(old)) {
        const QString name = entry.fileName();
        if (name == QLatin1String(UpdatePlatform::oldMarker) || isProgram(entry)
            || present(root, name)) {
            continue;
        }
        return false;
    }
    return true;
}
} // namespace

namespace UpdatePlatform {

QString key(Kind kind)
{
    switch (kind) {
    case Kind::None:
        break;
    case Kind::WindowsInstaller:
        return QStringLiteral("windows-x86_64-installer");
    case Kind::WindowsZip:
        return QStringLiteral("windows-x86_64-zip");
    case Kind::MacZip:
        return QStringLiteral("macos-zip");
    case Kind::LinuxDeb:
        return QStringLiteral("linux-deb-amd64");
    case Kind::LinuxPortable:
        return QStringLiteral("linux-portable-x86_64");
    }
    return {};
}

Layout detect(const QString& applicationDir)
{
    Layout layout;
#if defined(Q_OS_WIN) && defined(Q_PROCESSOR_X86_64)
    // The installer puts the program in bin, with uninstall.exe beside bin.
    const QDir program(applicationDir);
    if (program.dirName().compare(QLatin1String("bin"), Qt::CaseInsensitive) == 0
        && QFileInfo::exists(program.absoluteFilePath(QStringLiteral("../uninstall.exe")))) {
        layout.kind = Kind::WindowsInstaller;
        layout.root = QDir::cleanPath(program.absoluteFilePath(QStringLiteral("..")));
    } else {
        layout.kind = Kind::WindowsZip;
        layout.root = QDir::cleanPath(program.absolutePath());
    }
#elif defined(Q_OS_MACOS) && defined(Q_PROCESSOR_ARM_64)
    // .../Khandaq.app/Contents/MacOS
    QDir bundle(applicationDir);
    if (bundle.dirName() == QLatin1String("MacOS") && bundle.cdUp()
        && bundle.dirName() == QLatin1String("Contents") && bundle.cdUp()
        && bundle.dirName().endsWith(QLatin1String(".app"), Qt::CaseInsensitive)) {
        layout.kind = Kind::MacZip;
        layout.root = QDir::cleanPath(bundle.absolutePath());
    }
#elif defined(Q_OS_LINUX) && defined(Q_PROCESSOR_X86_64)
    if (qEnvironmentVariableIsSet("APPIMAGE") || qEnvironmentVariableIsSet("SNAP")
        || QFileInfo::exists(QStringLiteral("/.flatpak-info"))) {
        return layout;
    }
    // scripts/build-linux-deb.sh installs into /opt/khandaq; the portable folder runs khandaq.bin
    // through its khandaq launcher.
    const QDir program(applicationDir);
    const QString canonical = QFileInfo(applicationDir).canonicalFilePath();
    if (canonical == QLatin1String("/opt/khandaq")
        && QFileInfo::exists(QStringLiteral("/var/lib/dpkg/info/khandaq-messenger.list"))) {
        layout.kind = Kind::LinuxDeb;
        layout.root = canonical;
    } else if (QFileInfo::exists(program.filePath(QStringLiteral("khandaq.bin")))
               && QFileInfo::exists(program.filePath(QStringLiteral("khandaq")))) {
        layout.kind = Kind::LinuxPortable;
        layout.root = QDir::cleanPath(program.absolutePath());
    }
#else
    std::ignore = applicationDir;
#endif
    return layout;
}

QString installerArguments(qint64 pid, const QString& installDir)
{
    return QStringLiteral("/S /UPDATE /WAITPID=%1 /D=%2")
        .arg(QString::number(pid), QDir::toNativeSeparators(QDir::cleanPath(installDir)));
}

Swap plan(Kind kind, const QString& root, qint64 stamp)
{
    Swap swap;
    const QFileInfo copy(QDir::cleanPath(root));
    const QString parent = copy.absolutePath();
    const QString name = copy.fileName();
    const QString number = QString::number(stamp);
    swap.root = copy.absoluteFilePath();
    if (kind == Kind::MacZip) {
        // Hidden beside the bundle, and not bundles themselves, so Finder and Launch Services
        // leave them alone.
        swap.staging = parent + QStringLiteral("/.") + name + QStringLiteral(".update-") + number;
        swap.staged = swap.staging + QLatin1Char('/') + name;
        swap.old = parent + QStringLiteral("/.") + name + QStringLiteral(".old-") + number;
    } else {
        swap.staging = parent + QLatin1Char('/') + name + QStringLiteral(".update-") + number;
        swap.staged = swap.staging;
        swap.old = parent + QLatin1Char('/') + name + QStringLiteral(".old-") + number;
        swap.executable = QLatin1String(windowsExecutable);
    }
    return swap;
}

QStringList carriedNames(const QString& root, const QString& staged)
{
    QStringList names;
    for (const QFileInfo& entry : everything(root)) {
        const QString name = entry.fileName();
        if (present(staged, name) || isProgram(entry)) {
            continue;
        }
        names.append(name);
    }
    return names;
}

QString windowsScript()
{
    QString script = QString::fromLatin1(windowsHelper);
    script.replace(QLatin1Char('\n'), QStringLiteral("\r\n"));
    return script;
}

QStringList windowsEnvironment(const Swap& swap, qint64 pid)
{
    QStringList variables{
        QStringLiteral("KHQ_PID=") + QString::number(pid),
        QStringLiteral("KHQ_ROOT=") + QDir::toNativeSeparators(swap.root),
        QStringLiteral("KHQ_STAGING=") + QDir::toNativeSeparators(swap.staging),
        QStringLiteral("KHQ_OLD=") + QDir::toNativeSeparators(swap.old),
        QStringLiteral("KHQ_EXE=") + swap.executable,
        QStringLiteral("KHQ_CARRY_COUNT=") + QString::number(swap.carried.size()),
    };
    for (int i = 0; i < swap.carried.size(); ++i) {
        variables.append(QStringLiteral("KHQ_CARRY_%1=").arg(i + 1) + swap.carried.at(i));
    }
    return variables;
}

QString macScript()
{
    return QString::fromLatin1(macHelper);
}

QStringList macArguments(const QString& scriptPath, const Swap& swap, qint64 pid,
                         const QString& executableName, const QString& opener)
{
    return {scriptPath, QString::number(pid), swap.root,      swap.staged,
            swap.old,   swap.staging,         executableName, opener};
}

QStringList leftovers(Kind kind, const QString& root)
{
    QStringList found;
    if (kind != Kind::WindowsZip && kind != Kind::MacZip) {
        return found;
    }
    const QFileInfo copy(QDir::cleanPath(root));
    const QString prefix =
        (kind == Kind::MacZip ? QStringLiteral(".") : QString()) + copy.fileName();
    const QRegularExpression ours(
        QStringLiteral("\\A%1\\.(old|update)-[0-9]+\\z").arg(QRegularExpression::escape(prefix)),
        kind == Kind::WindowsZip ? QRegularExpression::CaseInsensitiveOption
                                 : QRegularExpression::NoPatternOption);
    const QFileInfoList siblings = QDir(copy.absolutePath())
                                       .entryInfoList(QDir::Dirs | QDir::Hidden | QDir::NoSymLinks
                                                      | QDir::NoDotAndDotDot);
    for (const QFileInfo& sibling : siblings) {
        const QRegularExpressionMatch match = ours.match(sibling.fileName());
        if (!match.hasMatch()) {
            continue;
        }
        const QString path = sibling.absoluteFilePath();
        // An unpacked copy holds only what came out of the download.
        const bool unpacked = match.captured(1) == QLatin1String("update");
        const bool marked = present(path, QLatin1String(oldMarker));
        if (unpacked
            || (marked && (kind == Kind::MacZip || onlyProgramsLeft(path, copy.absoluteFilePath())))) {
            found.append(path);
        }
    }
    return found;
}

} // namespace UpdatePlatform
