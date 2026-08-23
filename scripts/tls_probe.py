#!/usr/bin/env python3
"""Offer obsolete TLS versions on purpose, so a server's refusal can be proven.

KHANDAQ (re-review v2 2026-08-22, RR2-11). This code lives in its own file for one reason: it is the
only place in the repository that may lower `minimum_version` to TLS 1.0 or 1.1, and CodeQL's
py/insecure-protocol is right to flag that shape everywhere else.

Previously the probe sat inside scripts/verify-site-deploy.py and the query was excluded
REPOSITORY-WIDE, which also suppressed the finding for any future accidental use of TLS 1.0/1.1 in
deployment or relay tooling — a real blind spot bought to silence one deliberate line. Now the file
is the exclusion: .github/codeql/codeql-config.yml lists this path under paths-ignore, the query runs
everywhere else, and security/semgrep-khandaq.yml flags the same constants outside this file.

Nothing is ever transmitted over these sockets. The handshake is expected to FAIL, and a handshake
that succeeds is reported as a failure of the server being checked.
"""
from __future__ import annotations

import socket
import ssl
from typing import NamedTuple


class ProbeResult(NamedTuple):
    name: str
    want_ok: bool          # должен ли этот протокол согласовываться
    connected: bool        # согласовался ли на самом деле
    negotiated: str | None
    skipped: str | None    # причина, если локальный OpenSSL не дал даже попробовать


# (label, min, max, must this version be accepted by a correctly configured server)
VERSIONS = (
    ("TLS 1.0", ssl.TLSVersion.TLSv1, ssl.TLSVersion.TLSv1, False),
    ("TLS 1.1", ssl.TLSVersion.TLSv1_1, ssl.TLSVersion.TLSv1_1, False),
    ("TLS 1.2", ssl.TLSVersion.TLSv1_2, ssl.TLSVersion.TLSv1_2, True),
    ("TLS 1.3", ssl.TLSVersion.TLSv1_3, ssl.TLSVersion.TLSv1_3, True),
)


def probe(host: str, port: int = 443, timeout: int = 15) -> list[ProbeResult]:
    out: list[ProbeResult] = []
    for name, lo, hi, want_ok in VERSIONS:
        ctx = ssl.create_default_context()
        if not want_ok:
            # A modern OpenSSL will not even OFFER TLS 1.0/1.1 at the default security level, so
            # without this the handshake fails locally and the check would pass for the wrong
            # reason — reporting "the server refused it" when in fact we never asked.
            try:
                ctx.set_ciphers("DEFAULT:@SECLEVEL=0")
            except ssl.SSLError:
                out.append(ProbeResult(name, want_ok, False, None,
                                       "локальный OpenSSL не даёт его предложить"))
                continue
        try:
            ctx.minimum_version, ctx.maximum_version = lo, hi
        except (ValueError, OSError):
            out.append(ProbeResult(name, want_ok, False, None,
                                   "локальный OpenSSL его не поддерживает"))
            continue
        try:
            with socket.create_connection((host, port), timeout=timeout) as sock:
                with ctx.wrap_socket(sock, server_hostname=host) as tls:
                    negotiated = tls.version()
            out.append(ProbeResult(name, want_ok, True, negotiated, None))
        except Exception:  # noqa: BLE001 - any failure means "refused", which is the point
            out.append(ProbeResult(name, want_ok, False, None, None))
    return out
