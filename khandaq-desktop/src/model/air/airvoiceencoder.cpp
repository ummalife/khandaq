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

#include "airvoiceencoder.h"

#include "airvoicedecoder.h"

#include <QDebug>
#include <QFile>
#include <QRandomGenerator>

#include <algorithm>
#include <cerrno>
#include <limits>

extern "C"
{
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wold-style-cast"
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/samplefmt.h>
#pragma GCC diagnostic pop
}

namespace AirVoiceEncoder {

namespace {
// The rates of AAC from 8 to 48 kHz: the microphone gives 48 kHz, and the sample entry of an M4A
// file holds its rate in 16 bits.
constexpr int lowestSampleRate = 8000;
constexpr int highestSampleRate = 48000;
const QString partSuffix = QStringLiteral(".part");

/** What FFmpeg's AAC encoder makes of a note: raw AAC frames and how to read them. */
struct Coded
{
    std::vector<QByteArray> frames;
    /** The AudioSpecificConfig of the stream (ISO 14496-3), for the esds box. */
    QByteArray config;
    /** Samples of the encoder's delay at the start (1024 for AAC), which the edit list skips. */
    int priming = 0;
    int frameSize = 1024;
};

/** Everything FFmpeg allocates for one note, freed in one place. */
struct Session
{
    AVCodecContext* codec = nullptr;
    AVFrame* frame = nullptr;
    AVPacket* packet = nullptr;

    Session() = default;
    ~Session()
    {
        av_packet_free(&packet);
        av_frame_free(&frame);
        avcodec_free_context(&codec);
    }

    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;
};

void setMono(AVCodecContext* codec)
{
#if LIBAVUTIL_VERSION_INT >= AV_VERSION_INT(57, 24, 100)
    av_channel_layout_default(&codec->ch_layout, 1);
#else
    codec->channels = 1;
    codec->channel_layout = AV_CH_LAYOUT_MONO;
#endif
}

bool setMono(AVFrame* frame, const AVCodecContext* codec)
{
#if LIBAVUTIL_VERSION_INT >= AV_VERSION_INT(57, 24, 100)
    return av_channel_layout_copy(&frame->ch_layout, &codec->ch_layout) >= 0;
#else
    frame->channels = codec->channels;
    frame->channel_layout = codec->channel_layout;
    return true;
#endif
}

/** Takes every frame the encoder has ready; false on an error. */
bool drain(Session& session, Coded* out)
{
    while (true) {
        const int got = avcodec_receive_packet(session.codec, session.packet);
        if (got == AVERROR(EAGAIN) || got == AirVoiceDecoder::endOfFile) {
            return true;
        }
        if (got < 0) {
            return false;
        }
        out->frames.emplace_back(reinterpret_cast<const char*>(session.packet->data),
                                 session.packet->size);
        av_packet_unref(session.packet);
    }
}

/** The note as AAC-LC frames of one channel; false on any error. */
bool code(const std::vector<int16_t>& samples, int sampleRate, Coded* out)
{
    const AVCodec* const encoder = avcodec_find_encoder(AV_CODEC_ID_AAC);
    if (!encoder) {
        return false;
    }
    Session session;
    session.codec = avcodec_alloc_context3(encoder);
    session.frame = av_frame_alloc();
    session.packet = av_packet_alloc();
    if (!session.codec || !session.frame || !session.packet) {
        return false;
    }
    // AAC-LC, the default profile of FFmpeg's encoder, as the phones record it. The configuration
    // of the stream goes to the extradata (the esds box), the frames come raw.
    session.codec->sample_fmt = AV_SAMPLE_FMT_FLTP;
    session.codec->sample_rate = sampleRate;
    session.codec->bit_rate = bitRate;
    session.codec->time_base = AVRational{1, sampleRate};
    session.codec->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    setMono(session.codec);
    if (avcodec_open2(session.codec, encoder, nullptr) < 0 || !session.codec->extradata
        || session.codec->extradata_size < 2) {
        return false;
    }
    out->config = QByteArray(reinterpret_cast<const char*>(session.codec->extradata),
                             session.codec->extradata_size);
    out->priming = std::max(0, session.codec->initial_padding);
    out->frameSize = session.codec->frame_size > 0 ? session.codec->frame_size : 1024;

    // The encoder takes frames of frame_size samples; only the last one may be shorter.
    const size_t frameSize = static_cast<size_t>(out->frameSize);
    size_t done = 0;
    while (done < samples.size()) {
        const size_t count = std::min(frameSize, samples.size() - done);
        AVFrame* const frame = session.frame;
        frame->nb_samples = static_cast<int>(count);
        frame->format = session.codec->sample_fmt;
        frame->sample_rate = sampleRate;
        if (!setMono(frame, session.codec) || av_frame_get_buffer(frame, 0) < 0) {
            av_frame_unref(frame);
            return false;
        }
        auto* const plane = reinterpret_cast<float*>(frame->data[0]);
        for (size_t i = 0; i < count; ++i) {
            plane[i] = static_cast<float>(samples[done + i]) / 32768.0f;
        }
        frame->pts = static_cast<int64_t>(done);
        const bool sent = avcodec_send_frame(session.codec, frame) >= 0;
        av_frame_unref(frame);
        if (!sent || !drain(session, out)) {
            return false;
        }
        done += count;
    }
    // What the encoder still holds.
    return avcodec_send_frame(session.codec, nullptr) >= 0 && drain(session, out)
           && !out->frames.empty();
}

/**
 * ISO base media boxes (ISO 14496-12), big-endian, written into one buffer: open() leaves room for
 * the size, close() writes it once the box is whole.
 */
class Boxes
{
public:
    void u8(uint32_t value)
    {
        bytes.append(static_cast<char>(static_cast<uint8_t>(value & 0xFF)));
    }
    void u16(uint32_t value)
    {
        u8(value >> 8);
        u8(value);
    }
    void u24(uint32_t value)
    {
        u8(value >> 16);
        u16(value & 0xFFFF);
    }
    void u32(uint32_t value)
    {
        u16(value >> 16);
        u16(value & 0xFFFF);
    }
    void type(const char* fourcc)
    {
        bytes.append(fourcc, 4);
    }
    void zeros(int count)
    {
        bytes.append(QByteArray(count, '\0'));
    }
    void raw(const QByteArray& data)
    {
        bytes.append(data);
    }
    /** The unity matrix of mvhd and tkhd. */
    void matrix()
    {
        const uint32_t unity[9] = {0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000};
        for (const uint32_t value : unity) {
            u32(value);
        }
    }
    int open(const char* fourcc)
    {
        const int at = bytes.size();
        u32(0);
        type(fourcc);
        return at;
    }
    int openFull(const char* fourcc, uint32_t version, uint32_t flags)
    {
        const int at = open(fourcc);
        u8(version);
        u24(flags);
        return at;
    }
    void close(int at)
    {
        const auto size = static_cast<uint32_t>(bytes.size() - at);
        for (int i = 0; i < 4; ++i) {
            bytes[at + i] = static_cast<char>(static_cast<uint8_t>((size >> (24 - 8 * i)) & 0xFF));
        }
    }
    /** A descriptor of esds (ISO 14496-1): its tag, then its size in four bytes of 7 bits. */
    void descriptor(uint32_t tag, uint32_t size)
    {
        u8(tag);
        u8(((size >> 21) & 0x7F) | 0x80);
        u8(((size >> 14) & 0x7F) | 0x80);
        u8(((size >> 7) & 0x7F) | 0x80);
        u8(size & 0x7F);
    }

    QByteArray bytes;
};

/**
 * Where the sound lies in the coded frames. The priming of the encoder comes first; the last frame
 * kept holds the end of the sound and lasts only to it, so the track is as long as the priming and
 * the sound together. Apple's reader (that of iOS) takes the edit list only then; otherwise it
 * guesses 2112 samples of priming and cuts the start of the sound.
 */
struct Span
{
    /** Samples of sound the edit list shows after the priming. */
    uint32_t shown = 0;
    /** The length of the track in samples: the priming and the sound. */
    uint32_t length = 0;
    /** The frames the file keeps; any after them hold only the padding of the encoder. */
    uint32_t frames = 0;
};

Span spanOf(const Coded& coded, uint32_t soundSamples)
{
    const auto frameSize = static_cast<uint64_t>(coded.frameSize);
    const auto priming = static_cast<uint64_t>(coded.priming);
    const uint64_t codedSamples = static_cast<uint64_t>(coded.frames.size()) * frameSize;
    Span span;
    span.shown = static_cast<uint32_t>(
        std::min<uint64_t>(soundSamples, codedSamples > priming ? codedSamples - priming : 0));
    span.length = static_cast<uint32_t>(priming + span.shown);
    span.frames = static_cast<uint32_t>((span.length + frameSize - 1) / frameSize);
    return span;
}

/**
 * ftyp and moov of an M4A file with one AAC track, as the phones and iTunes write it: one chunk
 * of frames (stco points at the data of mdat, which follows), and an edit list that skips the
 * priming of the encoder, so the length is that of the sound. Times are zero: a note does not say
 * when it was recorded beyond its message. The movie and the track share the timescale (the
 * rate): Android before 10 reads the start of the edit in the timescale of the movie.
 */
QByteArray header(const Coded& coded, int sampleRate, const Span& span, uint32_t dataOffset)
{
    const auto frameSize = static_cast<uint32_t>(coded.frameSize);
    const auto rate = static_cast<uint32_t>(sampleRate);
    const auto priming = static_cast<uint32_t>(coded.priming);
    const uint32_t frames = span.frames;
    const uint32_t shown = span.shown;
    uint32_t largest = 0;
    uint64_t total = 0;
    for (uint32_t i = 0; i < frames; ++i) {
        largest = std::max(largest, static_cast<uint32_t>(coded.frames[i].size()));
        total += static_cast<uint64_t>(coded.frames[i].size());
    }
    const uint64_t keptSamples = static_cast<uint64_t>(frames) * frameSize;
    const auto average = static_cast<uint32_t>(
        keptSamples > 0 ? total * 8 * rate / keptSamples : static_cast<uint64_t>(bitRate));

    Boxes out;
    const int ftyp = out.open("ftyp");
    out.type("M4A ");
    out.u32(0);
    out.type("M4A ");
    out.type("mp42");
    out.type("isom");
    out.close(ftyp);

    const int moov = out.open("moov");
    const int mvhd = out.openFull("mvhd", 0, 0);
    out.u32(0); // creation
    out.u32(0); // modification
    out.u32(rate);
    out.u32(shown);
    out.u32(0x00010000); // rate 1.0
    out.u16(0x0100);     // volume 1.0
    out.zeros(10);
    out.matrix();
    out.zeros(24);
    out.u32(2); // the next track
    out.close(mvhd);

    const int trak = out.open("trak");
    const int tkhd = out.openFull("tkhd", 0, 0x000003); // enabled, in the movie
    out.u32(0);
    out.u32(0);
    out.u32(1); // track 1
    out.u32(0);
    out.u32(shown);
    out.zeros(8);
    out.u16(0);      // layer
    out.u16(1);      // alternate group: the sound
    out.u16(0x0100); // volume 1.0
    out.u16(0);
    out.matrix();
    out.u32(0); // width
    out.u32(0); // height
    out.close(tkhd);

    const int edts = out.open("edts");
    const int elst = out.openFull("elst", 0, 0);
    out.u32(1);
    out.u32(shown);   // in the timescale of mvhd
    out.u32(priming); // in that of mdhd
    out.u16(1);       // rate 1.0
    out.u16(0);
    out.close(elst);
    out.close(edts);

    const int mdia = out.open("mdia");
    const int mdhd = out.openFull("mdhd", 0, 0);
    out.u32(0);
    out.u32(0);
    out.u32(rate);
    out.u32(span.length);
    out.u16(0x55C4); // "und"
    out.u16(0);
    out.close(mdhd);
    const int hdlr = out.openFull("hdlr", 0, 0);
    out.u32(0);
    out.type("soun");
    out.zeros(12);
    out.raw(QByteArray("SoundHandler", 13)); // with its terminating zero
    out.close(hdlr);

    const int minf = out.open("minf");
    const int smhd = out.openFull("smhd", 0, 0);
    out.u16(0); // balance
    out.u16(0);
    out.close(smhd);
    const int dinf = out.open("dinf");
    const int dref = out.openFull("dref", 0, 0);
    out.u32(1);
    const int url = out.openFull("url ", 0, 0x000001); // the data is in this file
    out.close(url);
    out.close(dref);
    out.close(dinf);

    const int stbl = out.open("stbl");
    const int stsd = out.openFull("stsd", 0, 0);
    out.u32(1);
    const int mp4a = out.open("mp4a");
    out.zeros(6);
    out.u16(1); // data reference
    out.zeros(8);
    out.u16(1);  // one channel
    out.u16(16); // bits of a sample
    out.u16(0);
    out.u16(0);
    out.u32(rate << 16);
    const int esds = out.openFull("esds", 0, 0);
    const auto config = static_cast<uint32_t>(coded.config.size());
    const uint32_t decoderConfig = 13 + 5 + config;
    out.descriptor(0x03, 3 + 5 + decoderConfig + 5 + 1); // ES_Descriptor
    out.u16(1);                                         // ES_ID
    out.u8(0);
    out.descriptor(0x04, decoderConfig); // DecoderConfigDescriptor
    out.u8(0x40);                        // MPEG-4 audio
    out.u8(0x15);                        // an audio stream
    out.u24(largest);
    out.u32(std::max(average, static_cast<uint32_t>(bitRate)));
    out.u32(average);
    out.descriptor(0x05, config); // DecoderSpecificInfo: the AudioSpecificConfig
    out.raw(coded.config);
    out.descriptor(0x06, 1); // SLConfigDescriptor
    out.u8(0x02);
    out.close(esds);
    out.close(mp4a);
    out.close(stsd);

    // Every frame lasts frameSize samples but the last, which lasts to the end of the sound.
    const uint32_t whole = frames - 1;
    const uint32_t last = span.length - whole * frameSize;
    const int stts = out.openFull("stts", 0, 0);
    if (last == frameSize || whole == 0) {
        out.u32(1);
        out.u32(frames);
        out.u32(last);
    } else {
        out.u32(2);
        out.u32(whole);
        out.u32(frameSize);
        out.u32(1);
        out.u32(last);
    }
    out.close(stts);
    const int stsc = out.openFull("stsc", 0, 0);
    out.u32(1);
    out.u32(1);      // the first chunk
    out.u32(frames); // holds every frame
    out.u32(1);
    out.close(stsc);
    const int stsz = out.openFull("stsz", 0, 0);
    out.u32(0);
    out.u32(frames);
    for (uint32_t i = 0; i < frames; ++i) {
        out.u32(static_cast<uint32_t>(coded.frames[i].size()));
    }
    out.close(stsz);
    const int stco = out.openFull("stco", 0, 0);
    out.u32(1);
    out.u32(dataOffset);
    out.close(stco);
    // A frame of AAC needs the one before it: a seek starts a frame early ('roll', distance -1).
    const int sgpd = out.openFull("sgpd", 1, 0);
    out.type("roll");
    out.u32(2); // the length of an entry
    out.u32(1);
    out.u16(0xFFFF);
    out.close(sgpd);
    const int sbgp = out.openFull("sbgp", 0, 0);
    out.type("roll");
    out.u32(1);
    out.u32(frames);
    out.u32(1);
    out.close(sbgp);
    out.close(stbl);
    out.close(minf);
    out.close(mdia);
    out.close(trak);
    out.close(moov);
    return out.bytes;
}

/** The whole file: ftyp, moov, then mdat with the frames. */
QByteArray m4a(const Coded& coded, int sampleRate, uint32_t soundSamples)
{
    const Span span = spanOf(coded, soundSamples);
    if (span.shown == 0 || span.frames > coded.frames.size()) {
        return QByteArray();
    }
    uint64_t data = 0;
    for (uint32_t i = 0; i < span.frames; ++i) {
        data += static_cast<uint64_t>(coded.frames[i].size());
    }
    // A note is minutes long, never gigabytes.
    if (data + 8 > std::numeric_limits<uint32_t>::max() / 2) {
        return QByteArray();
    }
    // The header keeps its size whatever offset it holds: built once to learn where the data
    // starts, then with that offset.
    const QByteArray sized = header(coded, sampleRate, span, 0);
    const auto offset = static_cast<uint32_t>(sized.size()) + 8;
    QByteArray file = header(coded, sampleRate, span, offset);
    file.reserve(file.size() + 8 + static_cast<int>(data));
    Boxes mdat;
    mdat.u32(static_cast<uint32_t>(data) + 8);
    mdat.type("mdat");
    file.append(mdat.bytes);
    for (uint32_t i = 0; i < span.frames; ++i) {
        file.append(coded.frames[i]);
    }
    return file;
}
} // namespace

bool canEncode()
{
    return avcodec_find_encoder(AV_CODEC_ID_AAC) != nullptr;
}

bool encode(const std::vector<int16_t>& samples, int sampleRate, const QString& path)
{
    if (samples.empty() || sampleRate < lowestSampleRate || sampleRate > highestSampleRate
        || samples.size() > std::numeric_limits<uint32_t>::max() / 2 || path.isEmpty()
        || QFile::exists(path) || !canEncode()) {
        return false;
    }
    Coded coded;
    if (!code(samples, sampleRate, &coded)) {
        qWarning() << "A voice note could not be coded";
        return false;
    }
    const QByteArray file = m4a(coded, sampleRate, static_cast<uint32_t>(samples.size()));
    if (file.isEmpty()) {
        return false;
    }
    const QString partPath = path + partSuffix;
    // A part left by an earlier attempt goes; nothing else of that name is there to keep.
    QFile::remove(partPath);
    QFile part(partPath);
    bool written = part.open(QIODevice::WriteOnly | QIODevice::NewOnly)
                   && part.write(file) == file.size();
    part.close();
    written = written && part.error() == QFileDevice::NoError;
    if (!written || !QFile::rename(partPath, path)) {
        qWarning() << "A voice note could not be written";
        QFile::remove(partPath);
        return false;
    }
    return true;
}

QString newNoteName()
{
    const quint32 random = QRandomGenerator::global()->generate();
    return QStringLiteral("voice_%1.file.m4a")
        .arg(QString::number(random, 16).rightJustified(8, QLatin1Char('0')).toUpper());
}

} // namespace AirVoiceEncoder
