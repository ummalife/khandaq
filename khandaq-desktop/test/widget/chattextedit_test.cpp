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
#include <QSignalSpy>
#include <QTextBlock>
#include <QTextLayout>
#include <QtTest/QtTest>

class TestChatTextEdit : public QObject
{
    Q_OBJECT
private slots:
    void aJoinedEmojiIsDrawnInTheEmojiFont();
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
