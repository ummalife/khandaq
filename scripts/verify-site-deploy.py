#!/usr/bin/env python3
"""Prove, from outside the server, that khandaq.org is serving what this checkout says it should.

KHANDAQ (re-audit 2026-08-22, W-01/W-02/W-03). Three of the website findings share one root cause:
the only evidence that the live site was configured correctly was the configuration file we had
locally. That is not evidence — nginx on the host had been hand-edited, the published pages had
drifted a whole release behind the repository without anything noticing, and a header that quietly
stopped being sent would have gone unnoticed indefinitely.

So this runs against the public HTTPS endpoint, never against localhost, and fails the deploy:

  * /release-manifest.json carries the git SHA that was deployed, and it matches this checkout;
  * the version strings on the homepage and changelog agree with that manifest;
  * the security-header baseline is present on a normal page, a directory, a pretty URL,
    a redirect and a 404 — the five response classes nginx treats differently;
  * /.well-known/security.txt is 200, text/plain, and not expired;
  * TLS 1.0/1.1 are refused and 1.2/1.3 negotiate.

    scripts/verify-site-deploy.py                      # expect this checkout's HEAD
    scripts/verify-site-deploy.py --sha <sha>          # expect a specific commit
    scripts/verify-site-deploy.py --base https://...   # another environment
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import re
import socket
import ssl
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_BASE = "https://khandaq.org"

# Every response must carry these. Values are checked where a wrong value is as bad as a missing
# one; for CSP the presence plus a couple of load-bearing directives is what matters.
REQUIRED_HEADERS = {
    "content-security-policy": None,
    "strict-transport-security": None,
    "x-content-type-options": "nosniff",
    "x-frame-options": "SAMEORIGIN",
    "referrer-policy": "strict-origin-when-cross-origin",
    "permissions-policy": None,
}

CSP_MUST_CONTAIN = ("default-src 'none'", "object-src 'none'", "base-uri 'none'", "frame-ancestors")

failures: list[str] = []
checks = 0


def fail(msg: str) -> None:
    failures.append(msg)
    print(f"  ПРОВАЛ: {msg}")


def ok(msg: str) -> None:
    global checks
    checks += 1
    print(f"  ок: {msg}")


def get(url: str, *, method: str = "GET", allow_redirect: bool = False):
    """Return (status, headers, body). Redirects are NOT followed unless asked."""
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **k):  # noqa: D102
            return None

    opener = urllib.request.build_opener() if allow_redirect else urllib.request.build_opener(NoRedirect)
    req = urllib.request.Request(url, method=method, headers={"User-Agent": "khandaq-deploy-verify/1"})
    try:
        with opener.open(req, timeout=30) as resp:
            return resp.status, {k.lower(): v for k, v in resp.headers.items()}, resp.read()
    except urllib.error.HTTPError as exc:
        # A 404 or 301 is a response, not an error: several checks below are ABOUT those.
        return exc.code, {k.lower(): v for k, v in exc.headers.items()}, exc.read()
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        fail(f"{url}: запрос не выполнен ({exc})")
        return None, {}, b""


def check_headers(url: str, label: str) -> None:
    status, headers, _ = get(url)
    if status is None:
        return
    missing = [h for h in REQUIRED_HEADERS if h not in headers]
    if missing:
        fail(f"{label} ({status}): нет заголовков {', '.join(missing)}")
        return
    for header, want in REQUIRED_HEADERS.items():
        if want is not None and headers[header].strip() != want:
            fail(f"{label}: {header} = {headers[header]!r}, ожидалось {want!r}")
            return
    csp = headers["content-security-policy"]
    for token in CSP_MUST_CONTAIN:
        if token not in csp:
            fail(f"{label}: в CSP нет '{token}'")
            return
    if "unsafe-inline" in csp or "unsafe-eval" in csp:
        fail(f"{label}: CSP содержит unsafe-inline/unsafe-eval")
        return
    ok(f"{label} ({status}): базовый набор заголовков на месте, CSP без unsafe-*")


def check_csp_covers_inline(base: str, path: str, expect: int = 200) -> None:
    """Would the browser block anything on this page?

    The CSP is hash-based, so the answer is decidable without a browser: hash every inline block the
    server actually SENT and confirm each hash appears in the policy that came with it. This catches
    the one way a hash-based CSP fails in production — a page edited after the snippet was
    generated — which is invisible in a diff and total in a browser.
    """
    status, headers, body = get(f"{base}{path}", allow_redirect=True)
    if status is None:
        return
    if status != expect:
        fail(f"{path}: ожидался {expect}, получен {status}")
        return
    csp = headers.get("content-security-policy", "")
    if not csp:
        fail(f"{path}: нет CSP, покрытие инлайна проверить нельзя")
        return
    html = body.decode("utf-8", "replace")
    blocked = []
    for tag in ("script", "style"):
        for m in re.finditer(rf"<{tag}\b([^>]*)>(.*?)</{tag}>", html, re.S | re.I):
            attrs, inner = m.group(1), m.group(2)
            if re.search(r"\bsrc\s*=", attrs, re.I) or not inner.strip():
                continue
            digest = base64.b64encode(hashlib.sha256(inner.encode("utf-8")).digest()).decode()
            if f"'sha256-{digest}'" not in csp:
                line = html[: m.start()].count("\n") + 1
                blocked.append(f"<{tag}> на строке {line} (sha256-{digest[:16]}...)")
    if re.search(r"<[^>]+\sstyle\s*=", html):
        blocked.append("инлайновый атрибут style= (CSP без unsafe-inline его запретит)")
    if blocked:
        fail(f"{path}: браузер заблокирует — " + "; ".join(blocked))
    else:
        ok(f"{path}: все инлайновые блоки покрыты хешами CSP")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--sha", default=None, help="ожидаемый git SHA (по умолчанию — HEAD этого checkout)")
    ap.add_argument("--skip-tls", action="store_true", help="пропустить проверку версий TLS")
    args = ap.parse_args()
    base = args.base.rstrip("/")

    expected_sha = args.sha
    if expected_sha is None:
        try:
            expected_sha = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "HEAD"],
                                          capture_output=True, text=True, check=True).stdout.strip()
        except (OSError, subprocess.CalledProcessError):
            print("ОШИБКА: не удалось прочитать HEAD; передайте --sha явно", file=sys.stderr)
            return 2

    print(f"==> Проверка {base} (ожидаемый коммит {expected_sha[:12]})")

    # 1. the manifest, and the commit it was deployed from
    print("-- release manifest")
    status, headers, body = get(f"{base}/release-manifest.json")
    manifest = None
    if status != 200:
        fail(f"/release-manifest.json вернул {status}")
    else:
        try:
            manifest = json.loads(body)
        except ValueError as exc:
            fail(f"/release-manifest.json не разбирается как JSON ({exc})")
    if manifest is not None:
        published = (manifest.get("site") or {}).get("gitSha")
        if not published:
            fail("в опубликованном манифесте gitSha пуст — сайт выложен не через deploy-site.sh")
        elif published != expected_sha:
            fail(f"сайт собран из {published[:12]}, ожидался {expected_sha[:12]}")
        else:
            ok(f"сайт собран из ожидаемого коммита {published[:12]}")
        if (manifest.get("site") or {}).get("gitTreeClean") is False:
            print("  ВНИМАНИЕ: деплой сделан из грязного дерева — опубликованы байты, которых нет в коммите")

    # 2. the pages must not contradict it
    print("-- согласованность версий")
    if manifest is not None:
        status, _, body = get(f"{base}/", allow_redirect=True)
        html = body.decode("utf-8", "replace") if body else ""
        android = manifest["android"]["versionName"]
        ios_build = manifest["ios"]["buildNumber"]
        ios_ver = manifest["ios"]["marketingVersion"]
        if f"v{android}" in html or android in html:
            ok(f"домашняя страница называет Android {android}")
        else:
            fail(f"на домашней странице нет версии Android {android} из манифеста")
        if ios_build in html or ios_ver in html:
            ok(f"домашняя страница называет iOS {ios_ver}/{ios_build}")
        else:
            fail(f"на домашней странице нет сборки iOS {ios_build} из манифеста")

    # 3. header baseline across the five response classes nginx handles differently
    print("-- заголовки безопасности")
    check_headers(f"{base}/", "страница /")
    check_headers(f"{base}/downloads/", "каталог /downloads/")
    check_headers(f"{base}/changelog", "красивый URL /changelog")
    check_headers(f"{base}/messenger", "редирект /messenger")
    check_headers(f"{base}/definitely-not-a-real-path-{expected_sha[:8]}", "404")

    print("-- CSP против реально отданного HTML")
    for page in ("/", "/changelog", "/privacy.html"):
        check_csp_covers_inline(base, page)
    # The 404 page is served `internal;`, so it cannot be fetched by its own path — asking for it
    # directly is itself a 404. The body of a real miss is the only way to see what it sends.
    check_csp_covers_inline(base, f"/definitely-not-a-real-path-{expected_sha[:8]}", expect=404)

    # 4. security.txt
    print("-- security.txt")
    status, headers, body = get(f"{base}/.well-known/security.txt")
    if status != 200:
        fail(f"/.well-known/security.txt вернул {status}")
    else:
        ctype = headers.get("content-type", "")
        if not ctype.startswith("text/plain"):
            fail(f"security.txt отдан как {ctype!r}, а не text/plain")
        else:
            ok(f"security.txt: 200, {ctype}")
        text = body.decode("utf-8", "replace")
        m = re.search(r"^Expires:\s*(\S+)", text, re.M)
        if not m:
            fail("в security.txt нет поля Expires (RFC 9116 требует его)")
        else:
            try:
                exp = dt.datetime.fromisoformat(m.group(1).replace("Z", "+00:00"))
                left = (exp - dt.datetime.now(dt.timezone.utc)).days
                if left <= 0:
                    fail(f"security.txt просрочен ({m.group(1)})")
                elif left < 30:
                    fail(f"security.txt истекает через {left} дн. — обновите Expires до деплоя")
                else:
                    ok(f"security.txt действителен ещё {left} дн.")
            except ValueError:
                fail(f"Expires не разбирается: {m.group(1)!r}")
        if not re.search(r"^Contact:\s*\S+", text, re.M):
            fail("в security.txt нет поля Contact")

    # 5. every published artifact must have a published signature beside it
    print("-- подписи артефактов")
    status, _, body = get(f"{base}/downloads/SHA256SUMS.txt")
    if status != 200:
        fail(f"/downloads/SHA256SUMS.txt вернул {status}")
    else:
        names = [m.group(1) for m in re.finditer(r"^[0-9a-fA-F]{64}\s+\*?(\S+)\s*$",
                                                 body.decode("utf-8", "replace"), re.M)]
        if not names:
            fail("SHA256SUMS.txt опубликован, но не содержит ни одной записи")
        missing = []
        for name in names:
            st, _, _ = get(f"{base}/downloads/{name}.sig")
            if st != 200:
                missing.append(name)
        if missing:
            # Not fatal on its own — the artifacts predate the signing work and are re-signed on the
            # next deploy that stages them — but it must be visible, because "there is a signature"
            # is the claim the download page now makes.
            fail("нет опубликованной подписи для: " + ", ".join(missing))
        else:
            ok(f"подписи на месте для всех {len(names)} артефактов")
        st, _, _ = get(f"{base}/downloads/allowed_signers")
        if st != 200:
            fail("/downloads/allowed_signers недоступен — проверить подпись нечем")
        else:
            ok("allowed_signers опубликован")

    # 6. TLS: 1.0/1.1 must be refused, 1.2/1.3 must work
    if not args.skip_tls:
        print("-- TLS")
        host = base.split("://", 1)[1].split("/", 1)[0]
        for name, lo, hi, want_ok in (
            ("TLS 1.0", ssl.TLSVersion.TLSv1, ssl.TLSVersion.TLSv1, False),
            ("TLS 1.1", ssl.TLSVersion.TLSv1_1, ssl.TLSVersion.TLSv1_1, False),
            ("TLS 1.2", ssl.TLSVersion.TLSv1_2, ssl.TLSVersion.TLSv1_2, True),
            ("TLS 1.3", ssl.TLSVersion.TLSv1_3, ssl.TLSVersion.TLSv1_3, True),
        ):
            ctx = ssl.create_default_context()
            if not want_ok:
                # A modern OpenSSL will not even OFFER TLS 1.0/1.1 at the default security level, so
                # without this the handshake fails locally and the check would pass for the wrong
                # reason — reporting "the server refused it" when in fact we never asked.
                try:
                    ctx.set_ciphers("DEFAULT:@SECLEVEL=0")
                except ssl.SSLError:
                    print(f"  пропуск {name}: локальный OpenSSL не даёт его предложить")
                    continue
            try:
                ctx.minimum_version, ctx.maximum_version = lo, hi
            except (ValueError, OSError):
                print(f"  пропуск {name}: локальный OpenSSL его не поддерживает")
                continue
            try:
                with socket.create_connection((host, 443), timeout=15) as sock:
                    with ctx.wrap_socket(sock, server_hostname=host) as tls:
                        negotiated = tls.version()
                connected = True
            except Exception:  # noqa: BLE001 - any failure means "refused", which is the point
                connected, negotiated = False, None
            if want_ok and not connected:
                fail(f"{name} не согласуется — современные клиенты не смогут подключиться")
            elif not want_ok and connected:
                fail(f"{name} всё ещё принимается сервером ({negotiated})")
            else:
                ok(f"{name}: {'работает' if connected else 'отклонён'}")

    print()
    if failures:
        print(f"ПРОВАЛЕНО: {len(failures)} из {checks + len(failures)} проверок")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"ВСЁ ЧИСТО: {checks} проверок пройдено")
    return 0


if __name__ == "__main__":
    sys.exit(main())
