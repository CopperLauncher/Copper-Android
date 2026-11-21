package git.artdeell.mojo.game.renderer.impl;

import android.content.Context;
import android.util.Log;

import git.artdeell.mojo.R;
import git.artdeell.mojo.game.renderer.RenderSpec;
import git.artdeell.mojo.utils.plugins.PluginManager;
import git.artdeell.mojo.utils.plugins.RendererPlugin;

import java.io.File;
import java.util.Map;

import git.artdeell.mojoexec.MojoExec;

/**
 * Renderer backed by an installed ZalithLauncher/FCL renderer plugin.
 */
public class PluginRenderSpec implements RenderSpec {
    private final String packageName;
    private RendererPlugin plugin;

    public PluginRenderSpec(String packageName) {
        this.packageName = packageName;
    }

    private RendererPlugin resolve(Context context) {
        if (plugin == null) plugin = PluginManager.findRendererPlugin(context, packageName);
        return plugin;
    }

    public boolean compatibleDevice(Context context) {
        RendererPlugin resolved = resolve(context);
        return resolved != null && resolved.resolveEglPath() != null
                && new File(resolved.resolveEglPath()).exists();
    }

    public String name() {
        return plugin != null ? plugin.rendererId : packageName;
    }

    public int displayName() {
        return R.string.mcl_setting_renderer_plugin;
    }

    public String getDisplayName(Context context) {
        RendererPlugin resolved = resolve(context);
        return resolved != null ? resolved.displayName : packageName;
    }

    public String tag() {
        return PluginManager.RENDERER_TAG_PREFIX + packageName;
    }

    public String library() {
        return plugin != null ? plugin.resolveEglPath() : null;
    }

    public String librarySearchPath() {
        return plugin != null ? plugin.nativeLibraryDir : null;
    }

    public void setupEnvironment(Context context, Map<String, String> envMap) {
        RendererPlugin resolved = resolve(context);
        if (resolved == null) return;
        // POJAV_RENDERER is a PojavLauncher marker; Sodium refuses to start when
        // it is present, so drop it before merging plugin env.
        for (Map.Entry<String, String> e : resolved.env.entrySet()) {
            if ("POJAV_RENDERER".equals(e.getKey())) continue;
            envMap.put(e.getKey(), e.getValue());
        }
        String eglPath = resolved.resolveEglPath();
        String glPath = resolved.resolveGlPath();
        if (eglPath != null && !envMap.containsKey("LIBGL_EGL")) envMap.put("LIBGL_EGL", eglPath);
        if (glPath != null && !envMap.containsKey("LIBGL_GLES")) envMap.put("LIBGL_GLES", glPath);
    }

    public boolean setupRenderer() {
        if (plugin == null) return false;
        for (String library : plugin.dlopen) {
            if (library == null) continue;
            String path = plugin.resolveLibrary(library);
            if (path == null) continue;
            try {
                System.load(path);
            } catch (Throwable t) {
                Log.w("PluginRenderSpec", "Failed to preload plugin library " + library, t);
            }
        }
        String eglPath = plugin.resolveEglPath();
        if (eglPath == null) return false;
        return MojoExec.prepareEgl(eglPath, true, false, 0);
    }
}
