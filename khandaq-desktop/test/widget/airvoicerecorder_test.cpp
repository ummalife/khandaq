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

#include "src/widget/air/airvoicerecorder.h"

#include "audio/iaudiosource.h"

#include <QSignalSpy>
#include <QtTest/QtTest>

#include <algorithm>
#include <memory>
#include <vector>

namespace {

/** A microphone the test speaks into; it says when it is closed. */
class FakeMicrophone : public IAudioSource
{
public:
    explicit FakeMicrophone(bool* closed_)
        : closed(closed_)
    {
    }
    ~FakeMicrophone() override
    {
        *closed = true;
    }
    operator bool() const override
    {
        return true;
    }
    /** frames of channels interleaved, each channel at its own constant level. */
    void say(size_t frames, uint32_t rate, const std::vector<int16_t>& levels)
    {
        std::vector<int16_t> pcm;
        pcm.reserve(frames * levels.size());
        for (size_t i = 0; i < frames; ++i) {
            pcm.insert(pcm.end(), levels.begin(), levels.end());
        }
        emit frameAvailable(pcm.data(), frames, static_cast<uint8_t>(levels.size()), rate);
    }
    void unplug()
    {
        emit invalidated();
    }

private:
    bool* closed;
};

std::unique_ptr<IAudioSource> own(FakeMicrophone* microphone)
{
    return std::unique_ptr<IAudioSource>(microphone);
}

} // namespace

class TestAirVoiceRecorder : public QObject
{
    Q_OBJECT
private slots:
    void framesAreKeptAsOneChannel();
    void oneNoteAtATime();
    void anotherRateIsNotMixedIn();
    void aNoteStopsAtItsLimitAndWaits();
    void aClosedFormTakesItsNoteWithIt();
    void anUnpluggedMicrophoneKeepsTheNote();
};

/**
 * The microphone hears the quiet frames too (a voice note keeps its pauses), its channels are
 * averaged into one, and Send hands the note over with the microphone closed.
 */
void TestAirVoiceRecorder::framesAreKeptAsOneChannel()
{
    AirVoiceRecorder recorder;
    QObject form;
    bool closed = false;
    auto* const microphone = new FakeMicrophone(&closed);
    QSignalSpy elapsed(&recorder, &AirVoiceRecorder::elapsedChanged);
    QSignalSpy ended(&recorder, &AirVoiceRecorder::ended);
    QVERIFY(!microphone->hearsEveryFrame());
    QVERIFY(recorder.start(&form, own(microphone)));
    QVERIFY(microphone->hearsEveryFrame());
    QVERIFY(recorder.isRecording());
    QVERIFY(recorder.isRecordingFor(&form));
    QVERIFY(!recorder.isRecordingFor(nullptr));
    QCOMPARE(elapsed.count(), 1);
    QCOMPARE(elapsed.first().at(0).toLongLong(), qlonglong{0});

    // Fifty frames of 20 ms in stereo: one second.
    for (int i = 0; i < 50; ++i) {
        microphone->say(960, 48000, {1000, 3000});
    }
    QCOMPARE(recorder.elapsedMs(), qint64{1000});
    // Nothing for a frame that is not one.
    recorder.take(nullptr, 960, 2, 48000);
    recorder.take(nullptr, 0, 0, 0);
    QCOMPARE(recorder.elapsedMs(), qint64{1000});

    const AirVoiceDecoder::Pcm pcm = recorder.finish();
    QVERIFY(closed);
    QCOMPARE(pcm.sampleRate, 48000);
    QCOMPARE(pcm.samples.size(), size_t{48000});
    QVERIFY(std::all_of(pcm.samples.begin(), pcm.samples.end(),
                        [](int16_t sample) { return sample == 2000; }));
    QVERIFY(!recorder.isRecording());
    QVERIFY(!recorder.isRecordingFor(&form));
    QCOMPARE(ended.count(), 1);
    QVERIFY(recorder.finish().samples.empty());
    QCOMPARE(ended.count(), 1);
}

/** One note for the whole app: another waits until it is sent or cancelled. */
void TestAirVoiceRecorder::oneNoteAtATime()
{
    AirVoiceRecorder recorder;
    QObject first;
    QObject second;
    bool firstClosed = false;
    bool secondClosed = false;
    auto* const microphone = new FakeMicrophone(&firstClosed);
    QSignalSpy ended(&recorder, &AirVoiceRecorder::ended);
    QVERIFY(recorder.start(&first, own(microphone)));
    microphone->say(960, 48000, {500});
    QVERIFY(!recorder.start(&second, own(new FakeMicrophone(&secondClosed))));
    QVERIFY(secondClosed);
    QVERIFY(recorder.isRecordingFor(&first));
    QVERIFY(!recorder.start(nullptr, own(new FakeMicrophone(&secondClosed))));

    recorder.cancel();
    QVERIFY(firstClosed);
    QCOMPARE(ended.count(), 1);
    QVERIFY(!recorder.isRecording());
    QCOMPARE(recorder.elapsedMs(), qint64{0});
    recorder.cancel();
    QCOMPARE(ended.count(), 1);

    bool thirdClosed = false;
    QVERIFY(recorder.start(&second, own(new FakeMicrophone(&thirdClosed))));
    QVERIFY(recorder.isRecordingFor(&second));
    // The sound of the cancelled note is gone.
    QCOMPARE(recorder.elapsedMs(), qint64{0});
    recorder.cancel();
    QVERIFY(thirdClosed);
}

/** The note keeps the rate it started with, as the encoder writes one. */
void TestAirVoiceRecorder::anotherRateIsNotMixedIn()
{
    AirVoiceRecorder recorder;
    QObject form;
    bool closed = false;
    auto* const microphone = new FakeMicrophone(&closed);
    QVERIFY(recorder.start(&form, own(microphone)));
    microphone->say(960, 48000, {100});
    microphone->say(882, 44100, {100});
    const AirVoiceDecoder::Pcm pcm = recorder.finish();
    QCOMPARE(pcm.sampleRate, 48000);
    QCOMPARE(pcm.samples.size(), size_t{960});
}

/**
 * Five minutes is the longest note: past it no sound is taken, the microphone closes and the note
 * waits whole for Send.
 */
void TestAirVoiceRecorder::aNoteStopsAtItsLimitAndWaits()
{
    AirVoiceRecorder recorder;
    QObject form;
    bool closed = false;
    auto* const microphone = new FakeMicrophone(&closed);
    QSignalSpy limit(&recorder, &AirVoiceRecorder::limitReached);
    QVERIFY(recorder.start(&form, own(microphone)));
    // A second at a time at 8 kHz, one more than five minutes.
    const qint64 seconds = AirVoiceRecorder::longestMs / 1000;
    for (qint64 i = 0; i <= seconds; ++i) {
        microphone->say(8000, 8000, {700});
    }
    QCOMPARE(recorder.elapsedMs(), AirVoiceRecorder::longestMs);
    QVERIFY(limit.wait(2000));
    QCOMPARE(limit.count(), 1);
    QVERIFY(closed);
    QVERIFY(recorder.isRecordingFor(&form));
    QTest::qWait(250);
    QCOMPARE(limit.count(), 1);

    const AirVoiceDecoder::Pcm pcm = recorder.finish();
    QCOMPARE(pcm.sampleRate, 8000);
    QCOMPARE(pcm.samples.size(), static_cast<size_t>(seconds * 8000));
    QVERIFY(!recorder.isRecording());
}

/** A form closed while its note is recorded: nobody can send it, so it goes. */
void TestAirVoiceRecorder::aClosedFormTakesItsNoteWithIt()
{
    AirVoiceRecorder recorder;
    std::unique_ptr<QObject> form(new QObject());
    bool closed = false;
    auto* const microphone = new FakeMicrophone(&closed);
    QSignalSpy ended(&recorder, &AirVoiceRecorder::ended);
    QVERIFY(recorder.start(form.get(), own(microphone)));
    microphone->say(960, 48000, {100});
    form.reset();
    QVERIFY(ended.wait(2000));
    QVERIFY(closed);
    QVERIFY(!recorder.isRecording());
    QCOMPARE(recorder.elapsedMs(), qint64{0});
}

/**
 * A microphone that goes away with no other to carry on (no audio here) is closed; what it heard
 * stays for Send.
 */
void TestAirVoiceRecorder::anUnpluggedMicrophoneKeepsTheNote()
{
    AirVoiceRecorder recorder;
    QObject form;
    bool closed = false;
    auto* const microphone = new FakeMicrophone(&closed);
    QSignalSpy lost(&recorder, &AirVoiceRecorder::lost);
    QVERIFY(recorder.start(&form, own(microphone)));
    for (int i = 0; i < 100; ++i) {
        microphone->say(960, 48000, {300});
    }
    microphone->unplug();
    // Taken on the main thread, after the signal of the audio thread.
    QCOMPARE(lost.count(), 0);
    QVERIFY(lost.wait(2000));
    QVERIFY(closed);
    QVERIFY(recorder.isRecordingFor(&form));
    QCOMPARE(recorder.elapsedMs(), qint64{2000});
    const AirVoiceDecoder::Pcm pcm = recorder.finish();
    QCOMPARE(pcm.samples.size(), size_t{96000});
}

QTEST_GUILESS_MAIN(TestAirVoiceRecorder)
#include "airvoicerecorder_test.moc"
