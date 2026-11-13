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

#include "src/widget/tool/chatquotepreview.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"
#include "src/widget/air/airwidgets.h"

#include <QApplication>
#include <QImage>
#include <QSignalSpy>
#include <QVBoxLayout>
#include <QtTest/QtTest>

using AirTokens::Color;
using AirTokens::TextRole;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;

namespace {
/** A composer column of the given width with the quote above where the field would be. */
struct Column
{
    explicit Column(int width)
    {
        auto* const layout = new QVBoxLayout(&host);
        layout->setContentsMargins(0, 0, 0, 0);
        preview = new ChatQuotePreview(&host);
        layout->addWidget(preview);
        layout->addStretch();
        host.resize(width, 300);
        host.show();
    }

    QWidget host;
    ChatQuotePreview* preview = nullptr;
};

AirIconButton* closeOf(ChatQuotePreview* preview)
{
    return preview->findChild<AirIconButton*>(QStringLiteral("quotePreviewClose"));
}

int heightFor(int lines)
{
    const AirStyle& air = AirStyle::instance();
    return Space::s8 + air.lineHeight(TextRole::Caption) + Space::s2
           + lines * air.lineHeight(TextRole::Body) + Space::s8;
}

const QString longQuote = QStringLiteral(
    "Ассаляму алейкум всем! Материалы к четвергу в закрепе, а вторую часть доклада возьмёт "
    "Fatima, если успеет до среды. Список литературы лежит в общей папке, там же расписание "
    "встреч до конца месяца и ссылки на записи прошлых занятий.");
} // namespace

class TestChatQuotePreview : public QObject
{
    Q_OBJECT
private slots:
    void staysHiddenWithoutAQuote();
    void showsAQuoteAndTakesItAway();
    void takesOneLineOrTwo();
    void readsLineBreaksAsSpaces();
    void standsOnItsGroundWithoutFrameOrStripe();
    void closeIsAnIconWithAName();
    void closeStandsAtTheEndEdge();
};

void TestChatQuotePreview::staysHiddenWithoutAQuote()
{
    Column column(480);
    QVERIFY(!column.preview->isVisible());
    QSignalSpy changed(column.preview, &ChatQuotePreview::changed);
    column.preview->setQuoteText(QStringLiteral("   \n "));
    QVERIFY(!column.preview->hasQuote());
    QVERIFY(!column.preview->isVisible());
    QCOMPARE(changed.count(), 0);
}

void TestChatQuotePreview::showsAQuoteAndTakesItAway()
{
    Column column(480);
    QSignalSpy changed(column.preview, &ChatQuotePreview::changed);
    QSignalSpy cleared(column.preview, &ChatQuotePreview::cleared);
    column.preview->setQuoteText(QStringLiteral("  Материалы к четвергу в закрепе \n"));
    QVERIFY(column.preview->isVisible());
    QCOMPARE(column.preview->quoteText(), QStringLiteral("Материалы к четвергу в закрепе"));
    QCOMPARE(changed.count(), 1);

    AirIconButton* const close = closeOf(column.preview);
    QVERIFY(close);
    close->click();
    QVERIFY(!column.preview->hasQuote());
    QVERIFY(!column.preview->isVisible());
    QCOMPARE(cleared.count(), 1);
    QCOMPARE(changed.count(), 2);
}

void TestChatQuotePreview::takesOneLineOrTwo()
{
    Column column(480);
    column.preview->setQuoteText(QStringLiteral("Материалы к четвергу"));
    QCOMPARE(column.preview->shownLines(480).size(), 1);
    QCOMPARE(column.preview->heightForWidth(480), heightFor(1));
    QTRY_COMPARE(column.preview->height(), heightFor(1));

    column.preview->setQuoteText(longQuote);
    const QStringList lines = column.preview->shownLines(480);
    QCOMPARE(lines.size(), 2);
    // The second line is cut short: the words go on past it.
    QVERIFY(!longQuote.endsWith(lines.last()));
    QCOMPARE(column.preview->heightForWidth(480), heightFor(2));
    QTRY_COMPARE(column.preview->height(), heightFor(2));

    // Wide enough, the same words take fewer lines and the quote grows no taller than two.
    QVERIFY(column.preview->heightForWidth(4000) <= heightFor(2));
}

void TestChatQuotePreview::readsLineBreaksAsSpaces()
{
    Column column(480);
    column.preview->setQuoteText(QStringLiteral("Материалы\nк четвергу"));
    QCOMPARE(column.preview->shownLines(480), QStringList{QStringLiteral("Материалы к четвергу")});
}

void TestChatQuotePreview::standsOnItsGroundWithoutFrameOrStripe()
{
    Column column(480);
    column.preview->setQuoteText(QStringLiteral("Материалы к четвергу"));
    QTRY_COMPARE(column.preview->height(), heightFor(1));
    const QImage image = column.preview->grab().toImage();
    const qreal ratio = image.devicePixelRatio();
    const QColor sunken = AirStyle::instance().color(Color::Sunken);
    const int middle = qRound(column.preview->height() / 2.0 * ratio);
    // The start edge, where the old style put a green stripe, and the top and bottom edges, where
    // it put a frame: all ground.
    for (int x : {1, 2, 3, Space::s8}) {
        QCOMPARE(image.pixelColor(qRound(x * ratio), middle), sunken);
    }
    const int centre = qRound(column.preview->width() / 2.0 * ratio);
    const int inside = qCeil(ratio);
    QCOMPARE(image.pixelColor(centre, inside), sunken);
    QCOMPARE(image.pixelColor(centre, image.height() - 1 - inside), sunken);
    // The outermost pixels are the ground's antialiased edge, never a line of another colour.
    for (int y : {0, image.height() - 1}) {
        const QColor edge = image.pixelColor(centre, y);
        QVERIFY2(qAbs(edge.red() - sunken.red()) <= 8 && qAbs(edge.green() - sunken.green()) <= 8
                     && qAbs(edge.blue() - sunken.blue()) <= 8,
                 qPrintable(edge.name()));
    }
}

void TestChatQuotePreview::closeIsAnIconWithAName()
{
    Column column(480);
    AirIconButton* const close = closeOf(column.preview);
    QVERIFY(close);
    // A line icon, not a multiplication sign: the button has no text of its own.
    QVERIFY(close->text().isEmpty());
    QCOMPARE(close->accessibleName(), QStringLiteral("Cancel quote"));
    QCOMPARE(close->toolTip(), QStringLiteral("Cancel quote"));
    QCOMPARE(column.preview->accessibleName(), QStringLiteral("Quote"));
    column.preview->setQuoteText(QStringLiteral("Материалы к четвергу"));
    QCOMPARE(column.preview->accessibleDescription(), QStringLiteral("Материалы к четвергу"));
}

void TestChatQuotePreview::closeStandsAtTheEndEdge()
{
    Column column(480);
    column.preview->setQuoteText(QStringLiteral("Материалы к четвергу"));
    QTRY_COMPARE(column.preview->height(), heightFor(1));
    AirIconButton* const close = closeOf(column.preview);
    QCOMPARE(close->geometry().right(), column.preview->width() - Space::s4 - 1);
    QCOMPARE(close->size(), QSize(Size::control, Size::control));

    column.host.setLayoutDirection(Qt::RightToLeft);
    QTRY_COMPARE(close->geometry().left(), Space::s4);
}

int main(int argc, char* argv[])
{
    // The widgets need a platform, not a screen: CI runs the tests without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    QApplication app(argc, argv);
    // The Plex faces, as the app has them: the line heights the quote takes are theirs.
    AirStyle::installFonts();
    TestChatQuotePreview test;
    return QTest::qExec(&test, argc, argv);
}

#include "chatquotepreview_test.moc"
