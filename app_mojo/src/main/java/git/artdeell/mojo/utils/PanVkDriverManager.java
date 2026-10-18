package git.artdeell.mojo.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Manages the PanVK Kbase Vulkan driver for Mali GPUs. Drivers are fetched
 * from a user configured GitHub repository, or imported from a local file or
 * an adrenotools style ZIP package.
 */
public class PanVkDriverManager {
    private static final String TAG = "PanVkDriverManager";

    private static final String PREFS_NAME = "panvk_driver";
    private static final String KEY_REPO = "driver_repo";
    private static final String DEFAULT_REPO = "zenithblue-oss/panvk-kbase-android";
    private static final String FALLBACK_ASSET = "libvulkan_panfrost-android-aarch64.so";

    private final Context context;
    private final File driversDir;
    private volatile File cachedDriverFile;
    private volatile Integer cachedArchVersion;

    public interface DriverReadyListener {
        void onDriverReady(File driverFile);
        void onDriverError(String error);
    }

    /** A driver listed by the manager, either installed locally or remote. */
    public static class DriverEntry {
        public final int arch;
        public final String title;
        public final String subtitle;
        public final String url;
        public final boolean installed;

        public DriverEntry(int arch, String title, String subtitle, String url, boolean installed) {
            this.arch = arch;
            this.title = title;
            this.subtitle = subtitle;
            this.url = url;
            this.installed = installed;
        }
    }

    public PanVkDriverManager(Context context) {
        this.context = context.getApplicationContext();
        this.driversDir = new File(context.getFilesDir(), "panvk_drivers");
        if(!driversDir.exists()) driversDir.mkdirs();
    }

    /**
     * Get the Mali GPU architecture version for the current device.
     *
     * @return architecture version (9-13) or 0 if not a recognized Mali GPU
     */
    public int detectMaliArchVersion() {
        return GpuUtils.getGlInfo().getMaliArchVersion();
    }

    public boolean isDriverInstalled() {
        int arch = detectMaliArchVersion();
        return arch != 0 && getArchDriverFile(arch).exists();
    }

    /**
     * Resolve the driver download from the configured repository and install
     * it for the current device's Mali architecture.
     */
    public void installDriverForDevice(DriverReadyListener listener) {
        int arch = detectMaliArchVersion();
        if(arch == 0) {
            postError(listener, "Not a Mali GPU or unknown architecture");
            return;
        }
        new Thread(() -> {
            try {
                String url = resolveDriverUrl(getDriverRepo(context));
                Log.i(TAG, "Downloading PanVK driver from " + url);
                installFromUrl(arch, url, listener);
            } catch(IOException e) {
                Log.e(TAG, "Failed to resolve PanVK driver", e);
                postError(listener, e.getMessage());
            }
        }).start();
    }

    /** Install a driver for the given architecture from an explicit URL. */
    public void installDriverFromUrl(int arch, String url, DriverReadyListener listener) {
        new Thread(() -> {
            try {
                installFromUrl(arch, url, listener);
            } catch(IOException e) {
                Log.e(TAG, "Failed to install PanVK driver", e);
                postError(listener, e.getMessage());
            }
        }).start();
    }

    private void installFromUrl(int arch, String url, DriverReadyListener listener) throws IOException {
        File targetFile = getArchDriverFile(arch);
        File parent = targetFile.getParentFile();
        if(parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(parent, targetFile.getName() + ".tmp");
        try {
            downloadToFile(url, tmp);
            replaceDriver(tmp, targetFile);
            cachedDriverFile = targetFile;
            cachedArchVersion = arch;
            postReady(listener, targetFile);
        } catch(IOException e) {
            tmp.delete();
            throw e;
        }
    }

    /**
     * List every driver installed under the driver directory.
     */
    public List<DriverEntry> listInstalledDrivers() {
        List<DriverEntry> entries = new ArrayList<>();
        File[] dirs = driversDir.listFiles();
        if(dirs == null) return entries;
        for(File dir : dirs) {
            if(!dir.isDirectory()) continue;
            File file = new File(dir, "libvulkan_panfrost.so");
            if(!file.exists()) continue;
            int arch = parseArch(dir.getName());
            entries.add(new DriverEntry(arch, "Mali v" + arch, file.getAbsolutePath(), null, true));
        }
        return entries;
    }

    /**
     * List the driver assets available in the configured repository. Performs
     * a blocking network request, so it must not be called on the main thread.
     */
    public List<DriverEntry> listRemoteDrivers() throws IOException {
        String repo = getDriverRepo(context);
        List<DriverEntry> entries = new ArrayList<>();
        try {
            String api = "https://api.github.com/repos/" + repo + "/releases?per_page=20";
            HttpURLConnection conn = open(api);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            try {
                int code = conn.getResponseCode();
                if(code != HttpURLConnection.HTTP_OK)
                    throw new IOException("HTTP " + code + " listing releases");
                JSONArray releases = new JSONArray(readAll(conn.getInputStream()));
                for(int i = 0; i < releases.length(); i++) {
                    JSONObject release = releases.getJSONObject(i);
                    String tag = release.optString("tag_name", release.optString("name", ""));
                    JSONArray assets = release.optJSONArray("assets");
                    if(assets == null) continue;
                    for(int j = 0; j < assets.length(); j++) {
                        JSONObject asset = assets.getJSONObject(j);
                        String name = asset.optString("name", "");
                        if(driverAssetScore(name) == 0) continue;
                        String url = asset.optString("browser_download_url", null);
                        if(url == null) continue;
                        entries.add(new DriverEntry(0, tag, name, url, false));
                    }
                }
            } finally {
                conn.disconnect();
            }
        } catch(JSONException e) {
            throw new IOException("Invalid release data", e);
        }
        return entries;
    }

    private static int parseArch(String dirName) {
        try {
            return Integer.parseInt(dirName.substring(1));
        } catch(NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Delete the installed driver for the current device's Mali architecture.
     *
     * @return whether a driver file was removed
     */
    public boolean uninstallDriver() {
        int arch = detectMaliArchVersion();
        if(arch == 0) return false;
        return uninstallDriver(arch);
    }

    /** Delete the installed driver for a specific Mali architecture. */
    public boolean uninstallDriver(int arch) {
        File targetFile = getArchDriverFile(arch);
        new File(targetFile.getParentFile(), targetFile.getName() + ".tmp").delete();
        cachedDriverFile = null;
        cachedArchVersion = null;
        return !targetFile.exists() || targetFile.delete();
    }

    public void importDriverFromUri(Uri uri, DriverReadyListener listener) {
        int arch = detectMaliArchVersion();
        if(arch == 0) {
            postError(listener, "Not a Mali GPU or unknown architecture");
            return;
        }
        File targetFile = getArchDriverFile(arch);
        File parent = targetFile.getParentFile();
        if(parent != null && !parent.exists()) parent.mkdirs();

        new Thread(() -> {
            File tmp = null;
            try {
                ContentResolver cr = context.getContentResolver();
                tmp = new File(parent, targetFile.getName() + ".tmp");
                try(InputStream in = cr.openInputStream(uri);
                    FileOutputStream out = new FileOutputStream(tmp)) {
                    if(in == null) throw new IOException("Cannot open selected file");
                    copy(in, out);
                }
                replaceDriver(tmp, targetFile);
                cachedDriverFile = targetFile;
                cachedArchVersion = arch;
                postReady(listener, targetFile);
            } catch(IOException e) {
                if(tmp != null) tmp.delete();
                Log.e(TAG, "Failed to import driver", e);
                postError(listener, e.getMessage());
            }
        }).start();
    }

    public void importDriverFromFile(File source, DriverReadyListener listener) {
        int arch = detectMaliArchVersion();
        if(arch == 0) {
            postError(listener, "Not a Mali GPU or unknown architecture");
            return;
        }
        if(source == null || !source.exists()) {
            postError(listener, "Selected file does not exist");
            return;
        }
        File targetFile = getArchDriverFile(arch);
        File parent = targetFile.getParentFile();
        if(parent != null && !parent.exists()) parent.mkdirs();

        new Thread(() -> {
            File tmp = null;
            try {
                tmp = new File(parent, targetFile.getName() + ".tmp");
                try(InputStream in = new FileInputStream(source);
                    FileOutputStream out = new FileOutputStream(tmp)) {
                    copy(in, out);
                }
                replaceDriver(tmp, targetFile);
                cachedDriverFile = targetFile;
                cachedArchVersion = arch;
                postReady(listener, targetFile);
            } catch(IOException e) {
                if(tmp != null) tmp.delete();
                Log.e(TAG, "Failed to import driver", e);
                postError(listener, e.getMessage());
            }
        }).start();
    }

    /**
     * Import a driver from an adrenotools style package. The package is a ZIP
     * archive holding the Vulkan driver library, usually next to a meta.json.
     * The matching "libvulkan_*.so" entry is extracted and installed.
     */
    public void importDriverPackage(Uri uri, DriverReadyListener listener) {
        int arch = detectMaliArchVersion();
        if(arch == 0) {
            postError(listener, "Not a Mali GPU or unknown architecture");
            return;
        }
        File targetFile = getArchDriverFile(arch);
        File parent = targetFile.getParentFile();
        if(parent != null && !parent.exists()) parent.mkdirs();

        new Thread(() -> {
            File archive = null;
            try {
                ContentResolver cr = context.getContentResolver();
                archive = File.createTempFile("panvk_pkg", ".zip", context.getCacheDir());
                try(InputStream in = cr.openInputStream(uri)) {
                    if(in == null) throw new IOException("Cannot open selected file");
                    try(FileOutputStream out = new FileOutputStream(archive)) {
                        copy(in, out);
                    }
                }
                extractDriverFromArchive(archive, targetFile);
                cachedDriverFile = targetFile;
                cachedArchVersion = arch;
                postReady(listener, targetFile);
            } catch(IOException e) {
                Log.e(TAG, "Failed to import driver package", e);
                postError(listener, e.getMessage());
            } finally {
                if(archive != null) archive.delete();
            }
        }).start();
    }

    private void extractDriverFromArchive(File archive, File targetFile) throws IOException {
        try(ZipFile zipFile = new ZipFile(archive)) {
            ZipEntry driverEntry = null;
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if(entry.isDirectory()) continue;
                String name = entry.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                if(driverAssetScore(base) == 0) continue;
                if(base.equals("libvulkan_panfrost.so")) {
                    driverEntry = entry;
                    break;
                }
                if(driverEntry == null) driverEntry = entry;
            }
            if(driverEntry == null) throw new IOException("No Vulkan driver library found in the package");
            File tmp = new File(targetFile.getParentFile(), targetFile.getName() + ".tmp");
            try(InputStream in = zipFile.getInputStream(driverEntry);
                FileOutputStream out = new FileOutputStream(tmp)) {
                copy(in, out);
            }
            replaceDriver(tmp, targetFile);
        }
    }

    private static String resolveDriverUrl(String repo) throws IOException {
        try {
            String api = "https://api.github.com/repos/" + repo + "/releases?per_page=10";
            HttpURLConnection conn = open(api);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            try {
                if(conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    String url = pickAssetFromReleases(readAll(conn.getInputStream()));
                    if(url != null) return url;
                }
            } finally {
                conn.disconnect();
            }
        } catch(IOException | JSONException e) {
            Log.w(TAG, "GitHub API lookup failed, using latest release asset", e);
        }
        return "https://github.com/" + repo + "/releases/latest/download/" + FALLBACK_ASSET;
    }

    private static String pickAssetFromReleases(String json) throws JSONException {
        JSONArray releases = new JSONArray(json);
        String best = null;
        int bestScore = 0;
        for(int i = 0; i < releases.length() && bestScore < 2; i++) {
            JSONArray assets = releases.getJSONObject(i).optJSONArray("assets");
            if(assets == null) continue;
            for(int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                int score = driverAssetScore(asset.optString("name", ""));
                if(score > bestScore) {
                    bestScore = score;
                    best = asset.optString("browser_download_url", null);
                }
            }
        }
        return best;
    }

    private static int driverAssetScore(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if(!n.endsWith(".so") || n.contains("freedreno")) return 0;
        if(n.contains("panfrost") || n.contains("panvk")) return 2;
        if(n.contains("vulkan")) return 1;
        return 0;
    }

    private static void downloadToFile(String url, File target) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            int code = conn.getResponseCode();
            if(code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code + " downloading driver");
            try(InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(target)) {
                copy(in, out);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "MojoLauncher-PanVKDriverManager");
        return conn;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toString("UTF-8");
    }

    private static void copy(InputStream in, java.io.OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int len;
        while((len = in.read(buf)) != -1) out.write(buf, 0, len);
    }

    private static void replaceDriver(File tmp, File targetFile) throws IOException {
        if(targetFile.exists() && !targetFile.delete()) {
            tmp.delete();
            throw new IOException("Cannot replace existing driver");
        }
        if(!tmp.renameTo(targetFile)) {
            tmp.delete();
            throw new IOException("Cannot save imported driver");
        }
    }

    private void postReady(DriverReadyListener listener, File driverFile) {
        new Handler(Looper.getMainLooper()).post(() -> listener.onDriverReady(driverFile));
    }

    private void postError(DriverReadyListener listener, String error) {
        new Handler(Looper.getMainLooper()).post(() -> listener.onDriverError(error));
    }

    public static String getDriverRepo(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_REPO, DEFAULT_REPO);
    }

    public static void setDriverRepo(Context context, String repo) {
        String normalized = normalizeRepo(repo);
        if(normalized == null) normalized = DEFAULT_REPO;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_REPO, normalized).apply();
    }

    /**
     * Turn user input into an "owner/repo" pair. Accepts a full GitHub URL,
     * an "owner/repo" string or a URL with a trailing ".git".
     *
     * @return the normalized repository or null if it cannot be parsed
     */
    public static String normalizeRepo(String input) {
        if(input == null) return null;
        String value = input.trim();
        value = value.replaceFirst("(?i)^https?://", "");
        value = value.replaceFirst("(?i)^www\\.", "");
        value = value.replaceFirst("(?i)^github\\.com/", "");
        int releases = value.indexOf("/releases");
        if(releases != -1) value = value.substring(0, releases);
        if(value.endsWith(".git")) value = value.substring(0, value.length() - 4);
        while(value.endsWith("/")) value = value.substring(0, value.length() - 1);
        String[] parts = value.split("/");
        if(parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null;
        return parts[0] + "/" + parts[1];
    }

    public File getArchDriverFile(int arch) {
        return new File(getArchDriverDir(arch), "libvulkan_panfrost.so");
    }

    public File getArchDriverDir(int arch) {
        return new File(driversDir, "v" + arch);
    }

    public File getArchDriverFileForDevice() {
        int arch = detectMaliArchVersion();
        if(arch == 0) return null;
        return getArchDriverFile(arch);
    }
}
