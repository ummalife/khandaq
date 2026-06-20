package com.zoffcc.applications.trifa;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * KHANDAQ (#28): password-protected backup container (.kbk).
 *
 * <p>The on-disk SQLCipher DB and IOCipher VFS are already encrypted with the device-bound
 * {@code PREF__DB_secrect_key}. This container therefore only puts the small secrets — that key
 * plus the Tox identity ({@code savedata.tox}) — under a user passphrase (PBKDF2-HMAC-SHA256 +
 * AES-256-GCM) and appends the already-encrypted DB/VFS blobs raw, so backups don't re-encrypt
 * hundreds of MB of media. Blob integrity is covered by SHA-256 digests stored inside the
 * authenticated header.
 *
 * <p>The core is deliberately free of Android APIs (no {@code android.util.*}, no JSON lib) so it
 * can run as a plain-JVM self-test. The Android UI layer wraps these calls.
 */
public final class BackupHelper
{
    /** "KHQBK1\n" */
    static final byte[] MAGIC = {'K', 'H', 'Q', 'B', 'K', '1', '\n'};
    static final int KDF_PBKDF2_HMAC_SHA256 = 1;
    static final int PBKDF2_ITERS = 210_000;
    static final int SALT_LEN = 16;
    static final int NONCE_LEN = 12;
    static final int KEY_LEN = 32;        // AES-256
    static final int GCM_TAG_BITS = 128;
    static final int MANIFEST_VERSION = 1;
    private static final int SHA256_LEN = 32;

    private BackupHelper()
    {
    }

    /** Thrown for any malformed container, wrong passphrase, or integrity failure. */
    static final class BackupException extends IOException
    {
        BackupException(String message)
        {
            super(message);
        }
    }

    /** Optional progress sink for the large DB/VFS copy. */
    interface ProgressListener
    {
        /** @return false to request cancellation. */
        boolean onProgress(long bytesDone, long bytesTotal);
    }

    /** Decrypted, authenticated metadata. {@code dbLen}/{@code vfsLen} are filled by {@link #readHeader}. */
    static final class Manifest
    {
        int version;
        long createdAtMs;
        int appVersionCode;
        String dbKey;
        byte[] toxSavedata;
        byte[] dbSha256;
        byte[] vfsSha256;
        long dbLen = -1;
        long vfsLen = -1;
    }

    // ---------------------------------------------------------------------------------------------
    // Crypto primitives
    // ---------------------------------------------------------------------------------------------

    static byte[] deriveKey(char[] passphrase, byte[] salt, int iters) throws BackupException
    {
        try
        {
            final PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iters, KEY_LEN * 8);
            final SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            final byte[] key = skf.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return key;
        }
        catch (Exception e)
        {
            throw new BackupException("key derivation failed: " + e.getMessage());
        }
    }

    private static byte[] sha256(File f) throws IOException
    {
        final MessageDigest md = newSha256();
        final byte[] buf = new byte[8192];
        try (InputStream in = new FileInputStream(f))
        {
            int n;
            while ((n = in.read(buf)) > 0)
            {
                md.update(buf, 0, n);
            }
        }
        return md.digest();
    }

    private static MessageDigest newSha256() throws IOException
    {
        try
        {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (Exception e)
        {
            throw new BackupException("SHA-256 unavailable: " + e.getMessage());
        }
    }

    private static Cipher gcm(int mode, byte[] key, byte[] nonce, byte[] aad) throws BackupException
    {
        try
        {
            final Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            if (aad != null)
            {
                c.updateAAD(aad);
            }
            return c;
        }
        catch (Exception e)
        {
            throw new BackupException("cipher init failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Encrypted manifest payload (binary, length-prefixed — no JSON, binary-safe)
    // ---------------------------------------------------------------------------------------------

    private static byte[] encodeManifestPayload(Manifest m) throws IOException
    {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final DataOutputStream d = new DataOutputStream(bos);
        d.writeShort(m.version);
        d.writeLong(m.createdAtMs);
        d.writeInt(m.appVersionCode);
        final byte[] keyBytes = m.dbKey.getBytes(StandardCharsets.UTF_8);
        d.writeShort(keyBytes.length);
        d.write(keyBytes);
        d.writeInt(m.toxSavedata.length);
        d.write(m.toxSavedata);
        d.write(m.dbSha256);
        d.write(m.vfsSha256);
        d.flush();
        return bos.toByteArray();
    }

    private static Manifest decodeManifestPayload(byte[] payload) throws IOException
    {
        final DataInputStream d = new DataInputStream(new ByteArrayInputStream(payload));
        final Manifest m = new Manifest();
        m.version = d.readUnsignedShort();
        m.createdAtMs = d.readLong();
        m.appVersionCode = d.readInt();
        final int keyLen = d.readUnsignedShort();
        final byte[] keyBytes = new byte[keyLen];
        d.readFully(keyBytes);
        m.dbKey = new String(keyBytes, StandardCharsets.UTF_8);
        final int toxLen = d.readInt();
        if (toxLen < 0 || toxLen > (64 * 1024 * 1024))
        {
            throw new BackupException("implausible identity length");
        }
        m.toxSavedata = new byte[toxLen];
        d.readFully(m.toxSavedata);
        m.dbSha256 = new byte[SHA256_LEN];
        d.readFully(m.dbSha256);
        m.vfsSha256 = new byte[SHA256_LEN];
        d.readFully(m.vfsSha256);
        return m;
    }

    /** Header bytes used as AES-GCM AAD (everything before the header ciphertext length field). */
    private static byte[] aadFor(int iters, byte[] salt, byte[] nonce) throws IOException
    {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final DataOutputStream d = new DataOutputStream(bos);
        d.write(MAGIC);
        d.writeByte(KDF_PBKDF2_HMAC_SHA256);
        d.writeInt(iters);
        d.writeShort(salt.length);
        d.write(salt);
        d.writeShort(nonce.length);
        d.write(nonce);
        d.flush();
        return bos.toByteArray();
    }

    // ---------------------------------------------------------------------------------------------
    // Write
    // ---------------------------------------------------------------------------------------------

    /**
     * Stream a backup container to {@code out}. {@code dbFile}/{@code vfsFile} must be a quiescent,
     * checkpointed snapshot of the SQLCipher DB and IOCipher VFS.
     */
    static void writeBackup(OutputStream out, char[] passphrase, long createdAtMs, int appVersionCode,
                            String dbKey, byte[] toxSavedata, File dbFile, File vfsFile,
                            ProgressListener progress) throws IOException
    {
        final SecureRandom rng = new SecureRandom();
        final byte[] salt = new byte[SALT_LEN];
        final byte[] nonce = new byte[NONCE_LEN];
        rng.nextBytes(salt);
        rng.nextBytes(nonce);

        final Manifest m = new Manifest();
        m.version = MANIFEST_VERSION;
        m.createdAtMs = createdAtMs;
        m.appVersionCode = appVersionCode;
        m.dbKey = dbKey;
        m.toxSavedata = toxSavedata;
        m.dbSha256 = sha256(dbFile);   // pre-pass: hash so the digest is inside the authenticated header
        m.vfsSha256 = sha256(vfsFile);

        final byte[] aad = aadFor(PBKDF2_ITERS, salt, nonce);
        final byte[] key = deriveKey(passphrase, salt, PBKDF2_ITERS);
        final byte[] headerCt;
        try
        {
            headerCt = gcm(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(encodeManifestPayload(m));
        }
        catch (Exception e)
        {
            throw new BackupException("header encryption failed: " + e.getMessage());
        }
        finally
        {
            Arrays.fill(key, (byte) 0);
        }

        final long dbLen = dbFile.length();
        final long vfsLen = vfsFile.length();
        final DataOutputStream d = new DataOutputStream(out);
        d.write(aad);                  // magic..nonce
        d.writeInt(headerCt.length);
        d.write(headerCt);
        d.writeLong(dbLen);

        final long total = dbLen + vfsLen;
        final long[] done = {0};
        copyVerified(dbFile, d, total, done, progress);
        d.writeLong(vfsLen);
        copyVerified(vfsFile, d, total, done, progress);
        d.flush();
    }

    private static void copyVerified(File src, OutputStream out, long total, long[] done,
                                     ProgressListener progress) throws IOException
    {
        final byte[] buf = new byte[8192];
        try (InputStream in = new FileInputStream(src))
        {
            int n;
            while ((n = in.read(buf)) > 0)
            {
                out.write(buf, 0, n);
                done[0] += n;
                if (progress != null && !progress.onProgress(done[0], total))
                {
                    throw new BackupException("cancelled");
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Read
    // ---------------------------------------------------------------------------------------------

    /**
     * Read + authenticate the header, returning the decrypted {@link Manifest} (with {@code dbLen}
     * filled). The stream is left positioned at the start of the raw DB bytes; follow with
     * {@link #extractBlobs}.
     */
    static Manifest readHeader(InputStream in, char[] passphrase) throws IOException
    {
        final DataInputStream d = new DataInputStream(in);
        final byte[] magic = new byte[MAGIC.length];
        try
        {
            d.readFully(magic);
        }
        catch (EOFException e)
        {
            throw new BackupException("not a Khandaq backup (truncated)");
        }
        if (!Arrays.equals(magic, MAGIC))
        {
            throw new BackupException("not a Khandaq backup (bad magic)");
        }
        final int kdfId = d.readUnsignedByte();
        if (kdfId != KDF_PBKDF2_HMAC_SHA256)
        {
            throw new BackupException("unsupported KDF id " + kdfId);
        }
        final int iters = d.readInt();
        if (iters < 10_000 || iters > 10_000_000)
        {
            throw new BackupException("implausible KDF iterations");
        }
        final int saltLen = d.readUnsignedShort();
        final byte[] salt = readExact(d, saltLen, "salt");
        final int nonceLen = d.readUnsignedShort();
        final byte[] nonce = readExact(d, nonceLen, "nonce");
        final int headerCtLen = d.readInt();
        if (headerCtLen < 0 || headerCtLen > (128 * 1024 * 1024))
        {
            throw new BackupException("implausible header length");
        }
        final byte[] headerCt = readExact(d, headerCtLen, "header");

        final byte[] aad = aadFor(iters, salt, nonce);
        final byte[] key = deriveKey(passphrase, salt, iters);
        final byte[] payload;
        try
        {
            payload = gcm(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(headerCt);
        }
        catch (Exception e)
        {
            // AEADBadTagException lands here: wrong passphrase or tampered header
            throw new BackupException("wrong password or corrupted backup");
        }
        finally
        {
            Arrays.fill(key, (byte) 0);
        }

        final Manifest m = decodeManifestPayload(payload);
        if (m.version != MANIFEST_VERSION)
        {
            throw new BackupException("unsupported backup version " + m.version);
        }
        m.dbLen = d.readLong();
        if (m.dbLen < 0)
        {
            throw new BackupException("bad db length");
        }
        return m;
    }

    /**
     * Stream the raw DB then VFS blobs to the given files, verifying each against the manifest
     * digests. {@code in} must be positioned immediately after {@link #readHeader} (i.e. at the DB
     * bytes). Throws if a digest mismatches (tamper / truncation).
     */
    static void extractBlobs(InputStream in, Manifest m, File dbOut, File vfsOut,
                             ProgressListener progress) throws IOException
    {
        final DataInputStream d = new DataInputStream(in);
        final long[] done = {0};
        // dbLen already consumed by readHeader; vfsLen sits between the two blobs.
        final byte[] dbSha = copyOutVerified(d, m.dbLen, dbOut, done, m.dbLen, progress);
        if (!MessageDigest.isEqual(dbSha, m.dbSha256))
        {
            throw new BackupException("database integrity check failed");
        }
        m.vfsLen = d.readLong();
        if (m.vfsLen < 0)
        {
            throw new BackupException("bad vfs length");
        }
        final long total = m.dbLen + m.vfsLen;
        final byte[] vfsSha = copyOutVerified(d, m.vfsLen, vfsOut, done, total, progress);
        if (!MessageDigest.isEqual(vfsSha, m.vfsSha256))
        {
            throw new BackupException("media (VFS) integrity check failed");
        }
    }

    private static byte[] copyOutVerified(DataInputStream in, long len, File out, long[] done,
                                          long total, ProgressListener progress) throws IOException
    {
        final MessageDigest md = newSha256();
        final byte[] buf = new byte[8192];
        long remaining = len;
        try (OutputStream os = new FileOutputStream(out))
        {
            while (remaining > 0)
            {
                final int want = (int) Math.min(buf.length, remaining);
                final int n = in.read(buf, 0, want);
                if (n < 0)
                {
                    throw new BackupException("backup truncated");
                }
                os.write(buf, 0, n);
                md.update(buf, 0, n);
                remaining -= n;
                done[0] += n;
                if (progress != null && !progress.onProgress(done[0], total))
                {
                    throw new BackupException("cancelled");
                }
            }
        }
        return md.digest();
    }

    private static byte[] readExact(DataInputStream d, int len, String what) throws IOException
    {
        if (len < 0 || len > (1024 * 1024))
        {
            throw new BackupException("implausible " + what + " length");
        }
        final byte[] b = new byte[len];
        d.readFully(b);
        return b;
    }

    // ---------------------------------------------------------------------------------------------
    // Self-test (round-trip + tamper detection) — runnable on a plain JVM
    // ---------------------------------------------------------------------------------------------

    static boolean selfTest() throws IOException
    {
        final File dir = File.createTempFile("khqbk", "dir");
        dir.delete();
        dir.mkdirs();
        final File db = new File(dir, "db.bin");
        final File vfs = new File(dir, "vfs.bin");
        final File dbOut = new File(dir, "db.out");
        final File vfsOut = new File(dir, "vfs.out");
        try
        {
            final SecureRandom rng = new SecureRandom();
            final byte[] dbData = new byte[40_000];
            final byte[] vfsData = new byte[123_456];
            final byte[] tox = new byte[2048];
            rng.nextBytes(dbData);
            rng.nextBytes(vfsData);
            rng.nextBytes(tox);
            writeFile(db, dbData);
            writeFile(vfs, vfsData);

            final char[] pass = "correct horse battery staple".toCharArray();
            final String dbKey = "0123abcdEF_keyString_with_=symbols/and+stuff";

            final ByteArrayOutputStream container = new ByteArrayOutputStream();
            writeBackup(container, pass, 1_750_000_000_000L, 10305, dbKey, tox, db, vfs, null);
            final byte[] bytes = container.toByteArray();

            // 1) happy path
            Manifest m = readHeader(new ByteArrayInputStream(bytes), pass);
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            readHeader(in, pass); // re-read to advance the same stream to the blobs
            extractBlobs(in, m, dbOut, vfsOut, null);
            if (!m.dbKey.equals(dbKey)) { return fail("dbKey mismatch"); }
            if (!Arrays.equals(readFile(dbOut), dbData)) { return fail("db round-trip mismatch"); }
            if (!Arrays.equals(readFile(vfsOut), vfsData)) { return fail("vfs round-trip mismatch"); }
            if (!Arrays.equals(m.toxSavedata, tox)) { return fail("identity round-trip mismatch"); }

            // 2) wrong password → header auth fails
            if (!expectFail(bytes, "wrongpass".toCharArray())) { return fail("wrong password not rejected"); }

            // 3) tampered header byte (inside ciphertext region) → auth fails
            final byte[] th = bytes.clone();
            final int hdrIdx = MAGIC.length + 1 + 4 + 2 + SALT_LEN + 2 + NONCE_LEN + 4 + 2; // into header ct
            th[hdrIdx] ^= 0x01;
            if (!expectFail(th, pass)) { return fail("tampered header not rejected"); }

            // 4) tampered DB blob (last byte) → SHA mismatch on extract
            final byte[] tb = bytes.clone();
            tb[tb.length - 1] ^= 0x01;
            try
            {
                Manifest m2 = readHeader(new ByteArrayInputStream(tb), pass);
                ByteArrayInputStream in2 = new ByteArrayInputStream(tb);
                readHeader(in2, pass);
                extractBlobs(in2, m2, dbOut, vfsOut, null);
                return fail("tampered blob not rejected");
            }
            catch (BackupException expected)
            {
                // good
            }

            return true;
        }
        finally
        {
            db.delete();
            vfs.delete();
            dbOut.delete();
            vfsOut.delete();
            dir.delete();
        }
    }

    private static boolean expectFail(byte[] bytes, char[] pass)
    {
        try
        {
            readHeader(new ByteArrayInputStream(bytes), pass);
            return false;
        }
        catch (IOException expected)
        {
            return true;
        }
    }

    private static boolean fail(String msg)
    {
        System.err.println("BackupHelper.selfTest FAILED: " + msg);
        return false;
    }

    private static void writeFile(File f, byte[] data) throws IOException
    {
        try (OutputStream os = new FileOutputStream(f))
        {
            os.write(data);
        }
    }

    private static byte[] readFile(File f) throws IOException
    {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        try (InputStream in = new FileInputStream(f))
        {
            int n;
            while ((n = in.read(buf)) > 0)
            {
                bos.write(buf, 0, n);
            }
        }
        return bos.toByteArray();
    }

    /** Plain-JVM entry point: {@code java ... BackupHelper} → exit 0 on pass, 1 on fail. */
    public static void main(String[] args) throws IOException
    {
        final boolean ok = selfTest();
        System.out.println("BackupHelper.selfTest: " + (ok ? "PASS" : "FAIL"));
        System.exit(ok ? 0 : 1);
    }
}
