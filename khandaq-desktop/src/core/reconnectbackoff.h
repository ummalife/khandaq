#pragma once

#include <QString>
#include <QMutex>

class ReconnectBackoff
{
public:
    static ReconnectBackoff& instance();

    qint64 delayMsForAttempt(int attempt) const;
    void reset();
    void noteAttempt(const QString& reason, bool urgent);
    int currentAttempt() const;

private:
    ReconnectBackoff() = default;

    mutable QMutex mutex;
    int attempt_ = 0;
};
