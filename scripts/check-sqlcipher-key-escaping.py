#!/usr/bin/env python3
"""KHANDAQ gate — every PRAGMA that carries the database passphrase must escape it.

PRAGMA does not take bound parameters, so the passphrase has to be pasted into the SQL as a string
literal, and a single quote inside it changes what that SQL means. PRAGMA key was escaped; both
PRAGMA rekey statements were not. A passphrase like "a'; --" therefore rekeyed the database to "a"
while the app went on opening it with the escaped form — the database stops opening with the password
the user just set, and there is no way back to it.

The asymmetry is the whole bug, and nothing but reading these four lines side by side would show it.
"""
import re
import sys
from pathlib import Path

ORMA = Path(__file__).resolve().parent.parent / (
    "khandaq-android-trifa/android-refimpl-app/app/src/main/java/com/zoffcc/applications/sorm/OrmaDatabase.java")

text = ORMA.read_text(encoding="utf-8", errors="replace")
bad = []
for lineno, line in enumerate(text.splitlines(), 1):
    stripped = line.strip()
    if stripped.startswith("//") or stripped.startswith("*"):
        continue
    if not re.search(r'PRAGMA (re)?key\s*=', stripped):
        continue
    # The value must arrive through the escaper, not straight from a variable.
    if "escapeKeyLiteral(" not in stripped:
        bad.append(f"OrmaDatabase.java:{lineno}: {stripped}")

if not re.search(r"private static String escapeKeyLiteral", text):
    bad.append("escapeKeyLiteral is gone — nothing escapes the passphrase any more")

if bad:
    print("FAIL — a passphrase reaches SQL unescaped; a quote in it makes the database unopenable:\n")
    for b in bad:
        print(f"  - {b}")
    sys.exit(1)

print("ok — every PRAGMA key/rekey escapes the passphrase")
