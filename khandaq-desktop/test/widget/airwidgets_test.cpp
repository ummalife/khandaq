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

#include "src/widget/air/airwidgets.h"

#include <QApplication>
#include <QDialog>
#include <QGraphicsProxyWidget>
#include <QGraphicsScene>
#include <QGraphicsView>
#include <QMenu>
#include <QScreen>
#include <QScrollArea>
#include <QSet>
#include <QtTest/QtTest>

namespace {

/** The columns a menu lays its entries out in. */
int columnsOf(QMenu& menu)
{
    QSet<int> starts;
    for (QAction* const action : menu.actions()) {
        starts.insert(menu.actionGeometry(action).x());
    }
    return starts.size();
}

/** Gives menu count entries. */
void fill(QMenu& menu, int count)
{
    for (int i = 0; i < count; ++i) {
        menu.addAction(QStringLiteral("Language %1").arg(i));
    }
}

/** A widget Tab stops on, named for the test. */
QWidget* stop(const QString& name, QWidget* parent)
{
    auto* const widget = new QWidget(parent);
    widget->setObjectName(name);
    widget->setFocusPolicy(Qt::StrongFocus);
    return widget;
}

/** The names of the stops Tab reaches from first on, once round the window. */
QStringList tabOrder(QWidget* first)
{
    QStringList names{first->objectName()};
    for (QWidget* widget = first->nextInFocusChain(); widget && widget != first;
         widget = widget->nextInFocusChain()) {
        if (widget->focusPolicy() & Qt::TabFocus) {
            names.append(widget->objectName());
        }
    }
    return names;
}

/**
 * A window with a transcript in it, as the chat window has: a view whose scene holds an entry
 * through a proxy, and a button inside the entry.
 */
struct Transcript
{
    QWidget window;
    QGraphicsView* view = nullptr;
    QGraphicsScene scene;
    QWidget* entry = nullptr;
    QWidget* button = nullptr;

    Transcript()
    {
        window.resize(800, 600);
        view = new QGraphicsView(&window);
        view->setGeometry(200, 0, 600, 600);
        view->setScene(&scene);
        entry = new QWidget;
        entry->resize(320, 96);
        button = new QWidget(entry);
        scene.addWidget(entry);
    }
};

} // namespace

class TestAirWidgets : public QObject
{
    Q_OBJECT
private slots:
    void initTestCase();
    void aLongMenuScrollsInOneColumn();
    void aShortMenuKeepsItsSize();
    void rowsMadeLaterStandWhereTheirListStands();
    void rowsMadeAfreshStayUnderTheChevron();
    void aControlComesIntoViewInEitherDirection();
    void anEntryOfATranscriptIsShownInTheWindowOfItsView();
    void aWidgetOutsideAnySceneKeepsItsWindow();
    void aSceneInsideASceneLeadsToTheOuterWindow();
    void aSceneThatNoViewShowsHasNoWindow();
    void theViewOnScreenGivesTheWindow();
    void aDialogOverTheWindowStaysOutOfTheScene();
};

void TestAirWidgets::initTestCase()
{
    // Fusion spreads a long menu over columns, as the Windows styles do.
    QApplication::setStyle(QStringLiteral("Fusion"));
}

void TestAirWidgets::aLongMenuScrollsInOneColumn()
{
    // The main window carries a style sheet, and a menu's own sheet comes in under it.
    QWidget window;
    window.setStyleSheet(QStringLiteral("QLabel { color: black; }"));
    const QRect screen = QGuiApplication::primaryScreen()->availableGeometry();
    constexpr int entries = 120;

    QMenu columns(&window);
    fill(columns, entries);
    columns.popup(screen.topLeft());
    // Without the scrolling the menu breaks into columns: this is what the helper prevents.
    QVERIFY(columnsOf(columns) > 1);
    columns.hide();

    QMenu scrolling(&window);
    fill(scrolling, entries);
    AirPaint::scrollWhenLong(scrolling);
    scrolling.popup(screen.topLeft());
    QCOMPARE(columnsOf(scrolling), 1);
    QVERIFY(scrolling.height() <= screen.height());
    scrolling.hide();
}

void TestAirWidgets::aShortMenuKeepsItsSize()
{
    QWidget window;
    window.setStyleSheet(QStringLiteral("QLabel { color: black; }"));
    QMenu plain(&window);
    fill(plain, 3);
    QMenu scrolling(&window);
    fill(scrolling, 3);
    AirPaint::scrollWhenLong(scrolling);
    QCOMPARE(scrolling.sizeHint(), plain.sizeHint());
    QCOMPARE(columnsOf(scrolling), 1);
}

void TestAirWidgets::rowsMadeLaterStandWhereTheirListStands()
{
    // A search field, the list under it and a button under the list, made in that order.
    QWidget window;
    QWidget* const search = stop(QStringLiteral("search"), &window);
    auto* const holder = new QWidget(&window);
    stop(QStringLiteral("leave"), &window);

    // The rows come later, as a list makes them afresh: Qt puts them at the end of the chain.
    stop(QStringLiteral("first row"), holder);
    stop(QStringLiteral("second row"), holder);
    QCOMPARE(tabOrder(search),
             (QStringList{QStringLiteral("search"), QStringLiteral("leave"),
                          QStringLiteral("first row"), QStringLiteral("second row")}));

    AirFocus::inPlace(holder);
    QCOMPARE(tabOrder(search), (QStringList{QStringLiteral("search"), QStringLiteral("first row"),
                                            QStringLiteral("second row"), QStringLiteral("leave")}));

    // Made afresh once more, they take their place again; asking twice changes nothing.
    delete holder->findChild<QWidget*>(QStringLiteral("first row"));
    stop(QStringLiteral("third row"), holder);
    AirFocus::inPlace(holder);
    AirFocus::inPlace(holder);
    QCOMPARE(tabOrder(search), (QStringList{QStringLiteral("search"), QStringLiteral("second row"),
                                            QStringLiteral("third row"), QStringLiteral("leave")}));
}

void TestAirWidgets::rowsMadeAfreshStayUnderTheChevron()
{
    // As in a conversation: the transcript, the composer, whose field was made elsewhere and put
    // between its buttons, and the tray made last, its chevron over its rows.
    QWidget window;
    QWidget* const transcript = stop(QStringLiteral("transcript"), &window);
    auto* const composer = new QWidget(&window);
    QWidget* const attach = stop(QStringLiteral("attach"), composer);
    QWidget* const send = stop(QStringLiteral("send"), composer);
    QWidget* const field = stop(QStringLiteral("field"), nullptr);
    field->setParent(composer);
    QWidget::setTabOrder(attach, field);
    QWidget::setTabOrder(field, send);
    auto* const tray = new QWidget(&window);
    stop(QStringLiteral("chevron"), tray);
    auto* const list = new QWidget(tray);
    const auto orderTabs = [transcript, tray, composer] {
        QList<QWidget*> order{transcript};
        order << AirFocus::made(tray) << AirFocus::stops(composer);
        AirFocus::chain(order);
    };

    stop(QStringLiteral("row 1"), list);
    orderTabs();
    QCOMPARE(tabOrder(transcript), (QStringList{QStringLiteral("transcript"), QStringLiteral("chevron"),
                                                QStringLiteral("row 1"), QStringLiteral("attach"),
                                                QStringLiteral("field"), QStringLiteral("send")}));

    // The tray makes its rows afresh: in the order of the chain they would come first.
    delete list->findChild<QWidget*>(QStringLiteral("row 1"));
    stop(QStringLiteral("row 2"), list);
    stop(QStringLiteral("row 3"), list);
    QCOMPARE(AirFocus::stops(tray).first()->objectName(), QStringLiteral("row 2"));
    orderTabs();
    QCOMPARE(tabOrder(transcript),
             (QStringList{QStringLiteral("transcript"), QStringLiteral("chevron"),
                          QStringLiteral("row 2"), QStringLiteral("row 3"), QStringLiteral("attach"),
                          QStringLiteral("field"), QStringLiteral("send")}));
}

void TestAirWidgets::aControlComesIntoViewInEitherDirection()
{
    for (const Qt::LayoutDirection direction : {Qt::LeftToRight, Qt::RightToLeft}) {
        // A pane narrower and lower than what it holds, with a control at each far corner. A
        // right-to-left pane opens on the right of it, away from the control on the left.
        QScrollArea area;
        area.setLayoutDirection(direction);
        area.setFrameShape(QFrame::NoFrame);
        area.setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
        area.setVerticalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
        auto* const content = new QWidget;
        content->setFixedSize(900, 600);
        auto* const nearCorner = new QWidget(content);
        nearCorner->setGeometry(20, 20, 100, 30);
        auto* const farCorner = new QWidget(content);
        farCorner->setGeometry(780, 550, 100, 30);
        area.setWidget(content);
        area.resize(300, 200);
        area.show();
        QVERIFY(QTest::qWaitForWindowExposed(&area));
        for (QWidget* const control : {nearCorner, farCorner, nearCorner}) {
            AirFocus::bringIntoView(&area, control, 16);
            const QRect inView(control->mapTo(area.viewport(), QPoint(0, 0)), control->size());
            QVERIFY2(area.viewport()->rect().contains(inView),
                     qPrintable(QStringLiteral("direction %1, control at %2,%3")
                                    .arg(direction)
                                    .arg(inView.x())
                                    .arg(inView.y())));
        }
    }
}

/**
 * To Qt an entry of a transcript is a window of its own; what is asked or told over it belongs to
 * the window its view stands in, from the entry and from anything inside it.
 */
void TestAirWidgets::anEntryOfATranscriptIsShownInTheWindowOfItsView()
{
    Transcript transcript;
    transcript.window.show();
    QCOMPARE(transcript.entry->window(), transcript.entry);
    QCOMPARE(transcript.button->window(), transcript.entry);
    QCOMPARE(AirWindow::of(transcript.entry), &transcript.window);
    QCOMPARE(AirWindow::of(transcript.button), &transcript.window);
    QCOMPARE(AirWindow::of(transcript.view), &transcript.window);
}

void TestAirWidgets::aWidgetOutsideAnySceneKeepsItsWindow()
{
    QWidget window;
    auto* const field = new QWidget(&window);
    QCOMPARE(AirWindow::of(field), &window);
    QCOMPARE(AirWindow::of(&window), &window);
    QWidget alone;
    QCOMPARE(AirWindow::of(&alone), &alone);
    QVERIFY(AirWindow::of(nullptr) == nullptr);
}

void TestAirWidgets::aSceneInsideASceneLeadsToTheOuterWindow()
{
    Transcript transcript;
    // A view that is itself an entry of the transcript, with an entry of its own.
    auto* const innerView = new QGraphicsView;
    auto* const innerScene = new QGraphicsScene(innerView);
    innerView->setScene(innerScene);
    auto* const innerEntry = new QWidget;
    innerScene->addWidget(innerEntry);
    transcript.scene.addWidget(innerView);
    transcript.window.show();
    QCOMPARE(innerView->window(), innerView);
    QCOMPARE(AirWindow::of(innerEntry), &transcript.window);
}

void TestAirWidgets::aSceneThatNoViewShowsHasNoWindow()
{
    QGraphicsScene scene;
    auto* const entry = new QWidget;
    scene.addWidget(entry);
    QVERIFY(AirWindow::of(entry) == nullptr);
}

void TestAirWidgets::theViewOnScreenGivesTheWindow()
{
    Transcript transcript;
    // The scene shown in two windows; only the second is on screen.
    QWidget other;
    auto* const otherView = new QGraphicsView(&other);
    otherView->setScene(&transcript.scene);
    QCOMPARE(transcript.scene.views().first(), transcript.view);
    other.show();
    QCOMPARE(AirWindow::of(transcript.entry), &other);
    // With none on screen, the first view's window.
    other.hide();
    QCOMPARE(AirWindow::of(transcript.entry), &transcript.window);
}

/**
 * Qt takes a dialog whose parent is an entry into the scene with it: the save dialog of a received
 * file went into the transcript. Given the window of the view, it stays a window of the screen.
 */
void TestAirWidgets::aDialogOverTheWindowStaysOutOfTheScene()
{
    Transcript transcript;
    transcript.window.show();
    QDialog inTheScene(transcript.entry);
    QVERIFY(inTheScene.graphicsProxyWidget() != nullptr);
    QDialog overTheWindow(AirWindow::of(transcript.entry));
    QVERIFY(overTheWindow.graphicsProxyWidget() == nullptr);
    QCOMPARE(overTheWindow.parentWidget(), &transcript.window);
}

QTEST_MAIN(TestAirWidgets)
#include "airwidgets_test.moc"
