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

// KHANDAQ (beta ideas 2026-11-20): where "Unread messages" stands when a conversation opens with
// unread entries (ChatWidget::firstUnreadIdx), and the room its line takes (ChatLine::AirUnread).
// KHANDAQ (beta feedback 2026-11-22): and which new entry takes the transcript to its foot, one we
// sent from here (ChatWidget::sentFromHere).

#include "src/chatlog/chatline.h"
#include "src/chatlog/chatwidget.h"
#include "src/chatlog/content/airlabel.h"
#include "src/core/icoreidhandler.h"
#include "src/core/toxfile.h"
#include "src/friendlist.h"
#include "src/grouplist.h"
#include "src/model/air/airmedia.h"
#include "src/model/sessionchatlog.h"
#include "src/model/systemmessage.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"

#include <QtTest/QtTest>

#include <algorithm>
#include <memory>

namespace {

ToxPk keyOf(uint8_t fill)
{
    uint8_t bytes[ToxPk::size];
    std::fill(std::begin(bytes), std::end(bytes), fill);
    return ToxPk(bytes);
}

class SelfIdHandler : public ICoreIdHandler
{
public:
    ToxId getSelfId() const override
    {
        std::terminate();
        return ToxId();
    }

    ToxPk getSelfPublicKey() const override
    {
        return keyOf(0x05);
    }

    QString getUsername() const override
    {
        return QStringLiteral("Isa");
    }
};

Message textAt(const QString& content, const QDateTime& time)
{
    Message message;
    message.content = content;
    message.isAction = false;
    message.timestamp = time;
    return message;
}

/** A line as ChatMessage::createUnreadMark makes it, without the caches a message needs. */
class UnreadLine : public ChatLine
{
public:
    explicit UnreadLine(const QString& text)
    {
        setLayout(Layout::AirUnread);
        addColumn(new AirLabel(text, AirTokens::TextRole::Caption, AirLabel::Ink::Ink,
                               QFont::DemiBold, false),
                  ColumnFormat());
        addColumn(new AirLabel(QString(), AirTokens::TextRole::Caption, AirLabel::Ink::Ink3, -1,
                               false),
                  ColumnFormat());
        addColumn(new AirLabel(QString(), AirTokens::TextRole::Caption, AirLabel::Ink::Ink3, -1,
                               false),
                  ColumnFormat());
    }
};

} // namespace

class TestUnreadMark : public QObject
{
    Q_OBJECT

private slots:
    void init();

    void standsOverTheCountedEntryOfOthersBackFromTheNewest();
    void leavesOutWhatTheLogDoesNotShow();
    void standsOverTheOldestWhenTheLogHoldsFewer();
    void noCountOrNothingOfOthersMakesNoLine();
    void oursUnderTheGroupKeyAreOurs();
    void aMissedCallCountsAndOtherNewsDoesNot();
    void rowsRelayedFromTheHistoryKeepTheirTimeAndAreLeftOut();
    void oneOfOursFromBeforeEndsTheSearch();
    void looksBackNoFurtherThanItsLimit();
    void theLineTakesTheRoomOfItsWordsAtTheTextColumn();
    void onlyWhatWeSendFromHereTakesTheTranscriptToItsFoot();

private:
    ChatLogIdx received(const ToxPk& sender, const QString& text,
                        const QDateTime& time = QDateTime::currentDateTime());
    ChatLogIdx sent(const QString& text, const QDateTime& time = QDateTime::currentDateTime());
    bool firstUnread(int count, ChatLogIdx* idx, const QDateTime& since = QDateTime(),
                     const ToxPk& groupSelf = ToxPk());

    SelfIdHandler idHandler;
    std::unique_ptr<FriendList> friendList;
    std::unique_ptr<GroupList> groupList;
    std::unique_ptr<SessionChatLog> log;
    uint32_t nextDispatch = 0;
    const ToxPk maryam = keyOf(0x11);
    const ToxPk yusuf = keyOf(0x22);
};

void TestUnreadMark::init()
{
    friendList.reset(new FriendList());
    groupList.reset(new GroupList());
    log.reset(new SessionChatLog(idHandler, *friendList, *groupList));
    nextDispatch = 0;
}

ChatLogIdx TestUnreadMark::received(const ToxPk& sender, const QString& text, const QDateTime& time)
{
    const ChatLogIdx idx = log->getNextIdx();
    log->onMessageReceived(sender, textAt(text, time));
    return idx;
}

ChatLogIdx TestUnreadMark::sent(const QString& text, const QDateTime& time)
{
    const ChatLogIdx idx = log->getNextIdx();
    log->onMessageSent(DispatchedMessageId(nextDispatch++), textAt(text, time));
    return idx;
}

bool TestUnreadMark::firstUnread(int count, ChatLogIdx* idx, const QDateTime& since,
                                 const ToxPk& groupSelf)
{
    return ChatWidget::firstUnreadIdx(*log, idHandler.getSelfPublicKey(), groupSelf, count, since,
                                      idx);
}

/**
 * @brief Three came: the third of theirs back from the newest, a file among them, our messages in
 * between not counted.
 */
void TestUnreadMark::standsOverTheCountedEntryOfOthersBackFromTheNewest()
{
    received(maryam, QStringLiteral("Assalamu alaykum"));
    sent(QStringLiteral("Wa alaykum assalam"));
    const ChatLogIdx third = received(maryam, QStringLiteral("Are you coming tonight?"));

    ToxFile file(1, 0, QStringLiteral("route.pdf"), QString(), 2048, ToxFile::RECEIVING);
    file.status = ToxFile::INITIALIZING;
    const ChatLogIdx fileIdx = log->getNextIdx();
    log->onFileUpdated(maryam, file);
    QCOMPARE(log->at(fileIdx).getContentType(), ChatLogItem::ContentType::fileTransfer);

    ToxFile ours(2, 0, QStringLiteral("photo.jpg"), QString(), 4096, ToxFile::SENDING);
    ours.status = ToxFile::INITIALIZING;
    log->onFileUpdated(idHandler.getSelfPublicKey(), ours);
    const ChatLogIdx newest = received(maryam, QStringLiteral("The route is in the file"));

    ChatLogIdx idx{0};
    QVERIFY(firstUnread(1, &idx));
    QCOMPARE(idx, newest);
    QVERIFY(firstUnread(2, &idx));
    QCOMPARE(idx, fileIdx);
    QVERIFY(firstUnread(3, &idx));
    QCOMPARE(idx, third);
}

/** @brief A message its author deleted, or one cancelled, is not counted. */
void TestUnreadMark::leavesOutWhatTheLogDoesNotShow()
{
    const ChatLogIdx first = received(maryam, QStringLiteral("One"));
    const ChatLogIdx deleted = received(maryam, QStringLiteral("Two"));
    received(maryam, QStringLiteral("Three"));
    log->markHidden(deleted);

    ChatLogIdx idx{0};
    QVERIFY(firstUnread(2, &idx));
    QCOMPARE(idx, first);
}

/** @brief More counted than the log holds: the line stands over the oldest of theirs. */
void TestUnreadMark::standsOverTheOldestWhenTheLogHoldsFewer()
{
    sent(QStringLiteral("Salam"));
    const ChatLogIdx oldest = received(yusuf, QStringLiteral("Salam, brother"));
    sent(QStringLiteral("How are you?"));
    received(yusuf, QStringLiteral("Alhamdulillah"));

    ChatLogIdx idx{0};
    QVERIFY(firstUnread(7, &idx));
    QCOMPARE(idx, oldest);
}

/** @brief No count, or only our own entries, and there is no line at all. */
void TestUnreadMark::noCountOrNothingOfOthersMakesNoLine()
{
    ChatLogIdx idx{0};
    QVERIFY(!firstUnread(1, &idx));

    sent(QStringLiteral("Anyone here?"));
    sent(QStringLiteral("Hello?"));
    QVERIFY(!firstUnread(2, &idx));

    received(maryam, QStringLiteral("Yes"));
    QVERIFY(!firstUnread(0, &idx));
    QVERIFY(firstUnread(1, &idx));
}

/** @brief In a group our entries may carry the key the group knows us by: those are ours too. */
void TestUnreadMark::oursUnderTheGroupKeyAreOurs()
{
    const ToxPk groupSelf = keyOf(0x33);
    const ChatLogIdx theirs = received(maryam, QStringLiteral("Who brings the tent?"));
    received(groupSelf, QStringLiteral("I do"));
    sent(QStringLiteral("And the stove"));
    const ChatLogIdx newest = received(yusuf, QStringLiteral("Good"));

    ChatLogIdx idx{0};
    QVERIFY(firstUnread(2, &idx, QDateTime(), groupSelf));
    QCOMPARE(idx, theirs);
    QVERIFY(firstUnread(1, &idx, QDateTime(), groupSelf));
    QCOMPARE(idx, newest);
}

/**
 * @brief A call of theirs that nobody answered is counted as it rang; one of ours and the news of a
 * group are not.
 */
void TestUnreadMark::aMissedCallCountsAndOtherNewsDoesNot()
{
    const ChatLogIdx message = received(maryam, QStringLiteral("Call me"));
    const auto news = [this](SystemMessageType type, const SystemMessage::Args& args) {
        SystemMessage row;
        row.messageType = type;
        row.timestamp = QDateTime::currentDateTime();
        row.args = args;
        log->addSystemMessage(row);
    };
    const ChatLogIdx missed = log->getNextIdx();
    news(SystemMessageType::incomingCall, Air::unansweredCallArgs(QStringLiteral("Maryam")));
    news(SystemMessageType::outgoingCall, Air::unansweredCallArgs(QStringLiteral("Maryam")));
    SystemMessage::Args joined;
    joined[0] = QStringLiteral("Yusuf");
    news(SystemMessageType::userJoinedGroup, joined);

    ChatLogIdx idx{0};
    QVERIFY(firstUnread(1, &idx));
    QCOMPARE(idx, missed);
    QVERIFY(firstUnread(2, &idx));
    QCOMPARE(idx, message);
}

/**
 * @brief A member relays rows of the group's history with the time they were sent, after the live
 * ones that came since: they are not what was counted, wherever they stand.
 */
void TestUnreadMark::rowsRelayedFromTheHistoryKeepTheirTimeAndAreLeftOut()
{
    const QDateTime since = QDateTime::currentDateTime();
    received(yusuf, QStringLiteral("Read before"), since.addSecs(-3600));
    const ChatLogIdx live = received(maryam, QStringLiteral("We leave at six"), since);
    received(yusuf, QStringLiteral("Relayed one"), since.addSecs(-7200));
    received(yusuf, QStringLiteral("Relayed two"), since.addSecs(-7100));

    ChatLogIdx idx{0};
    QVERIFY(firstUnread(1, &idx, since));
    QCOMPARE(idx, live);
    // Written a moment before the alert noted the time, it still counts.
    QVERIFY(firstUnread(1, &idx, since.addMSecs(1500)));
    QCOMPARE(idx, live);
    // Without the time, the newest of theirs is taken, relayed or not.
    QVERIFY(firstUnread(1, &idx));
    QVERIFY(idx > live);
}

/**
 * @brief Our entries come in the order they were written: one older than the first unread means
 * what lies before it came before too.
 */
void TestUnreadMark::oneOfOursFromBeforeEndsTheSearch()
{
    const QDateTime since = QDateTime::currentDateTime();
    // Out of order on purpose: were the search to go on, it would take this one.
    received(maryam, QStringLiteral("Out of place"), since.addSecs(5));
    sent(QStringLiteral("Before the unread"), since.addSecs(-3600));
    const ChatLogIdx live = received(maryam, QStringLiteral("New"), since.addSecs(10));

    ChatLogIdx idx{0};
    QVERIFY(firstUnread(2, &idx, since));
    QCOMPARE(idx, live);
}

/** @brief However much was counted, no more than unreadLookBack entries are read back. */
void TestUnreadMark::looksBackNoFurtherThanItsLimit()
{
    received(maryam, QStringLiteral("Long ago"));
    for (size_t i = 0; i < ChatWidget::unreadLookBack; ++i) {
        sent(QStringLiteral("note %1").arg(i));
    }
    ChatLogIdx idx{0};
    QVERIFY(!firstUnread(1, &idx));

    init();
    const ChatLogIdx near = received(maryam, QStringLiteral("Not so long ago"));
    for (size_t i = 0; i + 1 < ChatWidget::unreadLookBack; ++i) {
        sent(QStringLiteral("note %1").arg(i));
    }
    QVERIFY(firstUnread(1, &idx));
    QCOMPARE(idx, near);
}

/**
 * @brief The line holds its words at the text column, 8 under its top and 4 over the entry, as the
 * room iOS keeps over the entry for its mark. KHANDAQ (owner, 28.11.2026): the text column starts
 * at the 16 px margin, as there is no time column before it any more.
 */
void TestUnreadMark::theLineTakesTheRoomOfItsWordsAtTheTextColumn()
{
    using namespace AirTokens;
    QGuiApplication::setLayoutDirection(Qt::LeftToRight);
    UnreadLine line(QStringLiteral("Unread messages"));
    line.layout(600.0, QPointF(0.0, 100.0));

    const int caption = AirStyle::instance().lineHeight(TextRole::Caption);
    QCOMPARE(line.sceneBoundingRect().top(), 100.0);
    QCOMPARE(line.sceneBoundingRect().height(),
             static_cast<qreal>(Space::s8 + caption + Space::s4));

    const ChatLineContent* const words = line.getContent(0);
    QVERIFY(words->isVisible());
    QCOMPARE(words->pos().x(), static_cast<qreal>(Space::s16));
    QCOMPARE(words->pos().y(), 100.0 + Space::s8);
    QVERIFY(!line.getContent(1)->isVisible());
    QVERIFY(!line.getContent(2)->isVisible());
    QCOMPARE(line.getContent(0)->getText(), QString());
    QCOMPARE(line.getContent(0)->getSpokenText(), QStringLiteral("Unread messages"));
}

/**
 * @brief A text, a file or a voice note we send is new to the log under our key: the transcript
 * shows its foot. What others send, the news of a group, ours that a group relays under our key in
 * it, a file of ours that goes on from before, and a change to an entry already told of are not.
 */
void TestUnreadMark::onlyWhatWeSendFromHereTakesTheTranscriptToItsFoot()
{
    const ToxPk self = idHandler.getSelfPublicKey();
    const ToxPk groupSelf = keyOf(0x33);
    const auto takesTheFoot = [this, &self](ChatLogIdx idx, ChatLogIdx knownEnd) {
        return ChatWidget::sentFromHere(*log, idx, knownEnd, self);
    };

    const ChatLogIdx theirs = received(maryam, QStringLiteral("Assalamu alaykum"));
    QVERIFY(!takesTheFoot(theirs, theirs));
    const ChatLogIdx ours = sent(QStringLiteral("Wa alaykum assalam"));
    QVERIFY(takesTheFoot(ours, ours));
    // Told of again (delivered, edited): no new entry.
    QVERIFY(!takesTheFoot(ours, ours + 1));

    ToxFile photo(2, 0, QStringLiteral("photo.jpg"), QString(), 4096, ToxFile::SENDING);
    photo.status = ToxFile::INITIALIZING;
    const ChatLogIdx file = log->getNextIdx();
    log->onFileUpdated(self, photo);
    QCOMPARE(log->at(file).getContentType(), ChatLogItem::ContentType::fileTransfer);
    QVERIFY(takesTheFoot(file, file));
    ToxFile route(3, 0, QStringLiteral("route.pdf"), QString(), 2048, ToxFile::RECEIVING);
    route.status = ToxFile::INITIALIZING;
    const ChatLogIdx theirFile = log->getNextIdx();
    log->onFileUpdated(maryam, route);
    QVERIFY(!takesTheFoot(theirFile, theirFile));
    // A file of ours that goes on from before, whose entry of then the log does not hold.
    ToxFile resumed(4, 0, QStringLiteral("video.mp4"), QString(), 8192, ToxFile::SENDING);
    resumed.status = ToxFile::INITIALIZING;
    resumed.continued = true;
    resumed.resumeFileId = QByteArray(32, '\x07');
    const ChatLogIdx goesOn = log->getNextIdx();
    log->onFileUpdated(self, resumed);
    QCOMPARE(log->getNextIdx(), goesOn + 1);
    QVERIFY(!takesTheFoot(goesOn, goesOn));

    // Ours, relayed from the history of a group under our key in it.
    const QDateTime anHourAgo = QDateTime::currentDateTime().addSecs(-3600);
    const ChatLogIdx relayed = log->getNextIdx();
    log->onRelayedMessageReceived(groupSelf, QStringLiteral("Isa"),
                                  textAt(QStringLiteral("I bring the tent"), anHourAgo));
    QVERIFY(!takesTheFoot(relayed, relayed));
    SystemMessage joined;
    joined.messageType = SystemMessageType::userJoinedGroup;
    joined.timestamp = QDateTime::currentDateTime();
    const ChatLogIdx news = log->getNextIdx();
    log->addSystemMessage(joined);
    QVERIFY(!takesTheFoot(news, news));
    // An index the log does not hold.
    QVERIFY(!takesTheFoot(log->getNextIdx(), log->getNextIdx()));
}

QTEST_MAIN(TestUnreadMark)
#include "unreadmark_test.moc"
