package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.zoffcc.applications.sorm.OrmaDatabase;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * KHANDAQ (#10 change password): crash-safe re-encryption of the profile databases with a new
 * passphrase. The actual passwords never touch this class — only derived passphrases, staged
 * Keystore-wrapped by {@link DbSecretKeyStorage#stagePendingRekey}.
 *
 * Runs in a FRESH process from StartMainActivityWrapper, before any DB/VFS is opened (same pattern
 * as the profile wipe). The sequence is an idempotent state machine driven by which key currently
 * opens each file, so a crash at any point either leaves the originals untouched, is rolled back,
 * or is finished on the next launch:
 *
 *   0. bail out if the service/DB/VFS are live in this process (rekey waits for a cold start);
 *      if a base file is missing but its .oldk backup exists (crash mid-swap) → restore FIRST
 *   1. prepare: copy main.db (+wal/shm) -> main.db.rk, PRAGMA rekey the copy, verify with new key
 *   2. same for files.db — the IOCipher container is SQLCipher with cipher_page_size=8192, so it
 *      uses the manual-keying probe/rekey variants
 *   3. commit: rename original -> .oldk, copy -> original (old key fully intact in .oldk until
 *      the very end)
 *   4. verify both open with the new key; on any failure restore the .oldk originals
 *   5. finalize: persist the new salt as DB_secret_salt, drop auto-keys, update last-working,
 *      delete .oldk, clear staging
 *
 * Every abort path marks DB_rekey_aborted so the UI can tell the user the OLD password still
 * applies — a silent rollback would leave them believing a compromised password was retired.
 */
public final class DbRekeyHelper
{
    private static final String TAG = "trifa.DbRekeyHelper";

    /** libsqlfs (IOCipher) hardcodes this page size for the VFS container. */
    private static final int IOCIPHER_PAGE_SIZE = 8192;

    private DbRekeyHelper()
    {
    }

    static boolean hasPendingRekey(final Context context)
    {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(DbSecretKeyStorage.PREFS_REKEY_PENDING, false);
    }

    /**
     * KHANDAQ (rekey): savedata.tox is toxencryptsave-encrypted with sha256(OLD key) — the SQLCipher
     * rekey alone left it on the old key, so the restarted app failed to load the profile and parked
     * it as .broken (tester report). Snapshot the LIVE savedata (exported plaintext, immediately
     * Keystore-GCM-wrapped) while the old-key tox is still running, so the rekey process can move it
     * onto the new key. Call from ChangePasswordActivity right after stagePendingRekey.
     */
    static boolean stageSavedataSnapshot(final Context context)
    {
        final File savedata = new File(context.getFilesDir(), "savedata.tox");
        final File staged = sibling(savedata, ".rk");
        //noinspection ResultOfMethodCallIgnored
        staged.delete();
        if (!TrifaToxService.is_tox_started)
        {
            // no live tox to export from: only OK when there is no savedata needing re-encryption
            return !savedata.isFile();
        }
        final File plain = sibling(savedata, ".rkplain");
        try
        {
            MainActivity.semaphore_tox_savedata.acquire();
            try
            {
                MainActivity.export_savedata_file_unsecure("", plain.getAbsolutePath());
            }
            finally
            {
                MainActivity.semaphore_tox_savedata.release();
            }
            return plain.isFile() && plain.length() > 0
                   && DbSecretKeyStorage.encryptFileWithRekeyStagingKey(context, plain, staged);
        }
        catch (Exception e)
        {
            Log.w(TAG, "stageSavedataSnapshot failed: " + e.getMessage());
            return false;
        }
        finally
        {
            //noinspection ResultOfMethodCallIgnored
            plain.delete();
        }
    }

    /** toxencryptsave files start with the 8-byte magic "toxEsave". */
    private static boolean isToxEncryptedFile(final File f)
    {
        if (!f.isFile() || f.length() < 8)
        {
            return false;
        }
        try (FileInputStream in = new FileInputStream(f))
        {
            final byte[] magic = new byte[8];
            return in.read(magic) == 8
                   && "toxEsave".equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII));
        }
        catch (Exception e)
        {
            return true; // unreadable -> assume encrypted, fail safe
        }
    }

    /** True once, after an abort — the caller shows "password NOT changed" and the flag clears. */
    static boolean consumeRekeyAbortedFlag(final Context context)
    {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean(DbSecretKeyStorage.PREFS_REKEY_ABORTED, false))
        {
            return false;
        }
        prefs.edit().remove(DbSecretKeyStorage.PREFS_REKEY_ABORTED).commit();
        return true;
    }

    /** Call ONLY from a fresh process before the DB/VFS are opened. Safe to call repeatedly. */
    public static void performPendingRekeyIfNeeded(final Context context)
    {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean(DbSecretKeyStorage.PREFS_REKEY_PENDING, false))
        {
            return;
        }

        // ---- guard: never touch the files while this process has them open --------------------
        // (a warm process can reach the wrapper again via task recreation; defer to a cold start)
        try
        {
            if (TrifaToxService.vfs != null && TrifaToxService.vfs.isMounted())
            {
                Log.w(TAG, "pending rekey: VFS is mounted in this process — deferring to a cold start");
                return;
            }
        }
        catch (Exception ignored)
        {
        }
        try
        {
            if (TrifaToxService.orma != null)
            {
                Log.w(TAG, "pending rekey: DB is open in this process — deferring to a cold start");
                return;
            }
        }
        catch (Exception ignored)
        {
        }

        final File mainDb = new File(context.getDir("dbs", Context.MODE_PRIVATE), MainActivity.MAIN_DB_NAME);
        final File filesDb = new File(context.getDir("vfs", Context.MODE_PRIVATE), MainActivity.MAIN_VFS_NAME);

        // ---- recovery first: a crash mid-swap leaves base missing and .oldk present -------------
        // (must run BEFORE any probe — a probe on a missing path must never be trusted)
        recoverMissingBase(mainDb);
        recoverMissingBase(filesDb);

        // savedata.tox too: a crash between its two renames leaves only savedata.tox.oldk
        final File savedataRec = new File(context.getFilesDir(), "savedata.tox");
        final File savedataRecOldk = sibling(savedataRec, ".oldk");
        if (!savedataRec.isFile() && savedataRecOldk.isFile())
        {
            Log.w(TAG, "savedata.tox missing with .oldk present — restoring the original first");
            //noinspection ResultOfMethodCallIgnored
            savedataRecOldk.renameTo(savedataRec);
        }

        final String[] staged = DbSecretKeyStorage.loadPendingRekey(context);
        if (staged == null)
        {
            // staging unreadable (e.g. Keystore invalidated by a reboot/upgrade). The keys are gone,
            // so the ONLY safe outcome is the pre-change state: put the .oldk originals back (they
            // are on the old key), restore the pre-change salt, and report the abort.
            Log.w(TAG, "pending rekey flag set but staging unreadable — rolling back");
            restoreOriginals(mainDb, filesDb);
            DbSecretKeyStorage.restorePreRekeySalt(context);
            DbSecretKeyStorage.markRekeyAborted(context);
            DbSecretKeyStorage.clearPendingRekey(context);
            return;
        }

        final String oldKey = staged[0];
        final String newKey = staged[1];
        final String saltB64 = staged[2];

        // files.db counts as present when the original OR any of its rekey artifacts exist —
        // "missing" after recoverMissingBase really means the profile never had one.
        final boolean haveFilesDb = (filesDb.isFile() && filesDb.length() > 0)
                                    || sibling(filesDb, ".oldk").isFile()
                                    || sibling(filesDb, ".rk").isFile();

        Log.i(TAG, "pending rekey: starting (files.db present=" + haveFilesDb + ")");

        try
        {
            // ---- figure out the current state of each file --------------------------------------
            final boolean mainOnNew = OrmaDatabase.probeEncryptedDatabase(mainDb.getAbsolutePath(), newKey);
            final boolean mainOnOld = !mainOnNew
                    && OrmaDatabase.probeEncryptedDatabase(mainDb.getAbsolutePath(), oldKey);
            if (!mainOnNew && !mainOnOld)
            {
                Log.w(TAG, "main.db opens with neither key — aborting, restoring originals if any");
                abortAndRestore(context, mainDb, filesDb);
                return;
            }

            boolean filesOnNew = true;
            boolean filesOnOld = false;
            if (haveFilesDb)
            {
                filesOnNew = OrmaDatabase.probeEncryptedDatabaseWithPageSize(
                        filesDb.getAbsolutePath(), newKey, IOCIPHER_PAGE_SIZE);
                filesOnOld = !filesOnNew && OrmaDatabase.probeEncryptedDatabaseWithPageSize(
                        filesDb.getAbsolutePath(), oldKey, IOCIPHER_PAGE_SIZE);
                if (!filesOnNew && !filesOnOld)
                {
                    Log.w(TAG, "files.db opens with neither key — rekey not supported here, aborting");
                    abortAndRestore(context, mainDb, filesDb);
                    return;
                }
            }

            // ---- phase A: prepare re-encrypted copies (originals untouched) ----------------------
            if (mainOnOld && !prepareRekeyedCopy(mainDb, oldKey, newKey, false))
            {
                Log.w(TAG, "main.db copy rekey failed — aborting, profile untouched");
                abortAndRestore(context, mainDb, filesDb);
                return;
            }
            if (haveFilesDb && filesOnOld && !prepareRekeyedCopy(filesDb, oldKey, newKey, true))
            {
                Log.w(TAG, "files.db copy rekey failed — aborting, profile untouched");
                abortAndRestore(context, mainDb, filesDb);
                return;
            }

            // ---- phase B: commit (swap copies in; originals kept as .oldk until verified) --------
            if (mainOnOld && !swapIn(mainDb))
            {
                Log.w(TAG, "main.db swap failed — restoring originals");
                abortAndRestore(context, mainDb, filesDb);
                return;
            }
            if (haveFilesDb && filesOnOld && !swapIn(filesDb))
            {
                Log.w(TAG, "files.db swap failed — restoring originals");
                abortAndRestore(context, mainDb, filesDb);
                return;
            }

            // ---- verify ---------------------------------------------------------------------------
            final boolean mainOk = OrmaDatabase.probeEncryptedDatabase(mainDb.getAbsolutePath(), newKey);
            final boolean filesOk = !haveFilesDb
                    || OrmaDatabase.probeEncryptedDatabaseWithPageSize(
                            filesDb.getAbsolutePath(), newKey, IOCIPHER_PAGE_SIZE);
            if (!mainOk || !filesOk)
            {
                Log.w(TAG, "post-swap verify failed (main=" + mainOk + " files=" + filesOk
                           + ") — restoring originals");
                abortAndRestore(context, mainDb, filesDb);
                return;
            }

            // ---- savedata.tox must follow the DBs onto the new key --------------------------------
            // (toxencryptsave-encrypted with sha256(oldKey); the staged Keystore-wrapped snapshot is
            // swapped in as PLAINTEXT — create_tox accepts unencrypted savedata and the first
            // update_savedata_file after login re-encrypts it with sha256(newKey))
            final File savedata = new File(context.getFilesDir(), "savedata.tox");
            final File stagedSd = sibling(savedata, ".rk");
            final File sdOldk = sibling(savedata, ".oldk");
            if (stagedSd.isFile())
            {
                final File sdNew = sibling(savedata, ".new");
                //noinspection ResultOfMethodCallIgnored
                sdNew.delete();
                if (!DbSecretKeyStorage.decryptFileWithRekeyStagingKey(context, stagedSd, sdNew))
                {
                    Log.w(TAG, "staged savedata unreadable — aborting rekey");
                    abortAndRestore(context, mainDb, filesDb);
                    return;
                }
                //noinspection ResultOfMethodCallIgnored
                sdOldk.delete();
                if (savedata.isFile() && !savedata.renameTo(sdOldk))
                {
                    abortAndRestore(context, mainDb, filesDb);
                    return;
                }
                if (!sdNew.renameTo(savedata))
                {
                    //noinspection ResultOfMethodCallIgnored
                    sdOldk.renameTo(savedata);
                    abortAndRestore(context, mainDb, filesDb);
                    return;
                }
            }
            else if (isToxEncryptedFile(savedata))
            {
                // no snapshot but the profile is still on the old key — finalizing would strand it
                Log.w(TAG, "savedata.tox on the OLD key with no staged snapshot — aborting rekey");
                abortAndRestore(context, mainDb, filesDb);
                return;
            }

            // ---- finalize -------------------------------------------------------------------------
            // Persist the salt FIRST: from here on the new password must be able to re-derive newKey.
            prefs.edit()
                    .putString("DB_secret_salt", saltB64)
                    .putBoolean("PW_SET_SCREEN_DONE", true)
                    .commit();
            DbSecretKeyStorage.clearAutoGeneratedKey(context);
            DbSecretKeyStorage.persistLastWorkingDbSecretKey(context, newKey);
            TRIFAGlobals.PREF__DB_secrect_key__user_hash = "";
            deleteWithSidecars(sibling(mainDb, ".oldk"));
            deleteWithSidecars(sibling(filesDb, ".oldk"));
            cleanupCopies(mainDb, filesDb);
            //noinspection ResultOfMethodCallIgnored
            sdOldk.delete();
            //noinspection ResultOfMethodCallIgnored
            stagedSd.delete();
            DbSecretKeyStorage.clearPendingRekey(context);
            Log.i(TAG, "pending rekey: SUCCESS");
        }
        catch (Exception e)
        {
            Log.w(TAG, "pending rekey: unexpected error: " + e.getMessage());
            try
            {
                abortAndRestore(context, mainDb, filesDb);
            }
            catch (Exception ignored)
            {
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static void abortAndRestore(final Context context, final File mainDb, final File filesDb)
    {
        restoreOriginals(mainDb, filesDb);
        // savedata.tox: put the old-key original back if the swap was underway, drop artifacts
        try
        {
            final File savedata = new File(context.getFilesDir(), "savedata.tox");
            final File sdOldk = sibling(savedata, ".oldk");
            if (!savedata.isFile() && sdOldk.isFile())
            {
                //noinspection ResultOfMethodCallIgnored
                sdOldk.renameTo(savedata);
            }
            //noinspection ResultOfMethodCallIgnored
            sibling(savedata, ".rk").delete();
            //noinspection ResultOfMethodCallIgnored
            sibling(savedata, ".new").delete();
        }
        catch (Exception ignored)
        {
        }
        DbSecretKeyStorage.markRekeyAborted(context);
        DbSecretKeyStorage.clearPendingRekey(context);
    }

    private static File sibling(final File base, final String suffix)
    {
        return new File(base.getParentFile(), base.getName() + suffix);
    }

    /** A crash between the two renames of swapIn leaves base missing: put the backup back FIRST. */
    private static void recoverMissingBase(final File base)
    {
        final boolean baseMissing = !base.isFile() || base.length() == 0;
        final File backup = sibling(base, ".oldk");
        if (baseMissing && backup.isFile())
        {
            Log.w(TAG, base.getName() + " missing with .oldk present — restoring the original first");
            deleteWithSidecars(base); // clear a 0-byte husk + stale sidecars before the rename
            renameWithSidecars(backup, base);
        }
    }

    /** Copy base (+ -wal/-shm sidecars) to base.rk and PRAGMA-rekey the copy. Idempotent. */
    private static boolean prepareRekeyedCopy(final File base, final String oldKey, final String newKey,
                                              final boolean iocipherContainer)
    {
        final File copy = sibling(base, ".rk");

        // a previous run may have left a fully prepared copy — reuse it only if it is NOT older
        // than the original (a stale snapshot must never overwrite newer data)
        if (copy.isFile() && copy.lastModified() >= base.lastModified()
            && probeFor(copy, newKey, iocipherContainer))
        {
            return true;
        }
        deleteWithSidecars(copy);

        if (!copyFile(base, copy))
        {
            deleteWithSidecars(copy);
            return false;
        }
        // bring the WAL sidecars along so the copy contains every committed transaction
        final File wal = sibling(base, "-wal");
        final File shm = sibling(base, "-shm");
        if (wal.isFile() && !copyFile(wal, sibling(copy, "-wal")))
        {
            deleteWithSidecars(copy);
            return false;
        }
        if (shm.isFile() && !copyFile(shm, sibling(copy, "-shm")))
        {
            deleteWithSidecars(copy);
            return false;
        }

        final boolean rekeyed = iocipherContainer
                ? OrmaDatabase.rekeyEncryptedDatabaseWithPageSize(copy.getAbsolutePath(), oldKey, newKey,
                                                                  IOCIPHER_PAGE_SIZE)
                : OrmaDatabase.rekeyEncryptedDatabase(copy.getAbsolutePath(), oldKey, newKey);
        if (!rekeyed)
        {
            deleteWithSidecars(copy);
            return false;
        }
        return true;
    }

    private static boolean probeFor(final File f, final String key, final boolean iocipherContainer)
    {
        return iocipherContainer
                ? OrmaDatabase.probeEncryptedDatabaseWithPageSize(f.getAbsolutePath(), key, IOCIPHER_PAGE_SIZE)
                : OrmaDatabase.probeEncryptedDatabase(f.getAbsolutePath(), key);
    }

    /** original -> original.oldk (with sidecars), original.rk -> original. */
    private static boolean swapIn(final File base)
    {
        final File copy = sibling(base, ".rk");
        final File backup = sibling(base, ".oldk");
        if (!copy.isFile())
        {
            return false;
        }
        deleteWithSidecars(backup);
        if (!renameWithSidecars(base, backup))
        {
            return false;
        }
        if (!renameWithSidecars(copy, base))
        {
            // put the original back
            renameWithSidecars(backup, base);
            return false;
        }
        return true;
    }

    /** If .oldk backups exist, put them back as the originals (undo a partial commit). */
    private static void restoreOriginals(final File mainDb, final File filesDb)
    {
        for (final File base : new File[]{mainDb, filesDb})
        {
            final File backup = sibling(base, ".oldk");
            if (backup.isFile())
            {
                // rename(2) atomically replaces the destination — no delete-then-rename window.
                // drop the (invalid for the old file) sidecars of the current base first.
                //noinspection ResultOfMethodCallIgnored
                sibling(base, "-wal").delete();
                //noinspection ResultOfMethodCallIgnored
                sibling(base, "-shm").delete();
                if (backup.renameTo(base))
                {
                    final File bwal = sibling(backup, "-wal");
                    final File bshm = sibling(backup, "-shm");
                    if (bwal.isFile())
                    {
                        //noinspection ResultOfMethodCallIgnored
                        bwal.renameTo(sibling(base, "-wal"));
                    }
                    if (bshm.isFile())
                    {
                        //noinspection ResultOfMethodCallIgnored
                        bshm.renameTo(sibling(base, "-shm"));
                    }
                }
            }
        }
        cleanupCopies(mainDb, filesDb);
    }

    private static void cleanupCopies(final File mainDb, final File filesDb)
    {
        deleteWithSidecars(sibling(mainDb, ".rk"));
        deleteWithSidecars(sibling(filesDb, ".rk"));
    }

    private static boolean renameWithSidecars(final File from, final File to)
    {
        if (!from.renameTo(to))
        {
            return false;
        }
        final File fromWal = sibling(from, "-wal");
        final File fromShm = sibling(from, "-shm");
        if (fromWal.isFile())
        {
            //noinspection ResultOfMethodCallIgnored
            fromWal.renameTo(sibling(to, "-wal"));
        }
        if (fromShm.isFile())
        {
            //noinspection ResultOfMethodCallIgnored
            fromShm.renameTo(sibling(to, "-shm"));
        }
        return true;
    }

    private static void deleteWithSidecars(final File f)
    {
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        //noinspection ResultOfMethodCallIgnored
        sibling(f, "-wal").delete();
        //noinspection ResultOfMethodCallIgnored
        sibling(f, "-shm").delete();
    }

    private static boolean copyFile(final File src, final File dst)
    {
        FileInputStream in = null;
        FileOutputStream out = null;
        try
        {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            final byte[] buf = new byte[1024 * 256];
            int n;
            while ((n = in.read(buf)) > 0)
            {
                out.write(buf, 0, n);
            }
            out.getFD().sync();
            return true;
        }
        catch (Exception e)
        {
            Log.w(TAG, "copyFile failed: " + e.getMessage());
            return false;
        }
        finally
        {
            try
            {
                if (in != null)
                {
                    in.close();
                }
            }
            catch (Exception ignored)
            {
            }
            try
            {
                if (out != null)
                {
                    out.close();
                }
            }
            catch (Exception ignored)
            {
            }
        }
    }
}
