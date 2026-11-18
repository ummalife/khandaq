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
#include "src/model/air/airvoiceencoder.h"

#include <QFile>
#include <QProcess>
#include <QRegularExpression>
#include <QSet>
#include <QTemporaryDir>
#include <QtTest/QtTest>

#include <cmath>

namespace {

constexpr double pi = 3.14159265358979323846;

/** Samples of a 440 Hz tone at half strength, one channel at rate. */
std::vector<int16_t> tone(int rate, size_t count)
{
    std::vector<int16_t> samples(count);
    for (size_t i = 0; i < count; ++i) {
        samples[i] = static_cast<int16_t>(16000.0 * std::sin(2.0 * pi * 440.0 * i / rate));
    }
    return samples;
}

double rms(const std::vector<int16_t>& samples)
{
    double sum = 0;
    for (const int16_t sample : samples) {
        sum += static_cast<double>(sample) * sample;
    }
    return samples.empty() ? 0 : std::sqrt(sum / samples.size());
}

QByteArray head(const QString& path, int bytes)
{
    QFile file(path);
    return file.open(QIODevice::ReadOnly) ? file.read(bytes) : QByteArray();
}

/** What the macOS reader of audio files (that of iOS) says of path; empty where there is none. */
QString appleSays(const QString& path)
{
    const QString tool = QStringLiteral("/usr/bin/afinfo");
    if (!QFile::exists(tool)) {
        return QString();
    }
    QProcess process;
    process.start(tool, {path});
    if (!process.waitForFinished(30000) || process.exitStatus() != QProcess::NormalExit
        || process.exitCode() != 0) {
        return QStringLiteral("unreadable");
    }
    return QString::fromUtf8(process.readAllStandardOutput());
}

} // namespace

class TestAirVoiceEncoder : public QObject
{
    Q_OBJECT
private slots:
    void initTestCase();
    void aNoteIsWrittenAsThePhonesWriteTheirs();
    void anyLengthAndRateComeWhole();
    void whatCannotBeWrittenLeavesNothing();
    void aPartLeftBeforeIsReplaced();
    void newNamesAreVoiceNotes();
};

void TestAirVoiceEncoder::initTestCase()
{
    if (!AirVoiceEncoder::canEncode() || !AirVoiceDecoder::canDecode()) {
        QSKIP("This FFmpeg writes or reads no AAC note");
    }
}

/**
 * Two and a half seconds of sound become an M4A file of AAC at 64 kbit/s that the desktop reads
 * back, whose header says its length, and that Apple's reader (that of iOS) takes as one channel
 * of AAC at the rate of the microphone.
 */
void TestAirVoiceEncoder::aNoteIsWrittenAsThePhonesWriteTheirs()
{
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString note = dir.filePath(QStringLiteral("voice_0A1B2C3D.file.m4a"));
    QVERIFY(AirVoiceEncoder::encode(tone(48000, 120000), 48000, note));
    QVERIFY(QFile::exists(note));
    QVERIFY(!QFile::exists(note + QStringLiteral(".part")));
    QVERIFY(Air::isVoiceNote(QFileInfo(note).fileName()));

    // An MP4 of the iTunes kind, as the phones write: "ftyp" with the brand "M4A ".
    const QByteArray start = head(note, 12);
    QCOMPARE(start.mid(4, 4), QByteArray("ftyp"));
    QCOMPARE(start.mid(8, 4), QByteArray("M4A "));
    // 64 kbit/s: 2.5 s are about 20 KB.
    const qint64 size = QFileInfo(note).size();
    QVERIFY2(size > 10000 && size < 40000, qPrintable(QString::number(size)));

    AirVoiceDecoder::Pcm pcm;
    QVERIFY(AirVoiceDecoder::decode(note, &pcm));
    QCOMPARE(pcm.sampleRate, 48000);
    // AAC adds a little silence at the edges.
    QVERIFY2(qAbs(pcm.lengthMs() - 2500) < 100, qPrintable(QString::number(pcm.lengthMs())));
    QVERIFY(rms(pcm.samples) > 9000);
    QFile file(note);
    QVERIFY(file.open(QIODevice::ReadOnly));
    QVERIFY(qAbs(Air::mp4DurationMs(file) - 2500) < 100);

    const QString apple = appleSays(note);
    if (!apple.isEmpty()) {
        QVERIFY2(apple.contains(QRegularExpression(QStringLiteral("1 ch,\\s+48000 Hz, aac "))),
                 qPrintable(apple));
        QVERIFY2(apple.contains(QStringLiteral("Channel layout: Mono")), qPrintable(apple));
        // Apple takes the edit list: every sample of the sound plays, none of the priming. Without
        // it the reader guesses 2112 samples of priming and cuts the start of the speech.
        const QRegularExpressionMatch frames =
            QRegularExpression(QStringLiteral("audio (\\d+) valid frames \\+ (\\d+) priming"))
                .match(apple);
        QVERIFY2(frames.hasMatch(), qPrintable(apple));
        QCOMPARE(frames.captured(1).toInt(), 120000);
        QVERIFY2(frames.captured(2).toInt() != 2112, qPrintable(apple));
        const QRegularExpressionMatch seconds =
            QRegularExpression(QStringLiteral("estimated duration: ([0-9.]+) sec")).match(apple);
        QVERIFY2(seconds.hasMatch(), qPrintable(apple));
        QVERIFY(qAbs(seconds.captured(1).toDouble() - 2.5) < 0.1);
    }
}

/** A length that is no whole number of AAC frames, and the rate of another microphone. */
void TestAirVoiceEncoder::anyLengthAndRateComeWhole()
{
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString note = dir.filePath(QStringLiteral("voice_00000001.file.m4a"));
    // A second and a third of a frame at 44.1 kHz.
    QVERIFY(AirVoiceEncoder::encode(tone(44100, 44100 + 333), 44100, note));
    AirVoiceDecoder::Pcm pcm;
    QVERIFY(AirVoiceDecoder::decode(note, &pcm));
    QCOMPARE(pcm.sampleRate, 44100);
    QVERIFY2(qAbs(pcm.lengthMs() - 1008) < 100, qPrintable(QString::number(pcm.lengthMs())));

    // Silence is a note too: the pauses of a speaker stay.
    const QString quiet = dir.filePath(QStringLiteral("voice_00000002.file.m4a"));
    QVERIFY(AirVoiceEncoder::encode(std::vector<int16_t>(48000, 0), 48000, quiet));
    QVERIFY(AirVoiceDecoder::decode(quiet, &pcm));
    QVERIFY(qAbs(pcm.lengthMs() - 1000) < 100);
    QVERIFY(rms(pcm.samples) < 50);
}

/** No sound, a rate the encoder does not take, a name taken or no folder: nothing is written. */
void TestAirVoiceEncoder::whatCannotBeWrittenLeavesNothing()
{
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString note = dir.filePath(QStringLiteral("voice_0000000A.file.m4a"));
    const std::vector<int16_t> second = tone(48000, 48000);

    QVERIFY(!AirVoiceEncoder::encode(std::vector<int16_t>(), 48000, note));
    QVERIFY(!AirVoiceEncoder::encode(second, 4000, note));
    QVERIFY(!AirVoiceEncoder::encode(second, 192000, note));
    QVERIFY(!AirVoiceEncoder::encode(second, 48000, QString()));
    QVERIFY(!QFile::exists(note));
    QVERIFY(!QFile::exists(note + QStringLiteral(".part")));

    // A file of that name stays as it was.
    QFile taken(note);
    QVERIFY(taken.open(QIODevice::WriteOnly));
    taken.write("mine");
    taken.close();
    QVERIFY(!AirVoiceEncoder::encode(second, 48000, note));
    QCOMPARE(head(note, 16), QByteArray("mine"));
    QVERIFY(!QFile::exists(note + QStringLiteral(".part")));

    const QString nowhere = dir.filePath(QStringLiteral("missing/voice_0000000B.file.m4a"));
    QVERIFY(!AirVoiceEncoder::encode(second, 48000, nowhere));
    QVERIFY(!QFile::exists(nowhere));
    QVERIFY(!QFile::exists(nowhere + QStringLiteral(".part")));
}

/** A part an interrupted note left gives way to the new one. */
void TestAirVoiceEncoder::aPartLeftBeforeIsReplaced()
{
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString note = dir.filePath(QStringLiteral("voice_0000000C.file.m4a"));
    QFile part(note + QStringLiteral(".part"));
    QVERIFY(part.open(QIODevice::WriteOnly));
    part.write(QByteArray(4096, 'x'));
    part.close();
    QVERIFY(AirVoiceEncoder::encode(tone(48000, 48000), 48000, note));
    QVERIFY(!QFile::exists(note + QStringLiteral(".part")));
    AirVoiceDecoder::Pcm pcm;
    QVERIFY(AirVoiceDecoder::decode(note, &pcm));
    QVERIFY(qAbs(pcm.lengthMs() - 1000) < 100);
}

/** "voice_", eight hexadecimal digits, ".file.m4a": the name the phones play as a voice note. */
void TestAirVoiceEncoder::newNamesAreVoiceNotes()
{
    const QRegularExpression shape(QStringLiteral("^voice_[0-9A-F]{8}\\.file\\.m4a$"));
    QSet<QString> names;
    for (int i = 0; i < 64; ++i) {
        const QString name = AirVoiceEncoder::newNoteName();
        QVERIFY2(shape.match(name).hasMatch(), qPrintable(name));
        QVERIFY(Air::isVoiceNote(name));
        names.insert(name);
    }
    QCOMPARE(names.size(), 64);
}

QTEST_GUILESS_MAIN(TestAirVoiceEncoder)
#include "airvoiceencoder_test.moc"
