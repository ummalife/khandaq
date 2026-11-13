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

#include "src/model/air/airmodel.h"
#include "src/widget/air/airaccessible.h"
#include "src/widget/air/airlistview.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"

#include <QAccessible>
#include <QApplication>
#include <QGlyphRun>
#include <QLineEdit>
#include <QRawFont>
#include <QScrollBar>
#include <QSignalSpy>
#include <QTextCharFormat>
#include <QTextLayout>
#include <QtTest/QtTest>

namespace {

const QString aminaKey =
    QStringLiteral("56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855");
const QString omarKey =
    QStringLiteral("F2BBA2E9DA9B0A7A1C7CD418D0EA4A520F2F892147325B03538934617DBA4E3A");

Air::Entry contact(const QString& id, const QString& title)
{
    Air::Entry entry;
    entry.id = id;
    entry.title = title;
    entry.reach = Air::Reach::direct();
    entry.activity = 1000;
    return entry;
}

/** A newer friend request and an older invitation over one conversation. */
Air::Snapshot cardsOverAConversation()
{
    const Air::Request request =
        Air::Request::make(aminaKey, QStringLiteral("Hello, it is Amina from the Thursday circle."),
                           QStringLiteral("12:05"), 2000);
    const Air::Request invitation =
        Air::Request::invitation(QStringLiteral("ab12"), omarKey, QStringLiteral("Book club"),
                                 QStringLiteral("Omar Haddad"), false, QStringLiteral("11:52"), 1000);
    return Air::build({contact(QStringLiteral("f:") + omarKey, QStringLiteral("Omar Haddad"))},
                      {request, invitation}, {}, Air::Filter::All, QString());
}

/**
 * The ground of each card from the top, read down a column just inside the start edge of the
 * cards, where no words stand: a run of the card's colour as high as a card at least.
 */
QVector<QColor> cardGrounds(AirListView& list)
{
    const QImage image = list.viewport()->grab().toImage();
    const QColor sunken = AirStyle::instance().color(AirTokens::Color::Sunken);
    const QColor lit = AirStyle::instance().color(AirTokens::Color::Line);
    const int x = AirTokens::Space::s12 + AirTokens::Space::s4;
    const int cardHeight = AirTokens::Space::s64;
    QVector<QColor> grounds;
    QColor run;
    int length = 0;
    for (int y = 0; y <= image.height(); ++y) {
        const QColor pixel = y < image.height() ? image.pixelColor(x, y) : QColor();
        if (pixel == run) {
            ++length;
            continue;
        }
        if ((run == sunken || run == lit) && length >= cardHeight) {
            grounds.append(run);
        }
        run = pixel;
        length = 1;
    }
    return grounds;
}

/** The families whose glyphs draw text in font, each once, in the order of the runs. */
QStringList drawingFamilies(const QString& text, const QFont& font)
{
    QTextLayout layout(text, font);
    layout.beginLayout();
    QTextLine line = layout.createLine();
    line.setLineWidth(10000);
    layout.endLayout();
    QStringList families;
    for (const QGlyphRun& run : layout.glyphRuns()) {
        const QString family = run.rawFont().familyName();
        if (!families.contains(family)) {
            families.append(family);
        }
    }
    return families;
}

/** Counts the paint events one widget gets while it is installed on it. */
class PaintCount : public QObject
{
public:
    explicit PaintCount(QWidget* watched)
    {
        watched->installEventFilter(this);
    }

    bool eventFilter(QObject* watched, QEvent* event) override
    {
        if (event->type() == QEvent::Paint) {
            ++paints;
        }
        return QObject::eventFilter(watched, event);
    }

    int paints = 0;
};

} // namespace

class TestAirListView : public QObject
{
    Q_OBJECT
private slots:
    void theCommandLineNeverPressesACard();
    void theKeyboardAnswersACardWithItsButtons();
    void leftAndRightFollowTheReadingDirection();
    void theRequestsFilterKeepsTheCardsFirst();
    void theFocusRingComesBackIntoView();
    void oneCardLightsUpAlone();
    void theKeyInACardKeepsToTheMonoFamily();
    void theTextFamiliesFallBackOnEachOther();
    void aScreenReaderFindsEveryLine();
    void aCardAnswersWithItsButtons();
    void aRowOpensWithItsPressAction();
    void aLineGoneLeavesTheTree();
    void theViewportIsFoundWhateverItIsCalled();
    void aRefreshThatMovesNothingPaintsOnce();
};

void TestAirListView::theCommandLineNeverPressesACard()
{
    QWidget window;
    window.resize(352, 760);
    auto* const command = new QLineEdit(&window);
    command->setGeometry(0, 0, 352, 40);
    auto* const view = new AirListView(&window);
    view->setGeometry(0, 60, 352, 700);
    AirListView& list = *view;
    command->setFocus();
    window.show();
    list.setSnapshot(cardsOverAConversation(), QString());
    QSignalSpy accepted(&list, &AirListView::requestAccepted);
    QSignalSpy declined(&list, &AirListView::requestDeclined);
    QSignalSpy opened(&list, &AirListView::entryActivated);

    // Down and Enter from the command line: the list has no keyboard, so the cursor passes over
    // the cards to the first conversation.
    QVERIFY(!list.hasFocus());
    list.moveCursor(1);
    list.activateCursor();
    QCOMPARE(accepted.count(), 0);
    QCOMPARE(declined.count(), 0);
    QCOMPARE(opened.count(), 1);
    QCOMPARE(opened.first().first().toString(), QStringLiteral("f:") + omarKey);
}

void TestAirListView::theKeyboardAnswersACardWithItsButtons()
{
    AirListView list;
    list.resize(352, 700);
    list.show();
    list.setSnapshot(cardsOverAConversation(), QString());
    QSignalSpy accepted(&list, &AirListView::requestAccepted);
    QSignalSpy declined(&list, &AirListView::requestDeclined);

    // In the list the cursor stops on the newest card, on its first button.
    QTest::keyClick(&list, Qt::Key_Down);
    QTest::keyClick(&list, Qt::Key_Return);
    QCOMPARE(accepted.count(), 1);
    QCOMPARE(accepted.first().first().toString(), aminaKey);

    // Right reaches Decline; the next card starts again on Join.
    QTest::keyClick(&list, Qt::Key_Right);
    QTest::keyClick(&list, Qt::Key_Space);
    QCOMPARE(declined.count(), 1);
    QCOMPARE(declined.first().first().toString(), aminaKey);

    QTest::keyClick(&list, Qt::Key_Down);
    QTest::keyClick(&list, Qt::Key_Return);
    QCOMPARE(accepted.count(), 2);
    QCOMPARE(accepted.last().first().toString(),
             Air::Request::invitationId(QStringLiteral("ab12")));
}

void TestAirListView::leftAndRightFollowTheReadingDirection()
{
    AirListView list;
    list.setLayoutDirection(Qt::RightToLeft);
    list.resize(352, 700);
    list.show();
    list.setSnapshot(cardsOverAConversation(), QString());
    QSignalSpy accepted(&list, &AirListView::requestAccepted);
    QSignalSpy declined(&list, &AirListView::requestDeclined);

    // Right to left, Decline stands to the left of Accept.
    QTest::keyClick(&list, Qt::Key_Down);
    QTest::keyClick(&list, Qt::Key_Left);
    QTest::keyClick(&list, Qt::Key_Return);
    QCOMPARE(declined.count(), 1);
    QTest::keyClick(&list, Qt::Key_Right);
    QTest::keyClick(&list, Qt::Key_Return);
    QCOMPARE(accepted.count(), 1);
}

void TestAirListView::theRequestsFilterKeepsTheCardsFirst()
{
    AirListView list;
    list.resize(352, 300);
    list.show();
    const Air::Request request =
        Air::Request::make(aminaKey, QString(), QStringLiteral("12:05"), 2000);
    // Requests keeps your own requests that wait for a reply too, under the cards.
    QVector<Air::Entry> waiting;
    for (int i = 0; i < 30; ++i) {
        Air::Entry entry = contact(QStringLiteral("f:%1").arg(i), QStringLiteral("Contact %1").arg(i));
        entry.reach = Air::Reach::pending();
        waiting.append(entry);
    }
    list.setSnapshot(Air::build(waiting, {request}, {}, Air::Filter::Requests, QString()), QString());
    list.verticalScrollBar()->setValue(list.verticalScrollBar()->maximum());
    QVERIFY(list.verticalScrollBar()->value() > 0);

    // The filter takes the list back to the card at the top.
    list.highlightRequests();
    QCOMPARE(list.verticalScrollBar()->value(), 0);
}

/**
 * Tab brings the keyboard back to the row it left, which the list may have scrolled away from
 * since: that row comes back into view, so its focus ring is seen.
 */
void TestAirListView::theFocusRingComesBackIntoView()
{
    AirListView list;
    list.resize(352, 240);
    list.show();
    QVector<Air::Entry> many;
    for (int i = 0; i < 30; ++i) {
        many.append(contact(QStringLiteral("f:%1").arg(i), QStringLiteral("Contact %1").arg(i)));
    }
    list.setSnapshot(Air::build(many, {}, {}, Air::Filter::All, QString()), QString());

    // The cursor stands on the first conversation, then the list is scrolled to its end.
    QTest::keyClick(&list, Qt::Key_Down);
    QString cursorId;
    for (const AirListView::SpokenLine& line : list.spokenLines()) {
        if (line.cursor) {
            cursorId = line.id;
        }
    }
    QVERIFY(!cursorId.isEmpty());
    QScrollBar* const bar = list.verticalScrollBar();
    bar->setValue(bar->maximum());
    QVERIFY(bar->value() > list.viewport()->height());

    QFocusEvent tabbedIn(QEvent::FocusIn, Qt::TabFocusReason);
    QApplication::sendEvent(&list, &tabbedIn);
    QVERIFY(bar->value() < list.viewport()->height());
    AirListView::SpokenLine line;
    QVERIFY(list.spokenLine(cursorId, &line));
    QVERIFY(line.cursor);
}

/**
 * Join refused while the inviter is away lights up that invitation alone, where its words say why;
 * the Requests filter lights every card.
 */
void TestAirListView::oneCardLightsUpAlone()
{
    AirListView list;
    list.resize(352, 700);
    list.show();
    list.setSnapshot(cardsOverAConversation(), QString());
    const QColor sunken = AirStyle::instance().color(AirTokens::Color::Sunken);
    const QColor lit = AirStyle::instance().color(AirTokens::Color::Line);
    // Once the rows have come in.
    QTRY_COMPARE(cardGrounds(list), (QVector<QColor>{sunken, sunken}));

    list.highlightRequests(Air::Request::invitationId(QStringLiteral("ab12")));
    QCOMPARE(cardGrounds(list), (QVector<QColor>{sunken, lit}));

    list.highlightRequests();
    QCOMPARE(cardGrounds(list), (QVector<QColor>{lit, lit}));
}

void TestAirListView::theKeyInACardKeepsToTheMonoFamily()
{
    // The application font is the body text with its Arabic fallback. The key in a card is a run of
    // a text layout, and a run takes the families of its font before the family.
    const QFont application = QApplication::font();
    QApplication::setFont(AirStyle::instance().font(AirTokens::TextRole::Body));
    QTextCharFormat key;
    key.setFont(AirStyle::instance().font(AirTokens::TextRole::Mono));
    QApplication::setFont(application);
    QCOMPARE(key.fontFamily(), QString::fromLatin1(AirTokens::monoFamily));
#if QT_VERSION >= QT_VERSION_CHECK(5, 13, 0)
    // The list exists from Qt 5.13 on; before it, the family alone decides.
    QCOMPARE(key.fontFamilies().toStringList(),
             QStringList{QString::fromLatin1(AirTokens::monoFamily)});
#endif
}

void TestAirListView::theTextFamiliesFallBackOnEachOther()
{
    // Arabic in the Latin face and Cyrillic in the Arabic face come from the other Plex family.
    const QFont application = QApplication::font();
    AirStyle::installFonts();
    const QString text = QString::fromLatin1(AirTokens::textFamily);
    const QString arabic = QString::fromLatin1(AirTokens::arabicFamily);
    const QString arabicWords = QStringLiteral("مرحبا");
    const QString cyrillicWords = QStringLiteral("Привет");

    // The fonts the client makes, in both reading directions.
    QCOMPARE(drawingFamilies(arabicWords, AirStyle::instance().font(AirTokens::TextRole::Body)),
             QStringList{arabic});
    QGuiApplication::setLayoutDirection(Qt::RightToLeft);
    QCOMPARE(drawingFamilies(cyrillicWords, AirStyle::instance().font(AirTokens::TextRole::Body)),
             QStringList{text});
    QGuiApplication::setLayoutDirection(Qt::LeftToRight);

    // The substitution table, which Qt 5.12 builds go by, gives a font that names one family the
    // same fallback.
    AirStyle::substituteTextFamilies();
    QCOMPARE(drawingFamilies(arabicWords, QFont(text)), QStringList{arabic});
    QCOMPARE(drawingFamilies(cyrillicWords, QFont(arabic)), QStringList{text});
    AirStyle::substituteTextFamilies();
    QCOMPARE(QFont::substitutes(text).size(), 1);
    QVERIFY(QFont::substitutes(text).first().compare(arabic, Qt::CaseInsensitive) == 0);
    QFont::removeSubstitutions(text);
    QFont::removeSubstitutions(arabic);
    QApplication::setFont(application);
}

namespace {
/** The interface of each painted line of the list, in order. */
QVector<QAccessibleInterface*> spokenChildren(AirListView& list)
{
    QVector<QAccessibleInterface*> children;
    QAccessibleInterface* const viewport = QAccessible::queryAccessibleInterface(list.viewport());
    const int lines = list.spokenIds().size();
    for (int i = 0; viewport && i < lines; ++i) {
        children.append(viewport->child(i));
    }
    return children;
}

/** Presses an element as a screen reader does; false when it has nothing to press. */
bool pressAsAScreenReader(QAccessibleInterface* element)
{
    QAccessibleActionInterface* const action = element ? element->actionInterface() : nullptr;
    if (!action) {
        return false;
    }
    action->doAction(QAccessibleActionInterface::pressAction());
    return true;
}

const QString aminaWords = QStringLiteral("Hello, it is Amina from the Thursday circle.");
} // namespace

/**
 * Each heading, card and row is an element of its own with what the phones say for it, in the
 * order the list shows them (iOS AirRowCell and the request card).
 */
void TestAirListView::aScreenReaderFindsEveryLine()
{
    AirListView list;
    list.resize(352, 700);
    list.show();
    list.setSnapshot(cardsOverAConversation(), QString());

    QAccessibleInterface* const viewport = QAccessible::queryAccessibleInterface(list.viewport());
    QVERIFY(viewport);
    QCOMPARE(viewport->role(), QAccessible::List);
    QCOMPARE(viewport->text(QAccessible::Name), QStringLiteral("Chats"));
    QVERIFY(viewport->childCount() >= list.spokenIds().size());

    // The cards stand above the sections without a heading of their own; the conversation comes
    // under the heading of its section, its name first.
    const QStringList said = AirAccessible::spokenTree(list.viewport());
    const QStringList expected{
        QStringLiteral("list\tChats"),
        QStringLiteral("group\tIncoming request, 12:05, ") + aminaWords
            + QStringLiteral(
                ", key 56A1ADE4B65B. You will see the name once you accept the request"),
        QStringLiteral("button\tAccept"),
        QStringLiteral("button\tDecline"),
        QStringLiteral(
            "group\tGroup invitation, 11:52, Book club, Omar Haddad invites you. You can "
            "join once Omar Haddad is online."),
        QStringLiteral("button\tJoin"),
        QStringLiteral("button\tDecline"),
        QStringLiteral("text\tOnline now, 1"),
        QStringLiteral("item\tOmar Haddad, online"),
    };
    QVERIFY2(said == expected, qPrintable(said.join(QLatin1Char('\n'))));

    // Every child knows its parent and its place.
    const QVector<QAccessibleInterface*> children = spokenChildren(list);
    for (int i = 0; i < children.size(); ++i) {
        QVERIFY(children[i]);
        QVERIFY(children[i]->isValid());
        QCOMPARE(children[i]->parent(), viewport);
        QCOMPARE(viewport->indexOfChild(children[i]), i);
        // Asked twice, a line is the same element: a screen reader keeps its place on it.
        QCOMPARE(viewport->child(i), children[i]);
    }
    // A line is found where it is painted.
    const QRect row = children.last()->rect();
    QVERIFY(!row.isEmpty());
    QCOMPARE(viewport->childAt(row.center().x(), row.center().y()), children.last());
}

void TestAirListView::aCardAnswersWithItsButtons()
{
    AirListView list;
    list.resize(352, 700);
    list.show();
    list.setSnapshot(cardsOverAConversation(), QString());
    QSignalSpy accepted(&list, &AirListView::requestAccepted);
    QSignalSpy declined(&list, &AirListView::requestDeclined);

    const QVector<QAccessibleInterface*> children = spokenChildren(list);
    QVector<QAccessibleInterface*> cards;
    for (QAccessibleInterface* const child : children) {
        if (child->role() == QAccessible::Grouping) {
            cards.append(child);
        }
    }
    QCOMPARE(cards.size(), 2);
    for (QAccessibleInterface* const card : cards) {
        // A card itself is not pressed: its buttons are.
        QVERIFY(!card->actionInterface());
        QCOMPARE(card->childCount(), 2);
        for (int i = 0; i < 2; ++i) {
            QAccessibleInterface* const button = card->child(i);
            QVERIFY(button);
            QCOMPARE(button->role(), QAccessible::Button);
            QCOMPARE(button->parent(), card);
            QCOMPARE(card->indexOfChild(button), i);
            QVERIFY(card->rect().contains(button->rect()));
            QCOMPARE(card->childAt(button->rect().center().x(), button->rect().center().y()), button);
            QAccessibleActionInterface* const action = button->actionInterface();
            if (!action) {
                QFAIL("A button of a card has nothing to press");
            }
            QCOMPARE(action->actionNames(), QStringList{QAccessibleActionInterface::pressAction()});
        }
    }

    QVERIFY(pressAsAScreenReader(cards[0]->child(0)));
    QCOMPARE(accepted.count(), 1);
    QCOMPARE(accepted.first().first().toString(), aminaKey);
    QVERIFY(pressAsAScreenReader(cards[1]->child(1)));
    QCOMPARE(declined.count(), 1);
    QCOMPARE(declined.first().first().toString(),
             Air::Request::invitationId(QStringLiteral("ab12")));
    QCOMPARE(accepted.count(), 1);
}

void TestAirListView::aRowOpensWithItsPressAction()
{
    AirListView list;
    list.resize(352, 700);
    list.show();
    list.setSnapshot(cardsOverAConversation(), QString());
    QSignalSpy opened(&list, &AirListView::entryActivated);

    QAccessibleInterface* const row = spokenChildren(list).last();
    QCOMPARE(row->role(), QAccessible::ListItem);
    QVERIFY(row->state().selectable);
    QVERIFY(!row->state().selected);
    QVERIFY(!row->state().offscreen);

    list.setOpenEntry(QStringLiteral("f:") + omarKey);
    QVERIFY(row->state().selected);

    QVERIFY(pressAsAScreenReader(row));
    QCOMPARE(opened.count(), 1);
    QCOMPARE(opened.first().first().toString(), QStringLiteral("f:") + omarKey);

    // A heading is words alone: nothing to press, nothing to select.
    QAccessibleInterface* heading = nullptr;
    for (QAccessibleInterface* const child : spokenChildren(list)) {
        if (child->role() == QAccessible::StaticText) {
            heading = child;
        }
    }
    QVERIFY(heading);
    QVERIFY(!heading->actionInterface());
    QVERIFY(!heading->state().selectable);
}

void TestAirListView::aLineGoneLeavesTheTree()
{
    AirListView list;
    list.resize(352, 700);
    list.show();
    list.setSnapshot(cardsOverAConversation(), QString());
    QSignalSpy accepted(&list, &AirListView::requestAccepted);

    // A screen reader has gone over the whole list once: every element it met is made.
    AirAccessible::spokenTree(list.viewport());
    QAccessibleInterface* const card = spokenChildren(list).first();
    QCOMPARE(card->role(), QAccessible::Grouping);
    const QAccessible::Id cardId = QAccessible::uniqueId(card);
    QAccessibleInterface* const accept = card->child(0);
    const QAccessible::Id acceptId = QAccessible::uniqueId(accept);

    // The request is answered and the list comes again without it.
    const Air::Snapshot without =
        Air::build({contact(QStringLiteral("f:") + omarKey, QStringLiteral("Omar Haddad"))}, {}, {},
                   Air::Filter::All, QString());
    list.setSnapshot(without, QString());
    const QString cardLine = QStringLiteral("request:") + aminaKey;
    QVERIFY(!list.spokenIds().contains(cardLine));
    QVERIFY(!list.hasSpokenLine(cardLine));

    // While the card fades out it is gone for a screen reader already: its button presses nothing.
    QVERIFY(pressAsAScreenReader(accept));
    QCOMPARE(accepted.count(), 0);
    QVERIFY(!accept->isValid());

    // The next look at the list lets the interfaces of the lines that went go too, the buttons of a
    // card with it.
    const QStringList said = AirAccessible::spokenTree(list.viewport());
    QCOMPARE(said, (QStringList{QStringLiteral("list\tChats"), QStringLiteral("text\tOnline now, 1"),
                                QStringLiteral("item\tOmar Haddad, online")}));
    QVERIFY(!QAccessible::accessibleInterface(cardId));
    QVERIFY(!QAccessible::accessibleInterface(acceptId));
}

/**
 * A view may put in a viewport of its own, without the object name Qt gives the one it makes:
 * QGraphicsView does, under the transcript. The lines are found all the same.
 */
void TestAirListView::theViewportIsFoundWhateverItIsCalled()
{
    AirListView list;
    list.resize(352, 700);
    QWidget* const viewport = list.viewport();
    viewport->setObjectName(QString());
    list.show();
    list.setSnapshot(cardsOverAConversation(), QString());

    QAccessibleInterface* const found = QAccessible::queryAccessibleInterface(viewport);
    QVERIFY(found);
    QCOMPARE(found->role(), QAccessible::List);
    QCOMPARE(found->childCount() >= list.spokenIds().size(), true);
    QVERIFY(AirAccessible::spokenTree(&list).contains(QStringLiteral("item\tOmar Haddad, online")));

    // A widget that is no viewport of the list is left to Qt.
    QWidget other(&list);
    QAccessibleInterface* const plain = QAccessible::queryAccessibleInterface(&other);
    QVERIFY(plain);
    QVERIFY(plain->role() != QAccessible::List);
}

/**
 * The list refreshes every half minute to keep times like "3 h ago" true. When that moves no row,
 * nothing slides: the list paints once, where it painted a frame every 16 ms for the whole slide.
 */
void TestAirListView::aRefreshThatMovesNothingPaintsOnce()
{
    if (AirStyle::instance().reducedMotion()) {
        QSKIP("Reduce motion is on: rows never slide, so no refresh starts frames either way");
    }
    AirListView list;
    list.resize(352, 700);
    list.show();
    QVERIFY(QTest::qWaitForWindowExposed(&list));
    list.setSnapshot(cardsOverAConversation(), QString());
    // The first rows come in one after another; the reveal and the stagger are over long before.
    QTest::qWait(1500);

    PaintCount count(list.viewport());
    list.setSnapshot(cardsOverAConversation(), QString());
    QTest::qWait(AirStyle::instance().duration(AirTokens::Motion::layout) + 200);
    QVERIFY2(count.paints <= 2, qPrintable(QStringLiteral("%1 paints").arg(count.paints)));
}

int main(int argc, char* argv[])
{
    // The widgets need a platform, not a screen: CI runs the tests without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    QApplication app(argc, argv);
    TestAirListView test;
    return QTest::qExec(&test, argc, argv);
}

#include "airlistview_test.moc"
