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

#include "src/widget/air/airband.h"
#include "src/widget/air/airchatparts.h"
#include "src/widget/air/airnewcontact.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airtokens.h"
#include "src/widget/air/airwidgets.h"

#include <QApplication>
#include <QClipboard>
#include <QLabel>
#include <QLineEdit>
#include <QSignalSpy>
#include <QTextEdit>
#include <QtTest/QtTest>

#include <memory>

using AirStrings::Text;

namespace {

/** An ID with a correct check (test/model/airmodel_test.cpp). */
const QString goodAddress =
    QStringLiteral("56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855D34D34D37CB5");
/** The same ID with its last character changed: the check no longer matches. */
const QString badAddress =
    QStringLiteral("56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855D34D34D37CB6");
/** The profile's own ID, also with a correct check. */
const QString ownAddress =
    QStringLiteral("7F3A91C05D2E8B4416A9C3E07D5F2B8164E0A3C9D7B25F1E8A4C6D0B3F9E2A7100000000062A");

/** A window with a list column on its left, which the panel covers. */
struct Window
{
    QWidget host;
    QWidget* column = nullptr;
    std::unique_ptr<AirNewContact> panel;
    QStringList contacts;
    QStringList blocked;

    Window()
    {
        host.resize(1000, 700);
        column = new QWidget(&host);
        column->setGeometry(0, 60, 352, 640);
        AirNewContact::Lookup lookup;
        lookup.ownAddress = [] { return ownAddress; };
        lookup.isContact = [this](const QString& key) { return contacts.contains(key); };
        lookup.hasRequest = [](const QString&) { return false; };
        lookup.isBlocked = [this](const QString& key) { return blocked.contains(key); };
        lookup.contactName = [](const QString&) { return QStringLiteral("Omar Haddad"); };
        panel.reset(new AirNewContact(lookup, column, &host));
    }

    template <typename T>
    T* part(const char* name) const
    {
        T* const found = panel->findChild<T*>(QLatin1String(name));
        if (!found) {
            qWarning() << "No part called" << name;
        }
        return found;
    }

    QString verdict() const
    {
        return part<QLabel>("newContactVerdict")->text();
    }

    bool canSend() const
    {
        return part<AirButton>("newContactSend")->isEnabled();
    }

    bool shows(const char* name) const
    {
        return part<QWidget>(name)->isVisibleTo(panel.get());
    }
};

} // namespace

class TestAirNewContact : public QObject
{
    Q_OBJECT
private slots:
    void theIdFieldKeepsGroupsOfFour();
    void aWholeIdPastedReplacesTheField();
    void theVerdictComesBeforeAnythingIsSent();
    void aContactOpensTheirChat();
    void aMessageOverTheLimitCannotGo();
    void aRequestGoesAndWaitsForThePerson();
    void aRefusedRequestIsSaidInThePanel();
    void aRefusedRequestAfterClosingIsSaidOverTheWindow();
    void theCommandRowSaysWhatIsMissing();
    void theCommandLineHandsTheStartOfAnIdOn();
    void eachOpeningStartsAfresh();
    void theEmptyColumnShowsTheWholeSentence();
};

/**
 * Typed characters land in groups of four with the caret after the last one; whatever is not hex
 * stays out, and Backspace or Delete next to the space between two groups takes a character, never
 * the space alone.
 */
void TestAirNewContact::theIdFieldKeepsGroupsOfFour()
{
    QWidget host;
    auto* const field = new AirTextField(AirTextField::Kind::Id, &host);
    host.resize(400, 200);
    field->setGeometry(0, 0, 320, 92);
    host.show();
    QVERIFY(QTest::qWaitForWindowExposed(&host));
    QTextEdit* const edit = field->edit();
    edit->setFocus();
    QSignalSpy changes(field, &AirTextField::changed);

    QTest::keyClicks(edit, QStringLiteral("56a1 ade4-b6"));
    QCOMPARE(field->text(), QStringLiteral("56A1 ADE4 B6"));
    QCOMPARE(edit->textCursor().position(), field->text().size());
    QVERIFY(!changes.isEmpty());

    QTest::keyClick(edit, Qt::Key_Backspace);
    QTest::keyClick(edit, Qt::Key_Backspace);
    QCOMPARE(field->text(), QStringLiteral("56A1 ADE4"));
    QCOMPARE(edit->textCursor().position(), 9);

    // Just after the space: Backspace takes the last character of the group before it.
    QTextCursor cursor = edit->textCursor();
    cursor.setPosition(5);
    edit->setTextCursor(cursor);
    QTest::keyClick(edit, Qt::Key_Backspace);
    QCOMPARE(field->text(), QStringLiteral("56AA DE4"));
    QCOMPARE(edit->textCursor().position(), 3);

    // Just before the space: Delete takes the first character of the group after it.
    cursor.setPosition(4);
    edit->setTextCursor(cursor);
    QTest::keyClick(edit, Qt::Key_Delete);
    QCOMPARE(field->text(), QStringLiteral("56AA E4"));

    // Enter submits instead of breaking the line; Shift+Enter does nothing to an ID.
    QSignalSpy submits(field, &AirTextField::submitted);
    QTest::keyClick(edit, Qt::Key_Return);
    QTest::keyClick(edit, Qt::Key_Return, Qt::ShiftModifier);
    QCOMPARE(submits.count(), 1);
    QCOMPARE(field->text(), QStringLiteral("56AA E4"));

    // The field grows with an ID of several lines and is never shorter than three lines.
    QCOMPARE(field->heightForWidth(320), AirTokens::Size::idField);
}

/** A paste that holds a whole ID, with a prefix and line breaks, takes the place of the field. */
void TestAirNewContact::aWholeIdPastedReplacesTheField()
{
    QWidget host;
    auto* const field = new AirTextField(AirTextField::Kind::Id, &host);
    field->setGeometry(0, 0, 320, 92);
    host.show();
    QVERIFY(QTest::qWaitForWindowExposed(&host));
    QTextEdit* const edit = field->edit();
    edit->setFocus();
    QTest::keyClicks(edit, QStringLiteral("ABCD"));

    QApplication::clipboard()->setText(QStringLiteral("tox:") + goodAddress.left(40).toLower()
                                       + QStringLiteral("\n") + goodAddress.mid(40).toLower());
    edit->paste();
    QCOMPARE(field->text(), Air::groupedHex(goodAddress));
    QCOMPARE(edit->textCursor().position(), field->text().size());
}

/** Partial, broken, own and fine IDs each get their words, and only a fine one may be sent. */
void TestAirNewContact::theVerdictComesBeforeAnythingIsSent()
{
    Window window;
    window.panel->open();
    QCOMPARE(window.verdict(), AirStrings::text(Text::NcVerdictEmpty));
    QVERIFY(!window.canSend());
    QVERIFY(window.shows("newContactPaste"));
    QVERIFY(!window.shows("newContactClear"));
    QCOMPARE(window.part<QLabel>("newContactWhy")->text(), AirStrings::text(Text::NcWhyWhole));

    window.panel->open(goodAddress.left(10));
    QCOMPARE(window.verdict(), AirStrings::text(Text::NcVerdictLeft, 66));
    QVERIFY(!window.canSend());
    QVERIFY(!window.shows("newContactPaste"));
    QVERIFY(window.shows("newContactClear"));

    window.panel->open(badAddress);
    QCOMPARE(window.verdict(), AirStrings::text(Text::NcVerdictBad));
    QVERIFY(!window.canSend());
    QCOMPARE(window.part<QLabel>("newContactWhy")->text(), AirStrings::text(Text::NcWhyFix));

    window.panel->open(ownAddress);
    QCOMPARE(window.verdict(), AirStrings::text(Text::NcVerdictOwn));
    QVERIFY(!window.canSend());

    window.blocked << goodAddress.left(64);
    window.panel->open(goodAddress);
    QCOMPARE(window.verdict(), AirStrings::text(Text::NcVerdictBlocked));
    QVERIFY(window.canSend());

    window.blocked.clear();
    window.panel->open(goodAddress);
    QCOMPARE(window.verdict(), AirStrings::text(Text::NcVerdictOk));
    QVERIFY(window.canSend());
    QVERIFY(!window.shows("newContactWhy"));
    QVERIFY(!window.shows("newContactVerdictLink"));
}

/** An ID of someone already in the list says so, and its link opens the chat. */
void TestAirNewContact::aContactOpensTheirChat()
{
    Window window;
    window.contacts << goodAddress.left(64);
    window.panel->open(goodAddress);
    QCOMPARE(window.verdict(), AirStrings::text(Text::NcVerdictContact).arg("Omar Haddad"));
    QVERIFY(!window.canSend());
    QVERIFY(window.shows("newContactVerdictLink"));

    QSignalSpy chats(window.panel.get(), &AirNewContact::openChatRequested);
    window.part<AirButton>("newContactVerdictLink")->click();
    QCOMPARE(chats.count(), 1);
    QCOMPARE(chats.at(0).at(0).toString(), goodAddress.left(64));
    QVERIFY(!window.panel->isOpen());
}

/** A request message takes at most 1016 bytes; over that it is red and nothing can be sent. */
void TestAirNewContact::aMessageOverTheLimitCannotGo()
{
    Window window;
    window.panel->open(goodAddress);
    QTextEdit* const message = window.part<AirTextField>("newContactMessage")->edit();
    message->insertPlainText(QString(Air::friendRequestMaxBytes, QLatin1Char('a')));
    QVERIFY(window.canSend());
    QVERIFY(!window.shows("newContactWhy"));
    message->insertPlainText(QStringLiteral("a"));
    QVERIFY(!window.canSend());
    QVERIFY(window.shows("newContactWhy"));
    QCOMPARE(window.part<QLabel>("newContactWhy")->text(), AirStrings::text(Text::NcMessageTooLong));
}

/**
 * Send request hands the whole ID and the greeting to Core, then waits for its answer: on success
 * the panel says the request is on its way, and once the person comes online that they accepted.
 */
void TestAirNewContact::aRequestGoesAndWaitsForThePerson()
{
    Window window;
    window.panel->open(goodAddress);
    QSignalSpy sends(window.panel.get(), &AirNewContact::sendRequested);
    window.part<AirButton>("newContactSend")->click();
    QCOMPARE(sends.count(), 1);
    QCOMPARE(sends.at(0).at(0).toString(), goodAddress);
    QCOMPARE(sends.at(0).at(1).toString(), AirStrings::text(Text::NcGreeting));
    QCOMPARE(window.panel->awaitedKey(), goodAddress.left(64));
    // Waiting for Core: a second press sends nothing.
    QVERIFY(!window.canSend());
    window.part<AirButton>("newContactSend")->click();
    QCOMPARE(sends.count(), 1);

    // An answer about another key changes nothing.
    window.panel->requestSent(ownAddress.left(64));
    QVERIFY(window.shows("newContactSend"));

    window.panel->requestSent(goodAddress.left(64));
    QVERIFY(!window.shows("newContactSend"));
    QVERIFY(window.shows("newContactDone"));
    QCOMPARE(window.part<QLabel>("newContactSentTitle")->text(), AirStrings::text(Text::NcSentTitle));
    QCOMPARE(window.panel->awaitedKey(), goodAddress.left(64));

    window.panel->personAccepted(QStringLiteral("Omar Haddad"), Air::LinkState::Direct);
    QCOMPARE(window.part<QLabel>("newContactSentTitle")->text(),
             AirStrings::text(Text::NcAcceptedTitle));
    QVERIFY(window.part<QWidget>("newContactPath")->accessibleName().contains("Omar Haddad"));

    // Done folds it away; opened again, it starts from an empty form.
    window.part<AirButton>("newContactDone")->click();
    QVERIFY(!window.panel->isOpen());
    window.panel->open();
    QVERIFY(window.shows("newContactSend"));
    QVERIFY(window.part<AirTextField>("newContactId")->text().isEmpty());
    QVERIFY(window.panel->awaitedKey().isEmpty());
}

/** When Core cannot send the request, the panel says so under the button and lets it go again. */
void TestAirNewContact::aRefusedRequestIsSaidInThePanel()
{
    Window window;
    window.panel->open(goodAddress);
    QTextEdit* const message = window.part<AirTextField>("newContactMessage")->edit();
    message->insertPlainText(QStringLiteral("Salam, it is Amina"));
    QSignalSpy sends(window.panel.get(), &AirNewContact::sendRequested);
    window.part<AirButton>("newContactSend")->click();
    QCOMPARE(sends.at(0).at(1).toString(), QStringLiteral("Salam, it is Amina"));
    QVERIFY(!window.shows("newContactError"));

    window.panel->requestFailed(goodAddress.left(64));
    QVERIFY(window.shows("newContactError"));
    QVERIFY(window.shows("newContactSend"));
    QVERIFY(window.canSend());
    QVERIFY(window.panel->awaitedKey().isEmpty());
    // What was typed stays for the next try.
    QCOMPARE(window.part<AirTextField>("newContactMessage")->text(),
             QStringLiteral("Salam, it is Amina"));
}

/**
 * Folded away before Core answered, a refusal is said over the window, the operation as its title
 * (an error is never a toast), and the form starts afresh.
 */
void TestAirNewContact::aRefusedRequestAfterClosingIsSaidOverTheWindow()
{
    Window window;
    window.host.show();
    QVERIFY(QTest::qWaitForWindowExposed(&window.host));
    window.panel->open(goodAddress);
    window.part<AirButton>("newContactSend")->click();
    window.panel->close();
    QCOMPARE(window.panel->awaitedKey(), goodAddress.left(64));

    window.panel->requestFailed(goodAddress.left(64));
    QVERIFY(window.panel->awaitedKey().isEmpty());
    QVERIFY(window.host.findChild<AirDialog*>() != nullptr);
    QVERIFY(window.host.findChild<AirToast*>() == nullptr);
    bool titleSaysIt = false;
    bool messageSaysIt = false;
    for (QLabel* const label : window.host.findChildren<QLabel*>()) {
        titleSaysIt = titleSaysIt || label->text() == AirStrings::text(Text::ErrorAddContactTitle);
        messageSaysIt = messageSaysIt || label->text() == AirStrings::text(Text::NcSendFailed);
    }
    QVERIFY(titleSaysIt);
    QVERIFY(messageSaysIt);
    QVERIFY(window.part<AirTextField>("newContactId")->text().isEmpty());
}

/** The row over the list tells what the start of an ID in the command line still needs. */
void TestAirNewContact::theCommandRowSaysWhatIsMissing()
{
    Window window;
    const auto note = [&window](const QString& hex) {
        return AirCommandIdRow::noteFor(window.panel->commandVerdict(hex), hex.size());
    };
    QCOMPARE(note(goodAddress.left(12)), AirStrings::text(Text::CmdIdLeft, 64));
    QCOMPARE(note(badAddress), AirStrings::text(Text::CmdIdBad));
    QCOMPARE(note(ownAddress), AirStrings::text(Text::CmdIdOwn));
    QCOMPARE(note(goodAddress), AirStrings::text(Text::CmdIdOk));
    window.contacts << goodAddress.left(64);
    QCOMPARE(note(goodAddress), AirStrings::text(Text::CmdIdContact));
    // A blocked key is told in the panel, not in the row.
    window.contacts.clear();
    window.blocked << goodAddress.left(64);
    QCOMPARE(note(goodAddress), AirStrings::text(Text::CmdIdOk));

    AirCommandIdRow row;
    row.setIdStart(goodAddress.left(12), window.panel->commandVerdict(goodAddress.left(12)));
    QCOMPARE(row.idStart(), goodAddress.left(12));
    QCOMPARE(row.accessibleName(), AirStrings::text(Text::CmdNewContact));
    QCOMPARE(row.accessibleDescription(), AirStrings::text(Text::CmdIdLeft, 64));
}

/**
 * The start of an ID in the command line is announced for the row over the list, and Enter takes it
 * to New contact; a whole ID goes there at once. Words stay a search.
 */
void TestAirNewContact::theCommandLineHandsTheStartOfAnIdOn()
{
    AirBand band;
    QLineEdit* const line = band.commandLine();
    QSignalSpy starts(&band, &AirBand::idStartChanged);
    QSignalSpy addresses(&band, &AirBand::contactAddressEntered);
    QSignalSpy activations(&band, &AirBand::activateRequested);

    line->setText(QStringLiteral("56a1 ade4 b65b"));
    QCOMPARE(starts.last().at(0).toString(), QStringLiteral("56A1ADE4B65B"));
    QTest::keyClick(line, Qt::Key_Return);
    QCOMPARE(addresses.count(), 1);
    QCOMPARE(addresses.at(0).at(0).toString(), QStringLiteral("56A1ADE4B65B"));
    QVERIFY(line->text().isEmpty());
    QVERIFY(starts.last().at(0).toString().isEmpty());
    QCOMPARE(activations.count(), 0);

    line->setText(QStringLiteral("Omar"));
    QVERIFY(starts.last().at(0).toString().isEmpty());
    QTest::keyClick(line, Qt::Key_Return);
    QCOMPARE(activations.count(), 1);
    QCOMPARE(addresses.count(), 1);

    line->setText(QStringLiteral("tox:") + badAddress);
    QCOMPARE(addresses.count(), 2);
    QCOMPARE(addresses.at(1).at(0).toString(), badAddress);
    QVERIFY(line->text().isEmpty());
}

/**
 * Every opening starts from an empty form, an ID from the command line included; an ID handed to
 * the open form replaces only the ID, so the message written so far stays.
 */
void TestAirNewContact::eachOpeningStartsAfresh()
{
    Window window;
    window.panel->open(goodAddress.left(8));
    QTextEdit* const message = window.part<AirTextField>("newContactMessage")->edit();
    message->insertPlainText(QStringLiteral("Salam, it is Amina"));

    window.panel->open(goodAddress);
    QCOMPARE(window.part<AirTextField>("newContactId")->text(), Air::groupedHex(goodAddress));
    QCOMPARE(window.part<AirTextField>("newContactMessage")->text(),
             QStringLiteral("Salam, it is Amina"));

    window.panel->close();
    window.panel->open(goodAddress);
    QVERIFY(window.part<AirTextField>("newContactMessage")->text().isEmpty());
    QCOMPARE(window.verdict(), AirStrings::text(Text::NcVerdictOk));
}

/** The sentence of the empty column gets the height of all its lines, centred in narrow and wide. */
void TestAirNewContact::theEmptyColumnShowsTheWholeSentence()
{
    for (const QSize& size : {QSize(420, 500), QSize(1200, 800)}) {
        AirNoChat view;
        view.resize(size);
        view.show();
        QVERIFY(QTest::qWaitForWindowExposed(&view));
        auto* const sentence = view.findChild<QLabel*>(QStringLiteral("noChatSentence"));
        QVERIFY(sentence);
        QVERIFY(sentence->width() > 0);
        QVERIFY2(sentence->height() >= sentence->heightForWidth(sentence->width()),
                 qPrintable(QStringLiteral("%1 px for %2 px of text")
                                .arg(sentence->height())
                                .arg(sentence->heightForWidth(sentence->width()))));
        QVERIFY(qAbs(sentence->geometry().center().x() - view.width() / 2) <= 1);
    }
}

int main(int argc, char* argv[])
{
    // The widgets need a platform, not a screen: CI runs the tests without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    QApplication app(argc, argv);
    TestAirNewContact test;
    return QTest::qExec(&test, argc, argv);
}

#include "airnewcontact_test.moc"
