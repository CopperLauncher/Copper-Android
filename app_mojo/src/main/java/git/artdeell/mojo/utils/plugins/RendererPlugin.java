package git.artdeell.mojo.utils.plugins;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import git.artdeell.mojo.R;

/**
 * Renderer provided by an installed ZalithLauncher/FCL renderer plugin APK.
 * The metadata follows the {@code renderer}/{@code pojavEnv} format used by those launchers.
 */
public class RendererPlugin {
    public final String packageName;
    public final String nativeLibraryDir;
    public final String displayName;
    public final String rendererId;
    public final String glLibrary;
    public final String eglLibrary;
    public final Map<String, String> env;
    public final List<String> dlopen;

    private RendererPlugin(String packageName, String nativeLibraryDir, String displayName,
                           String rendererId, String glLibrary, String eglLibrary,
                           Map<String, String> env, List<String> dlopen) {
        this.packageName = packageName;
        this.nativeLibraryDir = nativeLibraryDir;
        this.displayName = displayName;
        this.rendererId = rendererId;
        this.glLibrary = glLibrary;
        this.eglLibrary = eglLibrary;
        this.env = env;
        this.dlopen = dlopen;
    }

    static RendererPlugin parse(Context context, ApplicationInfo info, Bundle metaData) {
        if (info.nativeLibraryDir == null) return null;
        String rendererString = metaData.getString("renderer");
        if (rendererString == null) return null;
        String[] parts = rendererString.split(":");
        if (parts.length < 3) return null;

        String rendererId = parts[0];
        String glLibrary = parts[1];
        String eglLibrary = parts[2];

        String appLabel = String.valueOf(context.getPackageManager().getApplicationLabel(info));
        String description = metaData.getString("des");
        String displayName;
        if (description == null || description.isEmpty()) {
            displayName = appLabel;
        } else {
            displayName = description + " (" + context.getString(R.string.mcl_setting_renderer_from_plugins, appLabel) + ")";
        }

        Map<String, String> env = new LinkedHashMap<>();
        List<String> dlopen = new ArrayList<>();
        String pojavEnv = metaData.getString("pojavEnv");
        if (pojavEnv != null) {
            for (String entry : pojavEnv.split(":")) {
                int separator = entry.indexOf('=');
                if (separator < 0) continue;
                String key = entry.substring(0, separator);
                String value = entry.substring(separator + 1);
                if ("POJAV_RENDERER".equals(key)) {
                    rendererId = value;
                } else if ("DLOPEN".equals(key)) {
                    for (String library : value.split(",")) {
                        if (!library.isEmpty()) dlopen.add(library);
                    }
                } else if ("LIB_MESA_NAME".equals(key) || "MESA_LIBRARY".equals(key)) {
                    env.put(key, new File(info.nativeLibraryDir, value).getAbsolutePath());
                } else {
                    env.put(key, value);
                }
            }
        }
        return new RendererPlugin(info.packageName, info.nativeLibraryDir, displayName,
                rendererId, glLibrary, eglLibrary, env, dlopen);
    }

    /** Absolute path of the renderer EGL library, as requested by the plugin manifest. */
    public String resolveEglPath() {
        return resolveLibrary(eglLibrary);
    }

    /** Absolute path of the renderer GL library, as requested by the plugin manifest. */
    public String resolveGlPath() {
        return resolveLibrary(glLibrary);
    }

    private String resolveLibrary(String library) {
        if (library.startsWith("/")) return nativeLibraryDir + library;
        return new File(nativeLibraryDir, library).getAbsolutePath();
    }

    public String tag() {
        return PluginManager.RENDERER_TAG_PREFIX + packageName;
    }
}
