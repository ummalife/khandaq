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
 * KHANDAQ (0.2.60): the voice notes the desktop records, written as the phones write theirs: AAC-LC
 * at 64 kbit/s, one channel, in an M4A (MP4) file named "voice_<8 hex>.file.m4a". FFmpeg's native
 * AAC encoder codes the sound and the boxes of the file are written here: the builds link no FFmpeg
 * muxer, which the FFmpeg waivers in security-waivers.json rest on. A build whose FFmpeg has no AAC
 * encoder cannot write notes, and canEncode() says so.
 */
namespace AirVoiceEncoder {

/** The bit rate of a note: a minute is under half a megabyte, as on the phones. */
constexpr int bitRate = 64000;

/** Whether this build can write voice notes at all. */
bool canEncode();

/**
 * Writes samples (one channel of 16 bits at sampleRate, a rate of AAC from 8 to 48 kHz) to path.
 * The file is written beside it under a ".part" name and takes its own name only once whole; on
 * failure nothing is left at either. False for no samples, a rate the encoder does not
 * take, a path that is there already or cannot be written.
 */
bool encode(const std::vector<int16_t>& samples, int sampleRate, const QString& path);

/** A new name for a note: "voice_" and eight random hexadecimal digits, then ".file.m4a". */
QString newNoteName();

} // namespace AirVoiceEncoder
