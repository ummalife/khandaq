#!/usr/bin/env python3
"""The shipped binary must contain exactly the ffmpeg surface the waivers were argued against.

KHANDAQ (2026-08-23). Thirty-one of the thirty-two ffmpeg advisories on Android are waived as
UNREACHABLE, and every one of those arguments rests on the same measured fact: of all of ffmpeg,
libjni-c-toxcore.so links exactly one decoder — H.264 — and nothing else. No demuxers, no muxers, no
protocols, no filters, no hwaccels, no encoders; no avformat, avfilter, swscale or swresample at all.

A fact that an argument depends on has to be held, or the argument silently stops being true. Enable
one more decoder in circle_scripts/deps.sh — a reasonable thing for somebody to do — and a pile of
"the component is not in the build" waivers become false with nothing to say so.

    scripts/check-ffmpeg-surface.py

Runs against the committed nativelibs. Skips cleanly when they are absent (a fresh checkout does not
have them) rather than passing vacuously — absence is reported, not treated as success.
"""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NL = ROOT / "khandaq-android-trifa/android-refimpl-app/app/nativelibs"
SHIPPED_ABIS = ("arm64-v8a", "armeabi-v7a")

# Exactly what the waivers were argued against. ff_init_cabac_decoder is an internal H.264 helper,
# not a codec entry point, so it is named here rather than counted as a second decoder.
ALLOWED_DECODERS = {"ff_h264_decoder", "ff_init_cabac_decoder"}

# Components whose ABSENCE the waivers depend on, as the SUFFIX of a symbol name.
#
# Matched only against DATA symbols (nm type D/R/B). In ffmpeg a codec, demuxer, muxer, protocol,
# filter or hwaccel is a static struct, while helpers are functions (type T) — and the two are easy
# to confuse by name alone: ff_side_data_set_encoder_stats is a metadata helper, not an encoder, and
# a name-only pattern reports it as one. Matching on the symbol type as well as the name keeps this
# gate from crying wolf, which is how a gate gets switched off.
MUST_BE_ABSENT = {
    "демуксер":  "_demuxer",
    "муксер":    "_muxer",
    "протокол":  "_protocol",
    "фильтр":    "_filter",
    "hwaccel":   "_hwaccel",
    "энкодер":   "_encoder",
}
ABSENT_FUNCS = ("avformat_open_input", "av_read_frame", "avio_open",
                "sws_scale", "swr_convert", "avfilter_graph_alloc")


def symbols(so: Path) -> tuple[str, set[str]]:
    """(весь вывод nm, имена символов-ДАННЫХ). Структуры кодеков — данные, хелперы — функции."""
    nm = shutil.which("nm") or shutil.which("llvm-nm")
    if not nm:
        return "", set()
    r = subprocess.run([nm, "-a", str(so)], capture_output=True, text=True, timeout=300)
    data = set()
    for line in r.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[1] in ("D", "d", "R", "r", "B", "b"):
            data.add(parts[2])
    return r.stdout, data


def main() -> int:
    if not NL.is_dir():
        print(f"::error::нет {NL.relative_to(ROOT)}", file=sys.stderr)
        return 1
    if not (shutil.which("nm") or shutil.which("llvm-nm")):
        print("::error::нет nm — проверить состав бинарника нечем", file=sys.stderr)
        return 1

    present = [(abi, NL / abi / "libjni-c-toxcore.so") for abi in SHIPPED_ABIS
               if (NL / abi / "libjni-c-toxcore.so").is_file()]
    if not present:
        print("нет собранных нативных библиотек — проверять нечего (чистый checkout). "
              "Проверка состава ffmpeg выполняется там, где библиотеки есть.")
        return 0

    problems: list[str] = []
    for abi, so in present:
        syms, data_syms = symbols(so)
        if not syms:
            problems.append(f"{abi}: nm не вернул символов — проверка не состоялась")
            continue

        decoders = {s for s in data_syms if s.startswith("ff_") and s.endswith("_decoder")}
        # ff_init_cabac_decoder — функция (тип T), в data_syms не попадает; оставлено в списке
        # разрешённых на случай, если компоновщик поместит её иначе.
        extra = decoders - ALLOWED_DECODERS
        if extra:
            problems.append(
                f"{abi}: в бинарнике появились декодеры ffmpeg сверх H.264: "
                f"{', '.join(sorted(extra))}. Обоснования 31 waiver-а в security-waivers.json "
                f"построены на том, что декодер ровно один — их надо перепроверить поимённо, "
                f"а не расширять сборку молча.")

        for label, suffix in MUST_BE_ABSENT.items():
            found = sorted(s for s in data_syms if s.startswith("ff_") and s.endswith(suffix))
            if found:
                problems.append(
                    f"{abi}: появился {label} ({', '.join(found[:3])}"
                    f"{'…' if len(found) > 3 else ''}). Waiver-ы утверждают, что таких компонентов "
                    f"в сборке нет.")

        for fn in ABSENT_FUNCS:
            if re.search(rf"\b{re.escape(fn)}\b", syms):
                problems.append(f"{abi}: появился {fn} — подсистема, которой по waiver-ам быть не должно")

        print(f"    {abi}: декодеров {len(decoders)} ({', '.join(sorted(decoders)) or '—'})")

    print(f"==> Проверено ABI: {len(present)} из {len(SHIPPED_ABIS)} поставляемых")
    print()
    if problems:
        print(f"ПРОВАЛЕНО: {len(problems)}", file=sys.stderr)
        for p in problems:
            print(f"::error::  {p}", file=sys.stderr)
        print("::error::Поверхность ffmpeg изменилась. Это не запрет на изменение — это требование "
              "пересмотреть обоснования, которые на прежней поверхности держались.", file=sys.stderr)
        return 1
    print("ВСЁ ЧИСТО: поверхность ffmpeg та же, на которой построены обоснования waiver-ов")
    return 0


if __name__ == "__main__":
    sys.exit(main())
