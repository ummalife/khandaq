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

#include "src/widget/air/airtray.h"
#include "src/persistence/smileypack.h"

#include <QLabel>
#include <QSignalSpy>
#include <QTextDocument>
#include <QVBoxLayout>
#include <QtTest/QtTest>

namespace {

/** The share of its column the tray of a conversation takes at most (chatform.cpp). */
constexpr qreal share = 0.28;

QVector<AirQueueItem> waiting(int count)
{
    QVector<AirQueueItem> items;
    for (int i = 0; i < count; ++i) {
        AirQueueItem item;
        item.id = i + 1;
        item.time = QStringLiteral("9:%1").arg(40 + i);
        item.text = QStringLiteral("Message %1").arg(i + 1);
        items.append(item);
    }
    return items;
}

} // namespace

class TestAirTray : public QObject
{
    Q_OBJECT
private slots:
    void aShortColumnKeepsWhatStandsAroundTheTray();
    void aRowDrawsAJoinedEmojiWholeAndKeepsItsClicks();
};

/**
 * In a column too short for the tray's share, the tray's messages take the room the transcript and
 * the field leave them and scroll, instead of squeezing the column below what it needs.
 */
void TestAirTray::aShortColumnKeepsWhatStandsAroundTheTray()
{
    // A column like a conversation's: the transcript, the tray and the field under it. The window
    // places it as the main window's layout does at its least size, without growing it to what it
    // asks for.
    QWidget window;
    auto* const column = new QWidget(&window);
    auto* const layout = new QVBoxLayout(column);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->setSpacing(0);
    auto* const transcript = new QWidget(column);
    transcript->setMinimumHeight(50);
    transcript->setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Expanding);
    auto* const tray = new AirTray(column);
    auto* const field = new QWidget(column);
    field->setMinimumHeight(140);
    layout->addWidget(transcript);
    layout->addWidget(tray);
    layout->addWidget(field);
    tray->limitTo(column, share);
    window.resize(420, 700);
    column->setGeometry(0, 0, 420, 700);
    window.show();
    QVERIFY(QTest::qWaitForWindowExposed(&window));
    tray->setItems(waiting(6), QStringLiteral("Omar"), false);

    // A tall column: the share holds, and everything has the height it needs.
    QTRY_VERIFY(column->minimumSizeHint().height() <= column->height());

    // A short one: what stands around the tray keeps its height, and the tray is not cut either.
    column->setGeometry(0, 0, 420, 300);
    QTRY_VERIFY2(column->minimumSizeHint().height() <= column->height(),
                 qPrintable(QStringLiteral("the column needs %1 in %2")
                                .arg(column->minimumSizeHint().height())
                                .arg(column->height())));
    QTRY_VERIFY(tray->height() >= tray->minimumSizeHint().height());
    QVERIFY(transcript->height() >= transcript->minimumHeight());
    QVERIFY(field->height() >= field->minimumHeight());
}

/**
 * KHANDAQ (QA live 2026-11-14): the row of a message on its way drew 👨‍⚕️ as 👨 and ⚕. Its words are
 * rich text with the sequence in the emoji font; they read as typed, and a click or the context
 * menu on them still opens what can be done with the message.
 */
void TestAirTray::aRowDrawsAJoinedEmojiWholeAndKeepsItsClicks()
{
    const QString doctor =
        QStringLiteral("👨") + QChar(0x200D) + QStringLiteral("⚕") + QChar(0xFE0F);
    AirQueueItem item;
    item.id = 7;
    item.time = QStringLiteral("9:41");
    item.text = QStringLiteral("<b>a & b</b> ") + doctor + QStringLiteral("\nsecond line");
    QWidget window;
    auto* const row = new AirQueueRow(item, Air::EntryStatus::Transit, &window);
    window.resize(560, 120);
    row->setGeometry(0, 0, 560, 120);
    window.show();
    QVERIFY(QTest::qWaitForWindowExposed(&window));

    QLabel* words = nullptr;
    for (QLabel* label : row->findChildren<QLabel*>()) {
        if (label->textFormat() == Qt::RichText) {
            words = label;
        }
    }
    QVERIFY(words);
    QTextDocument shown;
    shown.setHtml(words->text());
    QCOMPARE(shown.toPlainText(), item.text);
    QVERIFY(words->text().contains(SmileyPack::getInEmojiFont(doctor, 0)));

    QSignalSpy actions(row, &AirQueueRow::actionsRequested);
    const QPoint onTheWords = words->mapTo(&window, QPoint(4, words->height() / 2));
    QTest::mouseClick(window.windowHandle(), Qt::LeftButton, Qt::NoModifier, onTheWords);
    QTRY_COMPARE(actions.count(), 1);
    QCOMPARE(actions.first().first().toLongLong(), item.id);
    QTest::mouseClick(window.windowHandle(), Qt::RightButton, Qt::NoModifier, onTheWords);
    QTRY_COMPARE(actions.count(), 2);
}

QTEST_MAIN(TestAirTray)
#include "airtray_test.moc"
