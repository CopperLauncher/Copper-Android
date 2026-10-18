package git.artdeell.mojo.utils.plugins;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Discovers renderer and native library plugins installed as separate APKs.
 * Compatible with the ZalithLauncher and FCL plugin formats.
 */
public final class PluginManager {
    public static final String RENDERER_TAG_PREFIX = "plugin_";
    public static final String PREF_ENABLED_NATIVE_PLUGINS = "enabledNativePlugins";

    private static List<RendererPlugin> sRendererPlugins;
    private static List<NativePlugin> sNativePlugins;

    private PluginManager() {}

    /**
     * Rescan installed packages for plugins. The result is cached until the next refresh.
     */
    public static synchronized void refresh(Context context) {
        List<RendererPlugin> rendererPlugins = new ArrayList<>();
        List<NativePlugin> nativePlugins = new ArrayList<>();
        PackageManager packageManager = context.getPackageManager();
        List<ResolveInfo> activities = packageManager.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN),
                PackageManager.GET_META_DATA | PackageManager.GET_SHARED_LIBRARY_FILES);
        Set<String> visitedPackages = new HashSet<>();
        for (ResolveInfo resolveInfo : activities) {
            ApplicationInfo info = resolveInfo.activityInfo == null ? null
                    : resolveInfo.activityInfo.applicationInfo;
            if (info == null) continue;
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (!visitedPackages.add(info.packageName)) continue;

            Bundle metaData = info.metaData;
            if (metaData == null) metaData = loadMetaData(packageManager, info.packageName);
            if (metaData == null) continue;

            if (metaData.getBoolean("fclPlugin") || metaData.getBoolean("zalithRendererPlugin")) {
                RendererPlugin plugin = RendererPlugin.parse(context, info, metaData);
                if (plugin != null) rendererPlugins.add(plugin);
            }
            if (metaData.getBoolean("FCLNativePlugin")) {
                NativePlugin plugin = NativePlugin.parse(context, info, metaData);
                if (plugin != null) nativePlugins.add(plugin);
            }
        }
        sRendererPlugins = rendererPlugins;
        sNativePlugins = nativePlugins;
    }

    private static Bundle loadMetaData(PackageManager packageManager, String packageName) {
        try {
            return packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    public static List<RendererPlugin> getRendererPlugins(Context context) {
        if (sRendererPlugins == null) refresh(context);
        return sRendererPlugins;
    }

    public static List<NativePlugin> getNativePlugins(Context context) {
        if (sNativePlugins == null) refresh(context);
        return sNativePlugins;
    }

    public static RendererPlugin findRendererPlugin(Context context, String packageName) {
        for (RendererPlugin plugin : getRendererPlugins(context)) {
            if (plugin.packageName.equals(packageName)) return plugin;
        }
        return null;
    }

    /**
     * Whether the given native plugin is enabled. When the user has never configured the list
     * every installed plugin is considered enabled.
     */
    public static boolean isNativePluginEnabled(Context context, String packageName) {
        Set<String> enabled = PreferenceManager.getDefaultSharedPreferences(context)
                .getStringSet(PREF_ENABLED_NATIVE_PLUGINS, null);
        return enabled == null || enabled.contains(packageName);
    }

    public static Set<String> getAllNativePluginPackageNames(Context context) {
        List<NativePlugin> plugins = getNativePlugins(context);
        Set<String> names = new HashSet<>(plugins.size());
        for (NativePlugin plugin : plugins) names.add(plugin.packageName);
        return names;
    }

    public static List<String> getNativePluginJvmArgs(Context context) {
        List<String> args = new ArrayList<>();
        for (NativePlugin plugin : getNativePlugins(context)) {
            if (isNativePluginEnabled(context, plugin.packageName)) args.addAll(plugin.jvmArgs);
        }
        return Collections.unmodifiableList(args);
    }

    /**
     * Native library directories of enabled native plugins, for the JVM's java.library.path.
     */
    public static List<String> getNativePluginLibraryDirs(Context context) {
        List<String> dirs = new ArrayList<>();
        for (NativePlugin plugin : getNativePlugins(context)) {
            if (isNativePluginEnabled(context, plugin.packageName)) dirs.add(plugin.nativeLibraryDir);
        }
        return Collections.unmodifiableList(dirs);
    }
}
