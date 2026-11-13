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

#include "src/model/air/airmedia.h"
#include "src/model/air/airvoicedecoder.h"

#include <QDataStream>
#include <QFile>
#include <QProcess>
#include <QTemporaryDir>
#include <QtTest/QtTest>

#include <cmath>
#include <cstring>

using AirVoiceDecoder::SampleKind;

namespace {

constexpr double pi = 3.14159265358979323846;

/** Seconds of a 440 Hz tone at half strength, mono 16-bit at rate, as a WAV file. */
bool writeTone(const QString& path, int rate, int seconds)
{
    QFile file(path);
    if (!file.open(QIODevice::WriteOnly)) {
        return false;
    }
    const quint32 samples = static_cast<quint32>(rate * seconds);
    QDataStream out(&file);
    out.setByteOrder(QDataStream::LittleEndian);
    out.writeRawData("RIFF", 4);
    out << static_cast<quint32>(36 + samples * 2);
    out.writeRawData("WAVEfmt ", 8);
    out << quint32{16} << quint16{1} << quint16{1} << static_cast<quint32>(rate)
        << static_cast<quint32>(rate * 2) << quint16{2} << quint16{16};
    out.writeRawData("data", 4);
    out << static_cast<quint32>(samples * 2);
    for (quint32 i = 0; i < samples; ++i) {
        out << static_cast<qint16>(16000.0 * std::sin(2.0 * pi * 440.0 * i / rate));
    }
    return out.status() == QDataStream::Ok;
}

/** The tone as an M4A file of the given format through the macOS converter; false where there is none. */
bool convert(const QString& wav, const QString& m4a, const QString& format)
{
    const QString tool = QStringLiteral("/usr/bin/afconvert");
    if (!QFile::exists(tool)) {
        return false;
    }
    QProcess process;
    process.start(tool, {QStringLiteral("-f"), QStringLiteral("m4af"), QStringLiteral("-d"), format,
                         wav, m4a});
    return process.waitForFinished(30000) && process.exitStatus() == QProcess::NormalExit
           && process.exitCode() == 0 && QFile::exists(m4a);
}

double rms(const std::vector<int16_t>& samples)
{
    double sum = 0;
    for (const int16_t sample : samples) {
        sum += static_cast<double>(sample) * sample;
    }
    return samples.empty() ? 0 : std::sqrt(sum / samples.size());
}

template <typename T>
QByteArray bytesOf(std::initializer_list<T> values)
{
    QByteArray bytes;
    for (const T value : values) {
        char raw[sizeof(T)];
        std::memcpy(raw, &value, sizeof(T));
        bytes.append(raw, sizeof(T));
    }
    return bytes;
}

std::vector<int16_t> mono(const QByteArray& data, SampleKind kind, int channels, int frames)
{
    const auto* const plane = reinterpret_cast<const uint8_t*>(data.constData());
    std::vector<int16_t> out;
    AirVoiceDecoder::appendMono(&plane, kind, false, channels, frames, &out);
    return out;
}

} // namespace

class TestAirVoiceDecoder : public QObject
{
    Q_OBJECT
private slots:
    void channelsAreAveragedIntoOne();
    void everySampleTypeLandsInSixteenBits();
    void planarFramesTakeOnePlanePerChannel();
    void nothingIsAddedForNoFrames();
    void aVoiceNoteOfThePhonesIsRead();
    void onlyAacIsRead();
    void filesThatAreNotNotesAreRefused();
};

void TestAirVoiceDecoder::channelsAreAveragedIntoOne()
{
    const std::vector<int16_t> out =
        mono(bytesOf<int16_t>({1000, 3000, -32768, -32768, 32767, -32767}), SampleKind::S16, 2, 3);
    QCOMPARE(out.size(), size_t{3});
    QCOMPARE(out[0], int16_t{2000});
    QCOMPARE(out[1], int16_t{-32767});
    QCOMPARE(out[2], int16_t{0});
}

void TestAirVoiceDecoder::everySampleTypeLandsInSixteenBits()
{
    // Floats out of range are clipped, not wrapped.
    std::vector<int16_t> out = mono(bytesOf<float>({0.5f, 2.0f, -3.0f}), SampleKind::Float, 1, 3);
    QCOMPARE(out, (std::vector<int16_t>{16384, 32767, -32767}));
    out = mono(bytesOf<double>({-0.25}), SampleKind::Double, 1, 1);
    QCOMPARE(out, std::vector<int16_t>{-8192});
    out = mono(bytesOf<uint8_t>({128, 255, 0}), SampleKind::U8, 1, 3);
    QCOMPARE(out, (std::vector<int16_t>{0, 32511, -32767}));
    out = mono(bytesOf<int32_t>({1 << 30}), SampleKind::S32, 1, 1);
    QCOMPARE(out, std::vector<int16_t>{16384});
}

void TestAirVoiceDecoder::planarFramesTakeOnePlanePerChannel()
{
    const QByteArray left = bytesOf<float>({1.0f, 0.0f});
    const QByteArray right = bytesOf<float>({0.0f, -1.0f});
    const uint8_t* const planes[] = {reinterpret_cast<const uint8_t*>(left.constData()),
                                     reinterpret_cast<const uint8_t*>(right.constData())};
    std::vector<int16_t> out{7};
    AirVoiceDecoder::appendMono(planes, SampleKind::Float, true, 2, 2, &out);
    // Appended after what was there.
    QCOMPARE(out, (std::vector<int16_t>{7, 16384, -16384}));
}

void TestAirVoiceDecoder::nothingIsAddedForNoFrames()
{
    std::vector<int16_t> out;
    const QByteArray data = bytesOf<int16_t>({1, 2});
    const auto* const plane = reinterpret_cast<const uint8_t*>(data.constData());
    AirVoiceDecoder::appendMono(&plane, SampleKind::S16, false, 0, 1, &out);
    AirVoiceDecoder::appendMono(&plane, SampleKind::S16, false, 1, 0, &out);
    AirVoiceDecoder::appendMono(nullptr, SampleKind::S16, false, 1, 1, &out);
    QVERIFY(out.empty());
}

void TestAirVoiceDecoder::aVoiceNoteOfThePhonesIsRead()
{
    if (!AirVoiceDecoder::canDecode()) {
        QSKIP("This FFmpeg reads no M4A or no AAC: notes go to the system player");
    }
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString wav = dir.filePath(QStringLiteral("tone.wav"));
    const QString note = dir.filePath(QStringLiteral("voice_3F9A2C71.file.m4a"));
    QVERIFY(writeTone(wav, 48000, 2));
    if (!convert(wav, note, QStringLiteral("aac"))) {
        QSKIP("No converter to make an AAC note with");
    }

    AirVoiceDecoder::Pcm pcm;
    QVERIFY(AirVoiceDecoder::decode(note, &pcm));
    QCOMPARE(pcm.sampleRate, 48000);
    // AAC adds a little silence at the edges.
    QVERIFY(qAbs(pcm.lengthMs() - 2000) < 100);
    QVERIFY(rms(pcm.samples) > 5000);

    // The header says the same length without decoding anything.
    QFile file(note);
    QVERIFY(file.open(QIODevice::ReadOnly));
    QVERIFY(qAbs(Air::mp4DurationMs(file) - 2000) < 100);
}

void TestAirVoiceDecoder::onlyAacIsRead()
{
    if (!AirVoiceDecoder::canDecode()) {
        QSKIP("This FFmpeg reads no M4A or no AAC");
    }
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString wav = dir.filePath(QStringLiteral("tone.wav"));
    const QString lossless = dir.filePath(QStringLiteral("voice_lossless.file.m4a"));
    QVERIFY(writeTone(wav, 44100, 1));
    if (!convert(wav, lossless, QStringLiteral("alac"))) {
        QSKIP("No converter to make an ALAC file with");
    }
    AirVoiceDecoder::Pcm pcm;
    pcm.samples = {1, 2, 3};
    pcm.sampleRate = 8000;
    QVERIFY(!AirVoiceDecoder::decode(lossless, &pcm));
    QVERIFY(pcm.samples.empty());
    QCOMPARE(pcm.sampleRate, 0);
}

void TestAirVoiceDecoder::filesThatAreNotNotesAreRefused()
{
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    AirVoiceDecoder::Pcm pcm;

    // Not there, empty, and a WAV under the name of a note.
    QVERIFY(!AirVoiceDecoder::decode(dir.filePath(QStringLiteral("missing.file.m4a")), &pcm));
    const QString empty = dir.filePath(QStringLiteral("empty.file.m4a"));
    QFile emptyFile(empty);
    QVERIFY(emptyFile.open(QIODevice::WriteOnly));
    emptyFile.close();
    QVERIFY(!AirVoiceDecoder::decode(empty, &pcm));
    const QString wav = dir.filePath(QStringLiteral("tone.file.m4a"));
    QVERIFY(writeTone(wav, 44100, 1));
    QVERIFY(!AirVoiceDecoder::decode(wav, &pcm));

    // Larger than a note can be: refused before anything is read.
    const QString huge = dir.filePath(QStringLiteral("huge.file.m4a"));
    QFile hugeFile(huge);
    QVERIFY(hugeFile.open(QIODevice::WriteOnly));
    QVERIFY(hugeFile.resize(33LL * 1024 * 1024));
    hugeFile.close();
    QVERIFY(!AirVoiceDecoder::decode(huge, &pcm));
    QVERIFY(pcm.samples.empty());
}

QTEST_GUILESS_MAIN(TestAirVoiceDecoder)
#include "airvoicedecoder_test.moc"
