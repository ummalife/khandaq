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

#pragma once

#include "src/model/air/airvoicedecoder.h"

#include <QMutex>
#include <QObject>
#include <QPointer>
#include <QTimer>

#include <cstdint>
#include <memory>
#include <vector>

#ifdef KHANDAQ_SCREENSHOT_HOOK
#include <functional>
#endif

class IAudioControl;
class IAudioSource;

/**
 * KHANDAQ (0.2.60): the voice note being recorded on the desktop, one at a time for the whole app.
 * The microphone is a source of IAudioControl that hears every frame, so the pauses of the speaker
 * stay; its frames come on the audio thread and are kept as one channel until the note is finished
 * (Send) or cancelled. A note lasts up to longestMs, which keeps it under the size the phones fetch
 * by themselves: there the microphone closes and the note waits for finish() or cancel().
 *
 * KHANDAQ (beta feedback 2026-11-22): a note can be paused and go on. The microphone stays open
 * meanwhile, so the note goes on at once, and what it hears is dropped: the note is one stretch of
 * sound without the pause, and its time stops with it.
 */
class AirVoiceRecorder : public QObject
{
    Q_OBJECT
public:
    /** A shorter note is not sent: a click, not a message. */
    static constexpr qint64 shortestMs = 1000;
    /** Five minutes at AirVoiceEncoder::bitRate are about 2.4 MB, under the 4 MB iOS fetches. */
    static constexpr qint64 longestMs = 5 * 60 * 1000;

    static AirVoiceRecorder& instance();
    explicit AirVoiceRecorder(QObject* parent = nullptr);
    ~AirVoiceRecorder() override;

    /** The microphone to record from; null cancels a note being recorded and records none. */
    void setAudio(IAudioControl* audio);
    bool hasAudio() const;

    /**
     * Starts a note for owner, the form whose bar shows it; false when one is being recorded
     * already or the microphone gives nothing.
     */
    bool start(QObject* owner);
    /** The same from a given source (start() makes one of the audio): the seam of the tests. */
    bool start(QObject* owner, std::unique_ptr<IAudioSource> source);
    bool isRecording() const;
    bool isRecordingFor(const QObject* owner) const;
    /** The length of the sound taken so far. */
    qint64 elapsedMs() const;

    /**
     * KHANDAQ (beta feedback 2026-11-22): pauses the note being recorded, or lets it go on. False
     * when nothing changed: no note, or no microphone open for it (canPause), or already so.
     */
    bool setPaused(bool paused);
    bool isPaused() const;
    /**
     * Whether the note being recorded has its microphone open, so that it can be paused: not once
     * it is longestMs long, nor after the microphone went away (lost).
     */
    bool canPause() const;

    /** Ends the note and hands over its sound, without its pauses; empty when none was recorded. */
    AirVoiceDecoder::Pcm finish();
    /** Ends the note and drops its sound. */
    void cancel();

    /**
     * Takes frames as the microphone gives them (interleaved, 16 bits), except while the note is
     * paused; any thread.
     */
    void take(const int16_t* pcm, size_t frames, uint8_t channels, uint32_t rate);

#ifdef KHANDAQ_SCREENSHOT_HOOK
    /**
     * Test builds only: start() takes its sound from make() instead of the microphone, for the
     * walks that send a voice note from a computer without one. Null: the microphone again.
     */
    void setTestMicrophone(std::function<std::unique_ptr<IAudioSource>()> make);
#endif

signals:
    /** Every tenth of a second while a note is recorded, and once as it starts. */
    void elapsedChanged(qint64 ms);
    /** The note is longestMs long: the microphone is closed, finish() hands the note over. */
    void limitReached();
    /**
     * The microphone went away during the note and no other one opens: it is closed, the sound
     * taken stays for finish() or cancel(). Another device chosen in Settings carries the note on.
     */
    void lost();
    /** A note stopped being recorded: finished, cancelled, or its form is gone. */
    void ended();

private:
    void listen(std::unique_ptr<IAudioSource> given);
    /** The device of the microphone changed: the note goes on from the new one, as a call does. */
    void reopen(quint64 note);
    void tick();
    /** Closes the microphone; the sound taken stays, and nothing is left to pause. */
    void closeMicrophone();
    void end();

    IAudioControl* audio = nullptr;
    bool active = false;
    QPointer<QObject> recordingFor;
    QTimer ticker;
    bool limitSaid = false;
    /** Which note a signal of the microphone, queued from the audio thread, belongs to. */
    quint64 generation = 0;
    /** What the audio thread writes, under lock. */
    mutable QMutex lock;
    std::vector<int16_t> samples;
    int sampleRate = 0;
    bool full = false;
    bool paused = false;
#ifdef KHANDAQ_SCREENSHOT_HOOK
    std::function<std::unique_ptr<IAudioSource>()> testMicrophone;
#endif
    /** Last, so that it goes first: no frame comes once it is gone. */
    std::unique_ptr<IAudioSource> source;
};
