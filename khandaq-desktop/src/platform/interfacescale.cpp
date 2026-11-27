/*
    Copyright © 2026 by The Khandaq Project Contributors

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

#include "interfacescale.h"

#include "src/persistence/paths.h"

#include <QDebug>
#include <QDir>
#include <QFileInfo>
#include <QSettings>

#include <vector>

#if defined(Q_OS_WIN)
#include <windows.h>
#elif defined(Q_OS_MACOS)
#include <mach-o/dyld.h>
#endif

namespace InterfaceScale {
namespace {

const char scaleFactorVariable[] = "QT_SCALE_FACTOR";
const char baseVariable[] = "KHANDAQ_SCALE_BASE";
/** KHANDAQ_SCALE_BASE when the process had no QT_SCALE_FACTOR (Windows drops an empty variable). */
const char noBase[] = "none";
const char settingsFile[] = "khandaq.ini";

int sizeInForce = 100;

#if defined(Q_OS_WIN)
const char screenFactorsVariable[] = "QT_SCREEN_SCALE_FACTORS";
/** Set with the QT_SCREEN_SCALE_FACTORS Khandaq made, so that a Khandaq started from this one makes them anew. */
const char ownScreenFactorsVariable[] = "KHANDAQ_SCREEN_FACTORS";

// shcore.dll, Windows 8.1 and later, resolved when it runs as Qt 5.12 resolves it
// (QWindowsContext::shcoredll): Khandaq still starts on a Windows without it.
using SetProcessDpiAwarenessFunction = HRESULT(WINAPI*)(int);
using GetDpiForMonitorFunction = HRESULT(WINAPI*)(HMONITOR, int, UINT*, UINT*);
const int perMonitorDpiAware = 2; // PROCESS_PER_MONITOR_DPI_AWARE
const int effectiveDpi = 0;       // MDT_EFFECTIVE_DPI

struct ScreenScan
{
    GetDpiForMonitorFunction dpiForMonitor;
    QVector<ScreenDpi> screens;
};

template <typename Function>
Function resolve(HMODULE module, const char* name)
{
    // Through void (*)(), which GCC's -Wcast-function-type takes for the type of any function.
    return reinterpret_cast<Function>(reinterpret_cast<void (*)()>(GetProcAddress(module, name)));
}

BOOL CALLBACK scanScreen(HMONITOR monitor, HDC, LPRECT, LPARAM data)
{
    ScreenScan* scan = reinterpret_cast<ScreenScan*>(data);
    MONITORINFOEXW info;
    ZeroMemory(&info, sizeof(info));
    info.cbSize = sizeof(info);
    UINT dpiX = 0;
    UINT dpiY = 0;
    if (GetMonitorInfoW(monitor, &info)
        && SUCCEEDED(scan->dpiForMonitor(monitor, effectiveDpi, &dpiX, &dpiY))) {
        ScreenDpi screen;
        screen.name = QString::fromWCharArray(info.szDevice);
        screen.dpi = static_cast<int>(dpiX);
        scan->screens.append(screen);
    }
    return TRUE;
}

/** The screens with the DPI Qt will see on them; none on a Windows without shcore.dll. */
QVector<ScreenDpi> windowsScreens()
{
    const HMODULE shcore = LoadLibraryW(L"shcore.dll");
    if (shcore == nullptr) {
        return QVector<ScreenDpi>();
    }
    const SetProcessDpiAwarenessFunction setAwareness =
        resolve<SetProcessDpiAwarenessFunction>(shcore, "SetProcessDpiAwareness");
    ScreenScan scan;
    scan.dpiForMonitor = resolve<GetDpiForMonitorFunction>(shcore, "GetDpiForMonitor");
    if (setAwareness == nullptr || scan.dpiForMonitor == nullptr) {
        return QVector<ScreenDpi>();
    }
    // A process sees the DPI of each screen only once it is aware of DPI per monitor. Qt makes it
    // so a moment later, its default on Windows, and takes it that it already is.
    setAwareness(perMonitorDpiAware);
    EnumDisplayMonitors(nullptr, nullptr, scanScreen, reinterpret_cast<LPARAM>(&scan));
    return scan.screens;
}
#endif

/**
 * The directory of the running program, without the application object that
 * QCoreApplication::applicationDirPath needs, found the way Qt finds it.
 */
QString programDirectory(const char* argv0)
{
#if defined(Q_OS_WIN)
    std::vector<wchar_t> buffer(MAX_PATH);
    while (buffer.size() <= 32768) {
        const DWORD length =
            GetModuleFileNameW(nullptr, buffer.data(), static_cast<DWORD>(buffer.size()));
        if (length == 0) {
            break;
        }
        if (length < buffer.size()) {
            return QFileInfo(QString::fromWCharArray(buffer.data(), static_cast<int>(length)))
                .absolutePath();
        }
        buffer.resize(buffer.size() * 2);
    }
#elif defined(Q_OS_MACOS)
    uint32_t size = 0;
    _NSGetExecutablePath(nullptr, &size);
    std::vector<char> buffer(size + 1, '\0');
    if (_NSGetExecutablePath(buffer.data(), &size) == 0) {
        const QFileInfo program(QString::fromUtf8(buffer.data()));
        const QString canonical = program.canonicalFilePath();
        return QFileInfo(canonical.isEmpty() ? program.absoluteFilePath() : canonical)
            .absolutePath();
    }
#else
    const QFileInfo self(QStringLiteral("/proc/self/exe"));
    const QString canonical = self.canonicalFilePath();
    if (!canonical.isEmpty()) {
        return QFileInfo(canonical).absolutePath();
    }
#endif
    if (argv0 && *argv0) {
        return QFileInfo(QString::fromLocal8Bit(argv0)).absolutePath();
    }
    return QString();
}

QByteArray environmentValue(const char* name, bool* set)
{
    *set = qEnvironmentVariableIsSet(name);
    return *set ? qgetenv(name) : QByteArray();
}

void putEnvironment(const char* name, bool set, const QByteArray& value)
{
    if (set) {
        qputenv(name, value);
    } else {
        qunsetenv(name);
    }
}

#if defined(Q_OS_WIN)
/** Each screen at its own scale (screenFactors), unless the factors came from outside Khandaq. */
void applyScreenFactors()
{
    if (qEnvironmentVariableIsSet(screenFactorsVariable)
        && !qEnvironmentVariableIsSet(ownScreenFactorsVariable)) {
        qDebug().nospace() << screenFactorsVariable << " from outside: "
                           << qgetenv(screenFactorsVariable);
        return;
    }
    const QByteArray factors = screenFactors(windowsScreens());
    putEnvironment(screenFactorsVariable, !factors.isEmpty(), factors);
    putEnvironment(ownScreenFactorsVariable, !factors.isEmpty(), QByteArrayLiteral("1"));
    if (!factors.isEmpty()) {
        qDebug().nospace() << "Screens at their own scale: " << screenFactorsVariable << "="
                           << factors;
    }
}
#endif

} // namespace

const QVector<int>& percents()
{
    static const QVector<int> offered{100, 110, 125, 150, 175, 200};
    return offered;
}

int normalized(int percent)
{
    return percents().contains(percent) ? percent : 100;
}

bool fits(const QSize& room, const QSize& least, int inForce, int percent)
{
    if (room.isEmpty() || inForce <= 0 || percent <= inForce) {
        return true;
    }
    return qint64(room.width()) * inForce >= qint64(least.width()) * percent
           && qint64(room.height()) * inForce >= qint64(least.height()) * percent;
}

QString globalSettingsFile(const QString& applicationDir)
{
    const QString directory = Paths::portableIn(applicationDir)
                                  ? applicationDir + QDir::separator()
                                  : Paths::systemSettingsDirPath();
    return directory + QLatin1String(settingsFile);
}

int storedPercent(const QString& path)
{
    if (path.isEmpty() || !QFileInfo::exists(path)) {
        return 100;
    }
    QSettings s(path, QSettings::IniFormat);
    s.setIniCodec("UTF-8");
    s.beginGroup(QStringLiteral("GUI"));
    bool number = false;
    const int percent = s.value(QStringLiteral("interfaceScale"), 100).toInt(&number);
    s.endGroup();
    return number ? normalized(percent) : 100;
}

Decision decide(int percent, const Environment& found)
{
    // The base is what came from outside Khandaq: kept by a Khandaq that started this one, else
    // the process's own QT_SCALE_FACTOR, else none.
    bool hasBase = false;
    QByteArray base;
    if (found.baseSet) {
        hasBase = found.base != noBase;
        base = hasBase ? found.base : QByteArray();
    } else if (found.scaleFactorSet) {
        hasBase = true;
        base = found.scaleFactor;
    }

    Decision decision;
    const int size = normalized(percent);
    if (size == 100) {
        // Qt scales as the base says, and nothing is left for a Khandaq started from this one.
        decision.scaleFactorSet = hasBase;
        decision.scaleFactor = base;
        return decision;
    }

    bool number = false;
    const double baseFactor = hasBase ? base.toDouble(&number) : 1.0;
    const double from = (hasBase && number && baseFactor > 0) ? baseFactor : 1.0;
    decision.scaleFactorSet = true;
    decision.scaleFactor = QByteArray::number(from * size / 100.0, 'g', 6);
    decision.baseSet = true;
    decision.base = hasBase ? base : QByteArray(noBase);
    return decision;
}

void applyBeforeApplication(const char* argv0)
{
#if defined(Q_OS_WIN)
    applyScreenFactors();
#endif
    const QString file = globalSettingsFile(programDirectory(argv0));
    const int percent = storedPercent(file);

    Environment found;
    found.scaleFactor = environmentValue(scaleFactorVariable, &found.scaleFactorSet);
    found.base = environmentValue(baseVariable, &found.baseSet);
    const Decision decision = decide(percent, found);
    putEnvironment(scaleFactorVariable, decision.scaleFactorSet, decision.scaleFactor);
    putEnvironment(baseVariable, decision.baseSet, decision.base);
    sizeInForce = percent;

    if (percent != 100 || found.scaleFactorSet) {
        const QByteArray factor =
            decision.scaleFactorSet ? decision.scaleFactor : QByteArrayLiteral("unset");
        qDebug().nospace() << "Interface size " << percent << "% from " << file << ": "
                           << scaleFactorVariable << "=" << factor;
    }
}

int inForce()
{
    return sizeInForce;
}

double screenCorrection(int dpi)
{
    const double asked = dpi / 96.0;
    if (asked <= 1.0) {
        return 1.0;
    }
    // As QWindowsScreen::pixelDensity of Qt 5.12 rounds it.
    const int given = qMax(1, qRound(asked));
    return asked / given;
}

QByteArray screenFactors(const QVector<ScreenDpi>& screens)
{
    QByteArray factors;
    for (const ScreenDpi& screen : screens) {
        const double factor = screenCorrection(screen.dpi);
        if (screen.name.isEmpty() || qFuzzyCompare(factor, 1.0)) {
            continue;
        }
        if (!factors.isEmpty()) {
            factors += ';';
        }
        // Qt reads the name back with QString::fromLocal8Bit.
        factors += screen.name.toLocal8Bit() + '=' + QByteArray::number(factor, 'g', 6);
    }
    return factors;
}

} // namespace InterfaceScale
