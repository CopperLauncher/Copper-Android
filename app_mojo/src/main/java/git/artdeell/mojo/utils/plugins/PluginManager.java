package git.artdeell.mojo.utils.plugins;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Discovers renderer plugins installed as separate APKs.
 * Compatible with the ZalithLauncher/FCL V1 and ZalithLauncher2 V2 plugin formats.
 */
public final class PluginManager {
    public static final String RENDERER_TAG_PREFIX = "plugin_";

    private static List<RendererPlugin> sRendererPlugins;
    private static List<RendererV2Plugin> sRendererV2Plugins;

    private PluginManager() {}

    /**
     * Rescan installed packages for plugins. The result is cached until the next refresh.
     * Discovers both V1 (colon-separated metadata) and V2 (JSON resource) renderer plugins.
     */
    public static synchronized void refresh(Context context) {
        List<RendererPlugin> rendererPlugins = new ArrayList<>();
        List<RendererV2Plugin> rendererV2Plugins = new ArrayList<>();
        PackageManager packageManager = context.getPackageManager();
        Set<String> visitedPackages = new HashSet<>();

        // Packages with launcher activities (usual V1 plugin layout)
        List<ResolveInfo> activities = packageManager.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN),
                PackageManager.GET_META_DATA | PackageManager.GET_SHARED_LIBRARY_FILES);
        for (ResolveInfo resolveInfo : activities) {
            ApplicationInfo info = resolveInfo.activityInfo == null ? null
                    : resolveInfo.activityInfo.applicationInfo;
            if (info == null) continue;
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (!visitedPackages.add(info.packageName)) continue;
            processPackage(context, packageManager, info, rendererPlugins, rendererV2Plugins);
        }

        // All remaining installed non-system apps (library-only plugins without activities)
        List<ApplicationInfo> apps;
        try {
            apps = packageManager.getInstalledApplications(
                    PackageManager.GET_META_DATA | PackageManager.GET_SHARED_LIBRARY_FILES);
        } catch (Throwable t) {
            apps = null;
        }
        if (apps != null) {
            for (ApplicationInfo info : apps) {
                if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (!visitedPackages.add(info.packageName)) continue;
                processPackage(context, packageManager, info, rendererPlugins, rendererV2Plugins);
            }
        }

        sRendererPlugins = rendererPlugins;
        sRendererV2Plugins = rendererV2Plugins;
    }

    private static void processPackage(Context context, PackageManager packageManager,
                                       ApplicationInfo info,
                                       List<RendererPlugin> rendererPlugins,
                                       List<RendererV2Plugin> rendererV2Plugins) {
        Bundle metaData = info.metaData;
        if (metaData == null) {
            metaData = loadMetaData(packageManager, info.packageName);
        }
        if (metaData == null) return;

        if (metaData.getBoolean("fclPlugin") || metaData.getBoolean("zalithRendererPlugin")) {
            RendererPlugin plugin = RendererPlugin.parse(context, info, metaData);
            if (plugin != null) rendererPlugins.add(plugin);
        }

        if (metaData.getInt("fclPlugin_V2", 0) != 0) {
            RendererV2Plugin plugin = RendererV2Plugin.parse(context, info, metaData);
            if (plugin != null) rendererV2Plugins.add(plugin);
        }
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

    public static RendererPlugin findRendererPlugin(Context context, String packageName) {
        for (RendererPlugin plugin : getRendererPlugins(context)) {
            if (plugin.packageName.equals(packageName)) return plugin;
        }
        return null;
    }

    public static List<RendererV2Plugin> getRendererV2Plugins(Context context) {
        if (sRendererV2Plugins == null) refresh(context);
        return sRendererV2Plugins;
    }

    public static RendererV2Plugin findRendererV2Plugin(Context context, String packageName) {
        for (RendererV2Plugin plugin : getRendererV2Plugins(context)) {
            if (plugin.packageName.equals(packageName)) return plugin;
        }
        return null;
    }
}
