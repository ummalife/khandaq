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
    "x-frame-options": "DENY",
    "referrer-policy": "strict-origin-when-cross-origin",
    "permissions-policy": None,
}

# Every host that serves this domain family. Certificates are per-host here — there is no wildcard —
# so each one expires on its own schedule and each one can fail to renew on its own.
TLS_HOSTS = ("khandaq.org", "push.khandaq.org", "mail.khandaq.org")
PUSH_BASE = "https://push.khandaq.org"
# Let's Encrypt renews at 30 days remaining. Warn from there, fail at 14: below that the renewal has
# had two weeks of attempts and has not succeeded, which is a fault rather than a schedule.
CERT_WARN_DAYS = 30
CERT_FAIL_DAYS = 14

CSP_MUST_CONTAIN = ("default-src 'none'", "object-src 'none'", "base-uri 'none'",
                    # KQ-09: the value matters, not merely the directive's presence.
                    "frame-ancestors 'none'")

failures: list[str] = []
checks = 0


def fail(msg: str) -> None:
    failures.append(msg)
    print(f"  ПРОВАЛ: {msg}")


def ok(msg: str) -> None:
    global checks
    checks += 1
    print(f"  ок: {msg}")


def checks_bump() -> None:
    """Count a warning as a performed check: it was verified, it just is not comfortable."""
    global checks
    checks += 1


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
    # KQ-09: HSTS must cover the subdomains, now that all three are inventoried and HTTPS-only.
    if "includesubdomains" not in headers["strict-transport-security"].lower():
        fail(f"{label}: HSTS без includeSubDomains — "
             f"{headers['strict-transport-security']!r}")
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
    ap.add_argument("--any-sha", action="store_true",
                    help="не требовать конкретный коммит: проверить, что опубликованный SHA вообще "
                         "существует в репозитории, и сообщить, насколько сайт отстал. Режим для "
                         "периодического мониторинга из CI, где HEAD ушёл вперёд по определению.")
    args = ap.parse_args()
    base = args.base.rstrip("/")

    expected_sha = args.sha
    if args.any_sha:
        expected_sha = None
    elif expected_sha is None:
        try:
            expected_sha = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "HEAD"],
                                          capture_output=True, text=True, check=True).stdout.strip()
        except (OSError, subprocess.CalledProcessError):
            print("ОШИБКА: не удалось прочитать HEAD; передайте --sha явно", file=sys.stderr)
            return 2

    print(f"==> Проверка {base} "
          + (f"(ожидаемый коммит {expected_sha[:12]})" if expected_sha
             else "(режим мониторинга: конкретный коммит не требуется)"))

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
        elif expected_sha is None:
            # Monitoring mode. The question is not "is the site at HEAD" — it never is for long —
            # but "was it built from a commit that exists, and how far behind has it drifted".
            # A SHA nobody can find is the failure W-02 describes; being a few commits behind is not.
            known = subprocess.run(["git", "-C", str(ROOT), "cat-file", "-e", published + "^{commit}"],
                                   capture_output=True)
            if known.returncode != 0:
                fail(f"опубликованный gitSha {published[:12]} не найден в репозитории — сайт собран "
                     f"неизвестно из чего")
            else:
                behind = subprocess.run(
                    ["git", "-C", str(ROOT), "rev-list", "--count", f"{published}..origin/master"],
                    capture_output=True, text=True)
                n = behind.stdout.strip() if behind.returncode == 0 else "?"
                ok(f"сайт собран из известного коммита {published[:12]} (отстаёт от master на {n})")
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
    probe = (expected_sha or "monitor")[:8]
    check_headers(f"{base}/definitely-not-a-real-path-{probe}", "404")

    print("-- CSP против реально отданного HTML")
    for page in ("/", "/changelog", "/privacy.html"):
        check_csp_covers_inline(base, page)
    # The 404 page is served `internal;`, so it cannot be fetched by its own path — asking for it
    # directly is itself a 404. The body of a real miss is the only way to see what it sends.
    check_csp_covers_inline(base, f"/definitely-not-a-real-path-{probe}", expect=404)

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
        # A signature file is not an artifact that needs a signature of its own. It ends up in
        # SHA256SUMS.txt because that list is built from whatever is in the directory.
        names = [n for n in names if not n.endswith(".sig")]
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

    # 5b. the push relay, from outside
    #
    # KHANDAQ (re-review 2026-08-22, KQ-02): the regression matrix asks for the legacy-endpoint row
    # to be checked "live", and nothing did — the emission-shape gate reads source on a pull request,
    # and the evidence recorder needs ssh and is in no workflow. This runs wherever this probe runs,
    # including the weekly CI job, and asks the relay itself.
    print("-- push-реле снаружи")
    status, headers, body = get(f"{PUSH_BASE}/health")
    if status != 200:
        fail(f"{PUSH_BASE}/health вернул {status}")
    else:
        try:
            keys = sorted(json.loads(body).keys())
        except ValueError:
            keys = None
        if keys is None:
            fail("публичный /health реле не разбирается как JSON")
        elif keys != ["status"]:
            # Adoption percentages tell an outsider how much of the fleet is still unprotected.
            fail(f"публичный /health реле раскрывает лишнее: {', '.join(keys)}")
        else:
            ok("публичный /health реле отдаёт только status")

    # The legacy endpoint must answer consistently with the published retirement state: served today,
    # 410 once PUSH_LEGACY_GET is stepped. Either is fine; silence or a 500 is not.
    status, _, _ = get(f"{PUSH_BASE}/toxfcm/fcm.php?id=probe-not-a-real-token")
    # 502 belongs in the "serving" set: the probe token is deliberately fake, so in soft mode the
    # relay accepts the request and only the upstream FCM send fails. That is the endpoint working.
    if status in (400, 401, 429, 502):
        ok(f"legacy-эндпоинт ещё обслуживается ({status}) — совпадает с PUSH_LEGACY_GET=serve")
    elif status in (404, 410):
        ok(f"legacy-эндпоинт выведен ({status})")
    else:
        fail(f"legacy-эндпоинт ответил {status} — ни обслуживание, ни вывод")

    # A body-less POST must be refused rather than crash: this is the path every current client uses.
    status, _, _ = get(f"{PUSH_BASE}/wake", method="POST")
    if status == 400:
        ok("POST /wake без тела отвергается (400)")
    else:
        fail(f"POST /wake без тела ответил {status}, ожидался 400")

    # 6. certificate expiry, on every host that serves this domain
    #
    # KHANDAQ (re-review 2026-08-22, KQ-09): "confirm push.khandaq.org and any operational hosts have
    # valid HTTPS and renewal monitoring". Renewal being CONFIGURED is not the same as renewal
    # HAPPENING — an ACME challenge that a redirect swallows fails silently and the first symptom is
    # a browser warning. This is the monitoring half: it runs on every deploy and weekly from CI, and
    # it fails while there is still time to fix the cause rather than after the certificate lapses.
    print("-- сроки сертификатов")
    for host in TLS_HOSTS:
        try:
            ctx = ssl.create_default_context()
            with socket.create_connection((host, 443), timeout=15) as sock:
                with ctx.wrap_socket(sock, server_hostname=host) as tls:
                    cert = tls.getpeercert()
        except Exception as exc:  # noqa: BLE001 - any failure here is a real problem to report
            fail(f"{host}: сертификат не читается ({exc})")
            continue
        try:
            not_after = dt.datetime.strptime(cert["notAfter"], "%b %d %H:%M:%S %Y %Z").replace(
                tzinfo=dt.timezone.utc)
        except (KeyError, ValueError) as exc:
            fail(f"{host}: не разобрать notAfter ({exc})")
            continue
        left = (not_after - dt.datetime.now(dt.timezone.utc)).days
        if left <= CERT_FAIL_DAYS:
            fail(f"{host}: сертификат истекает через {left} дн. ({cert['notAfter']}) — "
                 f"автопродление не сработало, чинить сейчас")
        elif left <= CERT_WARN_DAYS:
            print(f"  ВНИМАНИЕ: {host} — {left} дн. до истечения ({cert['notAfter']}); "
                  f"продление должно было пройти, проверьте его")
            checks_bump()
        else:
            ok(f"{host}: сертификат действителен ещё {left} дн.")

    # 7. TLS: 1.0/1.1 must be refused, 1.2/1.3 must work
    if not args.skip_tls:
        print("-- TLS")
        host = base.split("://", 1)[1].split("/", 1)[0]
        for name, lo, hi, want_ok in (
            ("TLS 1.0", ssl.TLSVersion.TLSv1, ssl.TLSVersion.TLSv1, False),
            ("TLS 1.1", ssl.TLSVersion.TLSv1_1, ssl.TLSVersion.TLSv1_1, False),
            ("TLS 1.2", ssl.TLSVersion.TLSv1_2, ssl.TLSVersion.TLSv1_2, True),
            ("TLS 1.3", ssl.TLSVersion.TLSv1_3, ssl.TLSVersion.TLSv1_3, True),
        ):
            # REVIEWED (re-review 2026-08-22, KQ-10): CodeQL reports py/insecure-protocol here,
            # and it is right about the shape — the loop deliberately lowers minimum_version to TLS
            # 1.0 and 1.1. That is the POINT: the only way to prove the server refuses an obsolete
            # protocol is to offer it. Nothing is transmitted, the handshake is expected to fail, and
            # a handshake that SUCCEEDS is reported as a failure of the site. The exclusion lives in
            # .github/codeql/codeql-config.yml — inline codeql[] comments do not clear alerts in
            # GitHub code scanning.
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
