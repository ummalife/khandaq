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

#include "airvoicerecorder.h"

#include "audio/iaudiocontrol.h"
#include "audio/iaudiosource.h"

#include <QMutexLocker>

namespace {
constexpr int tickMs = 100;
/** A minute of sound is set aside at the start; a longer note grows the buffer. */
constexpr qint64 firstReserveMs = 60 * 1000;
} // namespace

// Bound to references (qMax, QCOMPARE): C++11 wants them defined once.
constexpr qint64 AirVoiceRecorder::shortestMs;
constexpr qint64 AirVoiceRecorder::longestMs;

AirVoiceRecorder& AirVoiceRecorder::instance()
{
    static AirVoiceRecorder recorder;
    return recorder;
}

AirVoiceRecorder::AirVoiceRecorder(QObject* parent)
    : QObject(parent)
{
    ticker.setInterval(tickMs);
    connect(&ticker, &QTimer::timeout, this, &AirVoiceRecorder::tick);
}

AirVoiceRecorder::~AirVoiceRecorder()
{
    closeMicrophone();
}

void AirVoiceRecorder::setAudio(IAudioControl* audio_)
{
    if (audio == audio_) {
        return;
    }
    cancel();
    audio = audio_;
}

bool AirVoiceRecorder::hasAudio() const
{
    return audio != nullptr;
}

bool AirVoiceRecorder::start(QObject* owner)
{
#ifdef KHANDAQ_SCREENSHOT_HOOK
    if (testMicrophone) {
        return owner && !isRecording() && start(owner, testMicrophone());
    }
#endif
    if (!audio || !owner || isRecording()) {
        return false;
    }
    std::unique_ptr<IAudioSource> made = audio->makeSource();
    if (!made || !*made) {
        return false;
    }
    return start(owner, std::move(made));
}

#ifdef KHANDAQ_SCREENSHOT_HOOK
void AirVoiceRecorder::setTestMicrophone(std::function<std::unique_ptr<IAudioSource>()> make)
{
    testMicrophone = std::move(make);
}
#endif

bool AirVoiceRecorder::start(QObject* owner, std::unique_ptr<IAudioSource> given)
{
    if (!owner || !given || isRecording()) {
        return false;
    }
    {
        QMutexLocker locker(&lock);
        samples.clear();
        sampleRate = 0;
        full = false;
        paused = false;
    }
    active = true;
    limitSaid = false;
    ++generation;
    recordingFor = owner;
    listen(std::move(given));
    ticker.start();
    emit elapsedChanged(0);
    return true;
}

void AirVoiceRecorder::listen(std::unique_ptr<IAudioSource> given)
{
    source = std::move(given);
    source->setEveryFrame(true);
    // The frame lives only during the signal, on the audio thread: it is copied there.
    connect(source.get(), &IAudioSource::frameAvailable, this,
            [this](const int16_t* pcm, size_t frames, uint8_t channels, uint32_t rate) {
                take(pcm, frames, channels, rate);
            },
            Qt::DirectConnection);
    // Taken on the main thread, and only for the note it came during.
    const quint64 note = generation;
    connect(source.get(), &IAudioSource::invalidated, this, [this, note] { reopen(note); },
            Qt::QueuedConnection);
}

void AirVoiceRecorder::reopen(quint64 note)
{
    if (!active || generation != note || !source) {
        return;
    }
    std::unique_ptr<IAudioSource> made;
    if (audio) {
        made = audio->makeSource();
    }
    if (made && *made) {
        listen(std::move(made));
        return;
    }
    closeMicrophone();
    emit lost();
}

bool AirVoiceRecorder::isRecording() const
{
    return active;
}

bool AirVoiceRecorder::isRecordingFor(const QObject* owner) const
{
    return active && owner && recordingFor.data() == owner;
}

qint64 AirVoiceRecorder::elapsedMs() const
{
    QMutexLocker locker(&lock);
    if (sampleRate <= 0) {
        return 0;
    }
    return static_cast<qint64>(samples.size()) * 1000 / sampleRate;
}

bool AirVoiceRecorder::setPaused(bool paused_)
{
    if (!canPause()) {
        return false;
    }
    QMutexLocker locker(&lock);
    if (paused == paused_) {
        return false;
    }
    paused = paused_;
    return true;
}

bool AirVoiceRecorder::isPaused() const
{
    QMutexLocker locker(&lock);
    return paused;
}

bool AirVoiceRecorder::canPause() const
{
    return active && source != nullptr;
}

AirVoiceDecoder::Pcm AirVoiceRecorder::finish()
{
    AirVoiceDecoder::Pcm pcm;
    if (!active) {
        return pcm;
    }
    // The microphone closes first and without the lock: its last frame may be waiting for it.
    closeMicrophone();
    {
        QMutexLocker locker(&lock);
        pcm.samples.swap(samples);
        pcm.sampleRate = sampleRate;
        sampleRate = 0;
        full = false;
    }
    end();
    return pcm;
}

void AirVoiceRecorder::cancel()
{
    if (!active) {
        return;
    }
    closeMicrophone();
    {
        QMutexLocker locker(&lock);
        std::vector<int16_t>().swap(samples);
        sampleRate = 0;
        full = false;
    }
    end();
}

void AirVoiceRecorder::take(const int16_t* pcm, size_t frames, uint8_t channels, uint32_t rate)
{
    if (!pcm || frames == 0 || channels == 0 || rate == 0) {
        return;
    }
    QMutexLocker locker(&lock);
    // What the microphone hears during a pause is not part of the note.
    if (full || paused) {
        return;
    }
    if (sampleRate == 0) {
        sampleRate = static_cast<int>(rate);
        samples.reserve(static_cast<size_t>(firstReserveMs * sampleRate / 1000));
    }
    // One rate for the whole note, as the encoder writes it.
    if (static_cast<int>(rate) != sampleRate) {
        return;
    }
    const auto* const plane = reinterpret_cast<const uint8_t*>(pcm);
    AirVoiceDecoder::appendMono(&plane, AirVoiceDecoder::SampleKind::S16, false, channels,
                                static_cast<int>(frames), &samples);
    const size_t most = static_cast<size_t>(longestMs * sampleRate / 1000);
    if (samples.size() >= most) {
        samples.resize(most);
        full = true;
    }
}

void AirVoiceRecorder::tick()
{
    if (!active) {
        ticker.stop();
        return;
    }
    // The form that showed the note is gone: nobody can send it.
    if (!recordingFor) {
        cancel();
        return;
    }
    emit elapsedChanged(elapsedMs());
    bool reached;
    {
        QMutexLocker locker(&lock);
        reached = full;
    }
    if (reached && !limitSaid) {
        // The ticker goes on: a form closed while the note waits still lets it go.
        limitSaid = true;
        closeMicrophone();
        emit limitReached();
    }
}

void AirVoiceRecorder::closeMicrophone()
{
    source.reset();
    QMutexLocker locker(&lock);
    paused = false;
}

void AirVoiceRecorder::end()
{
    ticker.stop();
    active = false;
    recordingFor = nullptr;
    emit ended();
}
