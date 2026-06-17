#pragma once

#include <cstddef>
#include <cstdint>

/** Max file/media transfer size (private chat + NGC groups). */
constexpr std::uint64_t KHANDAQ_MAX_FILE_TRANSFER_BYTES = 200ULL * 1024ULL * 1024ULL;

/** Single-packet NGC file payload (TRIfA pkt 0x11). */
constexpr std::size_t NGC_SINGLE_PKT_MAX_FILESIZE = 36701;

constexpr std::uint8_t NGC_PKT_FILE = 0x11;
constexpr std::uint8_t NGC_PKT_SYNC_FILE = 0x03;
constexpr std::uint8_t NGC_PKT_FILE_BEGIN = 0x12;
constexpr std::uint8_t NGC_PKT_FILE_CHUNK = 0x13;

constexpr std::size_t NGC_FILE_HEADER_SIZE = 6 + 1 + 1 + 32 + 4 + 255;
constexpr std::size_t NGC_FILE_BEGIN_SIZE = NGC_FILE_HEADER_SIZE + 8 + 4 + 4;
constexpr std::size_t NGC_FILE_CHUNK_HEADER_SIZE = 6 + 1 + 1 + 32 + 4 + 4;
constexpr std::size_t NGC_MAX_PACKET_SIZE = 37000;
constexpr std::size_t NGC_CHUNK_PAYLOAD_MAX = NGC_MAX_PACKET_SIZE - NGC_FILE_CHUNK_HEADER_SIZE;
