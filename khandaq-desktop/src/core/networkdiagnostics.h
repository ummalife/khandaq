#pragma once

#include <QString>

class Core;

class NetworkDiagnostics
{
public:
    static void logEvent(const QString& event, const QString& detail = QString());
    static QString eventLogSnapshot();
    static QString runtimeSnapshot(const Core* core);
};
