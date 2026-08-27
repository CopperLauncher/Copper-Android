package net.kdt.pojavlaunch.utils;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

import net.kdt.pojavlaunch.*;
import org.apache.commons.io.*;

@SuppressWarnings("IOStreamConstructor")
public class DownloadUtils {
    public static final String USER_AGENT = Tools.APP_NAME;
    private static final int TIME_OUT = 10000;

    public static void download(String url, OutputStream os) throws IOException {
        download(new URL(url), os);
    }

    public static void download(URL url, OutputStream os) throws IOException {
        InputStream is = null;
        try {
            // System.out.println("Connecting: " + url.toString());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(TIME_OUT);
            conn.setReadTimeout(TIME_OUT);
            conn.setDoInput(true);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + conn.getResponseCode()
                        + ": " + conn.getResponseMessage());
            }
            is = conn.getInputStream();
            IOUtils.copy(is, os);
        } catch (IOException e) {
            throw new IOException("Unable to download from " + url, e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static String downloadString(String url) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        download(url, bos);
        bos.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void downloadFile(String url, File out) throws IOException {
        FileUtils.ensureParentDirectory(out);
        try (FileOutputStream fileOutputStream = new FileOutputStream(out)) {
            download(url, fileOutputStream);
        } catch (IOException e) {
            if (out.length() < 1) { // Only delete it if file is 0 bytes cause this file might already be downloaded and something else went wrong.
                Log.i("DownloadUtils", "Cleaning up failed download: " + out.getAbsolutePath());
                out.delete();
                throw e;
            }
        }
    }

    public static void downloadFileMonitored(String urlInput, File outputFile, @Nullable byte[] buffer,
                                             Tools.DownloaderFeedback monitor) throws IOException {
        FileUtils.ensureParentDirectory(outputFile);

        HttpURLConnection conn = (HttpURLConnection) new URL(urlInput).openConnection();
        conn.setConnectTimeout(TIME_OUT);
        conn.setReadTimeout(TIME_OUT);
        InputStream readStr = conn.getInputStream();
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            int current;
            int overall = 0;
            int length = conn.getContentLength();

            if (buffer == null) buffer = new byte[65535];

            while ((current = readStr.read(buffer)) != -1) {
                overall += current;
                fos.write(buffer, 0, current);
                monitor.updateProgress(overall, length);
            }
            fos.flush();
            // The connection can be closed early by a flaky network, a proxy, or the server
            // itself. When that happens read() just returns -1 like a normal EOF, so without
            // this check a truncated file is silently treated as a successful download. That
            // truncated file later fails with a confusing "Unexpected end of ZLIB input stream"
            // when it's opened as a ZIP (e.g. a .mrpack), instead of being retried here.
            if (length > 0 && overall != length) {
                throw new IOException("Truncated download from " + urlInput + ": expected "
                        + length + " bytes but got " + overall);
            }
        } catch (IOException e) {
            throw new IOException("Unable to download from " + urlInput, e);
        } finally {
            readStr.close();
            conn.disconnect();
        }
    }

    public static <T> T downloadStringCached(String url, String cacheName, ParseCallback<T> parseCallback) throws IOException, ParseException{
        File cacheDestination = new File(Tools.DIR_CACHE, "string_cache/"+cacheName);
        if(cacheDestination.isFile() &&
                cacheDestination.canRead() &&
                System.currentTimeMillis() < (cacheDestination.lastModified() + 86400000)) {
            try {
                String cachedString = Tools.read(new FileInputStream(cacheDestination));
                return parseCallback.process(cachedString);
            }catch(IOException e) {
                Log.i("DownloadUtils", "Failed to read the cached file", e);
            }catch (ParseException e) {
                Log.i("DownloadUtils", "Failed to parse the cached file", e);
            }
        }
        String urlContent = DownloadUtils.downloadString(url);
        // if we download the file and fail parsing it, we will yeet outta there
        // and not cache the unparseable sting. We will return this after trying to save the downloaded
        // string into cache
        T parseResult = parseCallback.process(urlContent);

        boolean tryWriteCache;
        if(cacheDestination.exists()) {
            tryWriteCache = cacheDestination.canWrite();
        } else {
            tryWriteCache = FileUtils.ensureParentDirectorySilently(cacheDestination);
        }

        if(tryWriteCache) try {
            Tools.write(cacheDestination.getAbsolutePath(), urlContent);
        }catch(IOException e) {
            Log.i("DownloadUtils", "Failed to cache the string", e);
        }
        return parseResult;
    }

    private static <T> T downloadFile(Callable<T> downloadFunction) throws IOException{
        try {
            return downloadFunction.call();
        } catch (IOException e){
            throw e;
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean verifyFile(File file, String sha1) {
        return file.exists() && Tools.compareSHA1(file, sha1);
    }

    public static <T> T ensureSha1(File outputFile, @Nullable String sha1, Callable<T> downloadFunction) throws IOException {
        return ensureSha1(outputFile, sha1, downloadFunction, null);
    }

    /**
     * Same as {@link #ensureSha1(File, String, Callable)}, but also requires the downloaded
     * file to pass an extra structural check (e.g. {@link ZipUtils#verifyIntegrity(File)}) before
     * it's accepted. This matters for archives like .mrpack files: their central directory can
     * still parse successfully even when the download was truncated or corrupted, and a hash
     * match alone doesn't rule that out if the hash wasn't available in the first place, or if
     * the server-side copy of the file is itself already broken. Failing the check triggers the
     * same retry-up-to-5-times behavior as a SHA1 mismatch.
     * @param extraValidator additional check the downloaded file must pass, or null to skip it
     */
    public static <T> T ensureSha1(File outputFile, @Nullable String sha1, Callable<T> downloadFunction,
                                    @Nullable Predicate<File> extraValidator) throws IOException {
        // Skip if needed
        if(sha1 == null) {
            // If the file exists and we don't know it's SHA1, don't try to redownload it.
            if(outputFile.exists()) return null;
            // We have no hash to verify against, so at least retry on transient/truncated
            // download failures instead of handing a possibly-corrupt file to the caller.
            return retryDownload(outputFile, downloadFunction, null, extraValidator);
        }

        boolean fileOkay = verifyFile(outputFile, sha1) && (extraValidator == null || extraValidator.test(outputFile));
        if (fileOkay) return null;
        return retryDownload(outputFile, downloadFunction, sha1, extraValidator);
    }

    private static <T> T retryDownload(File outputFile, Callable<T> downloadFunction, @Nullable String sha1,
                                        @Nullable Predicate<File> extraValidator) throws IOException {
        int attempts = 0;
        T result = null;
        IOException lastException = null;
        boolean fileOkay = false;
        while (attempts < 5 && !fileOkay) {
            attempts++;
            // A previous failed/truncated attempt may have left a broken file behind;
            // don't let it be mistaken for a valid download on the next check.
            //noinspection ResultOfMethodCallIgnored
            outputFile.delete();
            try {
                result = downloadFile(downloadFunction);
                lastException = null;
            } catch (IOException e) {
                lastException = e;
                continue;
            }
            fileOkay = (sha1 == null) ? outputFile.exists() : verifyFile(outputFile, sha1);
            if (fileOkay && extraValidator != null) fileOkay = extraValidator.test(outputFile);
        }
        if (!fileOkay) {
            if (lastException != null) {
                throw new IOException("Download failed after " + attempts + " attempts", lastException);
            }
            throw new SHA1VerificationException("SHA1 verifcation failed after " + attempts + " download attempts");
        }
        return result;
    }

    /**
     * Get the content length for a given URL.
     * @param url the URL to get the length for
     * @return the length in bytes or -1 if not available
     * @throws IOException if an I/O error occurs.
     */
    public static long getContentLength(String url) throws IOException {
        HttpURLConnection urlConnection = (HttpURLConnection) new URL(url).openConnection();
        urlConnection.setRequestMethod("HEAD");
        urlConnection.setDoInput(false);
        urlConnection.setDoOutput(false);
        urlConnection.connect();
        int responseCode = urlConnection.getResponseCode();
        if(responseCode >= 200 && responseCode <= 299) return urlConnection.getContentLength();
        return -1;
    }

    public interface ParseCallback<T> {
        T process(String input) throws ParseException;
    }
    public static class ParseException extends Exception {
        public ParseException(Exception e) {
            super(e);
        }
    }

    public static class SHA1VerificationException extends IOException {
        public SHA1VerificationException(String message) {
            super(message);
        }
    }
}

