package git.artdeell.mojo.game.renderer.impl;

import android.content.Context;
import android.util.Log;

import git.artdeell.mojo.R;
import git.artdeell.mojo.game.renderer.RenderSpec;
import git.artdeell.mojo.utils.plugins.PluginManager;
import git.artdeell.mojo.utils.plugins.RendererV2Plugin;

import java.io.File;
import java.util.Map;

import git.artdeell.mojo.utils.plugins.V2PluginEnvStore;
import git.artdeell.mojoexec.MojoExec;

/**
 * Renderer backed by an installed ZalithLauncher2 renderer plugin (V2 format).
 * Uses JSON-based configuration with typed environment variables and {@code **|}
 * path prefix resolution relative to the plugin's native library directory.
 */
public class PluginV2RenderSpec implements RenderSpec {
    private final String packageName;
    private RendererV2Plugin plugin;

    public PluginV2RenderSpec(String packageName) {
        this.packageName = packageName;
    }

    private RendererV2Plugin resolve(Context context) {
        if (plugin == null) plugin = PluginManager.findRendererV2Plugin(context, packageName);
        return plugin;
    }

    public boolean compatibleDevice(Context context) {
        RendererV2Plugin resolved = resolve(context);
        return resolved != null
                && resolved.resolveEglPath() != null
                && new File(resolved.resolveEglPath()).exists();
    }

    public String name() {
        return plugin != null ? plugin.rendererId : packageName;
    }

    public int displayName() {
        return R.string.mcl_setting_renderer_plugin_v2;
    }

    public String getDisplayName(Context context) {
        RendererV2Plugin resolved = resolve(context);
        String name = resolved != null ? resolved.displayName : null;
        // An empty display name would create a renderer list entry without any
        // text; fall back to the plugin package name so every entry is selectable.
        return name != null && !name.isEmpty() ? name : packageName;
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
        RendererV2Plugin resolved = resolve(context);
        if (resolved == null) return;

        // Apply the env variables resolved from plugin defaults and stored user config.
        // POJAV_RENDERER is a PojavLauncher marker; drop it here.
        Map<String, String> env = V2PluginEnvStore.resolveEnv(resolved,
                V2PluginEnvStore.load(packageName));
        env.remove("POJAV_RENDERER");
        envMap.putAll(env);

        // Set GL/EGL library paths unless already overridden
        String glPath = resolved.resolveGlPath();
        String eglPath = resolved.resolveEglPath();
        if (glPath != null && !envMap.containsKey("LIBGL_GLES")) {
            envMap.put("LIBGL_GLES", glPath);
        }
        if (eglPath != null && !envMap.containsKey("LIBGL_EGL")) {
            envMap.put("LIBGL_EGL", eglPath);
        }
    }

    public boolean setupRenderer() {
        if (plugin == null) return false;

        for (String library : plugin.dlopenLibPaths) {
            if (library == null) continue;
            String path = plugin.resolvePath(library);
            if (path == null) continue;
            try {
                System.load(path);
            } catch (Throwable t) {
                Log.w("PluginV2RenderSpec", "Failed to preload plugin library " + library, t);
            }
        }

        String eglPath = plugin.resolveEglPath();
        if (eglPath == null) return false;
        return MojoExec.prepareEgl(eglPath, true, false, 0);
    }
}
