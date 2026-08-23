#pragma once

/*
 * Writes size bytes of data to buffer, assumes buffer fits size bytes and
 * buffer holds single byte data
 */
#define toxext_write_to_buf(data, buffer, size)                                \
	do {                                                                   \
		size_t __effective_size =                                      \
			(size > sizeof(data)) ? sizeof(data) : size;           \
		size_t __offset =                                              \
			(size > sizeof(data)) ? size - sizeof(data) : 0;       \
		memset(buffer, 0, __offset);                                   \
		for (size_t i = 0; i < __effective_size; ++i) {                \
			(buffer)[i + (__offset)] =                             \
				((data) >> ((__effective_size - i - 1) * 8)) & \
				0xff;                                          \
		}                                                              \
	} while (0)

/*
 * Reads size bytes from buffer into a variable of type type.
 * Assumes buffer holds single byte data and is >= size bytes
 */
#define toxext_read_from_buf(type, buffer, size)                               \
	({                                                                     \
		type __val = 0;                                                \
		size_t __effective_size =                                      \
			(size > sizeof(type)) ? sizeof(type) : size;           \
		for (size_t i = 0; i < __effective_size; ++i) {                \
			/* KHANDAQ (2026-08-23): cast to `type` BEFORE shifting.  \
			 * (buffer)[i] is a uint8_t, which the usual arithmetic   \
			 * conversions promote to int — 32 bits. Reading a        \
			 * uint64_t therefore shifted an int by up to 56, which   \
			 * is undefined behaviour, and in practice the top four   \
			 * bytes landed in the wrong place: receipt_id,           \
			 * total_message_size and max_sending_message_size were   \
			 * decoded wrong for every packet that used them.         \
			 * Found by UBSan the first time this parser was fuzzed.  \
			 */                                                      \
			__val |= ((type)(buffer)[i])                           \
				 << ((__effective_size - i - 1) * 8);          \
		}                                                              \
		__val;                                                         \
	})
