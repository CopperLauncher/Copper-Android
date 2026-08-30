package net.kdt.pojavlaunch.plugins;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Discovers renderer plugins compatible with the FCLRendererPlugin format
 * (https://github.com/ShirosakiMio/FCLRendererPlugin) that are installed as
 * separate APKs on the device.
 *
 * A renderer plugin is any non-system installed app exposing a launcher
 * activity whose application metadata declares:
 *   fclPlugin  (boolean, must be true)
 *   renderer   ("id:glLibraryName:eglLibraryName")
 *   des        (display name shown in the renderer picker)
 *   pojavEnv   (optional, colon-separated "KEY=value" entries)
 *   boatEnv    (optional, colon-separated "KEY=value" entries, unused here)
 *   minMCVer / maxMCVer (optional version bounds, unused here)
 */
public class RendererPlugin {
    private static final String TAG = "RendererPlugin";
    private static final int PACKAGE_FLAGS = PackageManager.GET_META_DATA;

    /** Prefix applied to every discovered plugin's renderer id to avoid clashing with built-in renderers. */
    public static final String ID_PREFIX = "plugin:";

    /** SharedPreferences key the last successful scan's results are cached under, so they
     *  survive the app's process being killed and restarted (see {@link #loadCacheIfNeeded}). */
    private static final String CACHE_PREF_KEY = "renderer_plugin_cache";

    private static boolean sDiscovered = false;
    /** Whether the on-disk cache has already been consulted this process (successfully or
     *  not) - separate from {@link #sDiscovered}, which only tracks an actual, explicit scan. */
    private static boolean sCacheLoadAttempted = false;
    private static final List<PluginRenderer> sPluginRenderers = new ArrayList<>();

    public static class PluginRenderer {
        public final String id;
        public final String displayName;
        public final String glName;
        public final String eglName;
        public final String nativeLibraryDir;
        public final String packageName;
        public final List<String> pojavEnv;
        public final String minMCVer;
        public final String maxMCVer;

        PluginRenderer(String rendererId, String displayName, String glName, String eglName,
                       String nativeLibraryDir, String packageName, List<String> pojavEnv,
                       String minMCVer, String maxMCVer) {
            this.id = ID_PREFIX + rendererId;
            this.displayName = displayName;
            this.glName = glName;
            this.eglName = eglName;
            this.nativeLibraryDir = nativeLibraryDir;
            this.packageName = packageName;
            this.pojavEnv = pojavEnv;
            this.minMCVer = minMCVer;
            this.maxMCVer = maxMCVer;
        }

        /** Absolute path to this plugin's main render library. */
        public String getGlPath() {
            return nativeLibraryDir + "/" + glName;
        }

        /** Absolute path to this plugin's EGL library, or null if it doesn't provide one. */
        public String getEglPath() {
            if (eglName == null || eglName.isEmpty()) return null;
            if (eglName.startsWith("/")) return nativeLibraryDir + eglName;
            return nativeLibraryDir + "/" + eglName;
        }
    }

    /**
     * Kicks off {@link #discover(Context)} on a background thread so the (potentially slow,
     * device-wide) package scan never blocks the caller. Safe to call multiple times; only
     * actually re-scans once at a time thanks to the synchronized discover() below.
     * {@code onDone} (optional) is invoked on the UI thread once the scan finishes - use it to
     * refresh any UI built from {@link #getPluginRenderers(Context)}/
     * {@link net.kdt.pojavlaunch.Tools#getCompatibleRenderers(Context)}.
     * <p>
     * This is meant to be triggered explicitly by the user (e.g. a "Check for renderer
     * plugins" button), not automatically on every launch or screen open: the scan itself is
     * unavoidably a bit heavy, so running it up front unconditionally just moves the jank
     * around instead of removing it.
     */
    public static void discoverAsync(Context context, @Nullable Runnable onDone) {
        Context appContext = context.getApplicationContext();
        net.kdt.pojavlaunch.PojavApplication.sExecutorService.execute(() -> {
            discover(appContext);
            if (onDone != null) net.kdt.pojavlaunch.Tools.runOnUiThread(onDone);
        });
    }

    /** Re-scans installed packages for renderer plugins. Safe to call repeatedly.
     *  This performs a device-wide PackageManager query and must never be called
     *  from the UI thread; use {@link #discoverAsync(Context, Runnable)} instead. */
    public static synchronized void discover(Context context) {
        List<PluginRenderer> discovered = new ArrayList<>();
        try {
            PackageManager packageManager = context.getPackageManager();
            List<ResolveInfo> resolveInfos = packageManager.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN), PACKAGE_FLAGS);
            for (ResolveInfo resolveInfo : resolveInfos) {
                try {
                    PluginRenderer renderer = parse(resolveInfo.activityInfo.applicationInfo);
                    if (renderer != null) addPluginRenderer(discovered, renderer);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to parse a potential renderer plugin", e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to query installed renderer plugins", e);
        }
        synchronized (sPluginRenderers) {
            sPluginRenderers.clear();
            sPluginRenderers.addAll(discovered);
        }
        sDiscovered = true;
        sCacheLoadAttempted = true; // a fresh scan supersedes whatever was on disk
        persistCache();
        Log.i(TAG, "Discovered " + discovered.size() + " renderer plugin(s)");
    }

    /** Saves the current {@link #sPluginRenderers} to disk so a future process (after this one
     *  is killed and restarted) can restore them without forcing the user to re-scan - see
     *  {@link #loadCacheIfNeeded}. */
    private static void persistCache() {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs == null) return; // Not initialized yet; nothing sensible to do here.
        try {
            String json;
            synchronized (sPluginRenderers) {
                json = Tools.GLOBAL_GSON.toJson(sPluginRenderers);
            }
            prefs.edit().putString(CACHE_PREF_KEY, json).apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist discovered renderer plugins", e);
        }
    }

    /** Restores whatever plugin renderers were found by the last scan of a previous process,
     *  once per process. Without this, {@link #sPluginRenderers} - which only ever lives in
     *  memory - silently reverts to empty every time Android kills and restarts the app's
     *  process, making previously-discovered renderers disappear until the user explicitly
     *  re-scans again. This does NOT perform a new scan and does NOT set {@link #sDiscovered};
     *  it only seeds the in-memory list from the last known-good result. */
    private static synchronized void loadCacheIfNeeded() {
        if (sDiscovered || sCacheLoadAttempted) return;
        sCacheLoadAttempted = true;
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs == null) return; // Not initialized yet; try again next call.
        try {
            String json = prefs.getString(CACHE_PREF_KEY, null);
            if (json == null) return;
            PluginRenderer[] cached = Tools.GLOBAL_GSON.fromJson(json, PluginRenderer[].class);
            if (cached == null) return;
            synchronized (sPluginRenderers) {
                sPluginRenderers.clear();
                sPluginRenderers.addAll(Arrays.asList(cached));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load cached renderer plugins", e);
        }
    }

    private static PluginRenderer parse(ApplicationInfo info) {
        if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return null;
        Bundle metaData = info.metaData;
        if (metaData == null || !metaData.getBoolean("fclPlugin", false)) return null;

        String rendererString = metaData.getString("renderer");
        String des = metaData.getString("des");
        if (rendererString == null || des == null) return null;

        String[] renderer = rendererString.split(":");
        if (renderer.length < 3) {
            Log.w(TAG, "Malformed \"renderer\" metadata in " + info.packageName);
            return null;
        }

        List<String> pojavEnv = splitEnv(metaData.getString("pojavEnv"));
        String minMCVer = metaData.getString("minMCVer", "");
        String maxMCVer = metaData.getString("maxMCVer", "");

        return new PluginRenderer(renderer[0], des, renderer[1], renderer[2],
                info.nativeLibraryDir, info.packageName, pojavEnv, minMCVer, maxMCVer);
    }

    private static List<String> splitEnv(String envString) {
        if (envString == null || envString.isEmpty()) return null;
        List<String> list = new ArrayList<>();
        for (String entry : envString.split(":")) {
            if (!entry.isEmpty()) list.add(entry);
        }
        return list;
    }

    private static void addPluginRenderer(List<PluginRenderer> list, PluginRenderer renderer) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(renderer.id)) {
                list.set(i, renderer);
                return;
            }
        }
        list.add(renderer);
    }

    /** Returns whichever plugin renderers were found by the most recent {@link #discover}/
     *  {@link #discoverAsync} call, restoring the last scan's results from disk first if this
     *  process hasn't scanned or loaded them yet (see {@link #loadCacheIfNeeded}). Empty until
     *  a scan has actually happened at least once, ever - see {@link #discoverAsync(Context, Runnable)}. */
    public static List<PluginRenderer> getPluginRenderers(Context context) {
        loadCacheIfNeeded();
        synchronized (sPluginRenderers) {
            return new ArrayList<>(sPluginRenderers);
        }
    }

    /** Whether {@link #discover}/{@link #discoverAsync} has completed at least once this process. */
    public static boolean hasDiscovered() {
        loadCacheIfNeeded();
        return sDiscovered;
    }

    /** Looks up a discovered plugin renderer by its full (prefixed) renderer id. */
    public static PluginRenderer getById(String rendererId) {
        if (rendererId == null) return null;
        loadCacheIfNeeded();
        synchronized (sPluginRenderers) {
            for (PluginRenderer renderer : sPluginRenderers) {
                if (renderer.id.equals(rendererId)) return renderer;
            }
        }
        return null;
    }
}
