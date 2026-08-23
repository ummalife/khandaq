#!/usr/bin/env python3
"""The audio decode path must check the payload length before trusting it.

KHANDAQ (2026-08-23). An audio RTP payload is four bytes of sampling rate followed by an Opus frame.
Nothing on the path from the network enforced that: handle_rtp_packet_for_session takes
data_length_lower straight from the attacker-controlled header, ac_queue_message checks only the
payload type, and jbuf_write only sequence numbers. What stood between a one-byte payload and

    memcpy(&ac->lp_sampling_rate, msg->data, 4);
    ac->lp_channel_count = opus_packet_get_nb_channels(msg->data + 4);
    rc = opus_decode(ac->decoder, msg->data + 4, msg->len - 4, ...);

was `assert(msg->len > 4)` on iOS and nothing at all on the desktop. Release builds define NDEBUG, so
in the binaries users run there was no check on either side: four bytes read out of a one-byte
allocation, and msg->len - 4 reaching opus_decode as a negative length. One packet, from a contact
already in a call.

The check now exists in all three copies. This holds it there — the same shape as
check-history-file-gate.py, and for the same reason: a guard that only a comment remembers is one
refactor from being gone.

    scripts/check-audio-length-gate.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

COPIES = (
    ("iOS", "khandaq-ios/local_pod_repo/toxcore/toxcore/toxav/audio.m"),
    ("desktop", "khandaq-desktop/buildscripts/toxcore/toxav/audio.c"),
    ("desktop amalgamation", "khandaq-desktop/buildscripts/toxcore/amalgamation/toxcore_amalgamation.c"),
)

# The read that must be guarded, and the guard that must precede it.
READ = re.compile(r"memcpy\(\s*&\s*ac->lp_sampling_rate\s*,\s*msg->data\s*,\s*4\s*\)")
GUARD = re.compile(r"if\s*\(\s*msg->len\s*<=\s*4\s*\)")
# An assert is NOT a guard: NDEBUG removes it from exactly the build that ships.
ASSERT_ONLY = re.compile(r"assert\s*\(\s*msg->len\s*>\s*4\s*\)")

# How far back to look. The guard sits immediately above the read; a larger window would let an
# unrelated length check elsewhere in the function satisfy this.
WINDOW = 40


def main() -> int:
    problems: list[str] = []
    checked = 0

    for label, rel in COPIES:
        path = ROOT / rel
        if not path.is_file():
            problems.append(f"{label}: нет файла {rel} — путь декодирования аудио пропал?")
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        lines = text.splitlines()

        reads = [i for i, ln in enumerate(lines) if READ.search(ln)]
        if not reads:
            problems.append(
                f"{label}: не найдено чтение lp_sampling_rate из msg->data. Либо путь переписан — "
                f"тогда почините проверку, — либо она проверяет уже не то, что есть.")
            continue

        for i in reads:
            # Strip comment lines before looking. The explanatory comment above the guard quotes the
            # old `assert(msg->len > 4)` verbatim, and a naive window match reported that comment as
            # the guard — telling the reader the wrong thing about their own code. Found by removing
            # the guard and watching the gate blame an assert that was no longer there.
            window_lines = []
            for ln in lines[max(0, i - WINDOW):i]:
                stripped = ln.strip()
                if stripped.startswith(("//", "*", "/*")):
                    continue
                window_lines.append(ln)
            window = "\n".join(window_lines)
            if GUARD.search(window):
                checked += 1
                continue
            if ASSERT_ONLY.search(window):
                problems.append(
                    f"{rel}:{i + 1}: длину сторожит только assert(msg->len > 4). Релиз собирается с "
                    f"NDEBUG, поэтому в поставляемом бинарнике проверки нет вовсе — нужен "
                    f"`if (msg->len <= 4)`.")
            else:
                problems.append(
                    f"{rel}:{i + 1}: перед чтением четырёх байт из msg->data нет проверки "
                    f"`if (msg->len <= 4)`. Пакет от собеседника с однобайтовой полезной нагрузкой "
                    f"читается за пределами выделенного буфера, а msg->len - 4 уходит в opus_decode "
                    f"отрицательным.")

    # KHANDAQ (2026-08-23): Android is the fourth copy, and it is not in this repository.
    #
    # circle_scripts/deps.sh clones zoff99/c-toxcore fresh at a pinned commit, so the edits checked
    # above never reach the shipped .so — the fix travels as a build-time patch instead, the same way
    # the libvpx CVE backport does. A patch that stops being applied is invisible: the version string
    # does not move and the build still succeeds. So the patch file must exist AND still be invoked
    # once per libvpx-clone site.
    patch = ROOT / "khandaq-android-trifa/patches/apply_khandaq_audio_length.py"
    deps = ROOT / "khandaq-android-trifa/circle_scripts/deps.sh"
    ANDROID_SITES = 4
    if not patch.is_file():
        problems.append(f"нет {patch.relative_to(ROOT)} — Android клонирует toxcore заново, и без "
                        f"этого патча поставляемая .so собирается без проверки длины")
    elif not deps.is_file():
        problems.append("нет circle_scripts/deps.sh — непонятно, применяется ли патч вообще")
    else:
        sites = sum(1 for ln in deps.read_text(encoding="utf-8", errors="replace").splitlines()
                    if patch.name in ln and "python3" in ln and not ln.strip().startswith("#"))
        if sites < ANDROID_SITES:
            problems.append(
                f"deps.sh применяет {patch.name} {sites} раз(а) вместо {ANDROID_SITES}. toxcore "
                f"собирается отдельно под каждый ABI — недостающее место это архитектура, которая "
                f"уезжает пользователю без проверки длины.")
        else:
            checked += 1
            print(f"    Android: патч сборки применяется в {sites} местах")

    print(f"==> Копий пути декодирования аудио: {len(COPIES)} в дереве + Android через патч; "
          f"защищённых чтений: {checked}")
    print()
    if problems:
        print(f"ПРОВАЛЕНО: {len(problems)}", file=sys.stderr)
        for p in problems:
            print(f"::error::  {p}", file=sys.stderr)
        return 1
    print("ВСЁ ЧИСТО: длина проверяется до использования во всех копиях")
    return 0


if __name__ == "__main__":
    sys.exit(main())
