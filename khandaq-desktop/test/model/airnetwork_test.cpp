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
    void noBandForAnOutageUnderThreeSeconds();
    void bandGoesFromNoNetworkThroughConnectingAndBackOnline();
    void bandGoesAfterThirtySecondsOfConnecting();
    void bandSpeaksEveryLanguage();

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

/**
 * KHANDAQ (0.2.57): a quick change of network, or one lost for less than 3 s, shows nothing; the
 * node going offline with a network at hand is the line's to say.
 */
void TestAirNetwork::noBandForAnOutageUnderThreeSeconds()
{
    using Phase = Air::NoNetworkNotice::Phase;
    Air::NoNetworkNotice notice;
    QVERIFY(!notice.update(true, true, 0));
    QCOMPARE(notice.msToNextStep(0), qint64{-1});

    QVERIFY(!notice.update(false, true, 1000));
    QCOMPARE(notice.msToNextStep(1000), qint64{3000});
    QVERIFY(!notice.update(false, false, 3999));
    QCOMPARE(notice.msToNextStep(3999), qint64{1});
    QVERIFY(!notice.update(true, false, 3999));
    QCOMPARE(notice.phase(), Phase::Hidden);
    QCOMPARE(notice.msToNextStep(3999), qint64{-1});

    // Lost again: the 3 s count from the new loss, not the old one.
    QVERIFY(!notice.update(false, false, 5000));
    QVERIFY(!notice.update(false, false, 7999));
    QCOMPARE(notice.phase(), Phase::Hidden);

    // Connecting with a network at hand: no band, however long it lasts.
    Air::NoNetworkNotice connecting;
    QVERIFY(!connecting.update(true, false, 0));
    QVERIFY(!connecting.update(true, false, 60 * 1000));
    QCOMPARE(connecting.phase(), Phase::Hidden);
}

/**
 * KHANDAQ (0.2.57): no network for 3 s, then the network back: Connecting until our node is online,
 * Back online for 1.5 s, then the band goes. A network back with the node still online says Back
 * online at once.
 */
void TestAirNetwork::bandGoesFromNoNetworkThroughConnectingAndBackOnline()
{
    using Phase = Air::NoNetworkNotice::Phase;
    Air::NoNetworkNotice notice;
    QVERIFY(!notice.update(false, true, 10000));
    QVERIFY(notice.update(false, false, 13000));
    QCOMPARE(notice.phase(), Phase::NoNetwork);
    // It stays while there is no network, with nothing to wait for.
    QVERIFY(!notice.update(false, false, 300000));
    QCOMPARE(notice.msToNextStep(300000), qint64{-1});

    QVERIFY(notice.update(true, false, 300500));
    QCOMPARE(notice.phase(), Phase::Connecting);
    QCOMPARE(notice.msToNextStep(300500), qint64{30000});
    QVERIFY(!notice.update(true, false, 310000));

    QVERIFY(notice.update(true, true, 312000));
    QCOMPARE(notice.phase(), Phase::BackOnline);
    QCOMPARE(notice.msToNextStep(312000), qint64{1500});
    QVERIFY(!notice.update(true, true, 313499));
    QVERIFY(notice.update(true, true, 313500));
    QCOMPARE(notice.phase(), Phase::Hidden);
    QCOMPARE(notice.msToNextStep(313500), qint64{-1});

    // Back while the node never noticed the network go: Back online, then gone.
    Air::NoNetworkNotice quick;
    QVERIFY(!quick.update(false, true, 0));
    QVERIFY(quick.update(false, true, 3000));
    QVERIFY(quick.update(true, true, 4000));
    QCOMPARE(quick.phase(), Phase::BackOnline);
    QVERIFY(quick.update(true, true, 5500));
    QCOMPARE(quick.phase(), Phase::Hidden);

    // The network goes again while connecting: No network once it has been gone for 3 s.
    Air::NoNetworkNotice again;
    QVERIFY(!again.update(false, false, 0));
    QVERIFY(again.update(false, false, 3000));
    QVERIFY(again.update(true, false, 4000));
    QCOMPARE(again.phase(), Phase::Connecting);
    QVERIFY(!again.update(false, false, 5000));
    QCOMPARE(again.msToNextStep(5000), qint64{3000});
    QVERIFY(!again.update(false, false, 7999));
    QCOMPARE(again.phase(), Phase::Connecting);
    QVERIFY(again.update(false, false, 8000));
    QCOMPARE(again.phase(), Phase::NoNetwork);
}

/**
 * KHANDAQ (0.2.57): still connecting 30 s after the network came back, the band goes anyway; the
 * line under the name keeps saying connecting.
 */
void TestAirNetwork::bandGoesAfterThirtySecondsOfConnecting()
{
    using Phase = Air::NoNetworkNotice::Phase;
    Air::NoNetworkNotice notice;
    QVERIFY(!notice.update(false, false, 0));
    QVERIFY(notice.update(false, false, 3000));
    QVERIFY(notice.update(true, false, 20000));
    QCOMPARE(notice.phase(), Phase::Connecting);
    QVERIFY(!notice.update(true, false, 49999));
    QCOMPARE(notice.msToNextStep(49999), qint64{1});
    QVERIFY(notice.update(true, false, 50000));
    QCOMPARE(notice.phase(), Phase::Hidden);
    // Online later: nothing comes back.
    QVERIFY(!notice.update(true, true, 70000));
    QCOMPARE(notice.phase(), Phase::Hidden);
}

void TestAirNetwork::bandSpeaksEveryLanguage()
{
    using AirStrings::Text;
    QCOMPARE(AirStrings::text(Text::NoNetworkTitle), QStringLiteral("No network"));
    QCOMPARE(AirStrings::text(Text::NoNetworkNote),
             QStringLiteral("Messages and files will wait and go on their own once you're back "
                            "online."));
    QCOMPARE(AirStrings::text(Text::NoNetworkConnecting), QStringLiteral("Connecting"));
    QCOMPARE(AirStrings::text(Text::NoNetworkBack), QStringLiteral("Back online"));

    use(QStringLiteral("ru"));
    QCOMPARE(AirStrings::text(Text::NoNetworkTitle), QStringLiteral("Нет сети"));
    QCOMPARE(AirStrings::text(Text::NoNetworkNote),
             QStringLiteral("Сообщения и файлы подождут и уйдут сами, когда связь вернётся."));
    QCOMPARE(AirStrings::text(Text::NoNetworkConnecting), QStringLiteral("Подключаемся"));
    QCOMPARE(AirStrings::text(Text::NoNetworkBack), QStringLiteral("Снова в сети"));

    use(QStringLiteral("ar"));
    QCOMPARE(AirStrings::text(Text::NoNetworkTitle), QStringLiteral("لا شبكة"));
    QCOMPARE(AirStrings::text(Text::NoNetworkBack), QStringLiteral("عاد الاتصال"));

    use(QStringLiteral("zh_CN"));
    QCOMPARE(AirStrings::text(Text::NoNetworkTitle), QStringLiteral("无网络"));
    QCOMPARE(AirStrings::text(Text::NoNetworkConnecting), QStringLiteral("连接中"));
}

QTEST_GUILESS_MAIN(TestAirNetwork)
#include "airnetwork_test.moc"
