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

#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"
#include "src/widget/air/airwidgets.h"

#include <QApplication>
#include <QClipboard>
#include <QLabel>
#include <QMouseEvent>
#include <QPointer>
#include <QSignalSpy>
#include <QtTest/QtTest>

namespace {

/** The words of a toast, without its action. */
QLabel* wordsOf(const AirToast* toast)
{
    for (QLabel* const label : toast->findChildren<QLabel*>()) {
        if (label->text() == toast->text()) {
            return label;
        }
    }
    return nullptr;
}

AirButton* buttonOf(QWidget* dialog, const char* name)
{
    return dialog->findChild<AirButton*>(QLatin1String(name));
}

bool showsText(QWidget* dialog, const QString& text)
{
    for (QLabel* const label : dialog->findChildren<QLabel*>()) {
        if (label->isVisible() && label->text().contains(text)) {
            return true;
        }
    }
    return false;
}

/** The object names of the buttons Tab goes through from the focused one, once round. */
QStringList tabRound(QWidget* dialog)
{
    QStringList names;
    for (int i = 0; i < 6; ++i) {
        QWidget* const focused = QApplication::focusWidget();
        if (!focused || !dialog->isAncestorOf(focused)) {
            break;
        }
        names.append(focused->objectName());
        QTest::keyClick(focused, Qt::Key_Tab);
    }
    return names;
}

/** A window with the Air band across its top and a conversation column with its composer. */
struct BandWindow
{
    QWidget window;
    QWidget* band = nullptr;
    QWidget* composer = nullptr;

    BandWindow()
    {
        window.resize(1440, 900);
        band = new QWidget(&window);
        band->setObjectName(QStringLiteral("airBand"));
        band->setGeometry(0, 0, 1440, 92);
        composer = new QWidget(&window);
        composer->setObjectName(QStringLiteral("airComposer"));
        composer->setGeometry(480, 760, 960, 120);
        window.show();
    }
};

AirBanner::Content bandContent(const QString& body = QStringLiteral("Я уже рядом с библиотекой"))
{
    AirBanner::Content content;
    content.title = QStringLiteral("Марьям Хасанова");
    content.body = body;
    content.tileName = content.title;
    content.tone = 2;
    content.hint = QStringLiteral("Opens the chat");
    return content;
}

/** The card of a band: the ground the words stand on. */
QWidget* cardOf(AirBanner* banner)
{
    const QList<QWidget*> children =
        banner->findChildren<QWidget*>(QString(), Qt::FindDirectChildrenOnly);
    return children.isEmpty() ? nullptr : children.first();
}

void sendMouse(QWidget* widget, QEvent::Type type, const QPoint& pos, Qt::MouseButtons buttons)
{
    QMouseEvent event(type, pos, widget->mapToGlobal(pos),
                      type == QEvent::MouseMove ? Qt::NoButton : Qt::LeftButton, buttons,
                      Qt::NoModifier);
    QApplication::sendEvent(widget, &event);
}

/** The set of the names, without the range constructor that Qt 5.12 does not have. */
QSet<QString> setOf(const QStringList& names)
{
    QSet<QString> set;
    for (const QString& name : names) {
        set.insert(name);
    }
    return set;
}

QStringList heardWarnings;
QtMessageHandler passOn = nullptr;

void hearWarning(QtMsgType type, const QMessageLogContext& context, const QString& message)
{
    if (type == QtWarningMsg) {
        heardWarnings.append(message);
    }
    if (passOn) {
        passOn(type, context, message);
    }
}

/** Keeps the warnings of Qt while it lives, to find painting that Qt refused. */
struct WarningEar
{
    WarningEar()
    {
        heardWarnings.clear();
        passOn = qInstallMessageHandler(hearWarning);
    }

    ~WarningEar()
    {
        qInstallMessageHandler(passOn);
        passOn = nullptr;
    }

    /** The first warning about a painter, or nothing. */
    QString painterWarning() const
    {
        for (const QString& message : heardWarnings) {
            if (message.contains(QLatin1String("Painter"))) {
                return message;
            }
        }
        return QString();
    }
};

} // namespace

class TestAirOverlay : public QObject
{
    Q_OBJECT
private slots:
    void aShortToastKeepsItsWordsOnOneLine();
    void aLongToastWrapsWithinTheWidthOfADialog();
    void aToastStandsAboveTheComposerOnScreen();
    void aNoticeHasOneWayOut();
    void aNoticeKeepsTheCodeForSupportUnderDetails();
    void aChoiceClosesTheDialogAndTakesItsOwnWay();
    void buttonsThatDoNotFitStandOneUnderAnother();
    void aBandComesUnderTheAirBandOverTheColumn();
    void aClickOnTheBandOpensWhatItTellsOf();
    void aDragUpPutsTheBandAwayUnopened();
    void aNewBandTakesThePlaceOfTheOld();
    void theBandStaysWhileThePointerIsOverIt();
    void noBandComesOverAQuestion();
    void theBandShowsTwoLinesAtMost();
    void theBandLeavesInOnePiece();
    void aToastLeavesInOnePiece();
};

void TestAirOverlay::aShortToastKeepsItsWordsOnOneLine()
{
    QWidget window;
    window.resize(1440, 900);
    window.show();
    AirToast* const toast =
        AirToast::post(&window, QStringLiteral("Приглашение отклонено"), QStringLiteral("Отменить"));
    QVERIFY(toast);
    QCoreApplication::processEvents();
    QLabel* const words = wordsOf(toast);
    QVERIFY(words);
    // The row may be taller than the words: the label is as high as the button beside it.
    QVERIFY(words->heightForWidth(words->width()) < 2 * words->fontMetrics().lineSpacing());
}

void TestAirOverlay::aLongToastWrapsWithinTheWidthOfADialog()
{
    QWidget window;
    window.resize(1440, 900);
    window.show();
    const QString text = QStringLiteral("You can join once %1 is online.")
                             .arg(QStringLiteral("Ibrahim Aliev from the Thursday circle and the "
                                                 "Saturday football"));
    AirToast* const toast = AirToast::post(&window, text);
    QVERIFY(toast);
    QCoreApplication::processEvents();
    QLabel* const words = wordsOf(toast);
    QVERIFY(words);
    // The plate is no wider than a dialog; the shadow around it is not part of it.
    QVERIFY(toast->width() <= AirTokens::Size::dialog + 2 * AirTokens::Elevation::toast.blur);
    QVERIFY(words->heightForWidth(words->width()) >= 2 * words->fontMetrics().lineSpacing());
}

void TestAirOverlay::aToastStandsAboveTheComposerOnScreen()
{
    QWidget window;
    window.resize(1440, 900);
    // The conversation column on the right, with its composer at the foot.
    auto* const hidden = new QWidget(&window);
    hidden->setObjectName(QStringLiteral("airComposer"));
    hidden->setGeometry(0, 600, 400, 100);
    hidden->hide();
    auto* const composer = new QWidget(&window);
    composer->setObjectName(QStringLiteral("airComposer"));
    composer->setGeometry(480, 760, 960, 120);
    window.show();
    AirToast* const toast =
        AirToast::post(&window, QStringLiteral("Ключ разблокирован"), QStringLiteral("Отменить"));
    QVERIFY(toast);
    // Past the rise, where it stays.
    QTest::qWait(AirTokens::Motion::state + 200);
    // The plate is inside the shadow margins: its foot is above the composer, its centre over it.
    QVERIFY(toast->geometry().bottom() - AirTokens::Elevation::toast.blur < composer->y());
    QVERIFY(qAbs(toast->geometry().center().x() - composer->geometry().center().x()) <= 1);

    // Without a composer on screen it stands at the foot of the window, in the middle.
    composer->hide();
    AirToast* const second = AirToast::post(&window, QStringLiteral("Заметка удалена"));
    QVERIFY(second);
    QTest::qWait(AirTokens::Motion::state + 200);
    QVERIFY(second->geometry().bottom() > composer->y());
    QVERIFY(qAbs(second->geometry().center().x() - window.width() / 2) <= 1);
}

void TestAirOverlay::aNoticeHasOneWayOut()
{
    QWidget window;
    window.resize(1440, 900);
    window.show();
    QApplication::setActiveWindow(&window);
    AirDialog* const dialog = AirDialog::notice(&window, QStringLiteral("The file wasn't sent"),
                                                QStringLiteral("Try again."));
    QVERIFY(dialog);
    QVERIFY(buttonOf(dialog, "dialogConfirm")->isVisible());
    QCOMPARE(buttonOf(dialog, "dialogConfirm")->text(), QStringLiteral("OK"));
    QVERIFY(!buttonOf(dialog, "dialogCancel")->isVisible());
    QVERIFY(!buttonOf(dialog, "dialogDetails")->isVisible());
    QVERIFY(buttonOf(dialog, "dialogConfirm")->hasFocus());
    // Tab stays on the one button.
    QCOMPARE(setOf(tabRound(dialog)).size(), 1);

    QSignalSpy finished(dialog, &AirDialog::finished);
    buttonOf(dialog, "dialogConfirm")->click();
    QCOMPARE(finished.count(), 1);
    QVERIFY(dialog->isHidden());
}

void TestAirOverlay::aNoticeKeepsTheCodeForSupportUnderDetails()
{
    QWidget window;
    window.resize(1440, 900);
    window.show();
    QApplication::setActiveWindow(&window);
    AirDialog* const dialog =
        AirDialog::notice(&window, QStringLiteral("Couldn't start the call"),
                          QStringLiteral("Try again."), QStringLiteral("toxav_call 2"));
    QVERIFY(dialog);
    // The words never carry the code; it waits under Details.
    QVERIFY(showsText(dialog, QStringLiteral("Try again.")));
    QVERIFY(!showsText(dialog, QStringLiteral("toxav_call")));
    AirButton* const details = buttonOf(dialog, "dialogDetails");
    QVERIFY(details->isVisible());
    QCOMPARE(details->text(), QStringLiteral("Details"));
    QVERIFY(!buttonOf(dialog, "dialogCopy")->isVisible());
    // Copy is hidden with its row, so Tab goes round Details and OK alone.
    QCOMPARE(setOf(tabRound(dialog)),
             (QSet<QString>{QStringLiteral("dialogDetails"), QStringLiteral("dialogConfirm")}));

    details->click();
    QVERIFY(!details->isVisible());
    QVERIFY(showsText(dialog, QStringLiteral("Code for support: toxav_call 2")));
    AirButton* const copy = buttonOf(dialog, "dialogCopy");
    QVERIFY(copy->isVisible());
    QVERIFY(copy->hasFocus());
    QCOMPARE(setOf(tabRound(dialog)),
             (QSet<QString>{QStringLiteral("dialogCopy"), QStringLiteral("dialogConfirm")}));
    copy->click();
    QCOMPARE(QGuiApplication::clipboard()->text(), QStringLiteral("toxav_call 2"));
    // Copying does not close it.
    QVERIFY(dialog->isVisible());
}

void TestAirOverlay::aChoiceClosesTheDialogAndTakesItsOwnWay()
{
    QWidget window;
    window.resize(1440, 900);
    window.show();
    QApplication::setActiveWindow(&window);
    bool confirmed = false;
    bool chosen = false;
    AirDialog* const dialog =
        AirDialog::confirm(&window, QStringLiteral("No access to the camera"),
                           QStringLiteral("The call can go with sound."),
                           QStringLiteral("Call without video"), AirDialog::Verb::Ink,
                           [&confirmed] { confirmed = true; });
    QVERIFY(dialog);
    dialog->addChoice(QStringLiteral("Open Settings"), [&chosen] { chosen = true; });
    AirButton* const choice = buttonOf(dialog, "dialogChoice0");
    QVERIFY(choice);
    QVERIFY(choice->isVisible());
    // Between Cancel and the verb.
    const int cancelX = buttonOf(dialog, "dialogCancel")->mapTo(dialog, QPoint()).x();
    const int verbX = buttonOf(dialog, "dialogConfirm")->mapTo(dialog, QPoint()).x();
    const int choiceX = choice->mapTo(dialog, QPoint()).x();
    QVERIFY(cancelX < choiceX && choiceX < verbX);

    QSignalSpy finished(dialog, &AirDialog::finished);
    choice->click();
    QVERIFY(chosen);
    QVERIFY(!confirmed);
    QCOMPARE(finished.count(), 1);
    QCOMPARE(finished.takeFirst().at(0).toBool(), false);
    QVERIFY(dialog->isHidden());
}

/**
 * Three ways on with long words (Russian has them: Cancel, Open Settings, Call without video) do
 * not fit the width of a dialog: the buttons stand one under another, none of them cut.
 */
void TestAirOverlay::buttonsThatDoNotFitStandOneUnderAnother()
{
    QWidget window;
    window.resize(1440, 900);
    window.show();
    QApplication::setActiveWindow(&window);
    AirDialog* const dialog =
        AirDialog::confirm(&window, QStringLiteral("No access to the camera"),
                           QStringLiteral("The call can go with sound."),
                           QStringLiteral("Call without video, with the sound alone"),
                           AirDialog::Verb::Ink, [] {});
    QVERIFY(dialog);
    dialog->addChoice(QStringLiteral("Open the System Settings"), [] {});
    AirButton* const verb = buttonOf(dialog, "dialogConfirm");
    AirButton* const choice = buttonOf(dialog, "dialogChoice0");
    AirButton* const cancel = buttonOf(dialog, "dialogCancel");
    QWidget* const card = verb->parentWidget();

    // The verb on top and Cancel last, each as wide as the card less its padding.
    QVERIFY(verb->y() < choice->y());
    QVERIFY(choice->y() < cancel->y());
    for (AirButton* const button : {verb, choice, cancel}) {
        QCOMPARE(button->width(), card->width() - 2 * AirTokens::Space::s20);
        QVERIFY(button->width() >= button->sizeHint().width());
        QVERIFY(card->rect().contains(button->geometry()));
    }
    // Tab goes down the way they stand.
    QCOMPARE(tabRound(dialog).mid(0, 3),
             (QStringList{QStringLiteral("dialogConfirm"), QStringLiteral("dialogChoice0"),
                          QStringLiteral("dialogCancel")}));
}

/** The band comes out from under the Air band and stands over the conversation column. */
void TestAirOverlay::aBandComesUnderTheAirBandOverTheColumn()
{
    BandWindow host;
    AirBanner* const banner = AirBanner::post(&host.window, bandContent(), [] {});
    QVERIFY(banner);
    QCOMPARE(AirBanner::current(&host.window), banner);
    QTest::qWait(AirTokens::Motion::layout + 200);
    QWidget* const card = cardOf(banner);
    QVERIFY(card);
    const QRect area(card->mapTo(&host.window, QPoint(0, 0)), card->size());
    QCOMPARE(area.top(), host.band->height() + AirTokens::Space::s12);
    QVERIFY(qAbs(area.center().x() - host.composer->geometry().center().x()) <= 1);
    QVERIFY(area.width() <= AirTokens::Size::dialog);
    // It is cut at the foot of the Air band, so not even its shadow lies over the band, and the
    // card shows whole under it.
    const QRegion shown = banner->mask();
    QVERIFY(!shown.isEmpty());
    QCOMPARE(banner->mapTo(&host.window, shown.boundingRect().topLeft()).y(), host.band->height());
    QVERIFY(shown.boundingRect().contains(card->geometry()));
    // It never takes the keyboard from the field under it.
    QCOMPARE(banner->focusPolicy(), Qt::NoFocus);
    QCOMPARE(banner->accessibleName(),
             QStringLiteral("Марьям Хасанова, Я уже рядом с библиотекой"));
    QCOMPARE(banner->accessibleDescription(), QStringLiteral("Opens the chat"));
}

void TestAirOverlay::aClickOnTheBandOpensWhatItTellsOf()
{
    BandWindow host;
    int opened = 0;
    AirBanner* const banner = AirBanner::post(&host.window, bandContent(), [&opened] { ++opened; });
    QVERIFY(banner);
    QTest::qWait(AirTokens::Motion::layout + 200);
    QWidget* const card = cardOf(banner);
    QTest::mouseClick(card, Qt::LeftButton, Qt::NoModifier, card->rect().center());
    QCOMPARE(opened, 1);
    QVERIFY(AirBanner::current(&host.window) == nullptr);
    // Gone once its way out is over, and a second click finds nothing to open.
    QPointer<AirBanner> gone(banner);
    QTRY_VERIFY(gone.isNull());
    QCOMPARE(opened, 1);
}

void TestAirOverlay::aDragUpPutsTheBandAwayUnopened()
{
    BandWindow host;
    int opened = 0;
    AirBanner* const banner = AirBanner::post(&host.window, bandContent(), [&opened] { ++opened; });
    QVERIFY(banner);
    QTest::qWait(AirTokens::Motion::layout + 200);
    const int restY = banner->y();
    const QPoint start = cardOf(banner)->geometry().center();
    sendMouse(banner, QEvent::MouseButtonPress, start, Qt::LeftButton);
    sendMouse(banner, QEvent::MouseMove, start - QPoint(0, 12), Qt::LeftButton);
    // It follows the drag up.
    QCOMPARE(banner->y(), restY - 12);
    sendMouse(banner, QEvent::MouseMove, start - QPoint(0, 40), Qt::LeftButton);
    sendMouse(banner, QEvent::MouseButtonRelease, start - QPoint(0, 40), Qt::NoButton);
    QCOMPARE(opened, 0);
    QVERIFY(AirBanner::current(&host.window) == nullptr);

    // A drag too short to put it away takes it back to its place, and it stays.
    AirBanner* const second = AirBanner::post(&host.window, bandContent(), [&opened] { ++opened; });
    QTest::qWait(AirTokens::Motion::layout + 200);
    const QPoint middle = cardOf(second)->geometry().center();
    sendMouse(second, QEvent::MouseButtonPress, middle, Qt::LeftButton);
    sendMouse(second, QEvent::MouseMove, middle - QPoint(0, 15), Qt::LeftButton);
    sendMouse(second, QEvent::MouseButtonRelease, middle - QPoint(0, 15), Qt::NoButton);
    QCOMPARE(opened, 0);
    QCOMPARE(AirBanner::current(&host.window), second);
    QCOMPARE(second->y(), restY);
}

void TestAirOverlay::aNewBandTakesThePlaceOfTheOld()
{
    BandWindow host;
    int firstOpened = 0;
    QPointer<AirBanner> first(
        AirBanner::post(&host.window, bandContent(), [&firstOpened] { ++firstOpened; }));
    QVERIFY(first);
    AirBanner::Content next = bandContent(QStringLiteral("Захвати второй том"));
    next.title = QStringLiteral("Ибрагим Алиев");
    AirBanner* const second = AirBanner::post(&host.window, next, [] {});
    QCOMPARE(AirBanner::current(&host.window), second);
    QVERIFY(first.isNull() || !first->isVisible());
    QTRY_VERIFY(first.isNull());
    QCOMPARE(firstOpened, 0);
    QCOMPARE(second->content().title, QStringLiteral("Ибрагим Алиев"));
}

void TestAirOverlay::theBandStaysWhileThePointerIsOverIt()
{
    BandWindow host;
    QPointer<AirBanner> banner(AirBanner::post(&host.window, bandContent(), [] {}));
    QVERIFY(banner);
    QEvent enter(QEvent::Enter);
    QApplication::sendEvent(banner, &enter);
    QTest::qWait(AirBanner::stayMs + 300);
    QVERIFY(banner);
    QCOMPARE(AirBanner::current(&host.window), banner.data());
    // Let go, it leaves by itself, after a moment to read it again.
    QEvent leave(QEvent::Leave);
    QApplication::sendEvent(banner, &leave);
    QTest::qWait(1000);
    QVERIFY(banner);
    QTRY_VERIFY_WITH_TIMEOUT(banner.isNull(), AirBanner::stayMs + 2000);
}

void TestAirOverlay::noBandComesOverAQuestion()
{
    BandWindow host;
    AirDialog* const dialog =
        AirDialog::notice(&host.window, QStringLiteral("Couldn't add the contact"),
                          QStringLiteral("Try again."));
    QVERIFY(dialog);
    QVERIFY(AirBanner::post(&host.window, bandContent(), [] {}) == nullptr);
    QVERIFY(AirBanner::current(&host.window) == nullptr);
}

/** Words that go on are cut at the end of the second line: the band keeps its height. */
void TestAirOverlay::theBandShowsTwoLinesAtMost()
{
    BandWindow host;
    AirBanner* const shortOne = AirBanner::post(&host.window, bandContent(), [] {});
    QVERIFY(shortOne);
    const int oneLine = cardOf(shortOne)->height();
    QString words;
    for (int i = 0; i < 40; ++i) {
        words += QStringLiteral("План такой: сначала первая тема, потом отрывок из второго тома. ");
    }
    AirBanner* const longOne = AirBanner::post(&host.window, bandContent(words), [] {});
    QVERIFY(longOne);
    const int lineHeight = AirStyle::instance().lineHeight(AirTokens::TextRole::Label);
    QCOMPARE(cardOf(longOne)->height(), oneLine + lineHeight);
    // Nothing under the title: the card is as high as the tile with its padding, or the title.
    AirBanner::Content bare = bandContent(QString());
    AirBanner* const titleOnly = AirBanner::post(&host.window, bare, [] {});
    QVERIFY(titleOnly);
    QVERIFY(cardOf(titleOnly)->height() <= oneLine);
    QCOMPARE(titleOnly->accessibleName(), QStringLiteral("Марьям Хасанова"));
}

/**
 * The first frame of the way out is painted with the card: the fade and the shadow of the card never
 * ask for two painters on the window at once, which Qt refuses, leaving the frame without the card.
 */
void TestAirOverlay::theBandLeavesInOnePiece()
{
    BandWindow host;
    QPointer<AirBanner> banner(AirBanner::post(&host.window, bandContent(), [] {}));
    QVERIFY(banner);
    QTest::qWait(AirTokens::Motion::layout + 200);
    WarningEar ear;
    QWidget* const card = cardOf(banner);
    QTest::mouseClick(card, Qt::LeftButton, Qt::NoModifier, card->rect().center());
    host.window.repaint();
    QTRY_VERIFY(banner.isNull());
    QVERIFY2(ear.painterWarning().isEmpty(), qPrintable(ear.painterWarning()));
}

void TestAirOverlay::aToastLeavesInOnePiece()
{
    BandWindow host;
    QPointer<AirToast> toast(AirToast::post(&host.window, QStringLiteral("Ключ разблокирован"),
                                            QStringLiteral("Отменить")));
    QVERIFY(toast);
    QTest::qWait(AirTokens::Motion::state + 200);
    WarningEar ear;
    AirToast::dismiss(&host.window);
    host.window.repaint();
    QTRY_VERIFY(toast.isNull());
    QVERIFY2(ear.painterWarning().isEmpty(), qPrintable(ear.painterWarning()));
}

int main(int argc, char* argv[])
{
    // The widgets need a platform, not a screen: CI runs the tests without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    QApplication app(argc, argv);
    TestAirOverlay test;
    return QTest::qExec(&test, argc, argv);
}

#include "airoverlay_test.moc"
