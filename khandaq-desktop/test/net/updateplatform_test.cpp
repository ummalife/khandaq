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

#include "src/net/updatemanifest.h"
#include "src/net/updateplatform.h"

#include <QDir>
#include <QFile>
#include <QProcess>
#include <QRegularExpression>
#include <QTemporaryDir>
#include <QtTest/QtTest>

/**
 * KHANDAQ (0.2.65): how a copy of Khandaq is found and replaced. The helper scripts are checked for
 * what makes them safe with any folder name (paths only from the environment or the arguments, and
 * only ever quoted) and for their rollback; on a Unix system the macOS helper is also run on bundles
 * in a folder whose name has spaces, quotes and letters of other languages.
 */
class TestUpdatePlatform : public QObject
{
    Q_OBJECT

private slots:
    void keysAreTheManifestPlatforms();
    void installerArgumentsEndWithAnUnquotedFolder();
    void plansEverythingBesideTheCopy();
    void windowsScriptTakesPathsOnlyFromTheEnvironment();
    void windowsScriptRollsBack();
    void windowsScriptKeepsWhatItCannotPutBack();
    void windowsEnvironmentKeepsNamesAsTheyAre();
    void windowsNoteNamesTheFolders();
    void macScriptTakesPathsOnlyAsQuotedArguments();
    void carriesWhatTheNewFolderDoesNotHave();
    void leftoversAreOnlyWhatTheHelperIsDoneWith();
    void macHelperSwapsTheBundle();
    void macHelperRollsBack();
    void macHelperLeavesAnOldCopyAloneWhenItsNameIsTaken();

private:
    static bool write(const QString& path, const QByteArray& content);
    static QByteArray read(const QString& path);
    /** A bundle at path whose program says words. */
    static bool makeBundle(const QString& path, const QByteArray& words);
    /** Runs the macOS helper until it ends; false when it did not. opened gets what it opened. */
    static bool runMacHelper(const QString& folder, const UpdatePlatform::Swap& swap, QString& opened);
};

namespace {
/** Spaces, quotes, a dollar, an ampersand and Cyrillic and Arabic letters: a folder name a script
 * could take apart if it ever read it as code. */
const QString awkwardName = QStringLiteral("My Apps 'x' $HOME & \u041f\u0440\u0438\u043b \u0628\u0631");
} // namespace

bool TestUpdatePlatform::write(const QString& path, const QByteArray& content)
{
    if (!QDir().mkpath(QFileInfo(path).absolutePath())) {
        return false;
    }
    QFile file(path);
    return file.open(QIODevice::WriteOnly) && file.write(content) == content.size();
}

QByteArray TestUpdatePlatform::read(const QString& path)
{
    QFile file(path);
    return file.open(QIODevice::ReadOnly) ? file.readAll() : QByteArray();
}

bool TestUpdatePlatform::makeBundle(const QString& path, const QByteArray& words)
{
    return write(path + QStringLiteral("/Contents/Info.plist"), "<plist/>\n")
           && write(path + QStringLiteral("/Contents/MacOS/khandaq"), words);
}

bool TestUpdatePlatform::runMacHelper(const QString& folder, const UpdatePlatform::Swap& swap,
                                      QString& opened)
{
    const QString script = QDir(folder).filePath(QStringLiteral("khandaq-update.sh"));
    const QString opener = QDir(folder).filePath(QStringLiteral("opener.sh"));
    const QString record = QDir(folder).filePath(QStringLiteral("opened.txt"));
    // In place of /usr/bin/open, which would start what it is given.
    const QByteArray recorder = "#!/bin/sh\nprintf '%s' \"$1\" > '" + record.toUtf8() + "'\n";
    if (!write(script, UpdatePlatform::macScript().toUtf8()) || !write(opener, recorder)
        || !QFile::setPermissions(opener, QFile::ReadOwner | QFile::WriteOwner | QFile::ExeOwner)) {
        return false;
    }
    // A process of our own stands for Khandaq: the helper waits until it has ended.
    QProcess khandaq;
    khandaq.start(QStringLiteral("/bin/sleep"), {QStringLiteral("1")});
    if (!khandaq.waitForStarted()) {
        return false;
    }
    QProcess helper;
    helper.start(QStringLiteral("/bin/sh"),
                 UpdatePlatform::macArguments(script, swap, khandaq.processId(),
                                              QStringLiteral("khandaq"), opener));
    if (!helper.waitForStarted()) {
        return false;
    }
    QTest::qWait(300);
    if (helper.state() != QProcess::Running) {
        // It went on while Khandaq was still running.
        return false;
    }
    // Ended and reaped, so it is gone for kill -0.
    if (!khandaq.waitForFinished(10000) || !helper.waitForFinished(20000)) {
        return false;
    }
    opened = QString::fromUtf8(read(record));
    return helper.exitStatus() == QProcess::NormalExit;
}

void TestUpdatePlatform::keysAreTheManifestPlatforms()
{
    using UpdatePlatform::Kind;
    QStringList keys;
    for (const Kind kind : {Kind::WindowsInstaller, Kind::WindowsZip, Kind::MacZip, Kind::LinuxDeb,
                            Kind::LinuxPortable}) {
        keys.append(UpdatePlatform::key(kind));
    }
    QVERIFY(UpdatePlatform::key(Kind::None).isEmpty());
    keys.sort();
    QCOMPARE(keys, UpdateManifest::platformKeys());
}

void TestUpdatePlatform::installerArgumentsEndWithAnUnquotedFolder()
{
    const QString folder = QStringLiteral("C:/Program Files/Khandaq \u041a");
    const QString arguments = UpdatePlatform::installerArguments(4242, folder);
    QCOMPARE(arguments, QStringLiteral("/S /UPDATE /WAITPID=4242 /D=")
                            + QDir::toNativeSeparators(folder));
    // NSIS takes the rest of the line as the folder, spaces and all, and no quotes.
    QVERIFY(!arguments.contains(QLatin1Char('"')));
    QVERIFY(arguments.lastIndexOf(QStringLiteral(" /")) < arguments.indexOf(QStringLiteral("/D=")));
    // A folder with a trailing separator is the same folder.
    QCOMPARE(UpdatePlatform::installerArguments(1, folder + QLatin1Char('/')),
             UpdatePlatform::installerArguments(1, folder));
}

void TestUpdatePlatform::plansEverythingBesideTheCopy()
{
    using UpdatePlatform::Kind;
    const QString parent = QStringLiteral("/data/") + awkwardName;
    const UpdatePlatform::Swap windows =
        UpdatePlatform::plan(Kind::WindowsZip, parent + QStringLiteral("/Khandaq/"), 42);
    QCOMPARE(windows.root, parent + QStringLiteral("/Khandaq"));
    QCOMPARE(windows.staging, parent + QStringLiteral("/Khandaq.update-42"));
    QCOMPARE(windows.staged, windows.staging);
    QCOMPARE(windows.old, parent + QStringLiteral("/Khandaq.old-42"));
    QCOMPARE(windows.executable, QStringLiteral("Khandaq.exe"));

    const UpdatePlatform::Swap mac =
        UpdatePlatform::plan(Kind::MacZip, parent + QStringLiteral("/khandaq.app"), 7);
    QCOMPARE(mac.root, parent + QStringLiteral("/khandaq.app"));
    QCOMPARE(mac.staging, parent + QStringLiteral("/.khandaq.app.update-7"));
    QCOMPARE(mac.staged, mac.staging + QStringLiteral("/khandaq.app"));
    QCOMPARE(mac.old, parent + QStringLiteral("/.khandaq.app.old-7"));
}

void TestUpdatePlatform::windowsScriptTakesPathsOnlyFromTheEnvironment()
{
    const QString script = UpdatePlatform::windowsScript();
    QVERIFY(script.startsWith(QStringLiteral("@echo off\r\n")));
    // ASCII with CRLF line ends throughout, as cmd.exe reads a file in the console code page.
    for (const QChar c : script) {
        QVERIFY(c.unicode() < 0x80);
    }
    QVERIFY(!script.contains(QRegularExpression(QStringLiteral("[^\\r]\\n"))));
    QVERIFY(script.contains(QStringLiteral("setlocal EnableExtensions EnableDelayedExpansion")));
    // A path is read only with delayed expansion, whose value is never parsed again, and only
    // inside quotes; %NAME% is left to numbers.
    for (const char* name : {"KHQ_ROOT", "KHQ_STAGING", "KHQ_OLD", "KHQ_EXE", "KHQ_NOTE"}) {
        QVERIFY2(!script.contains(QStringLiteral("%%1%").arg(QLatin1String(name))), name);
        QVERIFY2(script.contains(QStringLiteral("!%1!").arg(QLatin1String(name))), name);
    }
    QVERIFY(!script.contains(QRegularExpression(QStringLiteral("%KHQ_CARRY_(?!COUNT%)"))));
    const QRegularExpression unquoted(QStringLiteral("(^|[^\"\\\\])!KHQ_(ROOT|STAGING|OLD|NOTE)!"),
                                      QRegularExpression::MultilineOption);
    QVERIFY(!unquoted.match(script).hasMatch());
    // The tools by their full path.
    QVERIFY(script.contains(QStringLiteral("\"%SystemRoot%\\System32\\tasklist.exe\"")));
    QVERIFY(!script.contains(QRegularExpression(QStringLiteral("(^|\\| )(tasklist|find|ping) "),
                                                QRegularExpression::MultilineOption)));
}

void TestUpdatePlatform::windowsScriptRollsBack()
{
    const QString script = UpdatePlatform::windowsScript();
    for (const char* label : {":give_up", ":keep_old", ":carry_again", ":carry_back",
                              ":carry_back_again", ":check_back", ":remove_new",
                              ":remove_new_again", ":restore_old", ":keep_new", ":old_elsewhere",
                              ":done"}) {
        QVERIFY2(script.contains(QLatin1String(label) + QStringLiteral("\r\n")), label);
        // Each one is gone to.
        QVERIFY2(script.contains(QStringLiteral("goto ") + QLatin1String(label).mid(1)
                                 + QStringLiteral("\r\n")),
                 label);
    }
    // A failed move of what is carried puts it back and the old folder in place, which starts.
    const int carryBack = script.indexOf(QStringLiteral(":carry_back\r\n"));
    const int restore = script.indexOf(QStringLiteral(":restore_old\r\n"));
    QVERIFY(carryBack > 0 && restore > carryBack);
    QVERIFY(script.indexOf(QStringLiteral("move \"!KHQ_OLD!\" \"!KHQ_ROOT!\""), restore) > restore);
    // Only the end that put the new copy in place marks the old one for removal.
    QCOMPARE(script.count(QStringLiteral("khandaq-update-old")), 1);
    QVERIFY(script.indexOf(QStringLiteral("khandaq-update-old"))
            > script.indexOf(QStringLiteral(":done\r\n")));
    QCOMPARE(QString::fromLatin1(UpdatePlatform::oldMarker), QStringLiteral("khandaq-update-old"));
}

/** The paragraph of the script that starts at label, up to the next label. */
static QString paragraph(const QString& script, const char* label)
{
    const QString start = QLatin1String(label) + QStringLiteral("\r\n");
    const int from = script.indexOf(start);
    if (from < 0) {
        return {};
    }
    const int to = script.indexOf(QStringLiteral("\r\n:"), from + start.size());
    return script.mid(from, to < 0 ? -1 : to - from);
}

void TestUpdatePlatform::windowsScriptKeepsWhatItCannotPutBack()
{
    const QString script = UpdatePlatform::windowsScript();
    // What is gone since Khandaq listed it (the lock it removes as it quits) is passed over, not
    // taken for a failure that rolls the update back.
    const QString carry = paragraph(script, ":carry");
    QVERIFY(carry.contains(
        QStringLiteral("if not exist \"!KHQ_OLD!\\!KHQ_CARRY_%carried%!\" goto carry\r\n")));
    QVERIFY(paragraph(script, ":carry_again")
                .contains(QStringLiteral("move \"!KHQ_OLD!\\!KHQ_CARRY_%carried%!\" ")));
    // A move held for a moment is tried again, both ways.
    QVERIFY(paragraph(script, ":carry_again").contains(QStringLiteral("goto carry_again\r\n")));
    QVERIFY(paragraph(script, ":carry_back_again").contains(QStringLiteral("goto carry_back_again\r\n")));
    QVERIFY(paragraph(script, ":remove_new_again").contains(QStringLiteral("goto remove_new_again\r\n")));

    // Whatever carried is still in the new folder keeps that folder.
    const int initial = script.indexOf(QStringLiteral("set /a kept=0\r\n"));
    QVERIFY(initial > 0 && initial < script.indexOf(QStringLiteral(":wait_for_quit\r\n")));
    QVERIFY(paragraph(script, ":check_back_next")
                .contains(QStringLiteral("if exist \"!KHQ_ROOT!\\!KHQ_CARRY_%back%!\" set /a kept=1\r\n")));
    const QString restore = paragraph(script, ":restore_old");
    // A folder still under the name of the copy would take the old one inside it.
    const int guard = restore.indexOf(QStringLiteral("if exist \"!KHQ_ROOT!\" goto old_elsewhere\r\n"));
    QVERIFY(guard > 0 && guard < restore.indexOf(QStringLiteral("move \"!KHQ_OLD!\" \"!KHQ_ROOT!\"")));
    const int keep = restore.indexOf(QStringLiteral("if !kept! neq 0 goto keep_new\r\n"));
    QVERIFY(keep > 0 && keep < restore.indexOf(QStringLiteral("rd /s /q")));
    // The unpacked folder is removed only where it holds nothing but the download: before anything
    // is carried, and once everything carried is back.
    QCOMPARE(script.count(QStringLiteral("rd /s /q \"!KHQ_STAGING!\"")), 3);
    QCOMPARE(script.count(QStringLiteral("rd ")), 3);
    for (const char* label : {":give_up", ":keep_old", ":restore_old"}) {
        QVERIFY2(paragraph(script, label).contains(QStringLiteral("rd /s /q \"!KHQ_STAGING!\"")), label);
    }
    // Where the person's files may be elsewhere, the note says where, and nothing is removed.
    for (const char* label : {":keep_new", ":old_elsewhere"}) {
        const QString end = paragraph(script, label);
        QVERIFY2(end.contains(QStringLiteral("if defined KHQ_NOTE start \"\" \"!KHQ_NOTE!\"\r\n")), label);
        QVERIFY2(!end.contains(QStringLiteral("rd ")) && !end.contains(QStringLiteral("del ")), label);
        QVERIFY2(end.contains(QStringLiteral("exit /b 1")), label);
    }
    QCOMPARE(script.count(QStringLiteral("!KHQ_NOTE!")), 2);
}

void TestUpdatePlatform::windowsEnvironmentKeepsNamesAsTheyAre()
{
    UpdatePlatform::Swap swap = UpdatePlatform::plan(UpdatePlatform::Kind::WindowsZip,
                                                     QStringLiteral("C:/") + awkwardName + QStringLiteral("/Khandaq"),
                                                     9);
    swap.carried = QStringList{QStringLiteral("khandaq.ini"), QStringLiteral("100% & !done! ^x")};
    const QStringList variables = UpdatePlatform::windowsEnvironment(swap, 4242);
    QVERIFY(variables.contains(QStringLiteral("KHQ_PID=4242")));
    QVERIFY(variables.contains(QStringLiteral("KHQ_ROOT=") + QDir::toNativeSeparators(swap.root)));
    QVERIFY(variables.contains(QStringLiteral("KHQ_STAGING=") + QDir::toNativeSeparators(swap.staging)));
    QVERIFY(variables.contains(QStringLiteral("KHQ_OLD=") + QDir::toNativeSeparators(swap.old)));
    QVERIFY(variables.contains(QStringLiteral("KHQ_EXE=Khandaq.exe")));
    QVERIFY(variables.contains(QStringLiteral("KHQ_CARRY_COUNT=2")));
    QVERIFY(variables.contains(QStringLiteral("KHQ_CARRY_1=khandaq.ini")));
    QVERIFY(variables.contains(QStringLiteral("KHQ_CARRY_2=100% & !done! ^x")));
    QVERIFY(!variables.join(QLatin1Char('\n')).contains(QStringLiteral("KHQ_NOTE")));
    const QString note = QStringLiteral("C:/Temp/") + awkwardName + QStringLiteral("/khandaq-update-note.txt");
    QVERIFY(UpdatePlatform::windowsEnvironment(swap, 4242, note)
                .contains(QStringLiteral("KHQ_NOTE=") + QDir::toNativeSeparators(note)));
}

void TestUpdatePlatform::windowsNoteNamesTheFolders()
{
    const UpdatePlatform::Swap swap = UpdatePlatform::plan(
        UpdatePlatform::Kind::WindowsZip, QStringLiteral("C:/") + awkwardName + QStringLiteral("/Khandaq"), 5);
    const QString intro = QStringLiteral("\u041d\u0435 \u0432\u0441\u0451 \u043d\u0430 \u043c\u0435\u0441\u0442\u0435:");
    const QString action = QStringLiteral("\u0417\u0430\u043a\u0440\u043e\u0439\u0442\u0435 Khandaq.");
    const QByteArray note = UpdatePlatform::windowsNote(swap, intro, action);
    // A byte order mark, so that Notepad reads UTF-8 on every Windows.
    QVERIFY(note.startsWith("\xEF\xBB\xBF"));
    const QString text = QString::fromUtf8(note.mid(3));
    QVERIFY(!text.contains(QRegularExpression(QStringLiteral("[^\\r]\\n"))));
    QCOMPARE(text.split(QStringLiteral("\r\n")),
             (QStringList{intro, QString(), QDir::toNativeSeparators(swap.root),
                          QDir::toNativeSeparators(swap.old), QDir::toNativeSeparators(swap.staging),
                          QString(), action, QString()}));
}

void TestUpdatePlatform::macScriptTakesPathsOnlyAsQuotedArguments()
{
    const QString script = UpdatePlatform::macScript();
    QVERIFY(script.startsWith(QStringLiteral("#!/bin/sh\n")));
    for (const QChar c : script) {
        QVERIFY(c.unicode() < 0x80);
    }
    // Every expansion stands in double quotes, and nothing is evaluated: outside quotes a $ only
    // opens arithmetic.
    QVERIFY(!script.contains(QStringLiteral("eval")));
    for (const QString& line : script.split(QLatin1Char('\n'))) {
        if (line.trimmed().startsWith(QLatin1Char('#'))) {
            continue;
        }
        bool quoted = false;
        for (int at = 0; at < line.size(); ++at) {
            if (line.at(at) == QLatin1Char('"')) {
                quoted = !quoted;
            } else if (line.at(at) == QLatin1Char('$') && !quoted) {
                QVERIFY2(line.mid(at, 3) == QStringLiteral("$(("), qPrintable(line));
            }
        }
        QVERIFY2(!quoted, qPrintable(line));
    }
    QVERIFY(script.contains(QStringLiteral("PATH=/usr/bin:/bin:/usr/sbin:/sbin")));
    // The rollback.
    QVERIFY(script.contains(QStringLiteral("mv \"$old\" \"$app\"")));
    const QStringList arguments = UpdatePlatform::macArguments(
        QStringLiteral("/tmp/s.sh"), UpdatePlatform::plan(UpdatePlatform::Kind::MacZip,
                                                          QStringLiteral("/A b/khandaq.app"), 3),
        77, QStringLiteral("khandaq"), QStringLiteral("/usr/bin/open"));
    QCOMPARE(arguments,
             (QStringList{QStringLiteral("/tmp/s.sh"), QStringLiteral("77"), QStringLiteral("/A b/khandaq.app"),
                          QStringLiteral("/A b/.khandaq.app.update-3/khandaq.app"),
                          QStringLiteral("/A b/.khandaq.app.old-3"), QStringLiteral("/A b/.khandaq.app.update-3"),
                          QStringLiteral("khandaq"), QStringLiteral("/usr/bin/open")}));
}

void TestUpdatePlatform::carriesWhatTheNewFolderDoesNotHave()
{
    QTemporaryDir temp;
    QVERIFY(temp.isValid());
    const QString root = temp.filePath(awkwardName + QStringLiteral("/Khandaq"));
    const QString staged = temp.filePath(awkwardName + QStringLiteral("/Khandaq.update-1"));
    // me.lock: the lock of the profile in use, which Khandaq removes as it quits, so that the helper
    // would find nothing to move.
    for (const char* name : {"Khandaq.exe", "Qt5Core.dll", "old-plugin.dll", "khandaq.ini",
                             ".hidden", "notes.txt", "profiles/me.tox", "platforms/qwindows.dll",
                             "updates/0.2.65/khandaq-windows-x86_64.zip", "me.lock", "me.tox"}) {
        QVERIFY(write(root + QLatin1Char('/') + QLatin1String(name), "old"));
    }
    for (const char* name : {"Khandaq.exe", "Qt5Core.dll", "platforms/qwindows.dll"}) {
        QVERIFY(write(staged + QLatin1Char('/') + QLatin1String(name), "new"));
    }
    QStringList carried = UpdatePlatform::carriedNames(root, staged);
    carried.sort();
    QCOMPARE(carried, (QStringList{QStringLiteral(".hidden"), QStringLiteral("khandaq.ini"),
                                   QStringLiteral("me.tox"), QStringLiteral("notes.txt"),
                                   QStringLiteral("profiles"), QStringLiteral("updates")}));
}

void TestUpdatePlatform::leftoversAreOnlyWhatTheHelperIsDoneWith()
{
    using UpdatePlatform::Kind;
    QTemporaryDir temp;
    QVERIFY(temp.isValid());
    const QString parent = temp.filePath(awkwardName);
    const QString root = parent + QStringLiteral("/Khandaq");
    QVERIFY(write(root + QStringLiteral("/Khandaq.exe"), "new"));
    QVERIFY(write(root + QStringLiteral("/khandaq.ini"), "settings"));
    // Done with: marked, and only programs and what the copy has as well are left.
    QVERIFY(write(parent + QStringLiteral("/Khandaq.old-1/Khandaq.exe"), "old"));
    QVERIFY(write(parent + QStringLiteral("/Khandaq.old-1/khandaq.ini"), "older settings"));
    QVERIFY(write(parent + QStringLiteral("/Khandaq.old-1/khandaq-update-old"), ""));
    // Marked, but something of the person's did not go across: kept.
    QVERIFY(write(parent + QStringLiteral("/Khandaq.old-2/Khandaq.exe"), "old"));
    QVERIFY(write(parent + QStringLiteral("/Khandaq.old-2/profiles/me.tox"), "profile"));
    QVERIFY(write(parent + QStringLiteral("/Khandaq.old-2/khandaq-update-old"), ""));
    // Not marked: the helper never finished with it.
    QVERIFY(write(parent + QStringLiteral("/Khandaq.old-3/Khandaq.exe"), "old"));
    // An unpacked copy no helper took: programs and libraries, in folders of their own.
    QVERIFY(write(parent + QStringLiteral("/Khandaq.update-4/Khandaq.exe"), "new"));
    QVERIFY(write(parent + QStringLiteral("/Khandaq.update-4/platforms/qwindows.dll"), "new"));
    // Not ours.
    QVERIFY(write(parent + QStringLiteral("/Khandaq.old-x/khandaq-update-old"), ""));
    QVERIFY(write(parent + QStringLiteral("/Other.old-5/khandaq-update-old"), ""));
    // Unpacked copies a rollback could not empty of the person's files: kept, even where the copy
    // has a file of that name by now (a new profile of the same name).
    QVERIFY(write(parent + QStringLiteral("/Khandaq.update-6/Khandaq.exe"), "new"));
    QVERIFY(write(parent + QStringLiteral("/Khandaq.update-6/me.tox"), "the only key to an account"));
    QVERIFY(write(root + QStringLiteral("/me.tox"), "a profile made since"));
    QVERIFY(write(parent + QStringLiteral("/Khandaq.update-7/avatars/friend.png"), "photo"));
    QVERIFY(write(parent + QStringLiteral("/Khandaq.update-8/khandaq.ini"), "settings"));
    // Marked, with a lock a crash left behind: nothing of the person's.
    QVERIFY(write(parent + QStringLiteral("/Khandaq.old-9/Khandaq.exe"), "old"));
    QVERIFY(write(parent + QStringLiteral("/Khandaq.old-9/other profile.lock"), "4242"));
    QVERIFY(write(parent + QStringLiteral("/Khandaq.old-9/khandaq-update-old"), ""));
    QStringList found = UpdatePlatform::leftovers(Kind::WindowsZip, root);
    found.sort();
    QCOMPARE(found, (QStringList{parent + QStringLiteral("/Khandaq.old-1"),
                                 parent + QStringLiteral("/Khandaq.old-9"),
                                 parent + QStringLiteral("/Khandaq.update-4")}));
#if defined(Q_OS_UNIX)
    // A link is never followed into: the folder stays.
    QVERIFY(QDir().mkpath(parent + QStringLiteral("/Khandaq.update-10")));
    QVERIFY(QFile::link(parent + QStringLiteral("/Khandaq.old-2"),
                        parent + QStringLiteral("/Khandaq.update-10/linked")));
    QVERIFY(!UpdatePlatform::leftovers(Kind::WindowsZip, root)
                 .contains(parent + QStringLiteral("/Khandaq.update-10")));
#endif
    QVERIFY(UpdatePlatform::leftovers(Kind::WindowsInstaller, root).isEmpty());
    QVERIFY(UpdatePlatform::leftovers(Kind::LinuxPortable, root).isEmpty());

    const QString bundle = parent + QStringLiteral("/khandaq.app");
    QVERIFY(makeBundle(bundle, "new"));
    QVERIFY(makeBundle(parent + QStringLiteral("/.khandaq.app.old-6"), "old"));
    QVERIFY(write(parent + QStringLiteral("/.khandaq.app.old-6/khandaq-update-old"), ""));
    QVERIFY(makeBundle(parent + QStringLiteral("/.khandaq.app.old-7"), "old"));
    QVERIFY(makeBundle(parent + QStringLiteral("/.khandaq.app.update-8/khandaq.app"), "new"));
    found = UpdatePlatform::leftovers(Kind::MacZip, bundle);
    found.sort();
    QCOMPARE(found, (QStringList{parent + QStringLiteral("/.khandaq.app.old-6"),
                                 parent + QStringLiteral("/.khandaq.app.update-8")}));
}

void TestUpdatePlatform::macHelperSwapsTheBundle()
{
#if defined(Q_OS_UNIX)
    QTemporaryDir temp;
    QVERIFY(temp.isValid());
    const QString parent = temp.filePath(awkwardName);
    const QString app = parent + QStringLiteral("/khandaq.app");
    QVERIFY(makeBundle(app, "old"));
    const UpdatePlatform::Swap swap = UpdatePlatform::plan(UpdatePlatform::Kind::MacZip, app, 1);
    QVERIFY(makeBundle(swap.staged, "new"));
    QString opened;
    QVERIFY(runMacHelper(temp.path(), swap, opened));
    QCOMPARE(opened, app);
    QCOMPARE(read(app + QStringLiteral("/Contents/MacOS/khandaq")), QByteArray("new"));
    QCOMPARE(read(swap.old + QStringLiteral("/Contents/MacOS/khandaq")), QByteArray("old"));
    QVERIFY(QFileInfo::exists(swap.old + QStringLiteral("/khandaq-update-old")));
    QVERIFY(!QFileInfo::exists(swap.staging));
    // On the next start the old bundle goes.
    QCOMPARE(UpdatePlatform::leftovers(UpdatePlatform::Kind::MacZip, app), QStringList{swap.old});
#else
    QSKIP("The macOS helper runs on Unix systems only");
#endif
}

void TestUpdatePlatform::macHelperRollsBack()
{
#if defined(Q_OS_UNIX)
    QTemporaryDir temp;
    QVERIFY(temp.isValid());
    const QString parent = temp.filePath(awkwardName);
    const QString app = parent + QStringLiteral("/khandaq.app");
    QVERIFY(makeBundle(app, "old"));
    UpdatePlatform::Swap swap = UpdatePlatform::plan(UpdatePlatform::Kind::MacZip, app, 2);
    // The new bundle cannot be moved in: it is not where it should be.
    QVERIFY(QDir().mkpath(swap.staging));
    QString opened;
    QVERIFY(runMacHelper(temp.path(), swap, opened));
    QCOMPARE(opened, app);
    QCOMPARE(read(app + QStringLiteral("/Contents/MacOS/khandaq")), QByteArray("old"));
    QVERIFY(!QFileInfo::exists(swap.old));
    QVERIFY(!QFileInfo::exists(swap.staging));
#else
    QSKIP("The macOS helper runs on Unix systems only");
#endif
}

void TestUpdatePlatform::macHelperLeavesAnOldCopyAloneWhenItsNameIsTaken()
{
#if defined(Q_OS_UNIX)
    QTemporaryDir temp;
    QVERIFY(temp.isValid());
    const QString parent = temp.filePath(awkwardName);
    const QString app = parent + QStringLiteral("/khandaq.app");
    QVERIFY(makeBundle(app, "old"));
    const UpdatePlatform::Swap swap = UpdatePlatform::plan(UpdatePlatform::Kind::MacZip, app, 3);
    QVERIFY(makeBundle(swap.staged, "new"));
    // mv would put the bundle inside a folder of that name rather than rename it.
    QVERIFY(QDir().mkpath(swap.old));
    QString opened;
    QVERIFY(runMacHelper(temp.path(), swap, opened));
    QCOMPARE(opened, app);
    QCOMPARE(read(app + QStringLiteral("/Contents/MacOS/khandaq")), QByteArray("old"));
    QVERIFY(QDir(swap.old).isEmpty());
    QVERIFY(!QFileInfo::exists(swap.staging));
#else
    QSKIP("The macOS helper runs on Unix systems only");
#endif
}

QTEST_GUILESS_MAIN(TestUpdatePlatform)
#include "updateplatform_test.moc"
