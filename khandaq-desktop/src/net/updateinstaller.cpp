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

#include "updateinstaller.h"

#include "src/net/updatecheck.h"
#include "src/persistence/iupdatesettings.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDebug>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QNetworkReply>
#include <QProcess>
#include <QRegularExpression>
#include <QSaveFile>
#include <QStandardPaths>

#include <tuple>
#include <utility>

#if defined(Q_OS_WIN)
#include <windows.h>
#include <shellapi.h>
#endif

namespace {
QString shellQuoted(const QString& text)
{
    static const QRegularExpression plain(QStringLiteral("\\A[A-Za-z0-9_./+-]+\\z"));
    if (plain.match(text).hasMatch()) {
        return text;
    }
    QString quoted = text;
    quoted.replace(QLatin1Char('\''), QStringLiteral("'\\''"));
    return QLatin1Char('\'') + quoted + QLatin1Char('\'');
}

/** The file is there with the size and SHA-256 the signed manifest gives. */
bool matches(const QString& path, const UpdateManifest::Entry& entry)
{
    QFile candidate(path);
    if (candidate.size() != entry.size || !candidate.open(QIODevice::ReadOnly)) {
        return false;
    }
    QCryptographicHash sum(QCryptographicHash::Sha256);
    return sum.addData(&candidate) && sum.result() == entry.sha256;
}

#if defined(Q_OS_WIN) || defined(Q_OS_MACOS)
bool writeScript(const QString& path, const QString& script)
{
    QSaveFile file(path);
    if (!file.open(QIODevice::WriteOnly)) {
        return false;
    }
    const QByteArray bytes = script.toLatin1();
    return file.write(bytes) == bytes.size() && file.commit();
}
#endif

#if defined(Q_OS_WIN)
/** A folder under Program Files belongs to an installer and is not ours to swap. */
bool underProgramFiles(const QString& path)
{
    const QString cleaned = QDir::cleanPath(path);
    for (const char* variable : {"ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"}) {
        const QString base = QDir::cleanPath(QDir::fromNativeSeparators(qEnvironmentVariable(variable)));
        if (!base.isEmpty()
            && (cleaned.compare(base, Qt::CaseInsensitive) == 0
                || cleaned.startsWith(base + QLatin1Char('/'), Qt::CaseInsensitive))) {
            return true;
        }
    }
    return false;
}

/** The bytes behind an open handle are the ones the manifest describes. */
bool sameBytes(HANDLE file, const UpdateManifest::Entry& entry)
{
    LARGE_INTEGER size;
    if (!GetFileSizeEx(file, &size) || size.QuadPart != entry.size) {
        return false;
    }
    QCryptographicHash sum(QCryptographicHash::Sha256);
    QByteArray buffer(1 << 20, Qt::Uninitialized);
    for (;;) {
        DWORD read = 0;
        if (!ReadFile(file, buffer.data(), static_cast<DWORD>(buffer.size()), &read, nullptr)) {
            return false;
        }
        if (read == 0) {
            break;
        }
        sum.addData(buffer.constData(), static_cast<int>(read));
    }
    return sum.result() == entry.sha256;
}
#endif
} // namespace

UpdateInstaller::UpdateInstaller(IUpdateSettings& settings_, Setup setup_, QObject* parent)
    : QObject(parent)
    , settings{settings_}
    , setup{std::move(setup_)}
{
    stall.setSingleShot(true);
    stall.setInterval(setup.stallMs);
    connect(&stall, &QTimer::timeout, this, [this] {
        // The reply is read once into a plain pointer, here and below: GCC's -Wnull-dereference,
        // an error in strict builds, cannot tell that a second read of a QPointer is not null.
        if (QNetworkReply* const running = reply.data()) {
            qWarning() << "Update: the download stalled";
            running->abort();
        }
    });
}

UpdateInstaller::~UpdateInstaller()
{
    if (QNetworkReply* const running = reply.data()) {
        running->disconnect(this);
        running->abort();
    }
    if (partial) {
        partial->remove();
    }
    if (unpacker) {
        unpacker->disconnect(this);
    }
#if defined(Q_OS_WIN)
    if (heldInstaller) {
        CloseHandle(static_cast<HANDLE>(heldInstaller));
    }
#endif
}

QString UpdateInstaller::downloadsFolder(bool portable)
{
    const QString folder = QStringLiteral("updates");
#if defined(Q_OS_WIN)
    const QFileInfo program(QCoreApplication::applicationDirPath());
    if (portable && program.isWritable()) {
        return QDir(program.absoluteFilePath()).filePath(folder);
    }
#else
    // Elsewhere the cache of the app: a macOS bundle is never written into.
    std::ignore = portable;
#endif
    return QDir(QStandardPaths::writableLocation(QStandardPaths::CacheLocation)).filePath(folder);
}

UpdateInstaller::Stage UpdateInstaller::stage() const
{
    return current;
}

UpdateInstaller::Failure UpdateInstaller::failure() const
{
    return failed;
}

int UpdateInstaller::percent() const
{
    return shownPercent;
}

QString UpdateInstaller::file() const
{
    return path;
}

QString UpdateInstaller::command() const
{
    if (current != Stage::Manual || setup.layout.kind != UpdatePlatform::Kind::LinuxDeb) {
        return {};
    }
    return QStringLiteral("sudo apt install ") + shellQuoted(path);
}

void UpdateInstaller::setStage(Stage next)
{
    current = next;
    emit changed();
}

void UpdateInstaller::failWith(Failure kind, const QString& why)
{
    qWarning() << "Update failed:" << why;
    if (partial) {
        partial->remove();
        partial.reset();
    }
    failed = kind;
    setStage(Stage::Failed);
}

void UpdateInstaller::install(const UpdateManifest::Entry& entry)
{
    if (current == Stage::Downloading || current == Stage::Preparing
        || current == Stage::Restarting) {
        return;
    }
    target = entry;
    failed = Failure::None;
    const QString folder = QDir(setup.downloads).filePath(UpdateManifest::toString(entry.version));
    if (!QDir().mkpath(folder)) {
        failWith(Failure::Download, QStringLiteral("cannot make ") + folder);
        return;
    }
    path = QDir(folder).filePath(entry.file);
    if (matches(path, entry)) {
        qInfo() << "Update: the verified download is already here";
        apply();
        return;
    }
    QFile::remove(path);

    QNetworkProxy proxy;
    if (!UpdateNetwork::route(settings.getProxy(), proxy)) {
        failWith(Failure::Download, QStringLiteral("the proxy in the settings has no address or port"));
        return;
    }
    partial.reset(new QFile(path + QStringLiteral(".part")));
    if (!partial->open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        failWith(Failure::Download, QStringLiteral("cannot write ") + partial->fileName());
        return;
    }
    manager.setProxy(proxy);
    hash.reset();
    overrun = false;
    received = 0;
    shownPercent = 0;
    const QUrl url = UpdateEndpoint::artifactUrl(setup.manifest, entry.file);
    qInfo() << "Update: downloading" << url.toString();
    reply = manager.get(UpdateNetwork::request(url));
    // A file announced as larger than the manifest says is not downloaded at all.
    connect(reply.data(), &QNetworkReply::metaDataChanged, this, [this] {
        QNetworkReply* const running = reply.data();
        if (!running) {
            return;
        }
        const QVariant length = running->header(QNetworkRequest::ContentLengthHeader);
        if (length.isValid() && length.toLongLong() > target.size) {
            qWarning() << "Update: the download is announced as larger than the signed manifest says";
            overrun = true;
            running->abort();
        }
    });
    connect(reply.data(), &QNetworkReply::readyRead, this, &UpdateInstaller::receive);
    connect(reply.data(), &QNetworkReply::finished, this, &UpdateInstaller::downloaded);
    stall.start();
    setStage(Stage::Downloading);
}

void UpdateInstaller::cancel()
{
    QNetworkReply* const running = reply.data();
    if (current != Stage::Downloading || !running) {
        return;
    }
    qInfo() << "Update: the download was stopped";
    setStage(Stage::Idle);
    running->abort();
}

void UpdateInstaller::receive()
{
    QNetworkReply* const running = reply.data();
    if (!running || !partial) {
        return;
    }
    const QByteArray chunk = running->readAll();
    received += chunk.size();
    // Never more than the manifest says the file is.
    if (received > target.size) {
        qWarning() << "Update: the download is larger than the signed manifest says";
        overrun = true;
        running->abort();
        return;
    }
    hash.addData(chunk);
    if (partial->write(chunk) != chunk.size()) {
        qWarning() << "Update: cannot write the download";
        running->abort();
        return;
    }
    stall.start();
    const int now = static_cast<int>(received * 100 / target.size);
    if (now != shownPercent) {
        shownPercent = now;
        emit changed();
    }
}

void UpdateInstaller::downloaded()
{
    stall.stop();
    if (!reply) {
        return;
    }
    QNetworkReply* const done = reply.data();
    reply.clear();
    done->deleteLater();
    if (current != Stage::Downloading) {
        // Stopped by the person.
        if (partial) {
            partial->remove();
            partial.reset();
        }
        return;
    }
    if (overrun) {
        failWith(Failure::Integrity,
                 QStringLiteral("the download is larger than the signed manifest says"));
        return;
    }
    const int status = done->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    if (done->error() != QNetworkReply::NoError || status != 200 || !partial) {
        failWith(Failure::Download, QStringLiteral("%1 answered %2 (%3)")
                                        .arg(done->url().toString(), QString::number(status),
                                             done->errorString()));
        return;
    }
    if (received != target.size || hash.result() != target.sha256) {
        failWith(Failure::Integrity,
                 QStringLiteral("the download is not the file the signed manifest describes"));
        return;
    }
    partial->close();
    QFile::remove(path);
    if (!partial->rename(path)) {
        failWith(Failure::Download, QStringLiteral("cannot keep the download as ") + path);
        return;
    }
    partial.reset();
    apply();
}

void UpdateInstaller::apply()
{
    qInfo() << "Update: the download matches the signed manifest";
    switch (setup.layout.kind) {
    case UpdatePlatform::Kind::WindowsInstaller:
        runInstaller();
        return;
    case UpdatePlatform::Kind::WindowsZip:
    case UpdatePlatform::Kind::MacZip:
        if (canSwap()) {
            unpack();
        } else {
            manual();
        }
        return;
    case UpdatePlatform::Kind::LinuxDeb:
    case UpdatePlatform::Kind::LinuxPortable:
    case UpdatePlatform::Kind::None:
        break;
    }
    manual();
}

void UpdateInstaller::manual()
{
    qInfo() << "Update: the verified file is left to install by hand:" << path;
    setStage(Stage::Manual);
}

void UpdateInstaller::runInstaller()
{
#if defined(Q_OS_WIN)
    // Opened so that nothing else can write the file, then checked again through the same handle:
    // the bytes Windows runs elevated are the bytes that were verified.
    const std::wstring file = QDir::toNativeSeparators(path).toStdWString();
    HANDLE held = CreateFileW(file.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING,
                              FILE_ATTRIBUTE_NORMAL, nullptr);
    if (held == INVALID_HANDLE_VALUE) {
        failWith(Failure::Start, QStringLiteral("cannot open the installer"));
        return;
    }
    if (!sameBytes(held, target)) {
        CloseHandle(held);
        QFile::remove(path);
        failWith(Failure::Integrity, QStringLiteral("the installer changed after it was checked"));
        return;
    }
    const std::wstring arguments =
        UpdatePlatform::installerArguments(QCoreApplication::applicationPid(), setup.layout.root)
            .toStdWString();
    SHELLEXECUTEINFOW info;
    ZeroMemory(&info, sizeof(info));
    info.cbSize = sizeof(info);
    info.fMask = SEE_MASK_NOCLOSEPROCESS;
    info.lpVerb = L"runas";
    info.lpFile = file.c_str();
    info.lpParameters = arguments.c_str();
    info.nShow = SW_SHOWNORMAL;
    if (!ShellExecuteExW(&info)) {
        const DWORD error = GetLastError();
        CloseHandle(held);
        if (error == ERROR_CANCELLED) {
            failWith(Failure::Declined, QStringLiteral("permission to install was not given"));
        } else {
            failWith(Failure::Start, QStringLiteral("the installer did not start (%1)").arg(error));
        }
        return;
    }
    if (info.hProcess) {
        CloseHandle(info.hProcess);
    }
    heldInstaller = held;
    qInfo() << "Update: the installer waits for Khandaq to quit";
    setStage(Stage::Restarting);
    emit quitRequested();
#else
    manual();
#endif
}

bool UpdateInstaller::canSwap() const
{
    const QFileInfo root(setup.layout.root);
    const QFileInfo parent(root.absolutePath());
    if (!root.isDir() || !parent.isDir() || !parent.isWritable()) {
        return false;
    }
#if defined(Q_OS_WIN)
    if (underProgramFiles(root.absoluteFilePath())) {
        return false;
    }
#endif
    // A portable macOS copy keeps its settings and profiles inside the bundle.
    return !(setup.layout.kind == UpdatePlatform::Kind::MacZip && setup.portable);
}

void UpdateInstaller::unpack()
{
    qint64 stamp = QDateTime::currentSecsSinceEpoch();
    swap = UpdatePlatform::plan(setup.layout.kind, setup.layout.root, stamp);
    while (QFileInfo::exists(swap.staging) || QFileInfo::exists(swap.old)) {
        swap = UpdatePlatform::plan(setup.layout.kind, setup.layout.root, ++stamp);
    }
    // Creating it is the test that counts: a folder Khandaq cannot write beside is left to the person.
    if (!QDir().mkdir(swap.staging)) {
        qInfo() << "Update: cannot unpack beside" << setup.layout.root;
        swap = UpdatePlatform::Swap{};
        manual();
        return;
    }
    setStage(Stage::Preparing);

#if defined(Q_OS_WIN)
    // bsdtar, part of Windows since 10 version 1803, unpacks zip.
    const QString program = QDir::toNativeSeparators(
        QDir(qEnvironmentVariable("SystemRoot")).filePath(QStringLiteral("System32/tar.exe")));
    const QStringList arguments{QStringLiteral("-xf"), QDir::toNativeSeparators(path),
                                QStringLiteral("-C"), QDir::toNativeSeparators(swap.staging)};
#else
    const QString program = QStringLiteral("/usr/bin/ditto");
    const QStringList arguments{QStringLiteral("-x"), QStringLiteral("-k"), path, swap.staging};
#endif
    auto* const process = new QProcess(this);
    unpacker = process;
    connect(process, QOverload<int, QProcess::ExitStatus>::of(&QProcess::finished), this,
            [this, process](int exitCode, QProcess::ExitStatus status) {
                process->deleteLater();
                unpacked(exitCode, status == QProcess::CrashExit);
            });
    connect(process, &QProcess::errorOccurred, this, [this, process](QProcess::ProcessError error) {
        if (error != QProcess::FailedToStart) {
            return;
        }
        process->deleteLater();
        qWarning() << "Update: cannot run" << process->program();
        dropStaging();
        manual();
    });
    process->start(program, arguments);
}

void UpdateInstaller::unpacked(int exitCode, bool crashed)
{
    if (crashed || exitCode != 0) {
        dropStaging();
        failWith(Failure::Unpack, QStringLiteral("unpacking ended with %1").arg(exitCode));
        return;
    }
    if (setup.layout.kind == UpdatePlatform::Kind::WindowsZip) {
        if (!QFileInfo(QDir(swap.staging).filePath(swap.executable)).isFile()) {
            dropStaging();
            failWith(Failure::Unpack, QStringLiteral("the zip has no ") + swap.executable);
            return;
        }
        swap.carried = UpdatePlatform::carriedNames(swap.root, swap.staged);
        qInfo() << "Update: the folder's own entries carried into the new one:" << swap.carried.size();
    } else {
        const QFileInfoList bundles = QDir(swap.staging).entryInfoList(
            {QStringLiteral("*.app")}, QDir::Dirs | QDir::NoDotAndDotDot);
        if (bundles.size() != 1
            || !QFileInfo(bundles.first().absoluteFilePath() + QStringLiteral("/Contents/Info.plist"))
                    .isFile()) {
            dropStaging();
            failWith(Failure::Unpack, QStringLiteral("the zip does not hold one app bundle"));
            return;
        }
        swap.staged = bundles.first().absoluteFilePath();
    }
    startHelper();
}

void UpdateInstaller::startHelper()
{
    const qint64 pid = QCoreApplication::applicationPid();
    const QString temp = QDir::tempPath();
    bool started = false;
#if defined(Q_OS_WIN)
    const QString script = QDir(temp).filePath(QStringLiteral("khandaq-update.cmd"));
    if (writeScript(script, UpdatePlatform::windowsScript())) {
        QString shell = qEnvironmentVariable("ComSpec");
        if (shell.isEmpty()) {
            shell = QDir(qEnvironmentVariable("SystemRoot")).filePath(QStringLiteral("System32/cmd.exe"));
        }
        QProcess helper;
        helper.setProgram(QDir::toNativeSeparators(shell));
        // With /s the line after /c runs as it stands, so the quoted path of the script is never
        // split; the path itself comes from the environment.
        helper.setNativeArguments(QStringLiteral("/d /s /c \"\"%KHQ_SCRIPT%\"\""));
        QProcessEnvironment environment = QProcessEnvironment::systemEnvironment();
        for (const QString& variable : UpdatePlatform::windowsEnvironment(swap, pid)) {
            const int equals = variable.indexOf(QLatin1Char('='));
            environment.insert(variable.left(equals), variable.mid(equals + 1));
        }
        environment.insert(QStringLiteral("KHQ_SCRIPT"), QDir::toNativeSeparators(script));
        helper.setProcessEnvironment(environment);
        // Not the folder that is about to be moved.
        helper.setWorkingDirectory(temp);
        helper.setCreateProcessArgumentsModifier([](QProcess::CreateProcessArguments* arguments) {
            arguments->flags &= ~static_cast<DWORD>(CREATE_NEW_CONSOLE);
            arguments->flags |= CREATE_NO_WINDOW;
        });
        started = helper.startDetached();
    }
#elif defined(Q_OS_MACOS)
    const QString script = QDir(temp).filePath(QStringLiteral("khandaq-update.sh"));
    if (writeScript(script, UpdatePlatform::macScript())) {
        const QString program = QFileInfo(QCoreApplication::applicationFilePath()).fileName();
        started = QProcess::startDetached(
            QStringLiteral("/bin/sh"),
            UpdatePlatform::macArguments(script, swap, pid, program, QStringLiteral("/usr/bin/open")),
            temp);
    }
#else
    std::ignore = pid;
    std::ignore = temp;
#endif
    if (!started) {
        dropStaging();
        failWith(Failure::Start, QStringLiteral("the update helper did not start"));
        return;
    }
    qInfo() << "Update: the helper waits for Khandaq to quit";
    setStage(Stage::Restarting);
    emit quitRequested();
}

void UpdateInstaller::dropStaging()
{
    if (!swap.staging.isEmpty()) {
        QDir(swap.staging).removeRecursively();
    }
    swap = UpdatePlatform::Swap{};
}

void UpdateInstaller::cleanUp()
{
    // A download or an unpacking of this run is among what this removes: its .part file, its
    // unpacked copy. What is left now goes at the next start.
    if (current == Stage::Downloading || current == Stage::Preparing || current == Stage::Restarting) {
        qInfo() << "Update: earlier leftovers stay until the next start, an update is under way";
        return;
    }
    const QDir downloads(setup.downloads);
    if (downloads.exists()) {
        for (const QFileInfo& folder : downloads.entryInfoList(QDir::Dirs | QDir::NoDotAndDotDot)) {
            UpdateManifest::Version version;
            if (UpdateManifest::parseVersion(folder.fileName(), version)
                && UpdateManifest::compare(version, setup.running) <= 0) {
                QDir(folder.absoluteFilePath()).removeRecursively();
                continue;
            }
            for (const QFileInfo& part : QDir(folder.absoluteFilePath())
                                             .entryInfoList({QStringLiteral("*.part")}, QDir::Files)) {
                QFile::remove(part.absoluteFilePath());
            }
        }
    }
    for (const QString& leftover : UpdatePlatform::leftovers(setup.layout.kind, setup.layout.root)) {
        qInfo() << "Update: removing what an earlier update left beside this copy:" << leftover;
        QDir(leftover).removeRecursively();
    }
}
