package net.kdt.pojavlaunch.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Small helper for computing hex-encoded file hashes.
 * <p/>
 * Used by the .mrpack exporter, which needs both a SHA1 (to match installed mods against
 * Modrinth's database) and a SHA512 (required by every file entry in modrinth.index.json)
 * for every mod jar being considered for export.
 */
public class HashUtils {

    /** Computes the lowercase hex-encoded SHA1 digest of a file. */
    public static String sha1Hex(File file) throws IOException {
        return hashHex(file, "SHA-1");
    }

    /** Computes the lowercase hex-encoded SHA512 digest of a file. */
    public static String sha512Hex(File file) throws IOException {
        return hashHex(file, "SHA-512");
    }

    private static String hashHex(File file, String algorithm) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            // Every Android version this launcher supports ships both SHA-1 and SHA-512
            // providers, so this is only a defensive fallback.
            throw new IOException("Hash algorithm not available: " + algorithm, e);
        }
        byte[] buffer = new byte[8192];
        try (InputStream inputStream = new FileInputStream(file)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hexBuilder = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hexBuilder.append(String.format("%02x", b));
        }
        return hexBuilder.toString();
    }
}