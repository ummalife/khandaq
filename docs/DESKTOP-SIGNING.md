# Desktop release signing

KHANDAQ (re-audit 2026-08-22, K-05). The finding: the site shipped unsigned Windows and macOS
builds and then taught people to click past the warnings — "More info → Run anyway", `xattr -cr`,
add an antivirus exception. Those are the defences that would catch a substituted binary, and a
user trained to dismiss them has none left. A `SHA256SUMS.txt` beside the download does not replace
them: whoever can replace the binary can replace the list, because both come down the same pipe.

There are three layers. One is done and live; two need certificates only the account holder can
obtain, and this is the runbook for those.

## 1. Detached Ed25519 signatures — DONE

`scripts/sign-desktop-artifacts.sh` signs every staged artifact and `scripts/deploy-site.sh` runs it
on every deploy. The private key lives at `~/.khandaq/release-signing/khandaq-release-ed25519` on the
release machine and is never committed; the public half is published in this repository as
`web/downloads/KHANDAQ-RELEASE-SIGNING.pub` and `web/downloads/allowed_signers`.

The repository is a **different distribution channel from the website**, which is the entire point:
an attacker who owns the web server cannot also rewrite git, so they cannot produce a signature that
verifies.

Verification needs nothing installed — `ssh-keygen` ships with macOS, every Linux, and Git for
Windows:

```
ssh-keygen -Y verify -f allowed_signers -I releases@khandaq.org -n khandaq-release \
    -s khandaq-messenger_amd64.deb.sig < khandaq-messenger_amd64.deb
```

**Back up that private key.** Losing it does not break anything already published, but it forces a
new public key onto every user who has learned the old one.

To rotate: delete the key, re-run the signing script (it mints a new one), commit the new public
half, and re-sign. Announce it — a public key that changes without notice is indistinguishable from
an attack.

## 2. Windows Authenticode — NEEDS A CERTIFICATE

Owner: **@UMMAPROJECTS**. Blocked on buying an OV or EV code-signing certificate; nothing in this
repository can proceed without one.

Once it exists:

1. Import the .pfx on the signing machine, or use a cloud HSM (an EV certificate requires hardware).
2. Sign and timestamp — the timestamp is what keeps the signature valid after the certificate
   expires:
   `signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 /a Khandaq-installer.exe`
3. Verify: `Get-AuthenticodeSignature Khandaq-installer.exe` must report `Valid`, not
   `UnknownError` or `NotSigned`.
4. Then, and only then, remove the "publisher is unknown" paragraph from `web/index.html`.

Reputation with SmartScreen accrues over downloads: an OV certificate will still warn at first. An
EV certificate carries reputation immediately, and costs more.

## 3. macOS Developer ID + notarization — NEEDS A CERTIFICATE

Owner: **@UMMAPROJECTS**. The Apple Developer Program membership already exists (team DQRPWB97AB),
so this costs nothing extra — but a **Developer ID Application** certificate is a different type
from the App Store ones already in the keychain, and creating it needs the Account Holder role.
`security find-identity -v -p codesigning` currently shows only *Apple Development* and *iPhone
Distribution*, which cannot sign a Mac app for distribution outside the App Store.

Once it exists:

1. `codesign --deep --force --options runtime --timestamp -s "Developer ID Application: …" khandaq.app`
   — `--options runtime` (hardened runtime) is required for notarization.
2. `ditto -c -k --keepParent khandaq.app khandaq-macos.zip`
3. `xcrun notarytool submit khandaq-macos.zip --key <ASC .p8> --key-id 8L7667TZPK \
       --issuer cb911406-51d8-49e2-adea-70a8477267c9 --wait`
   The App Store Connect API key already on this machine works; no app-specific password needed.
4. `xcrun stapler staple khandaq.app`, then re-zip. Stapling is what makes it open on a machine that
   is offline.
5. Verify: `spctl -a -vvv -t install khandaq.app` must say `accepted / source=Notarized Developer ID`.
6. Then remove the "not yet notarized" paragraph from `web/index.html`.

## 4. What the site says in the meantime

It does not tell anyone to disable anything. It states plainly that the Windows build is unsigned
and the macOS build is not notarized, points at the signature check as the thing that actually
answers "is this the file they built", and — for macOS — gives right-click → Open, which is Apple's
documented path for an app Gatekeeper cannot vouch for and leaves the rest of Gatekeeper working.
`xattr -cr` was removed: it strips quarantine from the whole bundle and switches off exactly the
check you would want on the day a download is not genuine.

This is a compensating control, not a fix. It stops teaching the wrong habit and it gives a real
verification path; it does not give Windows or macOS a way to check the publisher themselves. That
needs sections 2 and 3.
