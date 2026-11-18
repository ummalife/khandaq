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
    void showsAReplyOnOneLineUnderWhomItAnswers();
    void aReplyToYourOwnSaysYouAndSendsYourName();
    void aReplyAndAQuoteTakeEachOthersPlace();
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

namespace {
Air::ReplyMeta replyTo(const QString& name, const QString& words)
{
    Air::ReplyMeta meta;
    meta.localId = 42;
    meta.authorKey =
        QStringLiteral("0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF");
    meta.timeMs = 1763380800123;
    meta.authorName = name;
    meta.words = words;
    return meta;
}
} // namespace

/** KHANDAQ (0.2.58): "In reply to Yusuf" over one line of the words, as on the phones. */
void TestChatQuotePreview::showsAReplyOnOneLineUnderWhomItAnswers()
{
    Column column(480);
    QSignalSpy changed(column.preview, &ChatQuotePreview::changed);
    column.preview->setReply(replyTo(QStringLiteral("Yusuf"),
                                     longQuote + QStringLiteral("\n") + longQuote),
                             QStringLiteral("Yusuf"));
    QVERIFY(column.preview->isVisible());
    QVERIFY(column.preview->hasQuote());
    QVERIFY(column.preview->isReply());
    QCOMPARE(changed.count(), 1);
    QCOMPARE(column.preview->accessibleName(), QStringLiteral("In reply to Yusuf"));
    // The words it shows are the quote the header carries: on one line, cut at 200 characters.
    const QString quote = Air::replyQuote(longQuote + QStringLiteral("\n") + longQuote);
    QCOMPARE(column.preview->quoteText(), quote);
    QCOMPARE(quote.size(), 200);
    QCOMPARE(column.preview->accessibleDescription(), quote);
    QCOMPARE(column.preview->shownLines(480).size(), 1);
    QTRY_COMPARE(column.preview->height(), heightFor(1));

    AirIconButton* const close = closeOf(column.preview);
    QCOMPARE(close->toolTip(), QStringLiteral("Cancel reply"));
    QCOMPARE(close->accessibleName(), QStringLiteral("Cancel reply"));
    // Close takes the reply away.
    QSignalSpy cleared(column.preview, &ChatQuotePreview::cleared);
    close->click();
    QVERIFY(!column.preview->hasQuote());
    QVERIFY(!column.preview->isReply());
    QVERIFY(!column.preview->isVisible());
    QCOMPARE(cleared.count(), 1);
    QCOMPARE(changed.count(), 2);
    QCOMPARE(close->toolTip(), QStringLiteral("Cancel quote"));
}

/** The bar says "You"; the header names you as the others know you, never "You" (iOS 0.2.56). */
void TestChatQuotePreview::aReplyToYourOwnSaysYouAndSendsYourName()
{
    Column column(480);
    column.preview->setReply(replyTo(QStringLiteral("Amina"),
                                     QStringLiteral("Материалы к четвергу")),
                             QStringLiteral("Amina"), true);
    QCOMPARE(column.preview->accessibleName(), QStringLiteral("In reply to You"));
    const QString head = Air::replyHead(column.preview->reply());
    QVERIFY(head.startsWith(QStringLiteral("[KQ|42|89ABCDEF|1763380800123|Amina]\n")));
    QVERIFY(!head.contains(QStringLiteral("You")));
    QCOMPARE(Air::readMessageText(head + QStringLiteral("ok")).replyName, QStringLiteral("Amina"));
}

/** One waits at a time: a reply takes the place of a quote, and a quote of a reply. */
void TestChatQuotePreview::aReplyAndAQuoteTakeEachOthersPlace()
{
    Column column(480);
    column.preview->setQuoteText(QStringLiteral("Материалы к четвергу"));
    column.preview->setReply(replyTo(QStringLiteral("Yusuf"), QStringLiteral("at five")),
                             QStringLiteral("Yusuf"));
    QVERIFY(column.preview->isReply());
    QCOMPARE(column.preview->quoteText(), QStringLiteral("at five"));
    // A new reply replaces the one before it.
    column.preview->setReply(replyTo(QStringLiteral("Mariam"), QStringLiteral("after the prayer")),
                             QStringLiteral("Mariam"));
    QCOMPARE(column.preview->accessibleName(), QStringLiteral("In reply to Mariam"));
    QCOMPARE(column.preview->reply().authorName, QStringLiteral("Mariam"));

    column.preview->setQuoteText(QStringLiteral("Материалы к четвергу"));
    QVERIFY(!column.preview->isReply());
    QCOMPARE(column.preview->accessibleName(), QStringLiteral("Quote"));
    QCOMPARE(closeOf(column.preview)->toolTip(), QStringLiteral("Cancel quote"));

    // A reply with no words to quote is no reply: what waited goes.
    column.preview->setReply(replyTo(QStringLiteral("Yusuf"), QStringLiteral(" \n ")),
                             QStringLiteral("Yusuf"));
    QVERIFY(!column.preview->hasQuote());
    QVERIFY(!column.preview->isVisible());

    // Clearing from outside, as Escape does, takes a reply away as well.
    column.preview->setReply(replyTo(QStringLiteral("Yusuf"), QStringLiteral("at five")),
                             QStringLiteral("Yusuf"));
    column.preview->clearQuote();
    QVERIFY(!column.preview->hasQuote());
    QVERIFY(!column.preview->isReply());
    QCOMPARE(column.preview->accessibleName(), QStringLiteral("Quote"));
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
