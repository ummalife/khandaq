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

#include <QString>

#include <cstdint>
#include <vector>

/**
 * The sound of a voice note (an M4A file of the phones) as one channel of 16-bit samples, read with
 * the FFmpeg the client already links for video. Builds whose FFmpeg has no MP4 demuxer or no AAC
 * decoder (the minimal Windows one) cannot read it; canDecode() says so before anything is tried.
 */
namespace AirVoiceDecoder {

struct Pcm
{
    /** One channel. */
    std::vector<int16_t> samples;
    int sampleRate = 0;

    qint64 lengthMs() const;
};

/** The sample types of the decoder's frames. */
enum class SampleKind
{
    U8,
    S16,
    S32,
    Float,
    Double,
};

/** Whether this build can read voice notes at all. */
bool canDecode();

/**
 * The whole file as one channel; false, with out left empty, when it cannot be read or is not the
 * kind of note the phones send: M4A with AAC only, up to 32 MB, 15 minutes and 48 kHz.
 */
bool decode(const QString& path, Pcm* out);

/**
 * Appends frames of the decoder to one channel: the channels are averaged, floats clipped to the
 * 16-bit range. planes holds one pointer per channel when planar, else one interleaved buffer.
 */
void appendMono(const uint8_t* const* planes, SampleKind kind, bool planar, int channels,
                int frames, std::vector<int16_t>* out);

} // namespace AirVoiceDecoder
