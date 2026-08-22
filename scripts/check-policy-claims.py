#!/usr/bin/env python3
"""Hold the privacy policy and SECURITY.md to what the app and the relay actually do.

KHANDAQ (re-review v2 2026-08-22, RR2-13). For a privacy-focused messenger the policy page is part
of the control surface: users, reviewers and app stores read it to learn what is collected. It had
drifted in two directions at once — it listed three Android permissions the app no longer requests
(raised thread priority, disable keyguard, draw over other apps), and it stated that the store builds
carry no signing key, which stopped being true with Android 0.2.42 / iOS build 142986.

Neither error is catchable by reading the page: both require comparing it against the manifest and
against the release state. That is what this does.

    scripts/check-policy-claims.py

Three checks:

  1. Every Android permission the page NAMES must actually be declared by the shipped app.
  2. Every permission the page explicitly DENIES declaring must actually be absent.
  3. Claims that are false as of the signing rollout must not appear in either document.

The permission set is taken from the merged manifest when a build is present (that is what ships,
and libraries inject permissions the app's own manifest never mentions), otherwise from the app
manifest plus a committed list of the merge-injected ones. When both are available they are
cross-checked, so the committed list cannot rot the way the policy page did.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PRIVACY = ROOT / "web" / "privacy.html"
SECURITY = ROOT / "SECURITY.md"
APP_MANIFEST = ROOT / "khandaq-android-trifa/android-refimpl-app/app/src/main/AndroidManifest.xml"
INJECTED = ROOT / "docs" / "android-merge-injected-permissions.txt"
MANIFEST_JSON = ROOT / "web" / "release-manifest.json"
MERGED_GLOB = "khandaq-android-trifa/android-refimpl-app/app/build/intermediates/merged_manifest/*/*/AndroidManifest.xml"

# The versionCode from which the shipped artifacts actually carry a push signing key. Claims that
# depend on "no shipped client signs" become false at this point.
SIGNING_FROM_VERSION_CODE = 10420

P = "android.permission."
C2DM = "com.google.android.c2dm.permission.RECEIVE"

# Human wording on the page -> the permission constant it asserts. If the phrase is present, the
# permission must be declared.
NAMED: dict[str, tuple[str, ...]] = {
    "internet": (P + "INTERNET",),
    "network state": (P + "ACCESS_NETWORK_STATE",),
    "wake lock": (P + "WAKE_LOCK",),
    "special-use foreground service": (P + "FOREGROUND_SERVICE_SPECIAL_USE",),
    "foreground service": (P + "FOREGROUND_SERVICE",),
    "run at boot": (P + "RECEIVE_BOOT_COMPLETED",),
    "raised thread priority": ("org.thoughtcrime.securesms.RAISED_THREAD_PRIORITY",),
    "modify audio settings": (P + "MODIFY_AUDIO_SETTINGS",),
    "disable keyguard": (P + "DISABLE_KEYGUARD",),
    "full-screen intent": (P + "USE_FULL_SCREEN_INTENT",),
    "exact alarm": (P + "SCHEDULE_EXACT_ALARM",),
    "Google messaging receive permission": (C2DM,),
    "nearby devices": (P + "BLUETOOTH_CONNECT",),
    "draw over other apps": (P + "SYSTEM_ALERT_WINDOW",),
}

# Phrases the page uses inside a "does not declare" sentence -> the permission that must be ABSENT.
DENIED: dict[str, str] = {
    "all files access": P + "MANAGE_EXTERNAL_STORAGE",
    "ignore battery optimisations": P + "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
}

# False from SIGNING_FROM_VERSION_CODE onward. Each entry says why, because a bare regex list becomes
# unmaintainable the moment somebody rewords the page legitimately.
STALE_AFTER_SIGNING: list[tuple[str, str]] = [
    (r"built without the signing key",
     "магазинные сборки несут ключ подписи с Android 0.2.42 / iOS 142986"),
    (r"no shipped client signs",
     "клиенты с 0.2.42 подписывают пробуждения"),
    (r"apps currently on the App Store and Google Play are built without",
     "утверждение устарело с релиза 0.2.42"),
]

failures: list[str] = []


def strip_comments(xml: str) -> str:
    """XML comments survive into the merged manifest, so a naive grep reads removed permissions
    as live ones. Every check here runs on comment-free text."""
    return re.sub(r"<!--.*?-->", "", xml, flags=re.S)


def perms_from(path: Path) -> set[str]:
    text = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
    return set(re.findall(r'<uses-permission[^>]*android:name="([^"]+)"', text))


def shipped_permissions() -> set[str]:
    if not APP_MANIFEST.is_file():
        failures.append("нет AndroidManifest.xml приложения — не с чем сверять страницу")
        return set()
    declared = perms_from(APP_MANIFEST)

    injected: set[str] = set()
    if INJECTED.is_file():
        for line in INJECTED.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                injected.add(line)

    merged_files = sorted(ROOT.glob(MERGED_GLOB))
    if merged_files:
        merged = perms_from(merged_files[-1])
        # Всё, чего нет в манифесте приложения, пришло из библиотеки при слиянии.
        actually_injected = merged - declared
        unknown = actually_injected - injected
        stale = injected - actually_injected
        if unknown:
            failures.append(
                "в собранный манифест библиотеки добавили разрешения, которых нет в "
                f"{INJECTED.relative_to(ROOT)}: {', '.join(sorted(unknown))}. Допишите их туда — "
                "иначе список, по которому проверяется политика, отстаёт от того, что уезжает "
                "пользователю.")
        if stale:
            failures.append(
                f"{INJECTED.relative_to(ROOT)} перечисляет разрешения, которых в собранном "
                f"манифесте больше нет: {', '.join(sorted(stale))} — удалите строки.")
        print(f"    источник: собранный манифест ({merged_files[-1].name}), {len(merged)} разрешени(й)")
        return merged

    print(f"    источник: манифест приложения + {INJECTED.name} (сборки нет)")
    return declared | injected


def main() -> int:
    print("==> Сверка утверждений политики с приложением и релизом")
    if not PRIVACY.is_file():
        print("::error::нет web/privacy.html", file=sys.stderr)
        return 1

    shipped = shipped_permissions()
    privacy = PRIVACY.read_text(encoding="utf-8")
    security = SECURITY.read_text(encoding="utf-8") if SECURITY.is_file() else ""

    # 1. Что страница называет — должно быть объявлено.
    named_ok = 0
    for phrase, perms in NAMED.items():
        if re.search(r"<em>" + re.escape(phrase) + r"</em>", privacy, re.I):
            if not any(p in shipped for p in perms):
                failures.append(
                    f"privacy.html называет разрешение «{phrase}», но приложение его не запрашивает "
                    f"({' / '.join(perms)}). Либо разрешение убрали и страницу не поправили, либо "
                    f"страница описывает не тот билд.")
            else:
                named_ok += 1
    print(f"    названо на странице и подтверждено манифестом: {named_ok}")

    # 2. Что страница отрицает — должно отсутствовать.
    denial = re.search(r"does <strong>not</strong> declare[^<]*(?:<[^>]+>[^<]*)*", privacy)
    denial_text = denial.group(0) if denial else ""
    denied_ok = 0
    for phrase, perm in DENIED.items():
        if phrase.lower() in denial_text.lower():
            if perm in shipped:
                failures.append(
                    f"privacy.html утверждает, что приложение НЕ запрашивает «{phrase}», но {perm} "
                    f"есть в поставляемом манифесте. Это прямая неправда в политике "
                    f"конфиденциальности.")
            else:
                denied_ok += 1
        elif perm in shipped:
            failures.append(
                f"{perm} появилось в поставляемом манифесте, а privacy.html об этом молчит "
                f"(«{phrase}»). Разрешение, чувствительное для магазина, должно быть описано.")
    print(f"    отрицаний проверено: {denied_ok}")

    # 3. Утверждения, ставшие ложью после выката подписи.
    try:
        vc = int(json.loads(MANIFEST_JSON.read_text(encoding="utf-8"))["android"]["versionCode"])
    except (OSError, ValueError, KeyError):
        vc = 0
    if vc >= SIGNING_FROM_VERSION_CODE:
        for pattern, why in STALE_AFTER_SIGNING:
            for name, text in (("web/privacy.html", privacy), ("SECURITY.md", security)):
                if re.search(pattern, text, re.I):
                    failures.append(f"{name}: утверждение «{pattern}» устарело — {why} "
                                    f"(релиз versionCode {vc})")
        print(f"    релиз {vc} >= {SIGNING_FROM_VERSION_CODE}: проверено "
              f"{len(STALE_AFTER_SIGNING)} утверждени(й) о подписи")
    else:
        print(f"    релиз {vc} < {SIGNING_FROM_VERSION_CODE}: проверки про подпись не применяются")

    print()
    if failures:
        print(f"ПРОВАЛЕНО: {len(failures)} расхождени(я/й) политики с продуктом", file=sys.stderr)
        for f in failures:
            print(f"::error::  {f}", file=sys.stderr)
        return 1
    print("ВСЁ ЧИСТО: политика описывает то, что действительно поставляется")
    return 0


if __name__ == "__main__":
    sys.exit(main())
