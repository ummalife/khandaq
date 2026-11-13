#!/usr/bin/env python3
"""Generate (and verify) the nginx security-header baseline for khandaq.org.

KHANDAQ (re-audit 2026-08-22, W-01). The versioned nginx snippet set nosniff/X-Frame-Options/
Referrer-Policy on some locations and nothing on the rest, and the rest of the baseline — CSP,
HSTS, Permissions-Policy — lived only in a hand-edited server block on the host. Controls that
exist only on a server are controls that vanish the next time the server is rebuilt, and nothing
notices. So the whole baseline is generated here, committed, and checked after every deploy.

The CSP is hash-based, not 'unsafe-inline'. The pages carry a handful of inline <script> blocks
(the theme bootstrap has to run before first paint or the page flashes white) and privacy.html
carries its own <style>. Each one is hashed and named explicitly, which means an edit to any of
them changes the hash — and `--check` then fails in CI rather than the script being silently
blocked in the browser after deploy.

KHANDAQ (SEO, 2026-11-13). A <script> whose type is a data format is not hashed. The browser never
runs such a data block, and the CSP never sees it: HTML stops preparing the element as soon as the
type is not a script type, before the policy check. The JSON-LD that describes the app to search
engines is one, and a hash for it would only add a source to script-src that nothing executes. It
still has to be the data it claims to be, so a block that does not parse as JSON fails here.
scripts/verify-site-deploy.py makes the same exemption when it checks the served pages.

    scripts/generate-security-headers.py           # write infra/nginx/khandaq-security-headers.conf
    scripts/generate-security-headers.py --check   # fail if the committed file is stale
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "web"
OUT = ROOT / "infra" / "nginx" / "khandaq-security-headers.conf"

# Script types that only carry data. Kept to the one the site uses: any other type is still hashed,
# which costs nothing if it turns out to be data too.
DATA_SCRIPT_TYPES = ("application/ld+json",)
SCRIPT_TYPE = re.compile(r"(?:^|\s)type\s*=\s*(?:\"([^\"]*)\"|'([^']*)'|([^\s\"'>]+))", re.I)


def data_script_type(attrs: str) -> str | None:
    """The type of a <script> that only carries data, or None for one the browser would run."""
    m = SCRIPT_TYPE.search(attrs)
    if not m:
        return None
    value = next(group for group in m.groups() if group is not None).strip().lower()
    return value if value in DATA_SCRIPT_TYPES else None

# KHANDAQ (re-review 2026-08-22, KQ-09): includeSubDomains, after actually inventorying them.
#
# The previous value left it off because the subdomain list was not established. It is now: DNS has
# exactly three names — khandaq.org, push.khandaq.org and mail.khandaq.org — there is no wildcard
# record, each holds its own valid certificate, and all three already answer :80 with a 301 to
# https. So the directive describes what is already true rather than imposing something new, which
# is the only safe way to turn it on.
#
# Still NOT preload. That is a submission to a list baked into browser binaries and is effectively
# irreversible; it deserves a separate decision made on operational readiness, not a side effect of
# closing a finding.
HSTS = "max-age=31536000; includeSubDomains"

PERMISSIONS_POLICY = ", ".join(
    f"{feature}=()"
    for feature in (
        "accelerometer", "autoplay", "camera", "display-capture", "encrypted-media",
        "fullscreen", "geolocation", "gyroscope", "magnetometer", "microphone",
        "midi", "payment", "picture-in-picture", "publickey-credentials-get",
        "screen-wake-lock", "usb", "xr-spatial-tracking",
    )
)


def sha256_csp(text: str) -> str:
    return "'sha256-" + base64.b64encode(hashlib.sha256(text.encode("utf-8")).digest()).decode() + "'"


def inline_hashes() -> tuple[list[str], list[str], list[str], list[str]]:
    """Hash every inline <script> and <style> across the published pages.

    Returns (script hashes, style hashes, provenance lines for the file header, data blocks that
    were left unhashed).
    """
    scripts: list[str] = []
    styles: list[str] = []
    notes: list[str] = []
    data_blocks: list[str] = []
    # KHANDAQ (redesign E23, 2026-11-11): the Russian pages under web/ru/ are served with the same
    # header and carry the same kinds of inline block, so they are hashed exactly like the top level.
    for page in sorted(WEB.glob("*.html")) + sorted((WEB / "ru").glob("*.html")):
        name = page.relative_to(WEB).as_posix()
        html = page.read_text(encoding="utf-8")
        for tag, bucket in (("script", scripts), ("style", styles)):
            # Only blocks with a body: <script src=...> loads a file and is covered by 'self'.
            for m in re.finditer(rf"<{tag}\b([^>]*)>(.*?)</{tag}>", html, re.S | re.I):
                attrs, body = m.group(1), m.group(2)
                if re.search(r"\bsrc\s*=", attrs, re.I):
                    continue
                if not body.strip():
                    continue
                line = html[: m.start()].count("\n") + 1
                kind = data_script_type(attrs) if tag == "script" else None
                if kind:
                    try:
                        json.loads(body)
                    except ValueError as exc:
                        raise SystemExit(
                            f"ERROR: {name}:{line}: <script type=\"{kind}\"> is not valid JSON ({exc}). "
                            f"It is left out of the CSP because it is data, so it must parse as data."
                        )
                    data_blocks.append(f"#   {name}:{line}  <script type=\"{kind}\">")
                    continue
                h = sha256_csp(body)
                if h not in bucket:
                    bucket.append(h)
                notes.append(f"#   {name}:{line}  <{tag}>  {h}")
        # An inline style="" attribute cannot be hashed — it would force 'unsafe-inline' back into
        # style-src (style-src-attr is not carried by every supported browser). They were moved into
        # the stylesheets; this keeps them from creeping back.
        if re.search(r"<[^>]+\sstyle\s*=", html):
            raise SystemExit(
                f"ОШИБКА: {name} содержит инлайновый атрибут style= — он не хешируется и "
                f"потребовал бы 'unsafe-inline' в style-src. Перенесите объявление в CSS."
            )
    return scripts, styles, notes, data_blocks


def render() -> str:
    scripts, styles, notes, data_blocks = inline_hashes()
    csp = "; ".join([
        "default-src 'none'",
        "script-src 'self' " + " ".join(scripts),
        "style-src 'self' " + " ".join(styles),
        "img-src 'self'",
        "font-src 'self'",
        # changelog.html fetches /changelog.json; nothing else talks to the network.
        "connect-src 'self'",
        "base-uri 'none'",
        "form-action 'none'",
        # KQ-09: 'none', not 'self'. Nothing on this site embeds any other part of it, so same-origin
        # framing was permission granted for a use case that does not exist.
        "frame-ancestors 'none'",
        "object-src 'none'",
        "upgrade-insecure-requests",
    ])
    body = [
        "# Generated by scripts/generate-security-headers.py — do not edit by hand.",
        "# Re-run the generator after touching any inline <script>/<style> in web/*.html;",
        "# scripts/check-security-headers.py fails the build when this file is stale.",
        "#",
        "# Included from EVERY location block. nginx's add_header does not merge: a location that",
        "# sets one header of its own drops every header inherited from the server level, so a",
        "# baseline declared once at the top would silently disappear on exactly the locations that",
        "# customise caching — which is most of them.",
        "#",
        "# Hashed inline blocks:",
        *notes,
        *(["#", "# Inline data blocks, never executed and so not hashed:", *data_blocks] if data_blocks else []),
        "",
        f'add_header Content-Security-Policy "{csp}" always;',
        f'add_header Strict-Transport-Security "{HSTS}" always;',
        "add_header X-Content-Type-Options nosniff always;",
        "add_header X-Frame-Options DENY always;",
        'add_header Referrer-Policy "strict-origin-when-cross-origin" always;',
        f'add_header Permissions-Policy "{PERMISSIONS_POLICY}" always;',
        "add_header Cross-Origin-Opener-Policy same-origin always;",
        "add_header Cross-Origin-Resource-Policy same-origin always;",
        "",
    ]
    return "\n".join(body)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    want = render()
    if args.check:
        if not OUT.is_file():
            print(f"ОШИБКА: {OUT.relative_to(ROOT)} отсутствует — запустите генератор", file=sys.stderr)
            return 1
        have = OUT.read_text(encoding="utf-8")
        if have != want:
            print(f"ОШИБКА: {OUT.relative_to(ROOT)} устарел относительно web/*.html.", file=sys.stderr)
            print("Инлайновый скрипт или стиль изменился, а CSP-хеш — нет; после деплоя браузер", file=sys.stderr)
            print("заблокировал бы его. Перегенерируйте: scripts/generate-security-headers.py", file=sys.stderr)
            return 1
        print(f"ок: {OUT.relative_to(ROOT)} соответствует страницам")
        return 0

    OUT.parent.mkdir(parents=True, exist_ok=True)
    changed = (not OUT.is_file()) or OUT.read_text(encoding="utf-8") != want
    OUT.write_text(want, encoding="utf-8")
    print(f"{'обновлён' if changed else 'без изменений'}: {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
