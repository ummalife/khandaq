#!/usr/bin/env python3
"""Fails when text contains the glyphs the redesign brief bans from interface, code and comments.

Banned: em and en dashes, arrows, the one-character ellipsis, the multiplication sign, box drawing
and other pseudo-graphics, check marks and crosses, and the middle dot with its look-alikes.
The glyphs are listed by code point, so this file never contains them itself.

Usage: check-forbidden-glyphs.py PATH [PATH ...]
Directories are scanned recursively for text files; binary files and images are skipped.
"""

import pathlib
import sys
import unicodedata

SINGLE = {
    0x2012: "figure dash",
    0x2013: "en dash",
    0x2014: "em dash",
    0x2015: "horizontal bar",
    0x2026: "one-character ellipsis",
    0x00D7: "multiplication sign",
    0x00B7: "middle dot",
    0x2022: "bullet",
    0x2219: "bullet operator",
    0x22C5: "dot operator",
    0x30FB: "katakana middle dot",
    0x2610: "ballot box",
    0x2611: "ballot box with check",
    0x2612: "ballot box with x",
    0x2705: "check mark button",
    0x2713: "check mark",
    0x2714: "heavy check mark",
    0x2715: "multiplication x",
    0x2716: "heavy multiplication x",
    0x2717: "ballot x",
    0x2718: "heavy ballot x",
    0x274C: "cross mark",
    0x274E: "cross mark button",
}

RANGES = [
    (0x2190, 0x21FF, "arrow"),
    (0x27F0, 0x27FF, "arrow"),
    (0x2900, 0x297F, "arrow"),
    (0x2794, 0x27BF, "arrow"),
    (0x2B00, 0x2B4F, "arrow"),
    (0x2500, 0x257F, "box drawing"),
    (0x2580, 0x259F, "block element"),
    (0x25A0, 0x25FF, "geometric shape"),
]

TEXT_SUFFIXES = {
    ".html", ".htm", ".css", ".js", ".mjs", ".ts", ".tsx", ".jsx", ".json", ".md", ".txt", ".py", ".sh",
    ".swift", ".m", ".h", ".kt", ".java", ".xml", ".strings", ".stringsdict", ".cpp", ".hpp", ".c", ".qml",
    ".ui", ".qss", ".svg", ".yml", ".yaml",
}


def describe(cp: int):
    if cp in SINGLE:
        return SINGLE[cp]
    for low, high, label in RANGES:
        if low <= cp <= high:
            return label
    return None


def files_under(path: pathlib.Path):
    if path.is_file():
        yield path
        return
    for item in sorted(path.rglob("*")):
        if item.is_file() and item.suffix.lower() in TEXT_SUFFIXES:
            yield item


def scan(path: pathlib.Path):
    try:
        text = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return []
    found = []
    for number, line in enumerate(text.splitlines(), start=1):
        for column, char in enumerate(line, start=1):
            label = describe(ord(char))
            if label:
                name = unicodedata.name(char, "unnamed")
                found.append(f"{path}:{number}:{column}: U+{ord(char):04X} {name.lower()} ({label})")
    return found


def main(argv) -> int:
    if len(argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    problems = []
    checked = 0
    for raw in argv[1:]:
        root = pathlib.Path(raw)
        if not root.exists():
            print(f"{raw}: no such file or directory", file=sys.stderr)
            return 2
        for item in files_under(root):
            checked += 1
            problems.extend(scan(item))
    for line in problems:
        print(line)
    if problems:
        print(f"forbidden glyphs: {len(problems)} in {checked} files", file=sys.stderr)
        return 1
    print(f"forbidden glyphs: none in {checked} files")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
