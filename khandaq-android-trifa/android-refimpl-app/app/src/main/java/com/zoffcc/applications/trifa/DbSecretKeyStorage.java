package com.zoffcc.applications.trifa;

import org.khandaq.messenger.BuildConfig;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.zoffcc.applications.sorm.OrmaDatabase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import static com.zoffcc.applications.trifa.MainActivity.MAIN_DB_NAME;
import static com.zoffcc.applications.trifa.TRIFAGlobals.PREF__DB_secrect_key__user_hash;

/**
 * Stores auto-generated DB keys in Android Keystore instead of plaintext prefs.
 */
public final class DbSecretKeyStorage
{
    private static final String TAG = "trifa.DbSecretKeyStorage";
    private static final String PREFS_AUTO_KEY = "DB_secrect_key";
    private static final String PREFS_MIGRATED = "DB_secrect_key_keystore_migrated";
    private static final String KEYSTORE_ALIAS = "khandaq_db_auto_key";
    private static final String PREFS_ENC_BLOB = "DB_secrect_key_enc";
    private static final String PREFS_ENC_IV = "DB_secrect_key_iv";
    private static final String PREFS_AUTO_KEY_LEGACY_BACKUP = "DB_secrect_key_legacy_backup";
    private static final String PREFS_LAST_WORKING_ENC = "DB_last_working_key_enc";
    private static final String PREFS_LAST_WORKING_IV = "DB_last_working_key_iv";
    private static final String PREFS_INSTALLED_VERSION_CODE = "khandaq_installed_version_code";
    private static final String KEYSTORE_LAST_WORKING_ALIAS = "khandaq_db_last_working_key";

    // KHANDAQ (security NEW-3): the recovery backup is no longer stored in plaintext. It is encrypted
    // with a DEVICE-BOUND key derived from ANDROID_ID (PBKDF2), which survives the TEE/Keystore being
    // invalidated on app upgrade — so DB-key recovery still works — yet a stolen prefs file alone (no
    // device / ANDROID_ID) no longer yields the key.
    private static final String PREFS_DEVICE_BACKUP_ENC = "DB_secrect_key_devbk_enc";
    private static final String PREFS_DEVICE_BACKUP_IV = "DB_secrect_key_devbk_iv";
    private static final byte[] DEVICE_BACKUP_SALT =
            "khandaq.db.devicebackup.v1".getBytes(StandardCharsets.UTF_8);

    private DbSecretKeyStorage()
    {
    }

    private static SecretKey deviceBackupKey(final Context context) throws Exception
    {
        String androidId = android.provider.Settings.Secure.getString(
                context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        if (androidId == null)
        {
            androidId = "";
        }
        final String material = androidId + ":" + context.getPackageName();
        final javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                material.toCharArray(), DEVICE_BACKUP_SALT, 50_000, 256);
        final javax.crypto.SecretKeyFactory skf =
                javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        final byte[] derived = skf.generateSecret(spec).getEncoded();
        return new javax.crypto.spec.SecretKeySpec(derived, "AES");
    }

    /** Store the recovery backup encrypted with the device-bound key, dropping any plaintext copy. */
    private static void storeDeviceBackup(final Context context, final String key)
    {
        if (context == null || TextUtils.isEmpty(key))
        {
            return;
        }
        try
        {
            final SecretKey k = deviceBackupKey(context);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, k);
            final byte[] iv = cipher.getIV();
            final byte[] enc = cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString(PREFS_DEVICE_BACKUP_ENC, Base64.encodeToString(enc, Base64.NO_WRAP))
                    .putString(PREFS_DEVICE_BACKUP_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .remove(PREFS_AUTO_KEY_LEGACY_BACKUP)   // never keep a plaintext copy
                    .commit();
        }
        catch (Exception e)
        {
            Log.w(TAG, "storeDeviceBackup failed: " + e.getMessage());
        }
    }

    /** Read the device-encrypted recovery backup; transparently migrates any old plaintext copy. */
    private static String readDeviceBackup(final Context context)
    {
        if (context == null)
        {
            return null;
        }
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // One-time migration: an older build stored the backup in plaintext — re-encrypt + wipe it.
        final String plain = prefs.getString(PREFS_AUTO_KEY_LEGACY_BACKUP, null);
        if (!TextUtils.isEmpty(plain))
        {
            storeDeviceBackup(context, plain);
        }

        try
        {
            final String enc = prefs.getString(PREFS_DEVICE_BACKUP_ENC, null);
            final String ivB64 = prefs.getString(PREFS_DEVICE_BACKUP_IV, null);
            if (enc == null || ivB64 == null)
            {
                return plain;
            }
            final SecretKey k = deviceBackupKey(context);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, k,
                    new GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            Log.w(TAG, "readDeviceBackup failed: " + e.getMessage());
            return plain;
        }
    }

    static void saveAutoGeneratedKey(final Context context, final String plaintextKey)
    {
        if (context == null || plaintextKey == null || plaintextKey.isEmpty())
        {
            return;
        }

        try
        {
            final SecretKey secretKey = getOrCreateKey();
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            final byte[] iv = cipher.getIV();
            final byte[] encrypted = cipher.doFinal(plaintextKey.getBytes(StandardCharsets.UTF_8));

            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            prefs.edit()
                    .putString(PREFS_ENC_BLOB, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(PREFS_ENC_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putBoolean(PREFS_MIGRATED, true)
                    .remove(PREFS_AUTO_KEY)
                    .commit();

            // Recovery backup for when the Keystore key is invalidated on upgrade — encrypted, not plaintext.
            storeDeviceBackup(context, plaintextKey);
        }
        catch (Exception e)
        {
            // Keystore unavailable on this device: keep only a device-encrypted backup, never plaintext.
            Log.w(TAG, "keystore save failed, using device-encrypted backup: " + e.getMessage());
            storeDeviceBackup(context, plaintextKey);
        }
    }

    static String loadAutoGeneratedKey(final Context context)
    {
        if (context == null)
        {
            return null;
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        migratePlaintextToKeystore(context, prefs);

        try
        {
            final String enc = prefs.getString(PREFS_ENC_BLOB, null);
            final String ivB64 = prefs.getString(PREFS_ENC_IV, null);
            if (enc == null || ivB64 == null)
            {
                return prefs.getString(PREFS_AUTO_KEY, null);
            }

            final SecretKey secretKey = getOrCreateKey();
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)));
            final byte[] plain = cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP));
            final String key = new String(plain, StandardCharsets.UTF_8);
            // KHANDAQ (#12): keep a keystore-INDEPENDENT recovery backup fresh on every successful load.
            // Some OEMs (notably MIUI/Xiaomi) drop AndroidKeyStore keys on reboot/OS update; without a
            // backup written on a normal launch (this path) such a device can no longer decrypt the
            // keystore blob and the user is permanently locked out at the password screen.
            // storeDeviceBackup() encrypts with a device-bound key (ANDROID_ID), never plaintext.
            // Write-once (only if missing) so we don't pay PBKDF2 on every launch.
            if (prefs.getString(PREFS_DEVICE_BACKUP_ENC, null) == null)
            {
                storeDeviceBackup(context, key);
            }
            return key;
        }
        catch (Exception e)
        {
            Log.w(TAG, "keystore load failed: " + e.getMessage());
            final String legacy = prefs.getString(PREFS_AUTO_KEY, null);
            if (!TextUtils.isEmpty(legacy))
            {
                return legacy;
            }
            return readDeviceBackup(context);
        }
    }

    private static void migratePlaintextToKeystore(final Context context, final SharedPreferences prefs)
    {
        if (prefs.getBoolean(PREFS_MIGRATED, false))
        {
            return;
        }

        final String legacy = prefs.getString(PREFS_AUTO_KEY, null);
        if (legacy != null && !legacy.isEmpty())
        {
            saveAutoGeneratedKey(context, legacy);
        }
    }

    static void clearAutoGeneratedKey(final Context context)
    {
        if (context == null)
        {
            return;
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .remove(PREFS_AUTO_KEY)
                .remove(PREFS_ENC_BLOB)
                .remove(PREFS_ENC_IV)
                .remove(PREFS_MIGRATED)
                .remove(PREFS_AUTO_KEY_LEGACY_BACKUP)
                .remove(PREFS_DEVICE_BACKUP_ENC)
                .remove(PREFS_DEVICE_BACKUP_IV)
                .commit();
    }

    static boolean hasAutoGeneratedKeyStored(final Context context)
    {
        return !TextUtils.isEmpty(loadAutoGeneratedKey(context));
    }

    /** True when the user chose a manual password instead of skip/auto-key mode. */
    static boolean prefersManualPasswordUnlock(final Context context)
    {
        if (context == null)
        {
            return false;
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.contains("DB_secret_salt"))
        {
            return true;
        }

        return prefs.getBoolean("PW_SET_SCREEN_DONE", false)
                && !hasAutoGeneratedKeyStored(context);
    }

    private static SecretKey getOrCreateKey() throws Exception
    {
        return getOrCreateKey(KEYSTORE_ALIAS);
    }

    private static SecretKey getOrCreateKey(final String alias) throws Exception
    {
        final KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);

        if (!ks.containsAlias(alias))
        {
            final KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            final KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build();
            kg.init(spec);
            kg.generateKey();
        }

        return (SecretKey) ks.getKey(alias, null);
    }

    /** PBKDF2 with per-device salt; legacy SHA-256 remains for migration. */
    static String deriveManualPasswordHash(final Context context, final String password) throws Exception
    {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String saltB64 = prefs.getString("DB_secret_salt", null);

        if (saltB64 == null)
        {
            saltB64 = generateSaltB64();
            prefs.edit().putString("DB_secret_salt", saltB64).commit();
        }

        return deriveManualPasswordHashWithSalt(password, saltB64);
    }

    /**
     * KHANDAQ (#10 change password): pure PBKDF2 derivation with an explicit salt and NO side
     * effects. deriveManualPasswordHash() persists a missing salt immediately, which flips
     * prefersManualPasswordUnlock() — deadly if a password change is aborted before the rekey.
     * The change-password flow derives with a candidate salt and only commits it on success.
     */
    static String deriveManualPasswordHashWithSalt(final String password, final String saltB64) throws Exception
    {
        final byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
        final javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                password.toCharArray(), salt, 120_000, 256);
        final javax.crypto.SecretKeyFactory skf =
                javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        final byte[] derived = skf.generateSecret(spec).getEncoded();
        return TrifaSetPatternActivity.bytesToString(derived);
    }

    static String generateSaltB64()
    {
        final byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.encodeToString(salt, Base64.NO_WRAP);
    }

    static void onApplicationStart(final Context context)
    {
        if (context == null)
        {
            return;
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final int previousVersion = prefs.getInt(PREFS_INSTALLED_VERSION_CODE, 0);
        final int currentVersion = BuildConfig.VERSION_CODE;

        if (previousVersion > 0 && currentVersion > previousVersion)
        {
            Log.i(TAG, "app upgrade detected: " + previousVersion + " -> " + currentVersion);
            try
            {
                repairAfterAppUpgrade(context, prefs);
            }
            catch (Throwable t)
            {
                // KHANDAQ (crash-on-launch guard): the upgrade repair is BEST-EFFORT. On some
                // devices the Android Keystore or the encrypted device-backup file can throw after
                // an OS/app update (invalidated/rotated key, KeyStoreException/ProviderException,
                // I/O). This runs from MainApplication.onCreate BEFORE the app-wide
                // UncaughtExceptionHandler is armed, so an unguarded throw here bounced the app
                // straight back to the launcher — the exact silent "splash -> won't open after
                // update" symptom. Swallow it: the real DB secret key still resolves later through
                // the normal unlock/heal path, so losing this one repair pass is non-fatal.
                Log.i(TAG, "repairAfterAppUpgrade failed (non-fatal): " + t.getMessage());
            }
        }

        // Always advance the stored version code — even if the repair above threw — so the repair
        // is attempted at most once per upgrade and can never wedge the app into a boot loop.
        if (previousVersion != currentVersion)
        {
            prefs.edit().putInt(PREFS_INSTALLED_VERSION_CODE, currentVersion).commit();
        }
    }

    private static void repairAfterAppUpgrade(final Context context, final SharedPreferences prefs)
    {
        final String existingBackup = readDeviceBackup(context);
        final String autoKey = prefs.getString(PREFS_AUTO_KEY, null);
        if (TextUtils.isEmpty(existingBackup) && !TextUtils.isEmpty(autoKey))
        {
            storeDeviceBackup(context, autoKey);
        }

        final String loadedKey = loadAutoGeneratedKey(context);
        if (TextUtils.isEmpty(existingBackup) && !TextUtils.isEmpty(loadedKey))
        {
            storeDeviceBackup(context, loadedKey);
        }

        if (hasExistingUserDatabase(context))
        {
            final String resolved = resolveDbSecretKey(context);
            if (!TextUtils.isEmpty(resolved))
            {
                persistLastWorkingDbSecretKey(context, resolved);
                if (TextUtils.isEmpty(readDeviceBackup(context))
                        && !prefs.contains("DB_secret_salt"))
                {
                    storeDeviceBackup(context, resolved);
                }
            }
        }
    }

    static boolean hasExistingUserDatabase(final Context context)
    {
        if (context == null)
        {
            return false;
        }

        final String dbPath = context.getDir("dbs", Context.MODE_PRIVATE).getAbsolutePath()
                + "/" + MAIN_DB_NAME;
        final File dbFile = new File(dbPath);
        return dbFile.isFile() && dbFile.length() > 512;
    }

    /**
     * KHANDAQ (Профиль → Детали → Удалить профиль): erase the encrypted user DB and ALL key
     * material. MUST run in a fresh process, before the DB/VFS is opened — see
     * {@link StartMainActivityWrapper} which calls this when the pending-wipe flag is set.
     */
    static void wipeUserProfile(final Context context)
    {
        if (context == null)
        {
            return;
        }
        // 1) delete the encrypted DB directory (main.db, files.db, -wal, -journal, ...)
        try
        {
            deleteRecursive(context.getDir("dbs", Context.MODE_PRIVATE));
        }
        catch (Exception ignored)
        {
        }
        // 2) clear all stored key material + the onboarding gate from default prefs
        try
        {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .remove(PREFS_AUTO_KEY)
                    .remove(PREFS_MIGRATED)
                    .remove(PREFS_ENC_BLOB)
                    .remove(PREFS_ENC_IV)
                    .remove(PREFS_AUTO_KEY_LEGACY_BACKUP)
                    .remove(PREFS_LAST_WORKING_ENC)
                    .remove(PREFS_LAST_WORKING_IV)
                    .remove(PREFS_DEVICE_BACKUP_ENC)
                    .remove(PREFS_DEVICE_BACKUP_IV)
                    .remove(PREFS_SESSION_LOGGED_IN)
                    .putBoolean("PW_SET_SCREEN_DONE", false)
                    .commit();
        }
        catch (Exception ignored)
        {
        }
        // 3) drop the Android Keystore aliases used to wrap the DB key
        try
        {
            final KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(KEYSTORE_ALIAS))
            {
                ks.deleteEntry(KEYSTORE_ALIAS);
            }
            if (ks.containsAlias(KEYSTORE_LAST_WORKING_ALIAS))
            {
                ks.deleteEntry(KEYSTORE_LAST_WORKING_ALIAS);
            }
        }
        catch (Exception ignored)
        {
        }
        // 4) reset the in-memory password hash global
        try
        {
            PREF__DB_secrect_key__user_hash = "";
        }
        catch (Exception ignored)
        {
        }
    }

    private static void deleteRecursive(final File f)
    {
        if (f == null || !f.exists())
        {
            return;
        }
        if (f.isDirectory())
        {
            final File[] kids = f.listFiles();
            if (kids != null)
            {
                for (final File k : kids)
                {
                    deleteRecursive(k);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static void persistLastWorkingDbSecretKey(final Context context, final String dbSecretKey)
    {
        if (context == null || TextUtils.isEmpty(dbSecretKey))
        {
            return;
        }

        try
        {
            final SecretKey secretKey = getOrCreateKey(KEYSTORE_LAST_WORKING_ALIAS);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            final byte[] iv = cipher.getIV();
            final byte[] encrypted = cipher.doFinal(dbSecretKey.getBytes(StandardCharsets.UTF_8));

            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString(PREFS_LAST_WORKING_ENC, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(PREFS_LAST_WORKING_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .commit();
        }
        catch (Exception e)
        {
            Log.w(TAG, "persistLastWorkingDbSecretKey failed: " + e.getMessage());
        }
    }

    /**
     * KHANDAQ (#28): after restoring a password backup, make the backup's DB key the one the app
     * uses going forward. Writes it to every store {@link #resolveDbSecretKey} consults — the
     * primary Keystore blob + ANDROID_ID device backup ({@link #saveAutoGeneratedKey}) and the
     * last-working slot — re-wrapped for THIS device. Must run BEFORE the restored SQLCipher DB /
     * IOCipher VFS are first opened, otherwise a fresh auto key would be generated and the restored
     * data could never be decrypted. The candidate probe in resolveDbSecretKey tolerates a stale
     * user-password hash (it simply fails to open the restored DB and falls through to this key).
     */
    static void persistRestoredDbSecretKey(final Context context, final String dbSecretKey)
    {
        if (context == null || TextUtils.isEmpty(dbSecretKey))
        {
            return;
        }
        saveAutoGeneratedKey(context, dbSecretKey);
        persistLastWorkingDbSecretKey(context, dbSecretKey);
        MainActivity.PREF__DB_secrect_key = dbSecretKey;
    }

    // ---------------------------------------------------------------------------------------------
    // KHANDAQ (#10 change password): staging slot for a pending DB rekey. The old + new passphrases
    // (and the salt that must be committed as DB_secret_salt on success) survive the process restart
    // Keystore-wrapped, never in plaintext, and are wiped as soon as the rekey finishes or aborts.
    // ---------------------------------------------------------------------------------------------
    private static final String KEYSTORE_REKEY_ALIAS = "khandaq_db_rekey_staging";
    private static final String PREFS_REKEY_OLD_ENC = "DB_rekey_old_enc";
    private static final String PREFS_REKEY_OLD_IV = "DB_rekey_old_iv";
    private static final String PREFS_REKEY_NEW_ENC = "DB_rekey_new_enc";
    private static final String PREFS_REKEY_NEW_IV = "DB_rekey_new_iv";
    private static final String PREFS_REKEY_SALT = "DB_rekey_staged_salt";
    // the salt that was live BEFORE staging (plaintext — a salt is not a secret; it must survive a
    // Keystore loss so an aborted rekey can restore the original unlock state exactly)
    private static final String PREFS_REKEY_PREV_SALT = "DB_rekey_prev_salt";
    private static final String REKEY_NO_PREV_SALT = "__none__";
    static final String PREFS_REKEY_PENDING = "DB_rekey_pending";
    static final String PREFS_REKEY_ABORTED = "DB_rekey_aborted";

    // ---- persisted login session (tester report: logout must stick across app close; and the app
    // must NOT re-ask the profile password after the OS kills the idle process — the app-lock PIN is
    // the idle gate, the password gates only an explicit logout) --------------------------------
    static final String PREFS_SESSION_LOGGED_IN = "DB_session_logged_in";

    /** Default TRUE: existing installs never wrote the flag; only an explicit logout clears it. */
    static boolean isSessionLoggedIn(final Context context)
    {
        if (context == null)
        {
            return true;
        }
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREFS_SESSION_LOGGED_IN, true);
    }

    static void setSessionLoggedIn(final Context context, final boolean loggedIn)
    {
        if (context == null)
        {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(PREFS_SESSION_LOGGED_IN, loggedIn).commit();
    }

    // ---- KHANDAQ (rekey): file-level GCM staging for the tox savedata snapshot ----------------
    // (same Keystore staging key as the rekey passphrases; IV length-prefixed in the file)

    static boolean encryptFileWithRekeyStagingKey(final Context ctx, final java.io.File in,
                                                  final java.io.File out)
    {
        try
        {
            final byte[] plain = readAllFileBytes(in);
            final Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, getOrCreateKey(KEYSTORE_REKEY_ALIAS));
            final byte[] iv = c.getIV();
            final byte[] enc = c.doFinal(plain);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out))
            {
                fos.write(iv.length);
                fos.write(iv);
                fos.write(enc);
                fos.getFD().sync();
            }
            return out.length() > 0;
        }
        catch (Exception e)
        {
            Log.w(TAG, "encryptFileWithRekeyStagingKey failed: " + e.getMessage());
            return false;
        }
    }

    static boolean decryptFileWithRekeyStagingKey(final Context ctx, final java.io.File in,
                                                  final java.io.File out)
    {
        try
        {
            final byte[] all = readAllFileBytes(in);
            final int ivLen = all[0] & 0xff;
            final Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, getOrCreateKey(KEYSTORE_REKEY_ALIAS),
                   new GCMParameterSpec(128, java.util.Arrays.copyOfRange(all, 1, 1 + ivLen)));
            final byte[] plain = c.doFinal(java.util.Arrays.copyOfRange(all, 1 + ivLen, all.length));
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out))
            {
                fos.write(plain);
                fos.getFD().sync();
            }
            return true;
        }
        catch (Exception e)
        {
            Log.w(TAG, "decryptFileWithRekeyStagingKey failed: " + e.getMessage());
            return false;
        }
    }

    private static byte[] readAllFileBytes(final java.io.File f) throws Exception
    {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f))
        {
            final byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length)
            {
                final int n = in.read(buf, off, buf.length - off);
                if (n < 0)
                {
                    throw new java.io.EOFException();
                }
                off += n;
            }
            return buf;
        }
    }

    static boolean stagePendingRekey(final Context context, final String oldKey, final String newKey,
                                     final String saltB64)
    {
        if (context == null || TextUtils.isEmpty(oldKey) || TextUtils.isEmpty(newKey)
            || TextUtils.isEmpty(saltB64))
        {
            return false;
        }
        try
        {
            final SecretKey secretKey = getOrCreateKey(KEYSTORE_REKEY_ALIAS);

            final Cipher c1 = Cipher.getInstance("AES/GCM/NoPadding");
            c1.init(Cipher.ENCRYPT_MODE, secretKey);
            final byte[] iv1 = c1.getIV();
            final byte[] enc1 = c1.doFinal(oldKey.getBytes(StandardCharsets.UTF_8));

            final Cipher c2 = Cipher.getInstance("AES/GCM/NoPadding");
            c2.init(Cipher.ENCRYPT_MODE, secretKey);
            final byte[] iv2 = c2.getIV();
            final byte[] enc2 = c2.doFinal(newKey.getBytes(StandardCharsets.UTF_8));

            final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            final String prevSalt = sp.getString("DB_secret_salt", REKEY_NO_PREV_SALT);
            sp.edit()
                    .putString(PREFS_REKEY_OLD_ENC, Base64.encodeToString(enc1, Base64.NO_WRAP))
                    .putString(PREFS_REKEY_OLD_IV, Base64.encodeToString(iv1, Base64.NO_WRAP))
                    .putString(PREFS_REKEY_NEW_ENC, Base64.encodeToString(enc2, Base64.NO_WRAP))
                    .putString(PREFS_REKEY_NEW_IV, Base64.encodeToString(iv2, Base64.NO_WRAP))
                    .putString(PREFS_REKEY_SALT, saltB64)
                    .putString(PREFS_REKEY_PREV_SALT, prevSalt)
                    .putBoolean(PREFS_REKEY_PENDING, true)
                    .commit();
            return true;
        }
        catch (Exception e)
        {
            Log.w(TAG, "stagePendingRekey failed: " + e.getMessage());
            return false;
        }
    }

    /** @return {oldKey, newKey, saltB64} or null when nothing (valid) is staged. */
    static String[] loadPendingRekey(final Context context)
    {
        if (context == null)
        {
            return null;
        }
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean(PREFS_REKEY_PENDING, false))
        {
            return null;
        }
        try
        {
            final String oldEnc = prefs.getString(PREFS_REKEY_OLD_ENC, null);
            final String oldIv = prefs.getString(PREFS_REKEY_OLD_IV, null);
            final String newEnc = prefs.getString(PREFS_REKEY_NEW_ENC, null);
            final String newIv = prefs.getString(PREFS_REKEY_NEW_IV, null);
            final String salt = prefs.getString(PREFS_REKEY_SALT, null);
            if (oldEnc == null || oldIv == null || newEnc == null || newIv == null || salt == null)
            {
                return null;
            }

            final SecretKey secretKey = getOrCreateKey(KEYSTORE_REKEY_ALIAS);

            final Cipher c1 = Cipher.getInstance("AES/GCM/NoPadding");
            c1.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(128, Base64.decode(oldIv, Base64.NO_WRAP)));
            final String oldKey = new String(c1.doFinal(Base64.decode(oldEnc, Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);

            final Cipher c2 = Cipher.getInstance("AES/GCM/NoPadding");
            c2.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(128, Base64.decode(newIv, Base64.NO_WRAP)));
            final String newKey = new String(c2.doFinal(Base64.decode(newEnc, Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);

            return new String[]{oldKey, newKey, salt};
        }
        catch (Exception e)
        {
            Log.w(TAG, "loadPendingRekey failed: " + e.getMessage());
            return null;
        }
    }

    static void clearPendingRekey(final Context context)
    {
        if (context == null)
        {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .remove(PREFS_REKEY_OLD_ENC)
                .remove(PREFS_REKEY_OLD_IV)
                .remove(PREFS_REKEY_NEW_ENC)
                .remove(PREFS_REKEY_NEW_IV)
                .remove(PREFS_REKEY_SALT)
                .remove(PREFS_REKEY_PREV_SALT)
                .remove(PREFS_REKEY_PENDING)
                .commit();
        try
        {
            final KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(KEYSTORE_REKEY_ALIAS);
        }
        catch (Exception ignored)
        {
        }
    }

    /**
     * KHANDAQ (#10): restore the pre-staging salt state exactly — used when an interrupted rekey is
     * rolled back after the Keystore staging became unreadable (the DBs are back on the old key, so
     * the unlock state must be the pre-change one too). The staged prev-salt survives Keystore loss
     * because a salt is not a secret and is stored plaintext.
     */
    static void restorePreRekeySalt(final Context context)
    {
        if (context == null)
        {
            return;
        }
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String prevSalt = prefs.getString(PREFS_REKEY_PREV_SALT, null);
        if (prevSalt == null)
        {
            return; // nothing staged — leave as-is
        }
        if (REKEY_NO_PREV_SALT.equals(prevSalt))
        {
            prefs.edit().remove("DB_secret_salt").commit();
        }
        else
        {
            prefs.edit().putString("DB_secret_salt", prevSalt).commit();
        }
    }

    static void markRekeyAborted(final Context context)
    {
        if (context == null)
        {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(PREFS_REKEY_ABORTED, true)
                .commit();
    }

    static String loadLastWorkingDbSecretKey(final Context context)
    {
        if (context == null)
        {
            return null;
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        try
        {
            final String enc = prefs.getString(PREFS_LAST_WORKING_ENC, null);
            final String ivB64 = prefs.getString(PREFS_LAST_WORKING_IV, null);
            if (enc == null || ivB64 == null)
            {
                return null;
            }

            final SecretKey secretKey = getOrCreateKey(KEYSTORE_LAST_WORKING_ALIAS);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)));
            final byte[] plain = cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP));
            return new String(plain, StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            Log.w(TAG, "loadLastWorkingDbSecretKey failed: " + e.getMessage());
            return null;
        }
    }

    static String resolveDbSecretKey(final Context context)
    {
        if (context == null)
        {
            return "";
        }

        final String dbPath = context.getDir("dbs", Context.MODE_PRIVATE).getAbsolutePath()
                + "/" + MAIN_DB_NAME;
        final File dbFile = new File(dbPath);
        final boolean existingDatabase = dbFile.isFile() && dbFile.length() > 512;

        final List<String> candidates = buildDbSecretKeyCandidates(context);
        if (candidates.isEmpty())
        {
            return "";
        }

        if (!existingDatabase)
        {
            return candidates.get(0);
        }

        for (final String candidate : candidates)
        {
            if (OrmaDatabase.probeEncryptedDatabase(dbPath, candidate))
            {
                Log.i(TAG, "resolveDbSecretKey: matched existing database");
                persistLastWorkingDbSecretKey(context, candidate);
                return candidate;
            }
        }

        Log.w(TAG, "resolveDbSecretKey: no candidate opened existing database");
        return "";
    }

    // KHANDAQ (#195): expose the ordered DB-key candidate list so tox savedata decryption can
    // try the same keys the DB probe does. savedata.tox may be encrypted under an older key
    // after a rekey/restore, and unlike the DB key it had no multi-candidate fallback.
    static List<String> candidateDbSecretKeys(final Context context)
    {
        return buildDbSecretKeyCandidates(context);
    }

    private static List<String> buildDbSecretKeyCandidates(final Context context)
    {
        final Set<String> ordered = new LinkedHashSet<>();

        if (!TextUtils.isEmpty(PREF__DB_secrect_key__user_hash))
        {
            ordered.add(PREF__DB_secrect_key__user_hash);
        }

        final String lastWorking = loadLastWorkingDbSecretKey(context);
        if (!TextUtils.isEmpty(lastWorking))
        {
            ordered.add(lastWorking);
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String deviceBackup = readDeviceBackup(context);
        if (!TextUtils.isEmpty(deviceBackup))
        {
            ordered.add(deviceBackup);
        }

        final String autoKey = loadAutoGeneratedKey(context);
        if (!TextUtils.isEmpty(autoKey))
        {
            ordered.add(autoKey);
        }

        final String legacyAuto = prefs.getString(PREFS_AUTO_KEY, null);
        if (!TextUtils.isEmpty(legacyAuto))
        {
            ordered.add(legacyAuto);
        }

        return new ArrayList<>(ordered);
    }

    static String unlockManualPasswordHash(final Context context, final String password)
    {
        if (context == null || TextUtils.isEmpty(password))
        {
            return null;
        }

        final List<String> candidates = buildManualPasswordHashCandidates(context, password);
        if (candidates.isEmpty())
        {
            return null;
        }

        final String dbPath = context.getDir("dbs", Context.MODE_PRIVATE).getAbsolutePath()
                + "/" + MAIN_DB_NAME;
        final File dbFile = new File(dbPath);
        final boolean existingDatabase = dbFile.isFile() && dbFile.length() > 512;

        if (existingDatabase)
        {
            for (final String candidate : candidates)
            {
                if (OrmaDatabase.probeEncryptedDatabase(dbPath, candidate))
                {
                    Log.i(TAG, "unlockManualPasswordHash: matched existing database");
                    return candidate;
                }
            }
            Log.w(TAG, "unlockManualPasswordHash: password did not open existing database");
            return null;
        }

        return candidates.get(0);
    }

    private static List<String> buildManualPasswordHashCandidates(final Context context, final String password)
    {
        final Set<String> ordered = new LinkedHashSet<>();

        try
        {
            ordered.add(TrifaSetPatternActivity.bytesToString(
                    TrifaSetPatternActivity.sha256(TrifaSetPatternActivity.StringToBytes2(password))));
        }
        catch (Exception e)
        {
            Log.w(TAG, "legacy password hash failed: " + e.getMessage());
        }

        try
        {
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            if (prefs.contains("DB_secret_salt"))
            {
                ordered.add(deriveManualPasswordHash(context, password));
            }
        }
        catch (Exception e)
        {
            Log.w(TAG, "PBKDF2 password hash failed: " + e.getMessage());
        }

        return new ArrayList<>(ordered);
    }

    /**
     * After reinstall Samsung/Google may restore prefs with a Keystore blob that cannot be
     * decrypted on this device. Skip-mode users then see a password screen they never had.
     */
    static boolean recoverInconsistentUnlockStateIfNeeded(final Context context)
    {
        if (context == null)
        {
            return false;
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean("PW_SET_SCREEN_DONE", false))
        {
            return false;
        }

        if (prefs.contains("DB_secret_salt"))
        {
            return false;
        }

        if (TextUtils.isEmpty(prefs.getString(PREFS_ENC_BLOB, null)))
        {
            return false;
        }

        if (!TextUtils.isEmpty(loadAutoGeneratedKey(context)))
        {
            return false;
        }

        if (!TextUtils.isEmpty(resolveDbSecretKey(context)))
        {
            Log.i(TAG, "recoverInconsistentUnlockState: resolved DB key from fallback candidates");
            return false;
        }

        Log.w(TAG, "recoverInconsistentUnlockState: orphaned keystore prefs after reinstall/restore");
        clearOrphanedKeystoreEntries(context, prefs);

        // Never send existing users back to first-run SetPassword — that can overwrite keys.
        if (hasExistingUserDatabase(context))
        {
            Log.w(TAG, "recoverInconsistentUnlockState: existing DB present, keeping PW_SET_SCREEN_DONE");
            return true;
        }

        prefs.edit().putBoolean("PW_SET_SCREEN_DONE", false).commit();
        return true;
    }

    private static void clearOrphanedKeystoreEntries(final Context context, final SharedPreferences prefs)
    {
        prefs.edit()
                .remove(PREFS_ENC_BLOB)
                .remove(PREFS_ENC_IV)
                .remove(PREFS_MIGRATED)
                .remove(PREFS_AUTO_KEY)
                .remove(PREFS_LAST_WORKING_ENC)
                .remove(PREFS_LAST_WORKING_IV)
                .remove(PREFS_AUTO_KEY_LEGACY_BACKUP)
                .remove(PREFS_DEVICE_BACKUP_ENC)
                .remove(PREFS_DEVICE_BACKUP_IV)
                .commit();
        clearAutoGeneratedKey(context);
    }
}
