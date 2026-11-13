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

#include "src/core/networkdiagnostics.h"

#include <QRegularExpression>
#include <QtTest/QtTest>

/**
 * The node count and the log for support (E4): the relays come from the report of
 * tox_get_all_tcp_relays in this toxcore fork (Messenger.c print_all_tcp_relays), each relay once
 * however many friends and groups use it; the log says states and counts, never an address.
 */
class TestNetworkDiagnostics : public QObject
{
    Q_OBJECT

private slots:
    void relaysAreCountedOncePerAddress();
    void paddedPortsAndIpv6AddressesCount();
    void reportEndsAtItsFirstZeroByte();
    void otherLinesAreNotRelays();
    void supportReportHasStatesAndCountsButNoAddresses();

private:
    /** A report as toxcore leaves it: the text in a buffer of zero bytes of the size it asks for. */
    static QByteArray report(const QByteArray& text);
};

QByteArray TestNetworkDiagnostics::report(const QByteArray& text)
{
    QByteArray buffer(NetworkDiagnostics::relayReportSize, '\0');
    buffer.replace(0, text.size(), text);
    buffer.resize(NetworkDiagnostics::relayReportSize);
    return buffer;
}

void TestNetworkDiagnostics::relaysAreCountedOncePerAddress()
{
    // The relay of the friends serves a group as well: one node, not two.
    const QByteArray text = "FRS:=================\n"
                            "port=33445 ip=127.0.0.1\n"
                            "port=33445 ip=198.51.100.20\n"
                            "FRS:NUM:2\n"
                            "GRP:=================\n"
                            "port=33445 ip=127.0.0.1\n"
                            "GRP:NUM:1\n"
                            "END:=================\n";
    QCOMPARE(NetworkDiagnostics::relayCount(report(text)), 2);

    // The same host on another port is another relay.
    const QByteArray twoPorts = "FRS:=================\n"
                                "port=33445 ip=198.51.100.20\n"
                                "port= 3389 ip=198.51.100.20\n"
                                "FRS:NUM:2\n"
                                "GRP:=================\n"
                                "GRP:NUM:0\n"
                                "END:=================\n";
    QCOMPARE(NetworkDiagnostics::relayCount(report(twoPorts)), 2);

    const QByteArray none = "FRS:=================\nFRS:NUM:0\nGRP:=================\nGRP:NUM:0\n"
                            "END:=================\n";
    QCOMPARE(NetworkDiagnostics::relayCount(report(none)), 0);
    QCOMPARE(NetworkDiagnostics::relayCount(QByteArray()), 0);
}

void TestNetworkDiagnostics::paddedPortsAndIpv6AddressesCount()
{
    // %5d pads a short port with spaces; an IPv6 address toxcore could not print still names a
    // relay that is connected.
    const QByteArray text = "FRS:=================\n"
                            "port=  443 ip=203.0.113.7\n"
                            "port=33445 ip=2001:db8::1\n"
                            "port=33445 ip=<error in ipv6\n"
                            "FRS:NUM:3\n"
                            "GRP:=================\n"
                            "port=33445 ip=2001:db8::1\n"
                            "GRP:NUM:1\n"
                            "END:=================\n";
    QCOMPARE(NetworkDiagnostics::relayCount(report(text)), 3);
}

void TestNetworkDiagnostics::reportEndsAtItsFirstZeroByte()
{
    // Whatever stands after the end of the text is not part of the report.
    QByteArray buffer = report("FRS:=================\nport=33445 ip=127.0.0.1\nFRS:NUM:1\n");
    const QByteArray stale = "port=33445 ip=198.51.100.20\n";
    buffer.replace(200, stale.size(), stale);
    QCOMPARE(NetworkDiagnostics::relayCount(buffer), 1);

    // A report that fills its buffer without a zero byte is read whole.
    QByteArray full("port=33445 ip=127.0.0.1\n");
    QCOMPARE(NetworkDiagnostics::relayCount(full), 1);
}

void TestNetworkDiagnostics::otherLinesAreNotRelays()
{
    const QByteArray text = "FRS:NUM:5\n"
                            "port=abc ip=127.0.0.1\n"
                            "ip=127.0.0.1\n"
                            "port=33445\n"
                            "port=33445 ip=\n"
                            "\n"
                            "END:=================\n";
    QCOMPARE(NetworkDiagnostics::relayCount(report(text)), 0);
}

void TestNetworkDiagnostics::supportReportHasStatesAndCountsButNoAddresses()
{
    NetworkDiagnostics::logEvent(QStringLiteral("reconnect_manual"),
                                 QStringLiteral("asked for in Settings"));
    NetworkDiagnostics::Snapshot now;
    now.selfConnection = 1;
    now.internet = true;
    now.relays = 3;
    now.directContacts = 2;
    const QString said = NetworkDiagnostics::supportReport(now);
    QVERIFY2(said.startsWith(QStringLiteral("--- network ---\n")), qPrintable(said));
    QVERIFY(said.contains(QStringLiteral("\nself_connection=tcp\n")));
    QVERIFY(said.contains(QStringLiteral("\ninternet=yes\n")));
    QVERIFY(said.contains(QStringLiteral("\nrelays=3\n")));
    QVERIFY(said.contains(QStringLiteral("\ndirect_contacts=2\n")));
    QVERIFY(said.contains(QStringLiteral("\n--- network events ---\n")));
    QVERIFY(said.contains(QStringLiteral("[reconnect_manual] asked for in Settings")));
    QVERIFY(said.endsWith(QLatin1Char('\n')));

    // No address of any kind: neither IPv4 nor IPv6 nor a port. The times of the events have
    // three groups between colons; an IPv6 address has a double colon or more groups.
    QVERIFY(!said.contains(QRegularExpression(QStringLiteral("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b"))));
    QVERIFY(!said.contains(QStringLiteral("::")));
    QVERIFY(!said.contains(QRegularExpression(QStringLiteral("([0-9a-fA-F]{1,4}:){4,}"))));
    QVERIFY(!said.contains(QStringLiteral("port=")));
    QVERIFY(!said.contains(QStringLiteral("ip=")));

    now.selfConnection = 2;
    QVERIFY(
        NetworkDiagnostics::supportReport(now).contains(QStringLiteral("\nself_connection=udp\n")));
    now.selfConnection = 0;
    now.internet = false;
    const QString offline = NetworkDiagnostics::supportReport(now);
    QVERIFY(offline.contains(QStringLiteral("\nself_connection=none\n")));
    QVERIFY(offline.contains(QStringLiteral("\ninternet=no\n")));
}

QTEST_GUILESS_MAIN(TestNetworkDiagnostics)
#include "networkdiagnostics_test.moc"
