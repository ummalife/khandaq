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
            final byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP);
            prefs.edit().putString("DB_secret_salt", saltB64).commit();
        }

        final byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
        final javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                password.toCharArray(), salt, 120_000, 256);
        final javax.crypto.SecretKeyFactory skf =
                javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        final byte[] derived = skf.generateSecret(spec).getEncoded();
        return TrifaSetPatternActivity.bytesToString(derived);
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
            repairAfterAppUpgrade(context, prefs);
        }

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
