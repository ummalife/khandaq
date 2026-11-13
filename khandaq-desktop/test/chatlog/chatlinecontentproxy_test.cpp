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

#include "src/chatlog/chatlinecontentproxy.h"

#include <QApplication>
#include <QGraphicsScene>
#include <QVBoxLayout>
#include <QWidget>
#include <QtTest/QtTest>

namespace {

/**
 * A widget sized the way a file entry is: its height follows the parts it shows, here a row of
 * 36 px over a row of 28 px, 6 px apart.
 */
class Entry : public QWidget
{
public:
    Entry()
    {
        auto* const column = new QVBoxLayout(this);
        column->setContentsMargins(0, 0, 0, 0);
        column->setSpacing(6);
        top->setFixedHeight(36);
        bottom->setFixedHeight(28);
        column->addWidget(top);
        column->addWidget(bottom);
    }

    bool hasHeightForWidth() const override
    {
        return true;
    }

    int heightForWidth(int width) const override
    {
        const int height = layout()->heightForWidth(width);
        return height >= 0 ? height : layout()->sizeHint().height();
    }

    QWidget* const top = new QWidget(this);
    QWidget* const bottom = new QWidget(this);
};

} // namespace

class TestChatLineContentProxy : public QObject
{
    Q_OBJECT
private slots:
    void theLineTakesTheHeightOfWhatTheEntryShows_data();
    void theLineTakesTheHeightOfWhatTheEntryShows();
};

void TestChatLineContentProxy::theLineTakesTheHeightOfWhatTheEntryShows_data()
{
    QTest::addColumn<bool>("eventsRun");
    QTest::newRow("at once") << false;
    QTest::newRow("after the events ran") << true;
}

/**
 * A voice note that arrives loses its buttons: the line must shrink with it, not keep the height
 * the entry had while it showed them, and grow again when a part comes back.
 */
void TestChatLineContentProxy::theLineTakesTheHeightOfWhatTheEntryShows()
{
    QFETCH(bool, eventsRun);
    QGraphicsScene scene;
    Entry* const entry = new Entry;
    auto* const line = new ChatLineContentProxy(entry, 100);
    scene.addItem(line);
    // The proxy adds 5 px under the widget.
    const qreal below = 5.0;

    line->setWidth(400);
    QCOMPARE(entry->size(), QSize(400, 70));
    QCOMPARE(line->boundingRect().height(), 70.0 + below);

    entry->bottom->hide();
    if (eventsRun) {
        QCoreApplication::processEvents();
    }
    line->setWidth(400);
    QCOMPARE(entry->size(), QSize(400, 36));
    QCOMPARE(line->boundingRect().height(), 36.0 + below);

    entry->bottom->show();
    if (eventsRun) {
        QCoreApplication::processEvents();
    }
    line->setWidth(400);
    QCOMPARE(entry->size(), QSize(400, 70));
    QCOMPARE(line->boundingRect().height(), 70.0 + below);
}

int main(int argc, char* argv[])
{
    // The widgets need a platform, not a screen: CI runs the tests without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    QApplication app(argc, argv);
    TestChatLineContentProxy test;
    return QTest::qExec(&test, argc, argv);
}

#include "chatlinecontentproxy_test.moc"
