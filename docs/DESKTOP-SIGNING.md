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
an attack. Since the desktop app updates itself (section 5), the app trusts this key too, so a
rotation takes two releases; see "Rotating the key" there before deleting anything.

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

## 5. The desktop update manifest

KHANDAQ (0.2.66). The desktop app checks for a newer release by itself and installs one when the
person presses Update. What it trusts is `https://khandaq.org/downloads/khandaq-desktop-update.json`,
and only once the release key's signature over it, `khandaq-desktop-update.json.sig`, verifies with
the key compiled into the app. TLS carries it; it is not what the app trusts.

**What it says.** For each desktop download (`windows-x86_64-installer`, `windows-x86_64-zip`,
`macos-zip`, `linux-deb-amd64`, `linux-portable-x86_64`): the version inside it, the file, its size
and its SHA-256, plus the changelog pages in English and Russian, and a `serial`: the UTC time the
generator wrote it, in seconds, always above the serial of the manifest before. The app downloads
the file only after the click, refuses anything larger than the size or with another SHA-256, never
offers a version lower than one a verified manifest has already shown it for that kind of download,
and refuses a manifest with a lower serial than the highest it has verified. So an old signed
manifest served again takes nobody back, and is refused outright, even where it names a version
offered before with other bytes. What the serial cannot do is tell the newest manifest an app has
seen from one published since: there is no expiry date, which would mean signing the manifest again
on a schedule, without a release ([SECURITY.md](../SECURITY.md) has the limit).

**How it is made.** `scripts/deploy-site.sh` runs `scripts/generate-desktop-update-manifest.py` on
the downloads it has just staged from `dist/`, never on a file fetched from the server. The version
of each entry is read from the program inside the artifact (the `GIT_DESCRIBE` string the build
compiled in), so a build that is not the exact release tag (`v0.2.65-3-gabc1234` or `Nightly`), one
from a tree with changes nobody committed (`v0.2.65-dirty`: every build describes itself with
`git describe --dirty`) and a QA build are refused. The installer is unpacked with 7-Zip (`7zz` or
`7z`, held to the NSIS format), and the `bin/Khandaq.exe` it would install must be byte for byte the
`Khandaq.exe` of the zip staged with it, so that both come from one build; its version resource
(`VERSION` in `qtox64.nsi`) must agree too. Without 7-Zip on the release machine the installer is
refused. A platform not staged in that run keeps its entry while `SHA256SUMS.txt` still lists the
same bytes, and no platform goes back to an older version. A changed manifest gets a higher serial
and loses its old signature, and `scripts/sign-desktop-artifacts.sh` signs it with the release key in
the namespace `khandaq-desktop-update`, which the app verifies and no artifact signature
(`khandaq-release`) can pass for; written again with nothing changed, it keeps its serial, its bytes
and its signature. The manifest and its signature are committed
with the other `.sig` files and `SHA256SUMS.txt` in the post-release site PR; the manifest is not
listed in `SHA256SUMS.txt`, which lists downloads.

**How it goes up.** Only a deploy that staged a desktop download from `dist/`, and so wrote the
manifest, uploads it. Any other deploy leaves the live manifest as it is: the copy committed in the
checkout can be older than the one live, and putting it back would hold the apps on an older
release. The downloads go up first, and an error in any of them stops the deploy; the manifest goes
up last, its signature and then itself, each renamed into place. A rollback
(`deploy-site.sh --rollback`) brings back the snapshot's manifest, which apps that verified a newer
one refuse until the next release publishes a higher serial.

**How it is checked.**

- Before the upload, once the deploy has fetched from the server the downloads it did not stage,
  `generate-desktop-update-manifest.py --check --with-downloads`: every SHA-256 in it is the one
  `SHA256SUMS.txt` gives for that file, every download it names is there as those bytes, and the
  file is in the form the generator writes. Then `--against-live`, with the live manifest copied
  over ssh for this comparison and nothing else: a lower serial than the live one, a platform at a
  lower version or missing, or the live serial with other content stop the deploy.
- After the upload, `scripts/verify-site-deploy.py` over public HTTPS: the signature verifies with
  `allowed_signers` from this repository (not from the site), a copy with one bit flipped does not,
  every SHA-256 matches the published `SHA256SUMS.txt`, and every download is served at the size
  the manifest gives; after a deploy that left the manifest as it was, `--kept-update-manifest`
  lets it differ from the checkout's copy. `scripts/verify-desktop-signatures.py` downloads
  everything and compares the manifest with the bytes themselves.
- On every pull request: `scripts/check-desktop-update-key.py` (the key in the app is
  `KHANDAQ-RELEASE-SIGNING.pub`, the app and the scripts agree on names, schema, namespace and
  files, every release build script has `UPDATE_CHECK=ON` and `UPDATE_CHECK_QA=OFF`), the generator's
  `--self-test`, and case 7 of `scripts/check-signing-refuses-untrusted.py` (the namespaces do not
  cross).

By hand:

```
ssh-keygen -Y verify -f allowed_signers -I releases@khandaq.org -n khandaq-desktop-update \
    -s khandaq-desktop-update.json.sig < khandaq-desktop-update.json
```

**Rotating the key.** The app trusts exactly the key in `releaseKeyBlob`
(`khandaq-desktop/src/net/updatemanifest.cpp`). An app that does not know the new key refuses every
manifest signed with it and stops updating itself without a word. So, in this order:

1. Ship a release, signed with the old key, whose app trusts the old and the new key (a second key
   beside `releaseKeyBlob`, returned by `UpdateEndpoint::trustedKeys()`; teach
   `check-desktop-update-key.py` the pair).
2. Wait until that release has reached the people who update.
3. Switch the signing to the new key (section 1) and publish the new public half.
4. In a later release, drop the old key from the app.

Apps older than step 1 need one download by hand. If the old key leaked, skip step 2 and say so on
the site: whoever holds a leaked key still needs to serve files from khandaq.org (or to forge its TLS
certificate), because the app takes downloads only from beside the manifest.

**Testing the update from end to end (QA builds only, never a release).** Configure with
`-DUPDATE_CHECK_QA=ON -DUPDATE_CHECK_QA_KEY=<a throwaway key's .pub>`. That build reads
`KHANDAQ_UPDATE_MANIFEST_URL` (https, or http only to `127.0.0.1`) and trusts the throwaway key as
well as the release key; a build without the option ignores the variable and does not even contain
its name, and the generator refuses any program that does.

```
ssh-keygen -t ed25519 -N "" -f /tmp/qa/key            # throwaway, never the release key
# /tmp/qa/site/: the download to offer, and a manifest written for it (its size and
# `shasum -a 256`, a version above the build's own, and a serial above any this copy has
# verified, such as `date -u +%s`)
ssh-keygen -Y sign -f /tmp/qa/key -n khandaq-desktop-update /tmp/qa/site/khandaq-desktop-update.json
python3 -m http.server 8123 --bind 127.0.0.1 --directory /tmp/qa/site
KHANDAQ_UPDATE_MANIFEST_URL=http://127.0.0.1:8123/khandaq-desktop-update.json ./khandaq
```

Then Settings, About, Check now. Run a copy of the app, with a profile and a home of its own: on
macOS and in a portable Windows folder the swap replaces the copy that is running. On macOS the
app's folders follow `CFFIXED_USER_HOME`, not `HOME`, so set both; a different `USER` keeps the copy
from handing itself over to a Khandaq that is already running (the single-instance check is per
`USER`), and after the swap `open` starts the new bundle outside that environment.
