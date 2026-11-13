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

#include "airvoicedecoder.h"

#include <QDebug>
#include <QFile>

#include <cerrno>
#include <cmath>
#include <cstring>
#include <memory>

extern "C"
{
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wold-style-cast"
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/mem.h>
#include <libavutil/samplefmt.h>
#pragma GCC diagnostic pop
}

namespace AirVoiceDecoder {

namespace {
// AVERROR_EOF without the C cast inside its macro, which the warnings of the CI builds reject.
constexpr int endOfFile =
    -static_cast<int>(static_cast<unsigned>('E') | (static_cast<unsigned>('O') << 8)
                      | (static_cast<unsigned>('F') << 16) | (static_cast<unsigned>(' ') << 24));
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wold-style-cast"
static_assert(endOfFile == AVERROR_EOF, "the end of a file as FFmpeg says it");
#pragma GCC diagnostic pop

// The file comes from another person, so what is read in-process is kept small. A note held whole
// in memory takes 2 bytes a sample: 15 minutes at 48 kHz is under 90 MB. Anything past these limits
// is not a voice note the phones send, and it is left to the system player.
constexpr qint64 longestSeconds = 15 * 60;
constexpr int highestSampleRate = 48000;
constexpr int mostChannels = 8;
constexpr qint64 largestFileBytes = 32LL * 1024 * 1024;
constexpr int ioBufferSize = 32 * 1024;

int readFile(void* opaque, uint8_t* buffer, int size)
{
    auto* const file = static_cast<QFile*>(opaque);
    const qint64 read = file->read(reinterpret_cast<char*>(buffer), size);
    if (read < 0) {
        return AVERROR(EIO);
    }
    return read == 0 ? endOfFile : static_cast<int>(read);
}

int64_t seekFile(void* opaque, int64_t offset, int whence)
{
    auto* const file = static_cast<QFile*>(opaque);
    if (whence & AVSEEK_SIZE) {
        return file->size();
    }
    qint64 target = offset;
    switch (whence & ~AVSEEK_FORCE) {
    case SEEK_SET:
        break;
    case SEEK_CUR:
        target += file->pos();
        break;
    case SEEK_END:
        target += file->size();
        break;
    default:
        return -1;
    }
    return file->seek(target) ? target : -1;
}

double sampleAt(const uint8_t* where, SampleKind kind)
{
    switch (kind) {
    case SampleKind::U8:
        return (static_cast<double>(*where) - 128.0) / 128.0;
    case SampleKind::S16: {
        int16_t value;
        std::memcpy(&value, where, sizeof(value));
        return value / 32768.0;
    }
    case SampleKind::S32: {
        int32_t value;
        std::memcpy(&value, where, sizeof(value));
        return value / 2147483648.0;
    }
    case SampleKind::Float: {
        float value;
        std::memcpy(&value, where, sizeof(value));
        return static_cast<double>(value);
    }
    case SampleKind::Double: {
        double value;
        std::memcpy(&value, where, sizeof(value));
        return value;
    }
    }
    return 0.0;
}

int bytesOf(SampleKind kind)
{
    switch (kind) {
    case SampleKind::U8:
        return 1;
    case SampleKind::S16:
        return 2;
    case SampleKind::S32:
    case SampleKind::Float:
        return 4;
    case SampleKind::Double:
        return 8;
    }
    return 1;
}

bool kindOf(AVSampleFormat format, SampleKind* kind)
{
    switch (av_get_packed_sample_fmt(format)) {
    case AV_SAMPLE_FMT_U8:
        *kind = SampleKind::U8;
        return true;
    case AV_SAMPLE_FMT_S16:
        *kind = SampleKind::S16;
        return true;
    case AV_SAMPLE_FMT_S32:
        *kind = SampleKind::S32;
        return true;
    case AV_SAMPLE_FMT_FLT:
        *kind = SampleKind::Float;
        return true;
    case AV_SAMPLE_FMT_DBL:
        *kind = SampleKind::Double;
        return true;
    default:
        return false;
    }
}

/** Everything FFmpeg allocates for one file, freed in one place. */
struct Session
{
    QFile file;
    AVIOContext* io = nullptr;
    AVFormatContext* format = nullptr;
    AVCodecContext* codec = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* frame = nullptr;

    explicit Session(const QString& path)
        : file(path)
    {
    }

    ~Session()
    {
        av_frame_free(&frame);
        av_packet_free(&packet);
        avcodec_free_context(&codec);
        avformat_close_input(&format);
        if (io) {
            av_freep(&io->buffer);
            avio_context_free(&io);
        }
    }

    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;
};

/** Takes every frame the decoder has ready; false when one cannot be read. */
bool drain(Session& session, Pcm* out)
{
    while (true) {
        const int got = avcodec_receive_frame(session.codec, session.frame);
        if (got == AVERROR(EAGAIN) || got == endOfFile) {
            return true;
        }
        if (got < 0) {
            return false;
        }
        AVFrame* const frame = session.frame;
        SampleKind kind;
        if (!kindOf(static_cast<AVSampleFormat>(frame->format), &kind)) {
            av_frame_unref(frame);
            return false;
        }
#if LIBAVUTIL_VERSION_INT >= AV_VERSION_INT(57, 24, 100)
        const int channels = frame->ch_layout.nb_channels;
#else
        const int channels = frame->channels;
#endif
        if (out->sampleRate == 0) {
            out->sampleRate = frame->sample_rate;
        }
        // One rate for the whole note, as the phones record it.
        if (channels <= 0 || channels > mostChannels || frame->sample_rate != out->sampleRate
            || out->sampleRate > highestSampleRate) {
            av_frame_unref(frame);
            return false;
        }
        const bool planar = av_sample_fmt_is_planar(static_cast<AVSampleFormat>(frame->format)) != 0;
        appendMono(frame->extended_data, kind, planar, channels, frame->nb_samples, &out->samples);
        av_frame_unref(frame);
        if (out->sampleRate <= 0
            || static_cast<qint64>(out->samples.size()) > longestSeconds * out->sampleRate) {
            return false;
        }
    }
}
} // namespace

qint64 Pcm::lengthMs() const
{
    if (sampleRate <= 0) {
        return 0;
    }
    return static_cast<qint64>(samples.size()) * 1000 / sampleRate;
}

bool canDecode()
{
    return av_find_input_format("mov") != nullptr && avcodec_find_decoder(AV_CODEC_ID_AAC) != nullptr;
}

bool decode(const QString& path, Pcm* out)
{
    out->samples.clear();
    out->sampleRate = 0;
    if (!canDecode()) {
        return false;
    }

    Session session(path);
    if (!session.file.open(QIODevice::ReadOnly) || session.file.size() > largestFileBytes) {
        return false;
    }
    auto* const buffer = static_cast<unsigned char*>(av_malloc(ioBufferSize));
    if (!buffer) {
        return false;
    }
    session.io =
        avio_alloc_context(buffer, ioBufferSize, 0, &session.file, &readFile, nullptr, &seekFile);
    if (!session.io) {
        av_free(buffer);
        return false;
    }
    session.format = avformat_alloc_context();
    if (!session.format) {
        return false;
    }
    session.format->pb = session.io;
    session.format->flags |= AVFMT_FLAG_CUSTOM_IO;
    // The file is read as M4A and nothing else: no guessing at other formats, AAC as the only
    // decoder, no other file or protocol opened from inside it, a handful of streams at most.
    session.format->format_whitelist = av_strdup("mov,mp4,m4a,3gp,3g2,mj2");
    session.format->codec_whitelist = av_strdup("aac");
    session.format->protocol_whitelist = av_strdup("none");
    session.format->max_streams = 4;
    if (!session.format->format_whitelist || !session.format->codec_whitelist
        || !session.format->protocol_whitelist) {
        return false;
    }
    const auto m4a = av_find_input_format("mov");
    // On failure avformat_open_input frees the context and clears the pointer.
    if (avformat_open_input(&session.format, nullptr, m4a, nullptr) < 0
        || avformat_find_stream_info(session.format, nullptr) < 0) {
        return false;
    }
    const int index = av_find_best_stream(session.format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (index < 0) {
        return false;
    }
    const AVCodecParameters* const parameters = session.format->streams[index]->codecpar;
    if (parameters->codec_id != AV_CODEC_ID_AAC) {
        return false;
    }
    const AVCodec* const decoder = avcodec_find_decoder(AV_CODEC_ID_AAC);
    if (!decoder) {
        return false;
    }
    session.codec = avcodec_alloc_context3(decoder);
    session.packet = av_packet_alloc();
    session.frame = av_frame_alloc();
    if (!session.codec || !session.packet || !session.frame
        || avcodec_parameters_to_context(session.codec, parameters) < 0
        || avcodec_open2(session.codec, decoder, nullptr) < 0) {
        return false;
    }

    bool fine = true;
    while (fine && av_read_frame(session.format, session.packet) >= 0) {
        if (session.packet->stream_index == index) {
            fine = avcodec_send_packet(session.codec, session.packet) >= 0 && drain(session, out);
        }
        av_packet_unref(session.packet);
    }
    // What the decoder still holds.
    if (fine && avcodec_send_packet(session.codec, nullptr) >= 0) {
        fine = drain(session, out);
    }
    if (!fine || out->samples.empty() || out->sampleRate <= 0) {
        qDebug() << "A voice note could not be decoded";
        out->samples.clear();
        out->sampleRate = 0;
        return false;
    }
    return true;
}

void appendMono(const uint8_t* const* planes, SampleKind kind, bool planar, int channels,
                int frames, std::vector<int16_t>* out)
{
    if (!planes || channels <= 0 || frames <= 0) {
        return;
    }
    const int bytes = bytesOf(kind);
    out->reserve(out->size() + static_cast<size_t>(frames));
    for (int i = 0; i < frames; ++i) {
        double sum = 0.0;
        for (int channel = 0; channel < channels; ++channel) {
            const uint8_t* const where =
                planar ? planes[channel] + static_cast<size_t>(i) * static_cast<size_t>(bytes)
                       : planes[0]
                             + (static_cast<size_t>(i) * static_cast<size_t>(channels)
                                + static_cast<size_t>(channel))
                                   * static_cast<size_t>(bytes);
            sum += sampleAt(where, kind);
        }
        const double mixed = qBound(-1.0, sum / channels, 1.0);
        out->push_back(static_cast<int16_t>(std::lround(mixed * 32767.0)));
    }
}

} // namespace AirVoiceDecoder
