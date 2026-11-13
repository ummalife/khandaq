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

#include "src/widget/air/airmediaparts.h"
#include "src/widget/air/airwidgets.h"

#include <QApplication>
#include <QLabel>
#include <QSignalSpy>
#include <QtTest/QtTest>

#include <limits>

namespace {

AirButton* buttonOf(QWidget* parent, const char* name)
{
    return parent->findChild<AirButton*>(QLatin1String(name));
}

/** The buttons of the band that would show with it, by their object names. */
QStringList shownButtons(AirCallBand& band)
{
    QStringList names;
    for (AirButton* const button : band.findChildren<AirButton*>()) {
        if (button->isVisibleTo(&band)) {
            names.append(button->objectName());
        }
    }
    names.sort();
    return names;
}

QStringList shownTexts(QWidget& widget)
{
    QStringList texts;
    for (QLabel* const label : widget.findChildren<QLabel*>()) {
        if (label->isVisibleTo(&widget) && !label->text().isEmpty()) {
            texts.append(label->text());
        }
    }
    return texts;
}

} // namespace

class TestAirMediaParts : public QObject
{
    Q_OBJECT
private slots:
    void theBarStaysBetweenEmptyAndFull();
    void aClickOnTheWaveMovesTheNoteFromTheStartEdge();
    void aNoteThatIsNotHereShowsItsSizeAndWaits();
    void aNoteThatIsHereShowsItsLength();
    void eachPhaseOfACallHasItsOwnButtons();
    void theBandSaysTheStateInWords();
    void theButtonsOfTheBandAskForTheirActions();
};

void TestAirMediaParts::theBarStaysBetweenEmptyAndFull()
{
    AirProgressBar bar;
    bar.setProgress(0.4);
    QCOMPARE(bar.progress(), 0.4);
    bar.setProgress(1.7);
    QCOMPARE(bar.progress(), 1.0);
    bar.setProgress(-1);
    QCOMPARE(bar.progress(), 0.0);
    bar.setProgress(std::numeric_limits<double>::quiet_NaN());
    QCOMPARE(bar.progress(), 0.0);
}

void TestAirMediaParts::aClickOnTheWaveMovesTheNoteFromTheStartEdge()
{
    AirVoiceWave wave;
    wave.resize(200, 28);
    QSignalSpy seeks(&wave, &AirVoiceWave::seekRequested);
    QTest::mouseClick(&wave, Qt::LeftButton, Qt::NoModifier, QPoint(50, 14));
    QCOMPARE(seeks.count(), 1);
    QCOMPARE(seeks.takeFirst().at(0).toDouble(), 0.25);
    QCOMPARE(wave.played(), 0.25);

    // In Arabic the note starts at the right edge.
    wave.setLayoutDirection(Qt::RightToLeft);
    QTest::mouseClick(&wave, Qt::LeftButton, Qt::NoModifier, QPoint(50, 14));
    QCOMPARE(seeks.takeFirst().at(0).toDouble(), 0.75);

    // While the pointer holds the wave, the player does not move it.
    QTest::mousePress(&wave, Qt::LeftButton, Qt::NoModifier, QPoint(100, 14));
    wave.setPlayed(0.9);
    QCOMPARE(wave.played(), 0.5);
    QTest::mouseRelease(&wave, Qt::LeftButton, Qt::NoModifier, QPoint(100, 14));
    // The sound moves once, where the drag ends.
    QCOMPARE(seeks.count(), 1);
    QCOMPARE(seeks.takeFirst().at(0).toDouble(), 0.5);
    wave.setPlayed(0.9);
    QCOMPARE(wave.played(), 0.9);

    // A wave that waits takes no clicks.
    wave.setEnabled(false);
    QTest::mouseClick(&wave, Qt::LeftButton, Qt::NoModifier, QPoint(10, 14));
    QCOMPARE(seeks.count(), 0);
}

void TestAirMediaParts::aNoteThatIsNotHereShowsItsSizeAndWaits()
{
    AirVoiceNote note;
    note.setNote(QStringLiteral("/nowhere/voice_1.file.m4a"), -1, false, QStringLiteral("38 KB"));
    AirButton* const listen = buttonOf(&note, "voiceListen");
    QVERIFY(listen);
    QVERIFY(!listen->isEnabled());
    QVERIFY(shownTexts(note).contains(QStringLiteral("38 KB")));
    QCOMPARE(note.spokenText(), QStringLiteral("Voice message, 38 KB"));
}

void TestAirMediaParts::aNoteThatIsHereShowsItsLength()
{
    AirVoiceNote note;
    note.setNote(QStringLiteral("/nowhere/voice_2.file.m4a"), 42500, true, QStringLiteral("38 KB"));
    AirButton* const listen = buttonOf(&note, "voiceListen");
    QVERIFY(listen->isEnabled());
    QCOMPARE(listen->text(), QStringLiteral("Listen"));
    QVERIFY(shownTexts(note).contains(QStringLiteral("0:42")));
    QCOMPARE(note.spokenText(), QStringLiteral("Voice message, 0:42"));
}

void TestAirMediaParts::eachPhaseOfACallHasItsOwnButtons()
{
    QWidget parent;
    AirCallBand band(&parent);
    parent.show();
    QVERIFY(band.isHidden());

    band.setPhase(AirCallBand::Phase::Incoming, QStringLiteral("Yusuf"));
    QVERIFY(!band.isHidden());
    QCOMPARE(shownButtons(band),
             (QStringList{QStringLiteral("callAnswer"), QStringLiteral("callDecline")}));
    band.setPhase(AirCallBand::Phase::Incoming, QStringLiteral("Yusuf"), true);
    QCOMPARE(shownButtons(band),
             (QStringList{QStringLiteral("callAnswer"), QStringLiteral("callAnswerVideo"),
                          QStringLiteral("callDecline")}));
    band.setPhase(AirCallBand::Phase::Outgoing, QStringLiteral("Yusuf"));
    QCOMPARE(shownButtons(band), QStringList{QStringLiteral("callEnd")});
    band.setPhase(AirCallBand::Phase::Active, QStringLiteral("Yusuf"));
    QCOMPARE(shownButtons(band),
             (QStringList{QStringLiteral("callEnd"), QStringLiteral("callMicrophone"),
                          QStringLiteral("callSound")}));
    band.setPhase(AirCallBand::Phase::Waiting, QStringLiteral("Yusuf"));
    QCOMPARE(shownButtons(band), QStringList{QStringLiteral("callWaitCancel")});
    band.setPhase(AirCallBand::Phase::None);
    QVERIFY(band.isHidden());
}

void TestAirMediaParts::theBandSaysTheStateInWords()
{
    QWidget parent;
    AirCallBand band(&parent);
    parent.show();
    band.setPhase(AirCallBand::Phase::Incoming, QStringLiteral("Yusuf"));
    QCOMPARE(band.accessibleName(), QStringLiteral("Incoming call"));
    band.setPhase(AirCallBand::Phase::Outgoing, QStringLiteral("Yusuf"));
    QCOMPARE(band.accessibleName(), QStringLiteral("Calling"));

    band.setPhase(AirCallBand::Phase::Active, QStringLiteral("Yusuf"));
    QCOMPARE(band.accessibleName(), QStringLiteral("Call, 0:00"));
    band.setSeconds(252);
    QCOMPARE(band.accessibleName(), QStringLiteral("Call, 4:12"));
    // The switches say what a click does.
    QCOMPARE(buttonOf(&band, "callMicrophone")->text(), QStringLiteral("Mute the microphone"));
    band.setMuted(true, false);
    QCOMPARE(buttonOf(&band, "callMicrophone")->text(), QStringLiteral("Unmute the microphone"));
    QCOMPARE(buttonOf(&band, "callSound")->text(), QStringLiteral("Mute the sound"));
    band.setMuted(true, true);
    QCOMPARE(buttonOf(&band, "callSound")->text(), QStringLiteral("Unmute the sound"));

    // Waiting names the person and says what will happen.
    band.setPhase(AirCallBand::Phase::Waiting, QStringLiteral("Yusuf"));
    QCOMPARE(band.accessibleName(), QStringLiteral("Waiting for Yusuf to come online"));
    QVERIFY(shownTexts(band).contains(
        QStringLiteral("The call starts on its own once they are online.")));
    // A new call starts its time from nothing.
    band.setPhase(AirCallBand::Phase::Active, QStringLiteral("Yusuf"));
    QCOMPARE(band.accessibleName(), QStringLiteral("Call, 0:00"));
}

void TestAirMediaParts::theButtonsOfTheBandAskForTheirActions()
{
    QWidget parent;
    AirCallBand band(&parent);
    parent.show();
    QSignalSpy answers(&band, &AirCallBand::answerRequested);
    QSignalSpy declines(&band, &AirCallBand::declineRequested);
    QSignalSpy ends(&band, &AirCallBand::endRequested);
    QSignalSpy microphone(&band, &AirCallBand::microphoneToggled);
    QSignalSpy sound(&band, &AirCallBand::soundToggled);
    QSignalSpy cancels(&band, &AirCallBand::waitCancelled);

    band.setPhase(AirCallBand::Phase::Incoming, QStringLiteral("Yusuf"), true);
    buttonOf(&band, "callAnswer")->click();
    buttonOf(&band, "callAnswerVideo")->click();
    buttonOf(&band, "callDecline")->click();
    QCOMPARE(answers.count(), 2);
    QCOMPARE(answers.at(0).at(0).toBool(), false);
    QCOMPARE(answers.at(1).at(0).toBool(), true);
    QCOMPARE(declines.count(), 1);

    band.setPhase(AirCallBand::Phase::Active, QStringLiteral("Yusuf"));
    buttonOf(&band, "callMicrophone")->click();
    buttonOf(&band, "callSound")->click();
    buttonOf(&band, "callEnd")->click();
    QCOMPARE(microphone.count(), 1);
    QCOMPARE(sound.count(), 1);
    QCOMPARE(ends.count(), 1);

    band.setPhase(AirCallBand::Phase::Waiting, QStringLiteral("Yusuf"));
    buttonOf(&band, "callWaitCancel")->click();
    QCOMPARE(cancels.count(), 1);
}

int main(int argc, char* argv[])
{
    // The widgets need a platform, not a screen: CI runs the tests without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    QApplication app(argc, argv);
    TestAirMediaParts test;
    return QTest::qExec(&test, argc, argv);
}

#include "airmediaparts_test.moc"
