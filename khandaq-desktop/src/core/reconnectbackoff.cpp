#include "reconnectbackoff.h"

#include "src/core/networkdiagnostics.h"

#include <QRandomGenerator>

namespace {
constexpr qint64 BASE_DELAY_MS = 1000;
constexpr qint64 MAX_DELAY_MS = 30000;
}

ReconnectBackoff& ReconnectBackoff::instance()
{
    static ReconnectBackoff inst;
    return inst;
}

qint64 ReconnectBackoff::delayMsForAttempt(int attempt) const
{
    const qint64 exp = BASE_DELAY_MS * (1LL << qMin(attempt, 5));
    const qint64 capped = qMin(exp, MAX_DELAY_MS);
    const qint64 jitter = static_cast<qint64>(capped * 0.1 * QRandomGenerator::global()->generateDouble());
    return capped + jitter;
}

void ReconnectBackoff::reset()
{
    QMutexLocker lock(&mutex);
    if (attempt_ > 0) {
        NetworkDiagnostics::logEvent("reconnect_ok", QString("attempts_reset=%1").arg(attempt_));
    }
    attempt_ = 0;
}

void ReconnectBackoff::noteAttempt(const QString& reason, bool urgent)
{
    QMutexLocker lock(&mutex);
    if (urgent) {
        attempt_ = 0;
    }
    NetworkDiagnostics::logEvent("reconnect_attempt",
                                 QString("reason=%1 urgent=%2 attempt=%3").arg(reason).arg(urgent).arg(attempt_));
    if (!urgent) {
        ++attempt_;
    }
}

int ReconnectBackoff::currentAttempt() const
{
    QMutexLocker lock(&mutex);
    return attempt_;
}
