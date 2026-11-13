#include "networkdiagnostics.h"

#include "src/core/reconnectbackoff.h"

#include <QDateTime>
#include <QMutex>
#include <QRegularExpression>
#include <QSet>
#include <QStringList>

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

QString connectionName(int selfConnection)
{
    // TOX_CONNECTION_NONE, TOX_CONNECTION_TCP and TOX_CONNECTION_UDP.
    switch (selfConnection) {
    case 1:
        return QStringLiteral("tcp");
    case 2:
        return QStringLiteral("udp");
    default:
        return QStringLiteral("none");
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

QString NetworkDiagnostics::supportReport(const Snapshot& now)
{
    QString out = QStringLiteral("--- network ---\n");
    out += QStringLiteral("self_connection=") + connectionName(now.selfConnection) + QLatin1Char('\n');
    out += QStringLiteral("internet=")
           + (now.internet ? QStringLiteral("yes") : QStringLiteral("no")) + QLatin1Char('\n');
    out += QStringLiteral("relays=") + QString::number(now.relays) + QLatin1Char('\n');
    out +=
        QStringLiteral("direct_contacts=") + QString::number(now.directContacts) + QLatin1Char('\n');
    out += QStringLiteral("reconnect_attempt=")
           + QString::number(ReconnectBackoff::instance().currentAttempt()) + QLatin1Char('\n');
    out += QStringLiteral("--- network events ---\n");
    const QString events = eventLogSnapshot();
    out += events.isEmpty() ? QStringLiteral("(none)") : events;
    out += QLatin1Char('\n');
    return out;
}

int NetworkDiagnostics::relayCount(const QByteArray& report)
{
    // One line per connected relay: "port=%5d ip=%s" (toxcore TCP_connection.c), under the
    // section of friends and again under every group that uses it. An IPv6 address toxcore could
    // not print stands as "<error in ipv6": still a relay.
    static const QRegularExpression relayLine(QStringLiteral("^port=\\s*(\\d+)\\s+ip=(.+)$"));
    const int end = report.indexOf('\0');
    const QString text = QString::fromUtf8(end < 0 ? report : report.left(end));
    QSet<QString> relays;
    for (const QString& line : text.split(QLatin1Char('\n'))) {
        const QRegularExpressionMatch match = relayLine.match(line.trimmed());
        if (match.hasMatch()) {
            relays.insert(match.captured(2) + QLatin1Char(' ') + match.captured(1));
        }
    }
    return relays.size();
}
