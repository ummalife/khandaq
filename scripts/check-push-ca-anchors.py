#!/usr/bin/env python3
"""KHANDAQ (audit 2026-08-21, K-08) — keep the push.khandaq.org CA-anchor restriction honest.

Android's network security config pins the CA anchors for push.khandaq.org and, deliberately, lets
that restriction EXPIRE:

    <pin-set expiration="2027-08-01">

After that date Android silently reverts to ordinary system-CA validation. That expiry is an
anti-brick safety valve and is argued for in the file itself — it is not the defect. The defect is
that nothing enforces the refresh, and that the same policy is hand-maintained twice, in two
languages, with no link between the copies:

  * Android — `res/xml/network_security_config.xml`: four SHA-256 SPKI pins + an ISO expiry date.
  * iOS     — `OCTSubmanagerChatsImpl.m`: three whole base64 DER certificates handed to
              `SecTrustSetAnchorCertificates`, plus the SAME expiry written out a third time as
              separate `c.year / c.month / c.day` components.

Three ways that rots, and this script fails the build on each:

  (a) the expiry comes within KHANDAQ_PIN_WINDOW_DAYS (default 120) — roughly one release cycle of
      warning before a security control disappears on a known date;
  (b) the two platforms' anchor sets diverge;
  (c) the two hard-coded expiry dates disagree — the easiest mistake to make during exactly the
      rotation this check exists to prompt, and the one that silently fails open on one platform.

Note (b) cannot be a text diff: Android stores SPKI HASHES and iOS stores whole CERTIFICATES. The two
only become comparable after hashing each iOS certificate's SubjectPublicKeyInfo, which is what the
small DER walker below is for. It is stdlib-only on purpose — this runs in CI with no network, no
OpenSSL, no Android SDK and no Xcode.

Run locally:  python3 scripts/check-push-ca-anchors.py
"""
import base64
import datetime
import hashlib
import os
import re
import sys

WINDOW_DAYS = int(os.environ.get("KHANDAQ_PIN_WINDOW_DAYS", "120"))
ROOT = os.environ.get("KHANDAQ_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
XML = os.path.join(ROOT, "khandaq-android-trifa", "android-refimpl-app", "app", "src", "main",
                   "res", "xml", "network_security_config.xml")
IOS = os.path.join(ROOT, "khandaq-ios", "local_pod_repo", "objcTox", "Classes", "Private",
                   "Manager", "Submanagers", "OCTSubmanagerChatsImpl.m")

# The one pin that exists on Android and not on iOS, on purpose.
#
# Android's <pin-set> matches ANY certificate in the validated chain, so pinning the issuing
# intermediate as well as the roots is a free extra check. iOS calls
# SecTrustSetAnchorCertificatesOnly(true), which makes the chain terminate at one of OUR anchors —
# anchoring an intermediate there adds nothing once Root YE is anchored. So this asymmetry is
# deliberate and a strict-equality check would be the wrong check.
#
# The certificate is inlined rather than allowlisted by hash alone. Every other pin is cross-checkable
# against the certificate iOS embeds; this one had no counterpart anywhere in the tree, so a typo in
# it would have gone undetected until push wakes broke in the field. Verified 21 Aug 2026 against the
# live chain served by push.khandaq.org (leaf -> YE1 -> Root YE -> ISRG Root X2), so the pin below is
# now an assertion about a real certificate instead of a number nobody can check.
LE_YE1_DER_B64 = (
    "MIICizCCAhGgAwIBAgIQXd1w3TH4AchcGGp6BLgK/jAKBggqhkjOPQQDAzAuMQswCQYDVQQGEwJVUzENMAsGA1UEChMESVNSRzEQ"
    "MA4GA1UEAxMHUm9vdCBZRTAeFw0yNTA5MDMwMDAwMDBaFw0yODA5MDIyMzU5NTlaMDMxCzAJBgNVBAYTAlVTMRYwFAYDVQQKEw1M"
    "ZXQncyBFbmNyeXB0MQwwCgYDVQQDEwNZRTEwdjAQBgcqhkjOPQIBBgUrgQQAIgNiAAQHZVB1/mimla2hfSurylScjPMZaOJXLz/N"
    "nAc2sylm8WDyhU9Ccp+zASQi5vSwGGJjSGklkD9fdPR8GpyDIOIjCEfrnbt/v+ZSEPLLEGbaM6EccDbN7p9xteIm2Avf+ryjge4w"
    "geswDgYDVR0PAQH/BAQDAgGGMBMGA1UdJQQMMAoGCCsGAQUFBwMBMBIGA1UdEwEB/wQIMAYBAf8CAQAwHQYDVR0OBBYEFLsgykcL"
    "/tflnPmPCSqjjDdFsbzYMB8GA1UdIwQYMBaAFKPIJlqOoUzQNWP8myPIOq5W809WMDIGCCsGAQUFBwEBBCYwJDAiBggrBgEFBQcw"
    "AoYWaHR0cDovL3llLmkubGVuY3Iub3JnLzATBgNVHSAEDDAKMAgGBmeBDAECATAnBgNVHR8EIDAeMBygGqAYhhZodHRwOi8veWUu"
    "Yy5sZW5jci5vcmcvMAoGCCqGSM49BAMDA2gAMGUCMQDgjUEahFT/h3DRakqiPZpLvPgfZwkt6K2EOMmh1nvEzl83eMLYcod4GCl3"
    "b0J1Nn0CMBNYmEQJb4CEG5WoOe7aRn/LVKu6saHmHEynI7ysIPd8zQsK1HdmhlHKlw9Z5GpGvA=="
)
ANDROID_ONLY_REASON = "Let's Encrypt YE1 intermediate (issuer of the push.khandaq.org leaf)"


def die(msg):
    print("::error::" + msg, file=sys.stderr)
    sys.exit(1)


# --- minimal DER walk: Certificate -> tbsCertificate -> subjectPublicKeyInfo -------------------
def der_children(buf):
    """Split one DER SEQUENCE body into (tag, body) pairs. DER only — no indefinite lengths."""
    out, i = [], 0
    while i < len(buf):
        tag = buf[i]
        i += 1
        n = buf[i]
        i += 1
        if n & 0x80:
            k = n & 0x7F
            n = int.from_bytes(buf[i:i + k], "big")
            i += k
        out.append((tag, buf[i:i + n]))
        i += n
    return out


def spki_pin(der):
    """The RFC 7469 pin for a certificate: base64(sha256(DER SubjectPublicKeyInfo))."""
    cert = der_children(der)[0][1]           # Certificate SEQUENCE body
    tbs = der_children(cert)[0][1]           # tbsCertificate SEQUENCE body
    kids = der_children(tbs)
    # tbsCertificate = [0] version?, serialNumber, signature, issuer, validity, subject, SPKI, ...
    idx = 6 if kids and kids[0][0] == 0xA0 else 5
    tag, body = kids[idx]
    if len(body) < 0x80:
        hdr = bytes([tag, len(body)])
    else:
        length = len(body).to_bytes((len(body).bit_length() + 7) // 8, "big")
        hdr = bytes([tag, 0x80 | len(length)]) + length
    return base64.b64encode(hashlib.sha256(hdr + body).digest()).decode()


# --- the Android-only pin, asserted rather than trusted ---------------------------------------
try:
    ANDROID_ONLY = {spki_pin(base64.b64decode(LE_YE1_DER_B64)): ANDROID_ONLY_REASON}
except Exception as exc:                                          # pragma: no cover - fixture bug
    die("the inlined YE1 certificate does not parse (%s)" % exc)

# --- Android side ------------------------------------------------------------------------------
try:
    with open(XML, encoding="utf-8") as fh:
        xml = fh.read()
except OSError as exc:
    die("cannot read %s (%s)" % (XML, exc))

pin_sets = re.findall(r'<pin-set\s+expiration="(\d{4}-\d{2}-\d{2})"', xml)
if not pin_sets:
    die("no <pin-set expiration=...> in %s" % XML)
if len(pin_sets) > 1:
    # More than one pin-set means more than one expiry, and this check only enforces the first.
    die("%s declares %d <pin-set> blocks; this check assumes exactly one" % (XML, len(pin_sets)))
expiry = datetime.date.fromisoformat(pin_sets[0])

android = set(re.findall(r'<pin\s+digest="SHA-256">\s*([A-Za-z0-9+/=]+)\s*</pin>', xml))
if not android:
    die("no <pin> entries in %s — a vacuous pass would be worse than no check" % XML)

# --- iOS side ----------------------------------------------------------------------------------
try:
    with open(IOS, encoding="utf-8", errors="replace") as fh:
        src = fh.read()
except OSError as exc:
    die("cannot read %s (%s)" % (IOS, exc))

consts = dict(re.findall(r'static NSString \*const (kKhandaq\w+B64)\s*=\s*@"([A-Za-z0-9+/=]+)"', src))
used = re.search(r'NSArray \*embedded = @\[([^\]]+)\]', src)
if not used:
    die("cannot find the `NSArray *embedded = @[...]` anchor list in %s" % IOS)
# Read the ARRAY, not merely the set of defined constants: an anchor that is defined but not listed
# is not an anchor, and comparing against definitions would let one silently drop out of use.
names = re.findall(r'kKhandaq\w+B64', used.group(1))
if not names:
    die("the iOS anchor list is empty")

ios = set()
for name in names:
    if name not in consts:
        die("iOS anchor %s is listed in `embedded` but never defined" % name)
    try:
        ios.add(spki_pin(base64.b64decode(consts[name])))
    except Exception as exc:
        die("iOS anchor %s is not a parseable DER certificate (%s)" % (name, exc))

# --- (c) the two hard-coded expiry dates must agree --------------------------------------------
d = re.search(r'c\.year\s*=\s*(\d{4})\s*;\s*c\.month\s*=\s*(\d{1,2})\s*;\s*c\.day\s*=\s*(\d{1,2})\s*;', src)
if not d:
    die("cannot find the iOS pinningActive expiry components in %s" % IOS)
ios_expiry = datetime.date(int(d.group(1)), int(d.group(2)), int(d.group(3)))
if ios_expiry != expiry:
    die("expiry drift: Android %s != iOS %s — one platform would fail open while the other still "
        "pins. Both dates move together or neither does." % (expiry, ios_expiry))

# --- (b) the anchor sets must agree, modulo the one documented asymmetry -----------------------
only_android = android - ios - set(ANDROID_ONLY)
only_ios = ios - android
stale = set(ANDROID_ONLY) - android
if only_android or only_ios or stale:
    for p in sorted(only_android):
        print("::error::pin only on Android: %s" % p, file=sys.stderr)
    for p in sorted(only_ios):
        print("::error::anchor only on iOS: %s" % p, file=sys.stderr)
    for p in sorted(stale):
        # The allowlist cannot rot: dropping YE1 from the XML without dropping it here fails too.
        print("::error::the Android-only allowlist names a pin that is no longer in the XML: %s" % p,
              file=sys.stderr)
    die("push CA-anchor sets diverge between Android and iOS")

# --- (a) the expiry window ----------------------------------------------------------------------
left = (expiry - datetime.date.today()).days
print("push CA-anchor restriction: %d shared anchor(s) + %d Android-only, expiry %s (%d days left)"
      % (len(ios), len(ANDROID_ONLY), expiry, left))
for p in sorted(ios):
    print("  both     %s" % p)
for p, why in sorted(ANDROID_ONLY.items()):
    print("  android  %s  (%s)" % (p, why))
if left <= WINDOW_DAYS:
    die("push CA-anchor expiry %s is %d days away (<= %d). Refresh the anchors AND the date on BOTH "
        "platforms and ship a client release on both stores before it lapses — see the CA-rotation "
        "runbook in docs/PUSH_RELAY.md. After it lapses, both platforms silently fall back to "
        "ordinary system-CA validation." % (expiry, left, WINDOW_DAYS))
