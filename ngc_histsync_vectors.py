#!/usr/bin/env python3
"""
KHANDAQ — frozen test vectors for NGC signed history sync (DESIGN-ngc-signed-history-sync.md).

Why this exists: the signature itself is Ed25519 from libsodium and will not diverge between
Android, iOS and desktop. What WILL diverge is the byte string each platform decides to sign —
field order, integer width, endianness, whether the message text is hashed or embedded raw. That is
exactly the class of bug that makes a signature scheme fail open in production while every unit test
passes locally.

So the pre-image is frozen here, with its SHA-256, and every implementation must reproduce these
digests before it is allowed to sign or verify anything real. This is the same check that was run
against the push-relay HMAC, where all four implementations turned out to agree byte-for-byte.

Run:  python3 ngc_histsync_vectors.py            # print the vectors
      python3 ngc_histsync_vectors.py --check    # re-derive and assert they still match

The vectors are deliberately NOT random: each one isolates something a careless implementation gets
wrong.
"""

import hashlib
import json
import sys

HISTSYNC_DOMAIN = b"KQ-HISTSYNC-1"
ANNOUNCE_DOMAIN = b"KQ-HSK-ANNOUNCE-1"


def histsync_preimage(group_id: bytes, author_pub: bytes, msg_id: bytes, ts: int, text: bytes) -> bytes:
    """
    KQ-HISTSYNC-1 || group_id(32) || author_pub(32) || msg_id(4) || ts(8, big-endian) || sha256(text)(32)

    Every field is fixed width, so no two distinct messages can share a pre-image, and the domain
    prefix keeps a history signature from ever validating as an announcement signature.
    The TEXT IS HASHED, not embedded: the pre-image stays 121 bytes regardless of message length.
    """
    assert len(group_id) == 32, "group_id must be 32 bytes"
    assert len(author_pub) == 32, "author_pub must be 32 bytes"
    assert len(msg_id) == 4, "msg_id must be 4 bytes"
    assert 0 <= ts < 2**64, "ts must fit an unsigned 64-bit integer"
    return (HISTSYNC_DOMAIN + group_id + author_pub + msg_id
            + ts.to_bytes(8, "big") + hashlib.sha256(text).digest())


def announce_preimage(tox_pub: bytes, hsk_pub: bytes, valid_from_ts: int) -> bytes:
    """KQ-HSK-ANNOUNCE-1 || tox_pubkey(32) || hsk_pub(32) || valid_from_ts(8, big-endian)"""
    assert len(tox_pub) == 32 and len(hsk_pub) == 32
    assert 0 <= valid_from_ts < 2**64
    return ANNOUNCE_DOMAIN + tox_pub + hsk_pub + valid_from_ts.to_bytes(8, "big")


# Fixed inputs. Byte 0x00 and 0xff both appear so a platform that treats the key as a C string,
# or hex-encodes where it should not, fails immediately.
GROUP_A = bytes(range(32))
GROUP_B = bytes([0xff] * 32)
AUTHOR_A = bytes([0xAA] * 32)
AUTHOR_B = bytes([0x00] * 32)
HSK_A = bytes([0x11, 0x22] * 16)
MSG_ID = bytes([0xde, 0xad, 0xbe, 0xef])

VECTORS = [
    {
        "name": "ascii-basic",
        "why": "the ordinary case",
        "group_id": GROUP_A, "author_pub": AUTHOR_A, "msg_id": MSG_ID,
        "ts": 1_754_870_400, "text": "hello".encode("utf-8"),
    },
    {
        "name": "empty-text",
        "why": "sha256 of the empty string is well defined; an implementation that skips hashing "
               "an empty body, or substitutes a null, diverges here",
        "group_id": GROUP_A, "author_pub": AUTHOR_A, "msg_id": MSG_ID,
        "ts": 1_754_870_400, "text": b"",
    },
    {
        "name": "utf8-multibyte",
        "why": "catches a platform that hashes UTF-16 (Java/Swift native strings) instead of UTF-8",
        "group_id": GROUP_A, "author_pub": AUTHOR_A, "msg_id": MSG_ID,
        "ts": 1_754_870_400, "text": "Привет, мир 👋".encode("utf-8"),
    },
    {
        "name": "ts-above-32-bit",
        "why": "the CURRENT wire format transmits only the low 4 bytes of the timestamp; the signed "
               "pre-image uses all 8. A platform that truncates to 32 bits fails only here",
        "group_id": GROUP_A, "author_pub": AUTHOR_A, "msg_id": MSG_ID,
        "ts": 0x0000_0001_0000_0001, "text": b"x",
    },
    {
        "name": "ts-max-u64",
        "why": "an implementation using a SIGNED 64-bit timestamp wraps negative here",
        "group_id": GROUP_A, "author_pub": AUTHOR_A, "msg_id": MSG_ID,
        "ts": 2**64 - 1, "text": b"x",
    },
    {
        "name": "zero-author-key",
        "why": "an all-zero pubkey must still produce a normal pre-image, not be treated as absent",
        "group_id": GROUP_B, "author_pub": AUTHOR_B, "msg_id": bytes(4),
        "ts": 0, "text": b"",
    },
]

ANNOUNCE_VECTORS = [
    {
        "name": "announce-basic",
        "why": "the ordinary case",
        "tox_pub": AUTHOR_A, "hsk_pub": HSK_A, "valid_from_ts": 1_754_870_400,
    },
    {
        "name": "announce-zero-ts",
        "why": "ts=0 must not be confused with 'field absent'",
        "tox_pub": AUTHOR_B, "hsk_pub": HSK_A, "valid_from_ts": 0,
    },
]

# Frozen expected digests. If a change to the pre-image is ever intended, these MUST be regenerated
# in the same commit that changes the format, and the design document's version bumped with them.
EXPECTED = {
    "ascii-basic": "33599061b75b2c487120a845450367ee880c931d6c00095960f8c3828f3457ed",
    "empty-text": "d008aea0521ebdc8ac4488263746b0c63c7819b37aa966edcdf89ff7121b711f",
    "utf8-multibyte": "752e856237a501d9bb3c278d97b445b2d428375f54edf0692ffbb80818dafd49",
    "ts-above-32-bit": "b16b06ed10ce4bec25661e486350f2b8b4018014b9ddcb8cbcc35ca51779507d",
    "ts-max-u64": "e867afdab7e3f201da8b48259530733400dce9caf5d1306e22af0989e69db055",
    "zero-author-key": "0c0a9029409cf4a617254a0f5bc2fa78119ca3f4766ce77f377ade1f38defa99",
    "announce-basic": "70b75055b020a79fbc70fe27fb7d48adebf585dcf6fd79f9a1f58d195d11a88b",
    "announce-zero-ts": "b25c730609d8e16646630ff6f8dff11046dfaa4d8f617bda18c948fa843de278",
}


def compute_all():
    out = {}
    for v in VECTORS:
        pre = histsync_preimage(v["group_id"], v["author_pub"], v["msg_id"], v["ts"], v["text"])
        assert len(pre) == len(HISTSYNC_DOMAIN) + 32 + 32 + 4 + 8 + 32, "pre-image length drifted"
        out[v["name"]] = (pre, hashlib.sha256(pre).hexdigest())
    for v in ANNOUNCE_VECTORS:
        pre = announce_preimage(v["tox_pub"], v["hsk_pub"], v["valid_from_ts"])
        assert len(pre) == len(ANNOUNCE_DOMAIN) + 32 + 32 + 8, "pre-image length drifted"
        out[v["name"]] = (pre, hashlib.sha256(pre).hexdigest())
    return out


def main():
    got = compute_all()
    if "--check" in sys.argv:
        bad = [n for n, (_, d) in got.items() if EXPECTED.get(n) != d]
        for n in bad:
            print(f"MISMATCH {n}\n  expected {EXPECTED.get(n)}\n  actual   {got[n][1]}")
        print(f"\n{len(got) - len(bad)}/{len(got)} vectors match")
        return 1 if bad else 0
    if "--json" in sys.argv:
        print(json.dumps({n: {"preimage_hex": p.hex(), "sha256": d} for n, (p, d) in got.items()},
                         indent=2))
        return 0
    print(f"{'vector':<22} {'len':>4}  sha256(pre-image)")
    for n, (p, d) in got.items():
        print(f"{n:<22} {len(p):>4}  {d}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
