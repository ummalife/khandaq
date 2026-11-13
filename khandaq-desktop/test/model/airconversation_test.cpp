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

#include "src/model/air/airconversation.h"
#include "src/model/air/airlocalstore.h"
#include "src/widget/air/airstrings.h"

#include <QRegularExpression>
#include <QTimeZone>
#include <QTranslator>
#include <QtTest/QtTest>

#include <memory>

/**
 * The conversation's rules as plain values (redesign stage 2), with the vectors of
 * khandaq-ios/AntidoteTests/AirConversationModelTests.swift and the Android
 * AirConversationModelTest.java: the status of our messages, the runs of the transcript and the
 * day marks, the presence line, the hint and the length counter under the field, the key in
 * groups, the day strip and the best hours, and the store of hourly marks.
 */
class TestAirConversation : public QObject
{
    Q_OBJECT

private slots:
    void initTestCase();
    void init();
    void cleanup();

    void statusFollowsTheMessageAndThePerson();
    void everyStatusHasItsIconAndWords();
    void aRunGoesOnForTheSameAuthorWithinFiveMinutesOfTheSameDay();
    void shortNameIsTheFirstWord();
    void dayMarksSayTodayAndYesterdayThenTheDate();
    void linkFollowsTheReach();
    void presenceLineNamesTheLinkAndWhenThePersonCameOnline();
    void awayTextCountsInWordsThatAgreeWithTheNumber();
    void hintSaysWhatHappensToAMessageSentNow();
    void counterAppearsInTheLastTenthOfTheRoom();
    void trayHeadCountsTheMessages();
    void trayKeepsTheOrderTheMessagesWaited();
    void keyIsReadInGroupsOfFour();
    void dayStripCoversTheLastTwentyFourHours();
    void bestHoursNeedTwoDaysAndPickTheLongestRunSeenMostOften();
    void dayHintTriesTheBestHoursThenTheRuns();
    void localStoreFilesContactsUnderAHashAndKeepsMarksAcrossLaunches();
    void localStoreDropsHoursOlderThanAWeek();
    void blockListTakesKeysOnlyAndKeepsThemAcrossLaunches();
    void requestsFromABlockedKeyAreDroppedAndOthersStay();
    void removingAContactKeepsItsKeyBlocked();
    void waitingRequestsKeepTheirWordsAndTimeAcrossLaunches();
    void invitationsWaitUntilAnsweredAndGoWithTheirContact();
    void entryMarksKeepTheNewestAndGoWithTheirConversation();
    void groupIdsKeepTheNewestAndGoWithTheirConversation();
    void everyPluralTextAgreesWithItsNumberInEveryLanguage();
    void sizesCountInThousandsAsThePhonesDo();
    void aSizeThatRoundsUpTakesTheNextUnit();
    void sizesSpeakTheInterfaceLanguage();

private:
    void use(const QString& language);
    QDateTime date(int year, int month, int day, int hour, int minute) const;
    qint64 hourStart(int year, int month, int day, int hour) const;

    QTimeZone zone{QByteArrayLiteral("Europe/Istanbul")};
    std::unique_ptr<QTranslator> translator;
};

void TestAirConversation::initTestCase()
{
    Q_INIT_RESOURCE(translations);
    QVERIFY(zone.isValid());
}

void TestAirConversation::init()
{
    use(QStringLiteral("en"));
}

void TestAirConversation::cleanup()
{
    if (translator) {
        QCoreApplication::removeTranslator(translator.get());
        translator.reset();
    }
}

void TestAirConversation::use(const QString& language)
{
    cleanup();
    translator.reset(new QTranslator);
    QVERIFY2(translator->load(language, QStringLiteral(":translations/")), qPrintable(language));
    QCoreApplication::installTranslator(translator.get());
}

QDateTime TestAirConversation::date(int year, int month, int day, int hour, int minute) const
{
    return QDateTime(QDate(year, month, day), QTime(hour, minute), zone);
}

qint64 TestAirConversation::hourStart(int year, int month, int day, int hour) const
{
    return date(year, month, day, hour, 0).toSecsSinceEpoch();
}

void TestAirConversation::statusFollowsTheMessageAndThePerson()
{
    using Air::EntryStatus;
    QCOMPARE(Air::outgoingTextStatus(false, false, false, false), EntryStatus::Transit);
    QCOMPARE(Air::outgoingTextStatus(false, false, false, true), EntryStatus::Sending);
    QCOMPARE(Air::outgoingTextStatus(false, false, true, true), EntryStatus::Sent);
    QCOMPARE(Air::outgoingTextStatus(false, true, true, false), EntryStatus::Delivered);
    // Notes for yourself go nowhere and carry no mark.
    QCOMPARE(Air::outgoingTextStatus(true, true, false, false), EntryStatus::None);

    // A message the delivery gave up on is not sent, whether the person is online or not.
    QCOMPARE(Air::directTextStatus(false, false, true, true, true), EntryStatus::Failed);
    QCOMPARE(Air::directTextStatus(false, false, true, true, false), EntryStatus::Failed);
    QCOMPARE(Air::directTextStatus(false, true, false, true, false), EntryStatus::Delivered);
    QCOMPARE(Air::directTextStatus(false, false, false, true, false), EntryStatus::Transit);
    QCOMPARE(Air::directTextStatus(true, false, true, true, true), EntryStatus::None);

    // The list row says the same as the conversation.
    QCOMPARE(Air::rowDelivery(EntryStatus::Transit), Air::Delivery::Transit);
    QCOMPARE(Air::rowDelivery(EntryStatus::Sending), Air::Delivery::Transit);
    QCOMPARE(Air::rowDelivery(EntryStatus::Sent), Air::Delivery::Sent);
    QCOMPARE(Air::rowDelivery(EntryStatus::Delivered), Air::Delivery::Delivered);
    QCOMPARE(Air::rowDelivery(EntryStatus::Failed), Air::Delivery::Failed);
    QCOMPARE(Air::rowDelivery(EntryStatus::None), Air::Delivery::None);
}

void TestAirConversation::everyStatusHasItsIconAndWords()
{
    using Air::EntryStatus;
    QCOMPARE(Air::statusIconName(EntryStatus::Transit), QStringLiteral("clock"));
    QCOMPARE(Air::statusIconName(EntryStatus::Sending), QStringLiteral("clock"));
    QCOMPARE(Air::statusIconName(EntryStatus::Sent), QStringLiteral("check"));
    QCOMPARE(Air::statusIconName(EntryStatus::Delivered), QStringLiteral("check-double"));
    QCOMPARE(Air::statusIconName(EntryStatus::Failed), QStringLiteral("alert"));
    QVERIFY(Air::statusIconName(EntryStatus::None).isEmpty());
    QCOMPARE(AirStrings::statusText(EntryStatus::Failed), QStringLiteral("not sent"));
    QCOMPARE(AirStrings::statusText(EntryStatus::Transit), QStringLiteral("in transit"));
    QVERIFY(AirStrings::statusText(EntryStatus::None).isEmpty());
}

void TestAirConversation::aRunGoesOnForTheSameAuthorWithinFiveMinutesOfTheSameDay()
{
    const QDateTime start = date(2026, 11, 11, 12, 0);
    const Air::RunKey first{QStringLiteral("peer"), start, true};
    const Air::RunKey soon{QStringLiteral("peer"), start.addSecs(4 * 60), true};
    const Air::RunKey late{QStringLiteral("peer"), start.addSecs(6 * 60), true};
    const Air::RunKey other{QString(), start.addSecs(60), true};
    const Air::RunKey file{QStringLiteral("peer"), start.addSecs(60), false};

    QVERIFY(Air::startsRun(first, nullptr));
    QVERIFY(!Air::startsRun(soon, &first));
    QVERIFY(Air::startsRun(late, &first));
    QVERIFY(Air::startsRun(other, &first));
    QVERIFY(Air::startsRun(soon, &file));

    const Air::RunKey beforeMidnight{QStringLiteral("peer"), date(2026, 11, 10, 23, 58), true};
    const Air::RunKey afterMidnight{QStringLiteral("peer"), date(2026, 11, 11, 0, 1), true};
    QVERIFY(Air::startsRun(afterMidnight, &beforeMidnight));
}

void TestAirConversation::shortNameIsTheFirstWord()
{
    QCOMPARE(Air::shortName(QStringLiteral("  Yusuf Karimov ")), QStringLiteral("Yusuf"));
    QCOMPARE(Air::shortName(QStringLiteral("Amina")), QStringLiteral("Amina"));
    QCOMPARE(Air::shortName(QString()), QString());
}

void TestAirConversation::dayMarksSayTodayAndYesterdayThenTheDate()
{
    const QDate today(2026, 11, 11);
    const QLocale english(QStringLiteral("en_US"));
    QCOMPARE(AirStrings::dayMarkText(QDate(2026, 11, 11), today, english), QStringLiteral("Today"));
    QCOMPARE(AirStrings::dayMarkText(QDate(2026, 11, 10), today, english),
             QStringLiteral("Yesterday"));
    const QString older = AirStrings::dayMarkText(QDate(2026, 11, 2), today, english);
    QVERIFY2(older.contains(QLatin1Char('2')), qPrintable(older));
    QVERIFY2(!older.contains(QStringLiteral("2026")), qPrintable(older));
    QVERIFY(AirStrings::dayMarkText(QDate(2025, 3, 2), today, english).contains(QStringLiteral("2025")));

    use(QStringLiteral("ru"));
    QCOMPARE(AirStrings::dayMarkText(QDate(2026, 11, 11), today, QLocale(QStringLiteral("ru"))),
             QStringLiteral("Сегодня"));
    QCOMPARE(AirStrings::dayMarkText(QDate(2026, 11, 2), today, QLocale(QStringLiteral("ru"))),
             QStringLiteral("2 ноября"));

    // Arabic dates keep the Latin digits of the rest of the interface.
    use(QStringLiteral("ar"));
    const QString arabic =
        AirStrings::dayMarkText(QDate(2026, 11, 2), today, QLocale(QStringLiteral("ar")));
    QVERIFY2(arabic.contains(QLatin1Char('2')), qPrintable(arabic));
}

void TestAirConversation::linkFollowsTheReach()
{
    QCOMPARE(Air::linkState(Air::Reach::direct()), Air::LinkState::Direct);
    QCOMPARE(Air::linkState(Air::Reach::relay()), Air::LinkState::Relay);
    QCOMPARE(Air::linkState(Air::Reach::away(60)), Air::LinkState::None);
    QCOMPARE(Air::linkState(Air::Reach::pending()), Air::LinkState::None);
    QCOMPARE(AirStrings::linkText(Air::LinkState::Relay), QStringLiteral("link via a relay"));
}

void TestAirConversation::presenceLineNamesTheLinkAndWhenThePersonCameOnline()
{
    const QString since = QStringLiteral("9:40");
    QCOMPARE(AirStrings::presenceText(Air::Reach::direct(), since, false),
             QStringLiteral("online since 9:40, direct link"));
    QCOMPARE(AirStrings::presenceText(Air::Reach::relay(), QString(), false),
             QStringLiteral("online, via a relay"));
    QCOMPARE(AirStrings::presenceText(Air::Reach::direct(), since, true), QStringLiteral("typing"));
    QCOMPARE(AirStrings::presenceText(Air::Reach::pending(), QString(), false),
             QStringLiteral("waiting for a reply to the request"));
    // Typing shows only while the person is online.
    QCOMPARE(AirStrings::presenceText(Air::Reach::away(25 * 60), QString(), true),
             QStringLiteral("offline for 25 minutes"));

    use(QStringLiteral("ru"));
    QCOMPARE(AirStrings::presenceText(Air::Reach::direct(), since, false),
             QStringLiteral("в сети с 9:40, связь напрямую"));
}

void TestAirConversation::awayTextCountsInWordsThatAgreeWithTheNumber()
{
    QCOMPARE(AirStrings::awayText(30), QStringLiteral("offline for under a minute"));
    QCOMPARE(AirStrings::awayText(60), QStringLiteral("offline for 1 minute"));
    QCOMPARE(AirStrings::awayText(25 * 60), QStringLiteral("offline for 25 minutes"));
    QCOMPARE(AirStrings::awayText(3 * 3600), QStringLiteral("offline for 3 hours"));
    QCOMPARE(AirStrings::awayText(2 * 86400), QStringLiteral("offline for 2 days"));
    QCOMPARE(AirStrings::awayText(9 * 86400), QStringLiteral("offline for over a week"));

    use(QStringLiteral("ru"));
    QCOMPARE(AirStrings::awayText(21 * 60), QStringLiteral("не в сети 21 минуту"));
    QCOMPARE(AirStrings::awayText(25 * 60), QStringLiteral("не в сети 25 минут"));
    QCOMPARE(AirStrings::awayText(2 * 3600), QStringLiteral("не в сети 2 часа"));
    QCOMPARE(AirStrings::awayText(5 * 86400), QStringLiteral("не в сети 5 дней"));

    use(QStringLiteral("ar"));
    QCOMPARE(AirStrings::awayText(2 * 60), QStringLiteral("غير متصل منذ 2 دقيقتين"));
}

void TestAirConversation::hintSaysWhatHappensToAMessageSentNow()
{
    using Hint = Air::ComposeHint;
    const Hint live = Hint::make(false, true, Air::Reach::direct());
    QCOMPARE(AirStrings::composeHintText(live, QStringLiteral("Amina")),
             QStringLiteral("Goes right away: Amina is online"));
    QCOMPARE(live.dot, Hint::Dot::Live);

    const Hint waiting = Hint::make(false, true, Air::Reach::away(1500));
    QCOMPARE(AirStrings::composeHintText(waiting, QStringLiteral("Yusuf")),
             QStringLiteral("Waits in transit: Yusuf is offline. It will go on its own"));
    QCOMPARE(waiting.dot, Hint::Dot::Transit);

    const Hint saved = Hint::make(true, false, Air::Reach::local());
    QCOMPARE(AirStrings::composeHintText(saved, QString()),
             QStringLiteral("The note stays only on this device"));
    QCOMPARE(saved.dot, Hint::Dot::None);

    QVERIFY(
        AirStrings::composeHintText(Hint::make(false, false, Air::Reach::local()), QString()).isEmpty());

    // While our own node is not linked, the line speaks for it whoever the other side is; nothing
    // is refused, the message waits in transit (DESIGN.md, "Network state on the phone").
    const Hint connecting =
        Hint::make(false, true, Air::Reach::direct(), Air::NetworkState::Connecting);
    QCOMPARE(connecting.kind, Hint::Kind::SelfConnecting);
    QCOMPARE(connecting.dot, Hint::Dot::Transit);
    QCOMPARE(AirStrings::composeHintText(connecting, QStringLiteral("Amina")),
             QStringLiteral("Waits in transit: the app is connecting. It will go on its own"));
    const Hint noNetwork = Hint::make(false, true, Air::Reach::away(1500), Air::NetworkState::Offline);
    QCOMPARE(noNetwork.kind, Hint::Kind::SelfOffline);
    QCOMPARE(noNetwork.dot, Hint::Dot::Transit);
    QCOMPARE(AirStrings::composeHintText(noNetwork, QStringLiteral("Yusuf")),
             QStringLiteral(
                 "Waits in transit: this computer has no network. It will go on its own"));
    // Online, over a relay as well, the person's state speaks again.
    QCOMPARE(Hint::make(false, true, Air::Reach::direct(), Air::NetworkState::Relay).kind,
             Hint::Kind::Live);
    QCOMPARE(Hint::make(false, true, Air::Reach::away(1500), Air::NetworkState::Direct).kind,
             Hint::Kind::Wait);
    // Notes stay on this device and a removed contact has no line, whatever the node does.
    QCOMPARE(Hint::make(true, false, Air::Reach::local(), Air::NetworkState::Offline).kind,
             Hint::Kind::Saved);
    QVERIFY(AirStrings::composeHintText(Hint::make(false, false, Air::Reach::local(),
                                                   Air::NetworkState::Connecting),
                                        QString())
                .isEmpty());

    use(QStringLiteral("ru"));
    QCOMPARE(AirStrings::composeHintText(Hint::make(false, true, Air::Reach::relay()),
                                         QStringLiteral("Амина")),
             QStringLiteral("Уйдёт сразу: Амина в сети"));
    QCOMPARE(AirStrings::composeHintText(Hint::make(false, true, Air::Reach::pending()),
                                         QStringLiteral("Юсуф")),
             QStringLiteral("Подождёт в пути: Юсуф не в сети. Уйдёт само"));
    QCOMPARE(AirStrings::composeHintText(saved, QString()),
             QStringLiteral("Заметка останется только на этом устройстве"));
    QCOMPARE(AirStrings::composeHintText(connecting, QStringLiteral("Амина")),
             QStringLiteral("Подождёт в пути: приложение подключается. Уйдёт само"));
    QCOMPARE(AirStrings::composeHintText(noNetwork, QStringLiteral("Юсуф")),
             QStringLiteral("Подождёт в пути: у компьютера нет сети. Уйдёт само"));
}

void TestAirConversation::counterAppearsInTheLastTenthOfTheRoom()
{
    QCOMPARE(Air::composeLimitBytes, 1334);
    QVERIFY(!Air::composeRoom(0).shown);
    QVERIFY(!Air::composeRoom(1200).shown);
    QCOMPARE(Air::composeRoom(1201).left, 133);
    QVERIFY(Air::composeRoom(1201).shown);
    QCOMPARE(Air::composeRoom(1334).left, 0);
    QVERIFY(Air::composeRoom(1334).shown);
    QCOMPARE(Air::composeRoom(1340).left, -6);
    // The desktop counts against its own core limit, by the same rule.
    QVERIFY(!Air::composeRoom(1189, 1322).shown);
    QCOMPARE(Air::composeRoom(1190, 1322).left, 132);
    QVERIFY(Air::composeRoom(1190, 1322).shown);

    QCOMPARE(AirStrings::composeCounterText(1), QStringLiteral("1 byte left"));
    QCOMPARE(AirStrings::composeCounterText(120), QStringLiteral("120 bytes left"));
    QCOMPARE(AirStrings::composeCounterText(-6),
             QStringLiteral("6 bytes over one message: it goes in parts"));

    use(QStringLiteral("ru"));
    QCOMPARE(AirStrings::composeCounterText(1), QStringLiteral("остался 1 байт"));
    QCOMPARE(AirStrings::composeCounterText(3), QStringLiteral("осталось 3 байта"));
    QCOMPARE(AirStrings::composeCounterText(120), QStringLiteral("осталось 120 байт"));
    QCOMPARE(AirStrings::composeCounterText(-6),
             QStringLiteral("на 6 байт больше одного сообщения: уйдёт частями"));
}

void TestAirConversation::trayHeadCountsTheMessages()
{
    QCOMPARE(AirStrings::trayHeadText(1), QStringLiteral("In transit: 1 message"));
    QCOMPARE(AirStrings::trayHeadText(3), QStringLiteral("In transit: 3 messages"));
    use(QStringLiteral("ru"));
    QCOMPARE(AirStrings::trayHeadText(3), QStringLiteral("В пути: 3 сообщения"));
    QCOMPARE(AirStrings::trayHeadText(5), QStringLiteral("В пути: 5 сообщений"));
    QCOMPARE(AirStrings::text(AirStrings::Text::TransitFlag).arg(3), QStringLiteral("3 в пути"));
    use(QStringLiteral("ar"));
    QCOMPARE(AirStrings::trayHeadText(2), QStringLiteral("في الطريق: 2 رسالتان"));
    QCOMPARE(AirStrings::trayHeadText(3), QStringLiteral("في الطريق: 3 رسائل"));
}

void TestAirConversation::trayKeepsTheOrderTheMessagesWaited()
{
    QCOMPARE(Air::leftTray({4, 7, 9}, {7}), (QVector<qint64>{4, 9}));
    QCOMPARE(Air::leftTray({4, 7}, {}), (QVector<qint64>{4, 7}));
    QVERIFY(Air::leftTray({}, {1}).isEmpty());
    QCOMPARE(Air::trayDetail(true, true), Air::TrayDetail::Live);
    QCOMPARE(Air::trayDetail(false, true), Air::TrayDetail::Push);
    QCOMPARE(Air::trayDetail(false, false), Air::TrayDetail::Plain);
}

void TestAirConversation::keyIsReadInGroupsOfFour()
{
    const QString key =
        QStringLiteral("7f3a91c05d2e8b4416a9c3e07d5f2b8164e0a3c9d7b25f1e8a4c6d0b3f9e2a71");
    const QStringList groups = Air::keyGroups(key);
    QCOMPARE(groups.size(), 16);
    QCOMPARE(groups.first(), QStringLiteral("7F3A"));
    QCOMPARE(groups.last(), QStringLiteral("2A71"));
    const QStringList lines = Air::keyLines(key).split(QLatin1Char('\n'));
    QCOMPARE(lines.size(), 4);
    QCOMPARE(lines.at(0), QStringLiteral("7F3A 91C0 5D2E 8B44"));
    QVERIFY(Air::keyGroups(QString()).isEmpty());
}

void TestAirConversation::dayStripCoversTheLastTwentyFourHours()
{
    const QDateTime now = date(2026, 11, 11, 10, 30);
    // Yesterday 22:00 is still within the last day; yesterday 8:00 is not (today's 8:00 took its
    // cell).
    const QSet<qint64> hours{hourStart(2026, 11, 11, 9), hourStart(2026, 11, 11, 10),
                             hourStart(2026, 11, 10, 22), hourStart(2026, 11, 10, 8)};
    const Air::DayStrip strip = Air::DayStrip::make(hours, now);
    QCOMPARE(strip.nowHour, 10);
    QCOMPARE(strip.online.size(), 24);
    QVERIFY(strip.online.at(9));
    QVERIFY(strip.online.at(10));
    QVERIFY(strip.online.at(22));
    QVERIFY(!strip.online.at(8));
    QVERIFY(strip.hasMarks());
    QCOMPARE(strip.ranges(), (QVector<QPair<int, int>>{{9, 11}, {22, 23}}));

    const Air::DayStrip empty = Air::DayStrip::make({}, now);
    QVERIFY(!empty.hasMarks());
    const QLocale english(QStringLiteral("en_US"));
    QCOMPARE(AirStrings::dayStripSpokenText(empty, english, false), QStringLiteral("no marks"));
    QCOMPARE(AirStrings::dayStripSpokenText(strip, english, true),
             QStringLiteral("online 9:00 to 11:00 and 22:00 to 23:00. Online now"));

    QCOMPARE(Air::hourStart(date(2026, 11, 11, 10, 30)), hourStart(2026, 11, 11, 10));
}

void TestAirConversation::bestHoursNeedTwoDaysAndPickTheLongestRunSeenMostOften()
{
    const QDateTime now = date(2026, 11, 11, 12, 0);
    int from = -1;
    int to = -1;
    QVERIFY(!Air::bestHours({hourStart(2026, 11, 11, 9)}, now, &from, &to));

    QSet<qint64> hours;
    for (int day = 6; day <= 10; ++day) {
        for (int hour = 19; hour <= 21; ++hour) {
            hours.insert(hourStart(2026, 11, day, hour));
        }
    }
    hours.insert(hourStart(2026, 11, 10, 7));
    QVERIFY(Air::bestHours(hours, now, &from, &to));
    QCOMPARE(from, 19);
    QCOMPARE(to, 22);

    // Marks older than a week do not count.
    QVERIFY(!Air::bestHours({hourStart(2026, 10, 1, 19), hourStart(2026, 10, 2, 19)}, now, &from, &to));

    // Online around the clock: no "best time".
    QSet<qint64> allDay;
    for (int day = 9; day <= 10; ++day) {
        for (int hour = 0; hour < 24; ++hour) {
            allDay.insert(hourStart(2026, 11, day, hour));
        }
    }
    QVERIFY(!Air::bestHours(allDay, now, &from, &to));
}

void TestAirConversation::dayHintTriesTheBestHoursThenTheRuns()
{
    const QDateTime now = date(2026, 11, 11, 10, 30);
    const Air::DayStrip strip =
        Air::DayStrip::make({hourStart(2026, 11, 11, 7), hourStart(2026, 11, 11, 8),
                             hourStart(2026, 11, 10, 21)},
                            now);
    QCOMPARE(Air::dayHint(true, strip), Air::DayHint::Best);
    QCOMPARE(Air::dayHint(false, strip), Air::DayHint::Ranges);
    QCOMPARE(Air::dayHint(false, Air::DayStrip::make({}, now)), Air::DayHint::Empty);

    QCOMPARE(AirStrings::dayHintText(strip, true, 19, 22),
             QStringLiteral("usually 19:00 to 22:00"));
    QCOMPARE(AirStrings::dayHintText(strip, false, 0, 0),
             QStringLiteral("past day: 7:00 to 9:00, 21:00 to 22:00"));
    QCOMPARE(AirStrings::dayHintText(Air::DayStrip::make({}, now), false, 0, 0),
             QStringLiteral("no marks yet"));
    use(QStringLiteral("ru"));
    QCOMPARE(AirStrings::dayHintText(strip, true, 19, 22), QStringLiteral("обычно с 19 до 22"));
}

void TestAirConversation::localStoreFilesContactsUnderAHashAndKeepsMarksAcrossLaunches()
{
    const QString key =
        QStringLiteral("7F3A91C05D2E8B4416A9C3E07D5F2B8164E0A3C9D7B25F1E8A4C6D0B3F9E2A71");
    const QString hashed = Air::LocalStore::hashedKey(key);
    QCOMPARE(hashed.size(), 32);
    QCOMPARE(hashed, Air::LocalStore::hashedKey(key.toLower()));
    QVERIFY(!hashed.toUpper().contains(key.left(16)));

    const QDateTime now = QDateTime::currentDateTime();
    Air::LocalStore store;
    store.load(QByteArray(), now);
    QVERIFY(store.markOnline(key, now));
    QVERIFY(!store.markOnline(key, now.addSecs(1)) || now.time().minute() == 59);
    QVERIFY(store.setMuted(true, key));
    QVERIFY(!store.setMuted(true, key));
    QVERIFY(store.setVerified(now, key));
    QVERIFY(store.noteConnected(key, now));
    QVERIFY(!store.noteConnected(key, now.addSecs(60)));
    QCOMPARE(store.onlineSince(key), now);
    QVERIFY(!store.hours(key).isEmpty());

    const QByteArray saved = store.save();
    QVERIFY2(!saved.contains(key.toUtf8()), "the stored form never names a contact");
    QVERIFY(!saved.contains(key.toLower().toUtf8()));

    Air::LocalStore again;
    again.load(saved, now);
    QCOMPARE(again.hours(key), store.hours(key));
    QVERIFY(again.isMuted(key));
    QCOMPARE(again.verifiedAt(key).toMSecsSinceEpoch(), now.toMSecsSinceEpoch());
    // When someone came online is memory only.
    QVERIFY(!again.onlineSince(key).isValid());

    QVERIFY(again.forget(key));
    QVERIFY(again.hours(key).isEmpty());
    QVERIFY(!again.isMuted(key));
    QVERIFY(!again.verifiedAt(key).isValid());

    QVERIFY(store.noteDisconnected(key));
    QVERIFY(!store.onlineSince(key).isValid());
    QVERIFY(store.setVerified(QDateTime(), key));
    QVERIFY(!store.verifiedAt(key).isValid());

    // A stored form this version does not know is ignored, not misread.
    Air::LocalStore unknown;
    unknown.load(QByteArrayLiteral("{\"version\":7,\"contacts\":{}}"), now);
    QVERIFY(unknown.hours(key).isEmpty());
    unknown.load(QByteArrayLiteral("not json"), now);
    QVERIFY(!unknown.isMuted(key));
}

void TestAirConversation::localStoreDropsHoursOlderThanAWeek()
{
    const QString key = QStringLiteral("AB12");
    const QDateTime now = date(2026, 11, 11, 12, 0);
    Air::LocalStore store;
    QVERIFY(store.markOnline(key, now.addDays(-8)));
    QVERIFY(store.markOnline(key, now.addDays(-2)));
    // A new mark drops the hours that fell out of the week.
    QVERIFY(store.markOnline(key, now));
    QCOMPARE(store.hours(key).size(), 2);

    Air::LocalStore old;
    QVERIFY(old.markOnline(key, now.addDays(-9)));
    QVERIFY(old.markOnline(key, now.addDays(-3)));
    QVERIFY(old.pruneHours(now));
    QCOMPARE(old.hours(key).size(), 1);
    QVERIFY(!old.pruneHours(now));

    // A contact left with nothing to remember is dropped from the stored form.
    Air::LocalStore lonely;
    QVERIFY(lonely.markOnline(key, now.addDays(-9)));
    lonely.load(lonely.save(), now);
    QVERIFY(lonely.hours(key).isEmpty());
    QVERIFY(!lonely.save().contains(Air::LocalStore::hashedKey(key).toLatin1()));
}

namespace {
const QString aminaKey =
    QStringLiteral("7F3A91C05D2E8B4416A9C3E07D5F2B8164E0A3C9D7B25F1E8A4C6D0B3F9E2A71");
const QString yusufKey =
    QStringLiteral("0B1C2D3E4F5061728394A5B6C7D8E9F00112233445566778899AABBCCDDEEFF0");
} // namespace

void TestAirConversation::blockListTakesKeysOnlyAndKeepsThemAcrossLaunches()
{
    const QDateTime now = QDateTime::currentDateTime();
    Air::LocalStore store;
    store.load(QByteArray(), now);
    QVERIFY(store.block(QStringLiteral("  ") + aminaKey.toLower() + QStringLiteral("\n")));
    QVERIFY(!store.block(aminaKey));
    // A whole ID (76 characters) or a short string is not a key.
    QVERIFY(!store.block(aminaKey + QStringLiteral("01234567ABCD")));
    QVERIFY(!store.block(QStringLiteral("ABCD")));
    QVERIFY(!store.block(QStringLiteral("G") + aminaKey.mid(1)));
    QVERIFY(store.block(yusufKey));
    QCOMPARE(store.blockedKeys(), QStringList({aminaKey, yusufKey}));
    QVERIFY(store.isBlocked(aminaKey.toLower()));

    QVERIFY(store.unblock(aminaKey.toLower()));
    QVERIFY(!store.unblock(aminaKey));
    Air::LocalStore again;
    again.load(store.save(), now);
    QCOMPARE(again.blockedKeys(), QStringList({yusufKey}));
    QVERIFY(!again.isBlocked(aminaKey));

    // The oldest stays first, and a stored list that was tampered with keeps only real keys.
    again.load(QByteArrayLiteral("{\"version\":1,\"contacts\":{},\"blocked\":[\"") + yusufKey.toLatin1()
                   + QByteArrayLiteral("\",\"ABCD\",\"") + aminaKey.toLower().toLatin1()
                   + QByteArrayLiteral("\",\"") + yusufKey.toLatin1() + QByteArrayLiteral("\"]}"),
               now);
    QCOMPARE(again.blockedKeys(), QStringList({yusufKey, aminaKey}));
}

void TestAirConversation::requestsFromABlockedKeyAreDroppedAndOthersStay()
{
    Air::LocalStore store;
    store.load(QByteArray(), QDateTime::currentDateTime());
    const QStringList waiting = {aminaKey, yusufKey.toLower(), aminaKey, yusufKey};
    QVERIFY(store.blockedPositions(waiting).isEmpty());

    QVERIFY(store.block(yusufKey));
    QCOMPARE(store.blockedPositions(waiting), QVector<int>({1, 3}));

    QVERIFY(store.unblock(yusufKey));
    QVERIFY(store.blockedPositions(waiting).isEmpty());
}

/** What Block does to the store: the key goes on the list, then the contact is removed the way
 * Remove removes it, which forgets the marks but not the block. */
void TestAirConversation::removingAContactKeepsItsKeyBlocked()
{
    const QDateTime now = QDateTime::currentDateTime();
    Air::LocalStore store;
    store.load(QByteArray(), now);
    QVERIFY(store.setMuted(true, yusufKey));
    QVERIFY(store.block(yusufKey));
    QVERIFY(store.forget(yusufKey));

    Air::LocalStore again;
    again.load(store.save(), now);
    QVERIFY(!again.isMuted(yusufKey));
    QVERIFY(again.isBlocked(yusufKey));
}

/** A request waits as it came until it is answered, one card per key; a blocked key stays out. */
void TestAirConversation::waitingRequestsKeepTheirWordsAndTimeAcrossLaunches()
{
    const QDateTime now = date(2026, 11, 11, 12, 0);
    Air::LocalStore store;
    store.load(QByteArray(), now);
    QVERIFY(store.addRequest(aminaKey.toLower(), QStringLiteral("Salam, it is Amina"), now));
    // The same words again change nothing; other words take the place of the old ones.
    QVERIFY(!store.addRequest(aminaKey, QStringLiteral("Salam, it is Amina"), now.addSecs(60)));
    QCOMPARE(store.requests().size(), 1);
    QCOMPARE(store.requests().first().date, now.toMSecsSinceEpoch());
    QVERIFY(store.addRequest(yusufKey, QString(), now.addSecs(30)));
    QVERIFY(store.addRequest(aminaKey, QStringLiteral("Salam again"), now.addSecs(90)));
    QCOMPARE(store.requests().size(), 2);
    QCOMPARE(store.requests().at(0).publicKey, yusufKey);
    QCOMPARE(store.requests().at(1).publicKey, aminaKey);
    QCOMPARE(store.requests().at(1).message, QStringLiteral("Salam again"));
    QCOMPARE(store.requests().at(1).date, now.addSecs(90).toMSecsSinceEpoch());
    QVERIFY(store.hasRequest(aminaKey.toLower()));
    QVERIFY(!store.addRequest(QStringLiteral("ABCD"), QString(), now));

    Air::LocalStore again;
    again.load(store.save(), now);
    QCOMPARE(again.requests(), store.requests());

    // Answered, the request goes. A blocked key takes its request along and cannot come back.
    QVERIFY(again.removeRequest(yusufKey.toLower()));
    QVERIFY(!again.removeRequest(yusufKey));
    QVERIFY(again.block(aminaKey));
    QVERIFY(again.requests().isEmpty());
    QVERIFY(!again.addRequest(aminaKey, QStringLiteral("Salam"), now));
}

/**
 * An invitation waits until Join or Decline, with the same id across launches; a second one from
 * the same person to a group of the same name replaces the first, and removing the person takes
 * their invitations along.
 */
void TestAirConversation::invitationsWaitUntilAnsweredAndGoWithTheirContact()
{
    const QDateTime now = date(2026, 11, 11, 12, 0);
    const QByteArray data = QByteArray::fromHex("0102") + QByteArrayLiteral("invitation");
    Air::LocalStore store;
    store.load(QByteArray(), now);
    const QString first = store.addInvite(yusufKey.toLower(), QStringLiteral("Family"), data, now);
    QVERIFY(!first.isEmpty());
    QCOMPARE(store.addInvite(yusufKey, QStringLiteral("Family"), data, now), first);
    const QString second =
        store.addInvite(yusufKey, QStringLiteral("Family"), QByteArrayLiteral("newer"), now);
    QVERIFY(second != first);
    QCOMPARE(store.invites().size(), 1);
    const QString other =
        store.addInvite(aminaKey, QStringLiteral("Study circle"), data, now.addSecs(120));
    QCOMPARE(store.invites().size(), 2);
    QVERIFY(store.addInvite(QStringLiteral("ABCD"), QStringLiteral("Family"), data, now).isEmpty());
    QVERIFY(store.addInvite(aminaKey, QStringLiteral("Family"), QByteArray(), now).isEmpty());

    Air::LocalStore again;
    again.load(store.save(), now);
    QCOMPARE(again.invites(), store.invites());
    QCOMPARE(again.invite(second).inviterKey, yusufKey);
    QCOMPARE(again.invite(second).data, QByteArrayLiteral("newer"));
    QVERIFY(again.invite(QStringLiteral("unknown")).isNull());

    QVERIFY(again.removeInvite(other));
    QVERIFY(!again.removeInvite(other));
    QVERIFY(again.forget(yusufKey));
    QVERIFY(again.invites().isEmpty());
}

void TestAirConversation::entryMarksKeepTheNewestAndGoWithTheirConversation()
{
    const QDateTime now = date(2026, 11, 12, 12, 0);
    const QString text = Air::textEntryKey(yusufKey, yusufKey, 1762938000123);
    const QString file = Air::fileEntryKey(yusufKey, QByteArray(32, '\x07'));
    Air::EntryMarks marks;
    marks.edited = true;
    marks.editedAt = 1762938060;
    marks.react(Air::LocalStore::hashedKey(aminaKey), QStringLiteral("❤️"), true);
    marks.react(Air::LocalStore::hashedKey(yusufKey), QStringLiteral("❤️"), true);

    Air::LocalStore store;
    store.load(QByteArray(), now);
    QVERIFY(store.setEntryMarks(text, marks));
    QVERIFY(!store.setEntryMarks(text, marks));
    Air::EntryMarks fileMarks;
    fileMarks.react(Air::LocalStore::hashedKey(aminaKey), QStringLiteral("👍"), true);
    QVERIFY(store.setEntryMarks(file, fileMarks));
    // Only an entry key is a key: never a contact's key or a stray text.
    QVERIFY(!store.setEntryMarks(yusufKey, marks));
    QVERIFY(!store.setEntryMarks(QStringLiteral("x/y"), marks));

    const QByteArray saved = store.save();
    QVERIFY2(!saved.contains(yusufKey.toUtf8()) && !saved.contains(aminaKey.toUtf8()),
             "the stored marks never name a person");
    Air::LocalStore again;
    again.load(saved, now);
    QCOMPARE(again.entryMarks(text), marks);
    QCOMPARE(again.entryMarks(file), fileMarks);
    QCOMPARE(again.entryMarks(text).reactionsText(), QStringLiteral("❤️ 2"));

    // Empty marks forget the entry.
    QVERIFY(again.setEntryMarks(file, Air::EntryMarks()));
    QVERIFY(again.entryMarks(file).isEmpty());

    // The entries changed last are kept.
    Air::LocalStore many;
    many.load(QByteArray(), now);
    for (int i = 0; i < Air::LocalStore::entryLimit + 2; ++i) {
        QVERIFY(many.setEntryMarks(Air::textEntryKey(aminaKey, aminaKey, i), marks));
    }
    QVERIFY(many.entryMarks(Air::textEntryKey(aminaKey, aminaKey, 0)).isEmpty());
    QVERIFY(many.entryMarks(Air::textEntryKey(aminaKey, aminaKey, 1)).isEmpty());
    QCOMPARE(many.entryMarks(Air::textEntryKey(aminaKey, aminaKey, 2)), marks);
    // Changing an old entry makes it the newest.
    QVERIFY(many.setEntryMarks(Air::textEntryKey(aminaKey, aminaKey, 2), fileMarks));
    QVERIFY(many.setEntryMarks(Air::textEntryKey(aminaKey, aminaKey, 1000), marks));
    QCOMPARE(many.entryMarks(Air::textEntryKey(aminaKey, aminaKey, 2)), fileMarks);
    QVERIFY(many.entryMarks(Air::textEntryKey(aminaKey, aminaKey, 3)).isEmpty());

    // A removed contact takes the marks of its conversation along, and only those.
    QVERIFY(again.setEntryMarks(Air::textEntryKey(aminaKey, aminaKey, 5), marks));
    QVERIFY(again.forget(yusufKey));
    QVERIFY(again.entryMarks(text).isEmpty());
    QCOMPARE(again.entryMarks(Air::textEntryKey(aminaKey, aminaKey, 5)), marks);
    // An erased history leaves no entry to mark.
    QVERIFY(again.forgetEntries());
    QVERIFY(again.entryMarks(Air::textEntryKey(aminaKey, aminaKey, 5)).isEmpty());
    QVERIFY(!again.forgetEntries());
}

void TestAirConversation::groupIdsKeepTheNewestAndGoWithTheirConversation()
{
    const QDateTime now = date(2026, 11, 12, 12, 0);
    const QString group = aminaKey;
    const QString first = Air::textEntryKey(group, yusufKey, 1762938000123);
    Air::LocalStore store;
    store.load(QByteArray(), now);
    QVERIFY(store.setWireId(first, Air::groupWireId(0x0A0B0C0D)));
    QVERIFY(!store.setWireId(first, Air::groupWireId(0x0A0B0C0D)));
    // Four bytes, or nothing.
    QVERIFY(!store.setWireId(first, QByteArray(3, '\x01')));
    QVERIFY(!store.setWireId(QStringLiteral("not a key"), Air::groupWireId(1)));

    Air::LocalStore again;
    again.load(store.save(), now);
    QCOMPARE(again.wireId(first), QByteArray::fromHex("0A0B0C0D"));
    QVERIFY(again.setWireId(first, QByteArray()));
    QVERIFY(again.wireId(first).isEmpty());

    Air::LocalStore many;
    many.load(QByteArray(), now);
    for (int i = 0; i < Air::LocalStore::wireLimit + 1; ++i) {
        QVERIFY(many.setWireId(Air::textEntryKey(group, yusufKey, i), Air::groupWireId(i + 1)));
    }
    QVERIFY(many.wireId(Air::textEntryKey(group, yusufKey, 0)).isEmpty());
    QCOMPARE(many.wireId(Air::textEntryKey(group, yusufKey, 1)), Air::groupWireId(2));
    // A group left takes its ids along.
    QVERIFY(many.forgetGroup(group));
    QVERIFY(many.wireId(Air::textEntryKey(group, yusufKey, 1)).isEmpty());
}

void TestAirConversation::everyPluralTextAgreesWithItsNumberInEveryLanguage()
{
    QVector<AirStrings::Text> plurals;
    for (AirStrings::Text text : AirStrings::all()) {
        if (AirStrings::isNumerus(text)) {
            plurals.append(text);
        }
    }
    QCOMPARE(plurals.size(), 17);

    using Pairs = QVector<QPair<int, int>>;
    struct Language
    {
        QString code;
        /** Numbers that take the same form of the words. */
        Pairs same;
        /** Numbers that take different forms. */
        Pairs different;
    };
    const QVector<Language> languages{
        {QStringLiteral("en"), {{2, 5}}, {{1, 2}}},
        {QStringLiteral("ru"), {{1, 21}, {5, 11}, {2, 22}}, {{2, 5}}},
        {QStringLiteral("ar"), {{3, 10}, {11, 99}}, {{2, 3}, {3, 11}}},
        {QStringLiteral("zh_CN"), {{1, 2}}, {}},
    };
    // After "не хватает" the number takes the genitive, where two and five share one form ("не
    // хватает 2 знаков", "не хватает 5 знаков"), as on the phones; one still differs ("1 знака").
    // Where the number governs no word ("Invite 5", "Пригласить 5", "دعوة 5"), every form reads
    // the same; a Russian verb agrees only between one and more ("получит 1", "получат 2 из 5"),
    // and "All 2" stands in the Arabic dual ("الاثنان").
    const auto differentFor = [](const Language& language, AirStrings::Text text) {
        using AirStrings::Text;
        const bool noWordAgrees = text == Text::InviteSendCount || text == Text::NewGroupCreateInvite;
        if (noWordAgrees || (text == Text::GroupHintAll && language.code != QStringLiteral("ar"))) {
            return Pairs{};
        }
        if (language.code == QStringLiteral("ru")
            && (text == Text::CmdIdLeft || text == Text::GroupHintSome)) {
            return Pairs{{1, 2}};
        }
        if (language.code == QStringLiteral("ar")) {
            if (text == Text::GroupHintSome) {
                return Pairs{};
            }
            if (text == Text::GroupHintAll) {
                return Pairs{{2, 3}};
            }
        }
        return language.different;
    };
    for (const Language& language : languages) {
        use(language.code);
        for (AirStrings::Text text : plurals) {
            const QString english = QString::fromUtf8(AirStrings::source(text));
            const auto words = [&](int number) -> QString {
                const QString said = AirStrings::text(text, number);
                const QString where =
                    QStringLiteral("%1 %2: %3 -> %4").arg(language.code).arg(number).arg(english, said);
                // The Arabic singular and dual name the count by themselves ("رمزان", two
                // characters), as does the English "Invitation sent", as on the phones; every
                // other form shows the number.
                const bool countInWords = (language.code == QStringLiteral("ar") && number <= 2)
                                          || (language.code == QStringLiteral("en") && number == 1
                                              && text == AirStrings::Text::InviteSent);
                [&] {
                    QVERIFY2(countInWords || said.contains(QString::number(number)), qPrintable(where));
                    QVERIFY2(!said.contains(QStringLiteral("%n")), qPrintable(where));
                    QVERIFY2(!said.contains(QStringLiteral("(s)")), qPrintable(where));
                }();
                QString word = said;
                word.remove(QRegularExpression(QStringLiteral("\\d+")));
                return word;
            };
            for (const QPair<int, int>& pair : language.same) {
                QVERIFY2(words(pair.first) == words(pair.second), qPrintable(language.code + english));
            }
            for (const QPair<int, int>& pair : differentFor(language, text)) {
                QVERIFY2(words(pair.first) != words(pair.second), qPrintable(language.code + english));
            }
        }
    }
}

void TestAirConversation::sizesCountInThousandsAsThePhonesDo()
{
    const QLocale english(QLocale::English, QLocale::UnitedStates);
    QCOMPARE(AirStrings::byteSizeText(0, english), QStringLiteral("0 B"));
    QCOMPARE(AirStrings::byteSizeText(-5, english), QStringLiteral("0 B"));
    QCOMPARE(AirStrings::byteSizeText(999, english), QStringLiteral("999 B"));
    QCOMPARE(AirStrings::byteSizeText(1000, english), QStringLiteral("1 KB"));
    QCOMPARE(AirStrings::byteSizeText(154300, english), QStringLiteral("154 KB"));
    QCOMPARE(AirStrings::byteSizeText(12600000, english), QStringLiteral("12.6 MB"));
    QCOMPARE(AirStrings::byteSizeText(3000000, english), QStringLiteral("3 MB"));
    QCOMPARE(AirStrings::byteSizeText(1250000000, english), QStringLiteral("1.25 GB"));
    QCOMPARE(AirStrings::byteSizeText(Q_INT64_C(2000000000000), english),
             QStringLiteral("2,000 GB"));
    QCOMPARE(AirStrings::speedText(1200000, english), QStringLiteral("1.2 MB/s"));
}

void TestAirConversation::aSizeThatRoundsUpTakesTheNextUnit()
{
    const QLocale english(QLocale::English, QLocale::UnitedStates);
    QCOMPARE(AirStrings::byteSizeText(999600, english), QStringLiteral("1 MB"));
    QCOMPARE(AirStrings::byteSizeText(999960000, english), QStringLiteral("1 GB"));
    QCOMPARE(AirStrings::byteSizeText(999400, english), QStringLiteral("999 KB"));
}

void TestAirConversation::sizesSpeakTheInterfaceLanguage()
{
    use(QStringLiteral("ru"));
    QCOMPARE(AirStrings::byteSizeText(12600000, QLocale(QStringLiteral("ru"))),
             QStringLiteral("12,6 МБ"));
    QCOMPARE(AirStrings::byteSizeText(512, QLocale(QStringLiteral("ru"))), QStringLiteral("512 Б"));
    QCOMPARE(AirStrings::speedText(1200000, QLocale(QStringLiteral("ru"))),
             QStringLiteral("1,2 МБ/с"));

    use(QStringLiteral("ar"));
    // Latin digits with their own decimal point, as everywhere else in the app.
    const QString arabic = AirStrings::byteSizeText(12600000, QLocale(QStringLiteral("ar")));
    QVERIFY2(arabic.contains(QStringLiteral("12.6")), qPrintable(arabic));
    QVERIFY2(!arabic.contains(QStringLiteral("MB")), qPrintable(arabic));

    use(QStringLiteral("zh_CN"));
    QCOMPARE(AirStrings::byteSizeText(12600000, QLocale(QStringLiteral("zh_CN"))),
             QStringLiteral("12.6 MB"));
    QCOMPARE(AirStrings::byteSizeText(512, QLocale(QStringLiteral("zh_CN"))),
             QStringLiteral("512 字节"));
}

QTEST_GUILESS_MAIN(TestAirConversation)
#include "airconversation_test.moc"
