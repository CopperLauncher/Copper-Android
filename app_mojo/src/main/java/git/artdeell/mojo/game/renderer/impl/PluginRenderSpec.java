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
        return resolved != null && new File(resolved.resolveEglPath()).exists();
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
        envMap.putAll(resolved.env);
        if (!envMap.containsKey("LIBGL_EGL")) envMap.put("LIBGL_EGL", resolved.resolveEglPath());
        if (!envMap.containsKey("LIBGL_GLES")) envMap.put("LIBGL_GLES", resolved.resolveGlPath());
    }

    public boolean setupRenderer() {
        if (plugin == null) return false;
        for (String library : plugin.dlopen) {
            try {
                System.load(new File(plugin.nativeLibraryDir, library).getAbsolutePath());
            } catch (Throwable t) {
                Log.w("PluginRenderSpec", "Failed to preload plugin library " + library, t);
            }
        }
        return MojoExec.prepareEgl(plugin.resolveEglPath(), true, false, 0);
    }
}
