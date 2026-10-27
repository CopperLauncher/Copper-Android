package git.artdeell.mojo.utils;

import android.util.Base64;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public class HashUtils {

    public static final byte[] REQW_HASH = {0x51, 0x5d, 0x5f, 0x1c, 0x56, 0x5c, 0x53, 0x5f, 0x5d, 0x50, 0x5b, 0x5e, 0x57, 0x1c, 0x5f, 0x5d, 0x56, 0x5e, 0x4b, 0x5f, 0x5d, 0x56, 0x5f, 0x53, 0x5c, 0x53, 0x55, 0x57, 0x40};

    public static byte[] fileHash(MessageDigest messageDigest, File f) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(65535);
        try(FileInputStream fileInputStream = new FileInputStream(f);
            FileChannel channel = fileInputStream.getChannel()) {
            while(true) {
                buffer.rewind();
                if(channel.read(buffer) == -1) break;
                buffer.flip();
                messageDigest.update(buffer);
            }
        }
        return messageDigest.digest();
    }

    public static String longHash(String source) {
        try {
            byte[] stringBytes = source.getBytes(StandardCharsets.UTF_8);
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(stringBytes);
            byte[] hashBytes = digest.digest();
            return Base64.encodeToString(hashBytes, Base64.NO_PADDING | Base64.URL_SAFE | Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("WTF? SHA-1 digest missing!", e);
        }
    }

    public static boolean compareSHA1(File f, String sourceSHA) throws IOException{
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-1");
            byte[] wantedBytes = Hex.decodeHex(sourceSHA.toCharArray());
            byte[] localFileBytes = fileHash(messageDigest, f);
            return Arrays.equals(localFileBytes, wantedBytes);
        }catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("WTF? SHA-1 digest missing!", e);
        }catch (DecoderException e) {
            throw new IOException("Bad SHA-1 hash: "+sourceSHA+" for file "+f.getName());
        }
    }

    /** Computes the lowercase hex SHA-1 digest of a file. */
    public static String sha1Hex(File file) throws IOException {
        return hashHex(file, "SHA-1");
    }

    /** Computes the lowercase hex SHA-512 digest of a file. */
    public static String sha512Hex(File file) throws IOException {
        return hashHex(file, "SHA-512");
    }

    private static String hashHex(File file, String algorithm) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Hash algorithm not available: " + algorithm, e);
        }
        byte[] buffer = new byte[8192];
        try (InputStream inputStream = new FileInputStream(file)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return Hex.encodeHexString(digest.digest());
    }
}
