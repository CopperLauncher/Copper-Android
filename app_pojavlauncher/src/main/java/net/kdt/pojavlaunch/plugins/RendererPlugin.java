package net.kdt.pojavlaunch.plugins;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;
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

    private static boolean sDiscovered = false;
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
     * Use this to pre-warm the cache long before {@link Tools#getCompatibleRenderers(Context)}
     * is needed on the UI thread (e.g. opening the Profile Editor).
     */
    public static void discoverAsync(Context context) {
        Context appContext = context.getApplicationContext();
        net.kdt.pojavlaunch.PojavApplication.sExecutorService.execute(() -> discover(appContext));
    }

    /** Re-scans installed packages for renderer plugins. Safe to call repeatedly.
     *  This performs a device-wide PackageManager query and must never be called
     *  from the UI thread; use {@link #discoverAsync(Context)} instead when in doubt. */
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
        Log.i(TAG, "Discovered " + discovered.size() + " renderer plugin(s)");
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

    /** Returns the currently discovered plugin renderers, discovering them first if necessary. */
    public static List<PluginRenderer> getPluginRenderers(Context context) {
        if (!sDiscovered) discover(context);
        synchronized (sPluginRenderers) {
            return new ArrayList<>(sPluginRenderers);
        }
    }

    /** Looks up a discovered plugin renderer by its full (prefixed) renderer id. */
    public static PluginRenderer getById(String rendererId) {
        if (rendererId == null) return null;
        synchronized (sPluginRenderers) {
            for (PluginRenderer renderer : sPluginRenderers) {
                if (renderer.id.equals(rendererId)) return renderer;
            }
        }
        return null;
    }
}
