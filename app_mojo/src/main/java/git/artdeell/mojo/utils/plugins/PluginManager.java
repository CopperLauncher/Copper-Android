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
 * Compatible with the ZalithLauncher and FCL plugin formats.
 */
public final class PluginManager {
    public static final String RENDERER_TAG_PREFIX = "plugin_";

    private static List<RendererPlugin> sRendererPlugins;

    private PluginManager() {}

    /**
     * Rescan installed packages for plugins. The result is cached until the next refresh.
     */
    public static synchronized void refresh(Context context) {
        List<RendererPlugin> rendererPlugins = new ArrayList<>();
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
        }
        sRendererPlugins = rendererPlugins;
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
}
