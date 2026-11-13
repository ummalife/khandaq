#pragma once

#include <QByteArray>
#include <QString>

class NetworkDiagnostics
{
public:
    /** What the node does now, as the log for support says it: states and counts, no addresses. */
    struct Snapshot
    {
        /** A TOX_CONNECTION value: none, a TCP relay or direct UDP. */
        int selfConnection = 0;
        bool internet = false;
        int relays = 0;
        int directContacts = 0;
    };

    static void logEvent(const QString& event, const QString& detail = QString());
    static QString eventLogSnapshot();
    /**
     * The network part of the log for support: the state of the node, the reconnect attempt and
     * the events logged so far.
     */
    static QString supportReport(const Snapshot& now);

    /** The buffer tox_get_all_tcp_relays of this toxcore fork asks for, cleared to zero bytes. */
    static constexpr int relayReportSize = 60301;
    /**
     * The relays a report of tox_get_all_tcp_relays lists, each once: the connections of friends
     * and of groups to the same relay are one node. The report reads up to its first zero byte.
     */
    static int relayCount(const QByteArray& report);
};
