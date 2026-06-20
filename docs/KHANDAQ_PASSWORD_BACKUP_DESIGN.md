# Khandaq — Password-protected backup & restore (#28)

Status: **design** (no code yet). Target: Android first (iOS uses isolated profile
folders and has a separate path). Author handoff for implementation.

## 1. Problem

Today's "import" (`ToxProfileImportHelper` / `import_toxsave_file_unsecure`) restores
**only the Tox identity** (`savedata.tox`): contacts come back (re-added from toxcore),
but **message history, avatars, file metadata and the encrypted VFS media are lost**.

Root cause: the app DB (`dbs/<MAIN_DB>`, SQLCipher) and the media VFS
(`vfs/files.db`, IOCipher) are encrypted with `PREF__DB_secrect_key`, and that key is
**device-bound** — it is wrapped in the Android Keystore and in a PBKDF2-over-`ANDROID_ID`
device backup (`DbSecretKeyStorage`). On reinstall the Keystore is cleared; on a new
device `ANDROID_ID` differs. Either way the key is gone, so even if we copied the DB/VFS
they could never be opened.

To restore history across reinstall / devices we need the **key to travel with the
backup**, protected by something the user knows: a passphrase.

## 2. Key insight — wrap the key, don't re-encrypt the data

`vfs.mount(dbFile, PREF__DB_secrect_key)` (MainActivity ~1525) and the SQLCipher DB use
the **same** `PREF__DB_secrect_key`. The on-disk `MAIN_DB` and `files.db` are therefore
**already AES-encrypted**. They are useless without the key.

So the backup only needs to put **two small secrets under the passphrase**:

1. `PREF__DB_secrect_key` (the SQLCipher/IOCipher passphrase string), and
2. `savedata.tox` (the Tox identity secret key).

The large already-encrypted `MAIN_DB` + `files.db` blobs are appended **raw**. An attacker
holding the backup file but not the passphrase gets neither the DB key (→ can't decrypt
DB/VFS) nor the identity (→ can't impersonate). This avoids re-encrypting hundreds of MB
of media on every backup.

## 3. Container format — `*.kbk` (Khandaq BacKup)

Single self-describing file, written/read as a stream:

```
offset  field
0       magic            = "KHQBK1\n"            (7 bytes)
7       kdf_id           u8   (1 = PBKDF2-HMAC-SHA256)
8       kdf_iters        u32  big-endian  (>= 210000)
12      salt_len         u16  (= 16)
14      salt             salt_len bytes        (CSPRNG)
..      nonce_len        u16  (= 12)
..      nonce            nonce_len bytes       (CSPRNG, AES-GCM IV)
..      header_ct_len    u32
..      header_ct        header_ct_len bytes   (AES-256-GCM ciphertext+tag of the manifest)
..      db_len           u64
..      db_bytes         db_len bytes          (raw SQLCipher MAIN_DB)
..      vfs_len          u64
..      vfs_bytes        vfs_len bytes         (raw IOCipher files.db)
```

`header_ct` decrypts to a UTF-8 JSON **manifest** (the only confidential metadata):

```json
{
  "v": 1,
  "created_at": 1750000000000,
  "app_version_code": 10305,
  "db_key": "<PREF__DB_secrect_key>",
  "tox_savedata_b64": "<base64 of savedata.tox>",
  "db_sha256": "<hex>",
  "vfs_sha256": "<hex>"
}
```

Crypto:

- **KDF**: `PBKDF2WithHmacSHA256(passphrase, salt, iters=210000, dkLen=32)` → 256-bit key.
  PBKDF2 is already used in the codebase (`DbSecretKeyStorage.deviceBackupKey`), so no new
  dependency. *Future hardening:* swap to Argon2id behind `kdf_id = 2`.
- **AEAD**: AES-256-GCM. The GCM tag authenticates the manifest; a wrong passphrase fails
  decryption → surfaced as "wrong password" (no oracle, constant work).
- **Big-blob integrity/authenticity**: `db_sha256` / `vfs_sha256` live *inside* the
  authenticated manifest. On restore we hash the raw blobs and compare → tamper-evident.
  (Confidentiality of the blobs already comes from SQLCipher/IOCipher + the passphrase-gated
  `db_key`.)
- AAD for GCM = the fixed header prefix (magic..nonce) so the KDF params can't be swapped.

## 4. Export flow

UI: new entry in `MaintenanceActivity` — "Backup with password (.kbk)" — next to the
existing import/export. Prompt passphrase **twice** + strength hint + an explicit
"if you lose this password the backup cannot be opened" warning.

Worker (background thread, **not** UI — mirror the #24 import fix):

1. Force WAL checkpoint so the single `MAIN_DB` / `files.db` files are self-contained:
   `PRAGMA wal_checkpoint(TRUNCATE)` on both SQLCipher DB and the IOCipher container
   (or include `-wal`/`-shm`; checkpoint is cleaner). Pause group/file background work first.
2. `export_savedata_file_unsecure("_", tmp.tox)` → read bytes (live identity).
3. `dbKey = DbSecretKeyStorage.resolveDbSecretKey(ctx)`.
4. Compute `db_sha256`, `vfs_sha256`; build manifest JSON.
5. `key = PBKDF2(pass, salt, iters)`; `header_ct = AES-GCM(manifest, key, nonce, aad=header)`.
6. Stream-write container to a **SAF** destination (`ACTION_CREATE_DOCUMENT`,
   suggested name `khandaq-backup-YYYYMMDD.kbk`), copying DB/VFS via 8 KB buffer with a
   progress dialog + cancel.

## 5. Restore flow

UI: "Restore from password backup" → SAF `OpenDocument` (.kbk) → prompt passphrase.

Worker (background, reuse the #24 import machinery):

1. Parse header; `key = PBKDF2(pass, salt, iters)`; decrypt manifest.
   - GCM auth failure → "Wrong password or corrupted backup" (abort, profile untouched).
2. Stream-verify `db_sha256` / `vfs_sha256` while copying blobs to a **staging** dir.
   Any mismatch → abort before touching the live profile (atomicity).
3. `global_stop_tox()` + wait (background, ~10 s cap — same pattern as #24).
4. Wipe DB tables (the `.execute()`'d deletes from the #24 fix).
5. Move staged `MAIN_DB` → `dbs/`, `files.db` → `vfs/`, write `savedata.tox` → `files/`.
6. **Install the restored key** so the app opens the restored DB/VFS with it:
   `DbSecretKeyStorage.persistRestoredDbSecretKey(ctx, manifest.db_key)` — a new helper that
   writes the key to last-working + re-wraps the device backup + Keystore for *this* device,
   so `resolveDbSecretKey` returns it on next launch. **This is the crux**: the DB/VFS were
   encrypted with the source device's key; the app must keep using that exact string.
7. Restart the app (existing `System.exit(0)` path). On next launch `resolveDbSecretKey`
   yields `manifest.db_key`, SQLCipher + IOCipher mount, history + media + avatars are back,
   and `load_and_add_all_friends` reconciles contacts from the restored `savedata.tox`.

## 6. Files to touch

- **New** `BackupHelper.java` — container read/write, PBKDF2 + AES-GCM, SHA-256, manifest.
- `MaintenanceActivity.java` — two menu entries + two SAF `ActivityResultLauncher`s + the
  passphrase dialogs; call into `BackupHelper`.
- `DbSecretKeyStorage.java` — add `persistRestoredDbSecretKey(context, key)` (writes
  last-working, re-wraps device backup + Keystore). Reuse existing private writers.
- `HelperGeneric.import_toxsave_file_unsecure` — refactor its background stop-tox + wipe +
  copy into a reusable `applyRestoredProfile(...)` so backup-restore and plain .tox import
  share one code path.
- `res/values*/strings.xml` — titles, warnings, progress, error strings (en + ru).
- Optional: a small `org.khandaq.messenger.backup` MIME / `.kbk` extension association.

## 7. Edge cases & risks

- **Lost passphrase = unrecoverable** (by design). UX must say so loudly; no recovery hint.
- **Schema drift**: a backup from an older app version restores an older DB → orma runs its
  normal migrations on first open. Store `app_version_code` in the manifest; refuse a backup
  whose schema is *newer* than the running app (downgrade is unsafe).
- **Atomicity**: fully decrypt + verify into staging *before* `global_stop_tox` / wipe. If any
  step fails, the live profile is left intact.
- **Size/time**: VFS can be hundreds of MB. Stream everything, show progress, allow cancel;
  never load blobs fully into memory.
- **WAL consistency**: must checkpoint (or include -wal/-shm) or the backup can miss the most
  recent rows.
- **Key confusion**: never let restore fall through to generating a *fresh* auto key — that
  would leave the restored DB unopenable. `persistRestoredDbSecretKey` must run before the
  first DB open, and `resolveDbSecretKey` must prefer it.
- **MIUI/Keystore**: the new device may again drop the Keystore on reboot — but the PBKDF2
  device backup (re-wrapped in step 6) covers that, same as the #12 fix.

## 8. Security properties

- Backup file at rest: confidential + authenticated under the passphrase (AES-256-GCM,
  PBKDF2-SHA256 210k). Identity and DB key never appear in plaintext.
- No passphrase oracle; wrong password = single GCM failure.
- Forward parity with the existing device-bound model — we only add a *portable* wrapping.

## 9. Phasing

1. `BackupHelper` + unit-style self-test (encrypt→decrypt round-trip, tamper detection).
2. Export path + UI + progress.
3. `persistRestoredDbSecretKey` + shared `applyRestoredProfile`.
4. Restore path + UI + atomic staging.
5. Live QA: backup on device A → restore on a *clean install* / device B → verify history,
   media, avatars, contacts. Test wrong password, truncated file, older-schema backup.
6. Ship in a versionCode bump; document in changelog under "important".
