#!/usr/bin/env python3
"""The wake request must not carry the targeting token in its URL. On either client.

KHANDAQ (re-audit 2026-08-22, K-03). The FCM registration token is a targeting secret — hold it and
you can push to that device — and it used to travel in the request URI beside the HMAC and the
timestamp. nginx redacts that query string, and redaction of our own log is not the same as the
value never being there: a URL also reaches crash reporters, intermediary proxies, client-side
diagnostics, and anything that logs before our redaction applies.

Both clients now POST a JSON body to /wake for our own relay. This gate exists because that is the
kind of change that gets quietly reverted — a merge conflict resolved the easy way, a "simplify the
push call" refactor — and the revert is invisible: notifications keep working perfectly while the
credential goes back into the URI.

What is checked, on the real sources rather than on a description of them:

  * Android emits through KhandaqPush.buildWakeRequest, and does not build a URL to POST to.
  * iOS emits through khandaqBuildWakeRequest, likewise.
  * Both put token, sender and cap in the body of a request to /wake.
  * The signature pre-image is still id + "\\n" + from + "\\n" + ts on both, because a client that
    changes shape AND signature looks to the relay like a client with the wrong secret.
  * nginx still redacts the legacy endpoint's query string, since old clients still use it.

Comments are stripped before searching: a previous gate in this repository passed because it matched
the explanatory comment above the code it was supposed to be checking.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

ANDROID_EMIT = ROOT / "khandaq-android-trifa/android-refimpl-app/app/src/main/java/com/zoffcc/applications/trifa/HelperFriend.java"
ANDROID_PUSH = ROOT / "khandaq-android-trifa/android-refimpl-app/app/src/main/java/org/khandaq/messenger/KhandaqPush.java"
IOS_EMIT = ROOT / "khandaq-ios/local_pod_repo/objcTox/Classes/Private/Manager/Submanagers/OCTSubmanagerChatsImpl.m"
NGINX_PUSH = ROOT / "infra/push/nginx-push.conf"

problems: list[str] = []


def strip_comments(text: str) -> str:
    """Remove // and /* */ comments. String literals are left alone — that is what we search."""
    out, i, n = [], 0, len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif text[i] in "\"'":
            q = text[i]
            j = i + 1
            while j < n and text[j] != q:
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def read(path: Path) -> str:
    if not path.is_file():
        problems.append(f"{path.relative_to(ROOT)} отсутствует — гейт не может ничего проверить")
        return ""
    return strip_comments(path.read_text(encoding="utf-8", errors="replace"))


def require(cond: bool, msg: str) -> None:
    if not cond:
        problems.append(msg)


def main() -> int:
    android_emit = read(ANDROID_EMIT)
    android_push = read(ANDROID_PUSH)
    ios_emit = read(IOS_EMIT)
    nginx = read(NGINX_PUSH)

    # --- Android -----------------------------------------------------------------
    require("KhandaqPush.buildWakeRequest(" in android_emit,
            "Android: HelperFriend больше не вызывает KhandaqPush.buildWakeRequest — эмиссия "
            "вернулась к URL с токеном")
    # The old shape built a URL from the push token and posted to it. The new one targets
    # wake.url, which is the bare /wake endpoint. Matched without a leading dot on purpose: OkHttp
    # builder chains put the dot at the end of the PREVIOUS line, so requiring ".url(" here would
    # fail on correct code — the kind of false negative that gets a gate deleted rather than fixed.
    require(re.search(r"\burl\(\s*wake\.url\s*\)", android_emit) is not None,
            "Android: запрос строится не по wake.url — вероятно, вернулся прямой POST по push-URL")
    require('"/wake"' in android_push,
            "Android: KhandaqPush больше не знает про POST /wake")
    # Checked on the RAW text: the Java source escapes its quotes, and strip_comments preserves
    # string literals but the escaping makes the stripped form awkward to match against.
    raw_push = ANDROID_PUSH.read_text(encoding="utf-8", errors="replace")
    for field in ("token", "sender", "cap"):
        require(f'\\"{field}\\"' in raw_push,
                f"Android: в теле запроса нет поля {field}")
    require('id + "\\n" + from + "\\n" + ts' in raw_push
            or 'id + "\\n" + from + "\\n" + ts' in raw_push.replace("\\\\n", "\\n"),
            "Android: прообраз HMAC изменился — реле сочтёт клиента носителем чужого секрета")

    # --- iOS ---------------------------------------------------------------------
    require("khandaqBuildWakeRequest(" in ios_emit,
            "iOS: эмиссия больше не идёт через khandaqBuildWakeRequest")
    # One occurrence only, and it must be inside the builder's legacy branch — that is the path for
    # relays we do not own, which still speak the old shape. A second one means the emission loop has
    # gone back to building its own request from the push URL.
    require(ios_emit.count("[[NSMutableURLRequest alloc] initWithURL:") <= 2,
            "iOS: запрос снова строится напрямую по push-URL в точке отправки")
    require("khandaqBuildWakeRequest(strong_pushToken)" in ios_emit,
            "iOS: цикл отправки больше не берёт запрос у khandaqBuildWakeRequest")
    raw_ios = IOS_EMIT.read_text(encoding="utf-8", errors="replace")
    require('@"https://push.khandaq.org/wake"' in raw_ios,
            "iOS: эндпоинт /wake пропал")
    for field in ("token", "sender", "cap"):
        require(f'body[@"{field}"]' in raw_ios, f"iOS: в теле запроса нет поля {field}")
    require('@"%@\\n%@\\n%@"' in raw_ios,
            "iOS: прообраз HMAC изменился — он должен остаться id\\nfrom\\nts")
    require("X-Khandaq-Ts" in raw_ios and "Authorization" in raw_ios,
            "iOS: аутентификация больше не передаётся заголовками")

    # --- the legacy endpoint is still protected ----------------------------------
    # Old clients keep using it until PUSH_LEGACY_GET retires it, so the redaction has to stay.
    require(re.search(r"toxfcm", nginx) is not None,
            "nginx: блок для legacy-эндпоинта пропал")
    require("$loggable_uri" in nginx or "set $loggable" in nginx or "access_log off" in nginx
            or re.search(r"log_format\s+push_redacted", nginx) is not None,
            "nginx: редакция query-строки на legacy-эндпоинте пропала, а клиенты в поле им ещё пользуются")

    if problems:
        for p in problems:
            print(f"::error::{p}", file=sys.stderr)
        return 1
    print("ок: обе реализации шлют wake телом запроса, прообраз подписи не изменился, "
          "legacy-эндпоинт по-прежнему редактируется в логах")
    return 0


if __name__ == "__main__":
    sys.exit(main())
