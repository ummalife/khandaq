#!/usr/bin/env python3
"""Every Qt form must compile, and every widget the C++ names must exist in it.

KHANDAQ (deep review follow-up 2026-08-23). There is no desktop build in CI at all — the RR2-12
gap — so a broken .ui or a mistyped widget name reaches a release build before anything notices.
Both are cheap to catch without a Qt toolchain-sized job:

  * uic must parse each form. A malformed .ui fails the desktop build outright.
  * every `ui->someWidget` in a .cpp must name a widget its form actually declares. uic generates a
    struct member per objectName, so a typo here is a compile error on a build nobody runs.

This is a SMALL part of RR2-12, and saying so is the point: it is not a substitute for compiling the
desktop, running CodeQL over it, or fuzzing the parsers. It covers the class of mistake that editing
a form introduces.

    scripts/check-desktop-ui-forms.py
"""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "khandaq-desktop" / "src"

# `ui->x` where x is a widget name. Method calls on the pointer itself (ui->setupUi) are excluded by
# requiring the name to be a declared objectName, which setupUi never is.
UI_REF = re.compile(r"\bui->([A-Za-z_][A-Za-z0-9_]*)")


def find_uic() -> str | None:
    for cand in ("uic", "uic-qt5", "/opt/homebrew/opt/qt@5/bin/uic", "/usr/lib/qt5/bin/uic"):
        p = shutil.which(cand) if "/" not in cand else (cand if Path(cand).is_file() else None)
        if p:
            return p
    return None


def object_names(ui_path: Path) -> set[str]:
    """objectName of every widget, layout, action and spacer the form declares."""
    try:
        tree = ET.parse(ui_path)
    except ET.ParseError as exc:
        raise ValueError(f"{ui_path.name}: XML не разбирается ({exc})") from exc
    names = set()
    for el in tree.iter():
        if el.tag in ("widget", "layout", "action", "spacer"):
            n = el.get("name")
            if n:
                names.add(n)
    return names


def main() -> int:
    if not SRC.is_dir():
        print(f"::error::нет {SRC.relative_to(ROOT)}", file=sys.stderr)
        return 1

    forms = sorted(SRC.rglob("*.ui"))
    if not forms:
        print("::error::не найдено ни одной формы — проверка выродилась бы в пустую", file=sys.stderr)
        return 1

    problems: list[str] = []
    uic = find_uic()
    names_by_form: dict[str, set[str]] = {}

    for f in forms:
        try:
            names_by_form[f.stem] = object_names(f)
        except ValueError as exc:
            problems.append(str(exc))
            continue
        if uic:
            r = subprocess.run([uic, str(f)], capture_output=True, text=True, timeout=120)
            if r.returncode != 0:
                problems.append(f"{f.relative_to(ROOT)}: uic не компилирует форму — "
                                f"{(r.stderr or '').strip().splitlines()[0][:160] if r.stderr.strip() else 'без деталей'}")

    print(f"==> Форм: {len(forms)}; uic: {uic or 'не найден (проверяется только XML)'}")

    # Каждый ui->widget в .cpp должен существовать в парной форме.
    checked_refs = 0
    for cpp in sorted(SRC.rglob("*.cpp")):
        form_names = names_by_form.get(cpp.stem)
        if form_names is None:
            continue  # у файла нет своей формы — ссылок ui-> в нём и не должно быть
        text = cpp.read_text(encoding="utf-8", errors="replace")
        for m in UI_REF.finditer(text):
            name = m.group(1)
            if name in ("setupUi", "retranslateUi"):
                continue
            checked_refs += 1
            if name not in form_names:
                line = text[:m.start()].count("\n") + 1
                problems.append(
                    f"{cpp.relative_to(ROOT)}:{line}: ui->{name} — такого виджета нет в "
                    f"{cpp.stem}.ui. uic генерирует поле на каждый objectName, так что это ошибка "
                    f"компиляции сборки, которую здесь никто не собирает.")
    print(f"    проверено ссылок ui->widget: {checked_refs}")

    print()
    if problems:
        print(f"ПРОВАЛЕНО: {len(problems)}", file=sys.stderr)
        for p in problems:
            print(f"::error::  {p}", file=sys.stderr)
        return 1
    print("ВСЁ ЧИСТО: формы компилируются, и каждый названный виджет в них есть")
    return 0


if __name__ == "__main__":
    sys.exit(main())
