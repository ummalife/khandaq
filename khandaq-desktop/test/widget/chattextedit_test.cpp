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

#include "src/widget/tool/chattextedit.h"
#include "src/persistence/smileypack.h"

#include <QApplication>
#include <QClipboard>
#include <QElapsedTimer>
#include <QImage>
#include <QMimeData>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QTextBlock>
#include <QTextLayout>
#include <QUrl>
#include <QtTest/QtTest>

namespace {
/** Puts on the clipboard what a program that copied would put there. */
void copy(const QString& text, bool withPicture, const QString& html = QString())
{
    auto* const data = new QMimeData;
    if (!text.isNull()) {
        data->setText(text);
    }
    if (!html.isNull()) {
        data->setHtml(html);
    }
    if (withPicture) {
        QImage picture(32, 16, QImage::Format_ARGB32);
        picture.fill(Qt::darkGreen);
        data->setImageData(picture);
    }
    QApplication::clipboard()->setMimeData(data);
}

/** What a browser's Copy image puts as the HTML of the picture at address. */
QString imageHtml(const QString& address)
{
    return QStringLiteral("<meta charset='utf-8'><img src=\"") + address
           + QStringLiteral("\" alt=\"A\"/>");
}

/** What Excel puts as the HTML of one cell that holds only a link, in short. */
QString cellHtml(const QString& address)
{
    return QStringLiteral("<html><body><table><tr><!--StartFragment--><td><a href=\"") + address
           + QStringLiteral("\">") + address
           + QStringLiteral("</a></td><!--EndFragment--></tr></table></body></html>");
}

/** Hands field what a paste would, without the clipboard: insertFromMimeData, where it ends. */
void pasteWithoutClipboard(QTextEdit& field, const QMimeData& data)
{
    struct Opener : QTextEdit
    {
        using QTextEdit::insertFromMimeData;
    };
    void (QTextEdit::*const insert)(const QMimeData*) = &Opener::insertFromMimeData;
    (field.*insert)(&data);
}
} // namespace

class TestChatTextEdit : public QObject
{
    Q_OBJECT
private slots:
    void aJoinedEmojiIsDrawnInTheEmojiFont();
    void pasteKindPrefersTextToAPicture();
    void aTextWithAPictureBesideItIsPastedAsText();
    void aPictureWithoutTextIsOffered();
    void pasteKindTakesThePictureBesideOnlyItsAddress();
    void aPictureWithOnlyItsAddressBesideItIsOffered();
    void aCellWithOnlyALinkIsPastedAsItsText();
    void whereNoPictureIsTakenTheTextBesideItIsPasted();
    void aTextIsFilesOnlyWhenEveryLineNamesOne();
    void aFileCopiedInAFileManagerIsSent();
    void aLongTextIsPastedWholeAndTypingOnStaysQuick();
};

/**
 * KHANDAQ (QA live 2026-11-14): the field drew 👨‍⚕️ as 👨 and ⚕. Its joined sequences are drawn in
 * the emoji font; the text itself, and with it what is sent and the typing notice, stays as typed.
 */
void TestChatTextEdit::aJoinedEmojiIsDrawnInTheEmojiFont()
{
    ChatTextEdit edit;
    QSignalSpy changed(&edit, &QTextEdit::textChanged);
    const QString doctor = QStringLiteral("👨") + QChar(0x200D) + QStringLiteral("⚕") + QChar(0xFE0F);
    const QString typed = QStringLiteral("a ") + doctor + QStringLiteral(" b");
    edit.setPlainText(typed);

    QCOMPARE(edit.toPlainText(), typed);
    QCOMPARE(changed.count(), 1);
    const QVector<QTextLayout::FormatRange> formats =
        edit.document()->firstBlock().layout()->formats();
    QCOMPARE(formats.size(), 1);
    QCOMPARE(formats.first().start, 2);
    QCOMPARE(formats.first().length, doctor.size());
    QCOMPARE(formats.first().format.fontFamily(), SmileyPack::emojiFontFamily());

    // Typed on, the sequence keeps its font and the rest has none.
    edit.moveCursor(QTextCursor::End);
    edit.insertPlainText(QStringLiteral(" more"));
    QCOMPARE(changed.count(), 2);
    QCOMPARE(edit.document()->firstBlock().layout()->formats().size(), 1);
}

/**
 * KHANDAQ (0.2.56): Excel, Word and other programs put a picture of what was copied beside its
 * text, and the field took the picture. Files come first, then a text that is more than spaces,
 * and a picture only without one: a browser's Copy image puts a picture and HTML, no text.
 */
void TestChatTextEdit::pasteKindPrefersTextToAPicture()
{
    using Kind = ChatTextEdit::PasteKind;
    QCOMPARE(ChatTextEdit::pasteKind(true, QStringLiteral("report.xlsx"), true), Kind::Files);
    QCOMPARE(ChatTextEdit::pasteKind(false, QStringLiteral("A1\tB1\r\n"), true), Kind::Text);
    QCOMPARE(ChatTextEdit::pasteKind(false, QStringLiteral("слово"), false), Kind::Text);
    QCOMPARE(ChatTextEdit::pasteKind(false, QString(), true), Kind::Image);
    // An empty cell of Excel is a line break and a picture of nothing.
    QCOMPARE(ChatTextEdit::pasteKind(false, QStringLiteral(" \r\n\t"), true), Kind::Image);
    QCOMPARE(ChatTextEdit::pasteKind(false, QString(), false), Kind::Text);
}

void TestChatTextEdit::aTextWithAPictureBesideItIsPastedAsText()
{
    ChatTextEdit edit;
    QSignalSpy pictures(&edit, &ChatTextEdit::pasteImage);
    QSignalSpy files(&edit, &ChatTextEdit::pasteFiles);
    const QString cells = QStringLiteral("Имя\tСумма\nАли\t1:30\n");
    copy(cells, true, QStringLiteral("<table><tr><td>Имя</td></tr></table>"));

    // The key of the paste, then the menu's Paste.
    QTest::keySequence(&edit, QKeySequence::Paste);
    QCOMPARE(edit.toPlainText(), cells);
    edit.paste();
    QCOMPARE(edit.toPlainText(), cells + cells);
    QCOMPARE(pictures.count(), 0);
    QCOMPARE(files.count(), 0);
}

void TestChatTextEdit::aPictureWithoutTextIsOffered()
{
    ChatTextEdit edit;
    QSignalSpy pictures(&edit, &ChatTextEdit::pasteImage);
    copy(QString(), true, QStringLiteral("<img src=\"https://example.org/a.png\">"));

    QTest::keySequence(&edit, QKeySequence::Paste);
    QCOMPARE(pictures.count(), 1);
    QCOMPARE(pictures.first().first().value<QPixmap>().size(), QSize(32, 16));
    QVERIFY(edit.toPlainText().isEmpty());
}

/**
 * KHANDAQ (0.2.56): a browser's Copy image may put the address of the picture as its text, and
 * the link came instead of the picture. A text that is one web, file or data address is the
 * picture's when the HTML beside it shows only a picture; any other text beside a picture is
 * still the text, and so is an address alone, or one whose HTML holds text, as a spreadsheet's.
 */
void TestChatTextEdit::pasteKindTakesThePictureBesideOnlyItsAddress()
{
    using Kind = ChatTextEdit::PasteKind;
    const QString address = QStringLiteral("https://example.org/a.png");
    const QString html = imageHtml(address);
    QCOMPARE(ChatTextEdit::pasteKind(false, address, true, html), Kind::Image);
    QCOMPARE(ChatTextEdit::pasteKind(false, QStringLiteral(" HTTP://EXAMPLE.ORG/A.PNG\r\n"), true,
                                     html),
             Kind::Image);
    QCOMPARE(ChatTextEdit::pasteKind(false, QStringLiteral("file:///tmp/a.png"), true,
                                     imageHtml(QStringLiteral("file:///tmp/a.png"))),
             Kind::Image);
    const QString data = QStringLiteral("data:image/png;base64,iVBORw0KGgo=");
    QCOMPARE(ChatTextEdit::pasteKind(false, data, true, imageHtml(data)), Kind::Image);
    // Windows wraps the HTML of the clipboard; the address of a sharp screen's picture or of a
    // link around it is not the src.
    QCOMPARE(ChatTextEdit::pasteKind(false, QStringLiteral("https://example.org/a@2x.png"), true,
                                     QStringLiteral("<html><body>\r\n<!--StartFragment--><img "
                                                    "src=\"https://example.org/a.png\"><!--"
                                                    "EndFragment-->\r\n</body></html>")),
             Kind::Image);

    QCOMPARE(ChatTextEdit::pasteKind(false, address, false, html), Kind::Text);
    QCOMPARE(ChatTextEdit::pasteKind(false, QStringLiteral("Look: ") + address, true, html),
             Kind::Text);
    QCOMPARE(ChatTextEdit::pasteKind(false, address + QStringLiteral("\n") + address, true, html),
             Kind::Text);
    QCOMPARE(ChatTextEdit::pasteKind(false, QStringLiteral("ftp://example.org/a.png"), true, html),
             Kind::Text);
    QCOMPARE(ChatTextEdit::pasteKind(false, QStringLiteral("example.org/a.png"), true, html),
             Kind::Text);
    // What the code sees beside the picture is not a browser's picture alone.
    QCOMPARE(ChatTextEdit::pasteKind(false, address, true), Kind::Text);
    QCOMPARE(ChatTextEdit::pasteKind(false, address, true, cellHtml(address)), Kind::Text);
    QCOMPARE(ChatTextEdit::pasteKind(false, address, true,
                                     QStringLiteral("<p>Caption</p>") + imageHtml(address)),
             Kind::Text);
    QCOMPARE(ChatTextEdit::pasteKind(false, address, true,
                                     QStringLiteral("<a href=\"") + address
                                         + QStringLiteral("\"></a>")),
             Kind::Text);
}

void TestChatTextEdit::aPictureWithOnlyItsAddressBesideItIsOffered()
{
    ChatTextEdit edit;
    QSignalSpy pictures(&edit, &ChatTextEdit::pasteImage);
    const QString address = QStringLiteral("https://example.org/a.png");
    copy(address, true, QStringLiteral("<img src=\"https://example.org/a.png\">"));

    QTest::keySequence(&edit, QKeySequence::Paste);
    QCOMPARE(pictures.count(), 1);
    QCOMPARE(pictures.first().first().value<QPixmap>().size(), QSize(32, 16));
    QVERIFY(edit.toPlainText().isEmpty());

    // A word beside the address makes it a text again.
    copy(QStringLiteral("Look: ") + address, true);
    QTest::keySequence(&edit, QKeySequence::Paste);
    QCOMPARE(pictures.count(), 1);
    QCOMPARE(edit.toPlainText(), QStringLiteral("Look: ") + address);
}

/**
 * KHANDAQ (0.2.56): Excel and LibreOffice put a picture of the cell beside its text; a cell that
 * holds only a link is the link, not its picture.
 */
void TestChatTextEdit::aCellWithOnlyALinkIsPastedAsItsText()
{
    ChatTextEdit edit;
    QSignalSpy pictures(&edit, &ChatTextEdit::pasteImage);
    const QString address = QStringLiteral("https://example.org/report");
    copy(address + QStringLiteral("\r\n"), true, cellHtml(address));

    QTest::keySequence(&edit, QKeySequence::Paste);
    QCOMPARE(pictures.count(), 0);
    QCOMPARE(edit.toPlainText(), address + QStringLiteral("\n"));
}

/**
 * KHANDAQ (0.2.56): only the chat with a person takes a picture (ChatForm connects pasteImage).
 * Elsewhere, a group among them, a browser's Copy image pasted nothing, its address included:
 * the text beside a picture is pasted there, and a picture alone leaves the field as it was.
 * This field has nothing connected to pasteImage; a QSignalSpy would connect it.
 */
void TestChatTextEdit::whereNoPictureIsTakenTheTextBesideItIsPasted()
{
    ChatTextEdit edit;
    const QString address = QStringLiteral("https://example.org/a.png");
    copy(address, true, imageHtml(address));

    QTest::keySequence(&edit, QKeySequence::Paste);
    QCOMPARE(edit.toPlainText(), address);
    edit.paste();
    QCOMPARE(edit.toPlainText(), address + address);

    copy(QString(), true, imageHtml(address));
    QTest::keySequence(&edit, QKeySequence::Paste);
    edit.paste();
    QCOMPARE(edit.toPlainText(), address + address);

    // Where it is taken, the same paste is the picture.
    QSignalSpy pictures(&edit, &ChatTextEdit::pasteImage);
    copy(address, true, imageHtml(address));
    edit.paste();
    QCOMPARE(pictures.count(), 1);
    QCOMPARE(edit.toPlainText(), address + address);
}

void TestChatTextEdit::aTextIsFilesOnlyWhenEveryLineNamesOne()
{
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    QStringList names;
    for (const QString& name : {QStringLiteral("one.txt"), QStringLiteral("two.txt")}) {
        QFile file(dir.filePath(name));
        QVERIFY(file.open(QIODevice::WriteOnly));
        file.write("x");
        names.append(QUrl::fromLocalFile(QFileInfo(file).absoluteFilePath()).toString());
    }

    ChatTextEdit edit;
    QSignalSpy files(&edit, &ChatTextEdit::pasteFiles);
    copy(names.join(QLatin1Char('\n')), false);
    edit.paste();
    QCOMPARE(files.count(), 1);
    QCOMPARE(files.first().first().toStringList().size(), 2);
    QVERIFY(edit.toPlainText().isEmpty());

    // A path among other lines is part of a text: the text is pasted, no file is sent.
    const QString note =
        QStringLiteral("See the file\n") + names.first() + QStringLiteral("\nThanks");
    copy(note, false);
    edit.paste();
    QCOMPARE(files.count(), 1);
    QCOMPARE(edit.toPlainText(), note);
}

/**
 * KHANDAQ (0.2.60): a file copied in Finder, in Explorer or in a file manager of Linux comes as its
 * file:// address, its name beside it as the text, and is sent. On macOS and Linux it came in as
 * its name: the path of the address passed only as a path of Windows. Spaces, # and % in a name,
 * which the address encodes, come back as they were.
 */
void TestChatTextEdit::aFileCopiedInAFileManagerIsSent()
{
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    QStringList paths;
    QList<QUrl> urls;
    for (const QString& name :
         {QStringLiteral("notes.txt"), QStringLiteral("May report #2 (100%).pdf")}) {
        QFile file(dir.filePath(name));
        QVERIFY(file.open(QIODevice::WriteOnly));
        file.write("x");
        paths.append(QFileInfo(file).absoluteFilePath());
        urls.append(QUrl::fromLocalFile(paths.last()));
    }

    ChatTextEdit edit;
    QSignalSpy files(&edit, &ChatTextEdit::pasteFiles);
    QMimeData copied;
    copied.setUrls(urls);
    copied.setText(QStringLiteral("notes.txt\nMay report #2 (100%).pdf"));
    pasteWithoutClipboard(edit, copied);
    QCOMPARE(files.count(), 1);
    QCOMPARE(files.first().first().toStringList(), paths);
    QVERIFY(edit.toPlainText().isEmpty());

    // A file gone since it was copied leaves the text, as typed.
    QMimeData gone;
    gone.setUrls({QUrl::fromLocalFile(dir.filePath(QStringLiteral("gone.txt")))});
    gone.setText(QStringLiteral("gone.txt"));
    pasteWithoutClipboard(edit, gone);
    QCOMPARE(files.count(), 1);
    QCOMPARE(edit.toPlainText(), QStringLiteral("gone.txt"));
}

/**
 * KHANDAQ (0.2.56): a long text comes into the field whole, and the letters typed after it do not
 * lay it all out again: the field set its width at each change, which froze the window for a
 * third of a second per letter after a paste of 1.5 MB. Typing ten letters now takes less than
 * the one paste; before, each letter took about half of it.
 */
void TestChatTextEdit::aLongTextIsPastedWholeAndTypingOnStaysQuick()
{
    ChatTextEdit edit;
    edit.resize(600, 120);
    edit.show();
    QString text;
    for (int i = 0; i < 20000; ++i) {
        text += QStringLiteral("Line %1: words in English, по-русски, 1:30\t42\n").arg(i);
    }
    copy(text, true);

    QElapsedTimer clock;
    clock.start();
    QTest::keySequence(&edit, QKeySequence::Paste);
    const qint64 pasteMs = clock.elapsed();
    QCOMPARE(edit.toPlainText(), text);

    clock.restart();
    for (int i = 0; i < 10; ++i) {
        QTest::keyClick(&edit, Qt::Key_A);
    }
    const qint64 typingMs = clock.elapsed();
    QCOMPARE(edit.toPlainText(), text + QStringLiteral("aaaaaaaaaa"));
    const QString times =
        QStringLiteral("paste %1 ms, ten letters %2 ms").arg(pasteMs).arg(typingMs);
    QVERIFY2(typingMs <= qMax<qint64>(pasteMs, 50), qPrintable(times));
}

int main(int argc, char* argv[])
{
    // The widgets need a platform, not a screen: CI runs the tests without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    QApplication app(argc, argv);
    TestChatTextEdit test;
    return QTest::qExec(&test, argc, argv);
}

#include "chattextedit_test.moc"
