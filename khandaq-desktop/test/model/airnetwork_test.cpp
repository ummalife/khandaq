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

#include "src/model/air/airnetwork.h"
#include "src/widget/air/airstrings.h"

#include <QTranslator>
#include <QtTest/QtTest>

#include <memory>

/**
 * The state of our own node (E4) as the line under your name and Settings, Network say it, with
 * the states of khandaq-ios/Antidote/AirNetworkScreen.swift: what each state means for messages,
 * when Connect again helps, when the dot is red, and the words of the line and of the reach mark of
 * a relayed contact in every language. The count of nodes stays in Settings, Network.
 */
class TestAirNetwork : public QObject
{
    Q_OBJECT

private slots:
    void initTestCase();
    void init();
    void cleanup();

    void everyStateSaysTheLinkTheInternetAndWhatHelps();
    void nodesAreTheRelaysAndTheContactsReachedDirectly();
    void lineNamesTheStateWithoutACount();
    void lineAndSettingsSpeakEveryLanguage();

private:
    void use(const QString& language);

    std::unique_ptr<QTranslator> translator;
};

void TestAirNetwork::initTestCase()
{
    Q_INIT_RESOURCE(translations);
}

void TestAirNetwork::init()
{
    use(QStringLiteral("en"));
}

void TestAirNetwork::cleanup()
{
    if (translator) {
        QCoreApplication::removeTranslator(translator.get());
        translator.reset();
    }
}

void TestAirNetwork::use(const QString& language)
{
    cleanup();
    translator.reset(new QTranslator);
    QVERIFY2(translator->load(language, QStringLiteral(":translations/")), qPrintable(language));
    QCoreApplication::installTranslator(translator.get());
}

void TestAirNetwork::everyStateSaysTheLinkTheInternetAndWhatHelps()
{
    using Air::NetworkReport;
    using Air::NetworkState;
    using Link = NetworkReport::Link;

    const NetworkReport direct = NetworkReport::make(NetworkState::Direct);
    QCOMPARE(direct.link, Link::Direct);
    QVERIFY(direct.internet);
    QVERIFY(direct.live);
    QVERIFY(!direct.offersReconnect);
    QVERIFY(!direct.alarm);

    const NetworkReport relay = NetworkReport::make(NetworkState::Relay);
    QCOMPARE(relay.link, Link::Relay);
    QVERIFY(relay.internet);
    QVERIFY(relay.live);
    QVERIFY(!relay.offersReconnect);
    QVERIFY(!relay.alarm);

    // Connect again helps only while the computer has internet and the node has not found the
    // network; the dot is red only when there is no network at all.
    const NetworkReport connecting = NetworkReport::make(NetworkState::Connecting);
    QCOMPARE(connecting.link, Link::None);
    QVERIFY(connecting.internet);
    QVERIFY(!connecting.live);
    QVERIFY(connecting.offersReconnect);
    QVERIFY(!connecting.alarm);

    const NetworkReport offline = NetworkReport::make(NetworkState::Offline);
    QCOMPARE(offline.link, Link::None);
    QVERIFY(!offline.internet);
    QVERIFY(!offline.live);
    QVERIFY(!offline.offersReconnect);
    QVERIFY(offline.alarm);

    // Each state is a report of its own, so a change of state is told from a count that moved.
    const NetworkReport all[] = {direct, relay, connecting, offline};
    for (int one = 0; one < 4; ++one) {
        for (int other = 0; other < 4; ++other) {
            QCOMPARE(all[one] == all[other], one == other);
        }
    }
}

void TestAirNetwork::nodesAreTheRelaysAndTheContactsReachedDirectly()
{
    Air::Nodes nodes;
    QCOMPARE(nodes.total(), 0);
    nodes.relays = 2;
    nodes.directContacts = 5;
    QCOMPARE(nodes.total(), 7);

    Air::Nodes same;
    same.relays = 2;
    same.directContacts = 5;
    QVERIFY(nodes == same);
    QVERIFY(!(nodes != same));
    same.directContacts = 4;
    QVERIFY(nodes != same);
}

void TestAirNetwork::lineNamesTheStateWithoutACount()
{
    using Air::NetworkState;
    QCOMPARE(AirStrings::networkText(NetworkState::Direct), QStringLiteral("online, direct"));
    QCOMPARE(AirStrings::networkText(NetworkState::Relay), QStringLiteral("online, via a relay"));
    QCOMPARE(AirStrings::networkText(NetworkState::Connecting), QStringLiteral("connecting"));
    QCOMPARE(AirStrings::networkText(NetworkState::Offline),
             QStringLiteral("no network: messages wait"));

    // The mark of a relayed contact shares the line with the name and keeps short; a screen reader
    // hears the whole state.
    Air::Entry entry;
    entry.title = QStringLiteral("Марьям Хасанова");
    entry.reach.kind = Air::Reach::Kind::Relay;
    QCOMPARE(AirStrings::reachText(entry.reach), QStringLiteral("via a relay"));
    QVERIFY2(AirStrings::spokenRow(entry).startsWith(
                 QStringLiteral("Марьям Хасанова, online, via a relay")),
             qPrintable(AirStrings::spokenRow(entry)));
}

void TestAirNetwork::lineAndSettingsSpeakEveryLanguage()
{
    using Air::NetworkState;
    using Link = Air::NetworkReport::Link;
    Air::Reach relayed;
    relayed.kind = Air::Reach::Kind::Relay;

    QCOMPARE(AirStrings::networkTitle(NetworkState::Connecting), QStringLiteral("Connecting"));
    QCOMPARE(AirStrings::networkTitle(NetworkState::Offline), QStringLiteral("No network"));
    QCOMPARE(AirStrings::networkMeaning(NetworkState::Relay),
             QStringLiteral("Messages go at once through a relay: this network closes the direct "
                            "path, and delivery does not suffer."));
    QCOMPARE(AirStrings::networkLinkText(Link::None), QStringLiteral("Not yet"));

    // "Through a relay", as on the phones, with no count of nodes in the line (decided 12.11.2026).
    use(QStringLiteral("ru"));
    QCOMPARE(AirStrings::networkText(NetworkState::Direct), QStringLiteral("в сети, напрямую"));
    QCOMPARE(AirStrings::networkText(NetworkState::Relay),
             QStringLiteral("в сети, через ретранслятор"));
    QCOMPARE(AirStrings::networkText(NetworkState::Offline),
             QStringLiteral("нет сети: сообщения ждут"));
    QCOMPARE(AirStrings::reachText(relayed), QStringLiteral("через ретранслятор"));
    QCOMPARE(AirStrings::text(AirStrings::Text::NetworkNodes), QStringLiteral("Узлы"));
    QCOMPARE(AirStrings::networkTitle(NetworkState::Relay),
             QStringLiteral("В сети, через ретранслятор"));
    QCOMPARE(AirStrings::networkLinkText(Link::Direct), QStringLiteral("Напрямую"));
    QCOMPARE(AirStrings::text(AirStrings::Text::NetworkInternetNo), QStringLiteral("Нет"));

    use(QStringLiteral("ar"));
    QCOMPARE(AirStrings::networkText(NetworkState::Direct), QStringLiteral("متصل، مباشرةً"));
    QCOMPARE(AirStrings::networkText(NetworkState::Relay), QStringLiteral("متصل، عبر خادم وسيط"));
    QCOMPARE(AirStrings::reachText(relayed), QStringLiteral("عبر خادم وسيط"));
    QCOMPARE(AirStrings::networkTitle(NetworkState::Offline), QStringLiteral("لا شبكة"));

    use(QStringLiteral("zh_CN"));
    QCOMPARE(AirStrings::networkText(NetworkState::Direct), QStringLiteral("在线，直连"));
    QCOMPARE(AirStrings::networkText(NetworkState::Relay), QStringLiteral("在线，经中继服务器"));
    QCOMPARE(AirStrings::reachText(relayed), QStringLiteral("经中继"));
    QCOMPARE(AirStrings::networkLinkText(Link::Relay), QStringLiteral("经中继服务器"));
}

QTEST_GUILESS_MAIN(TestAirNetwork)
#include "airnetwork_test.moc"
