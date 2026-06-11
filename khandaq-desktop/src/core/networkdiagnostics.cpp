#include "networkdiagnostics.h"

#include "src/core/core.h"
#include "src/core/reconnectbackoff.h"

#include <QDateTime>
#include <QMutex>
#include <QStringList>
#include <tox/tox.h>

namespace {
QMutex logMutex;
QStringList logLines;
constexpr int MAX_LINES = 500;

void appendLine(const QString& line)
{
    QMutexLocker lock(&logMutex);
    logLines.append(line);
    while (logLines.size() > MAX_LINES) {
        logLines.removeFirst();
    }
}
} // namespace

void NetworkDiagnostics::logEvent(const QString& event, const QString& detail)
{
    const QString line = QDateTime::currentDateTime().toString(Qt::ISODateWithMs)
        + " [" + event + "] " + detail;
    appendLine(line);
}

QString NetworkDiagnostics::eventLogSnapshot()
{
    QMutexLocker lock(&logMutex);
    return logLines.join('\n');
}

QString NetworkDiagnostics::runtimeSnapshot(const Core* core)
{
    QString out;
    if (!core) {
        return QStringLiteral("core=null\n");
    }

    const Tox* tox = core->getTox();
    if (tox) {
        out += QStringLiteral("tox_self_connection_status=")
            + QString::number(tox_self_get_connection_status(tox)) + QLatin1Char('\n');
    }
    out += QStringLiteral("reconnect_attempt=")
        + QString::number(ReconnectBackoff::instance().currentAttempt()) + QLatin1Char('\n');
    return out;
}
