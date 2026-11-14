package git.artdeell.mojo.utils.plugins;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Renderer plugin provided by an installed ZalithLauncher2 renderer plugin APK (V2 format).
 * The metadata follows the {@code fclPlugin_V2} format: an integer resource ID whose value
 * is a JSON-encoded RendererConfig. Paths prefixed with {@code **|} are resolved relative
 * to the plugin's native library directory at runtime, making the plugin relocatable.
 *
 * Environment variables are typed: NormalEnv (fixed), SelectableEnv (dropdown),
 * CustomizableEnv (free-form text), ToggleableEnv (on/off). The rendererId is
 * not exported to the game: Sodium treats {@code POJAV_RENDERER} as a sign of
 * PojavLauncher and refuses to start.
 */
public class RendererV2Plugin {
    public final String packageName;
    public final String nativeLibraryDir;
    public final String displayName;
    public final String rendererId;
    public final String glPath;
    public final String eglPath;
    public final List<String> dlopenLibPaths;
    public final List<Env> env;
    public final String minMCVer;
    public final String maxMCVer;

    private RendererV2Plugin(String packageName, String nativeLibraryDir, String displayName,
                             String rendererId, String glPath, String eglPath,
                             List<String> dlopenLibPaths, List<Env> env,
                             String minMCVer, String maxMCVer) {
        this.packageName = packageName;
        this.nativeLibraryDir = nativeLibraryDir;
        this.displayName = displayName;
        this.rendererId = rendererId;
        this.glPath = glPath;
        this.eglPath = eglPath;
        this.dlopenLibPaths = dlopenLibPaths;
        this.env = env;
        this.minMCVer = minMCVer;
        this.maxMCVer = maxMCVer;
    }

    /**
     * Parse a V2 renderer plugin from APK metadata. The {@code fclPlugin_V2} metadata entry
     * must be an integer resource ID whose string value is a JSON-encoded RendererConfig.
     */
    static RendererV2Plugin parse(Context context, ApplicationInfo info, Bundle metaData) {
        if (info.nativeLibraryDir == null) return null;

        int resId = metaData.getInt("fclPlugin_V2", 0);
        if (resId == 0) return null;

        Context pluginContext;
        String json;
        try {
            pluginContext = context.createPackageContext(info.packageName, Context.CONTEXT_IGNORE_SECURITY);
            json = pluginContext.getResources().getString(resId);
        } catch (Throwable t) {
            return null;
        }

        JSONObject config;
        try {
            config = new JSONObject(json);
        } catch (Throwable t) {
            return null;
        }

        String displayName = config.optString("displayName", info.packageName);
        String rendererId = config.optString("rendererId", info.packageName);
        String glPath = config.optString("rendererGLPath", null);
        String eglPath = config.optString("rendererEGLPath", null);
        String minMCVer = config.isNull("minMCVer") ? null : config.optString("minMCVer", null);
        String maxMCVer = config.isNull("maxMCVer") ? null : config.optString("maxMCVer", null);

        List<String> dlopenLibPaths = new ArrayList<>();
        JSONArray dlopen = config.optJSONArray("dlopenLibPaths");
        if (dlopen != null) {
            for (int i = 0; i < dlopen.length(); i++) {
                String path = dlopen.isNull(i) ? null : dlopen.optString(i, null);
                if (path != null) dlopenLibPaths.add(path);
            }
        }

        Resources pluginResources = pluginContext.getResources();
        List<Env> env = new ArrayList<>();
        JSONArray envArray = config.optJSONArray("env");
        if (envArray != null) {
            for (int i = 0; i < envArray.length(); i++) {
                Env e = parseEnv(info.packageName, pluginResources, envArray.optJSONObject(i));
                if (e != null) env.add(e);
            }
        }

        return new RendererV2Plugin(info.packageName, info.nativeLibraryDir, displayName,
                rendererId, glPath, eglPath, dlopenLibPaths, env, minMCVer, maxMCVer);
    }

    /**
     * Resolve the env entry label from the plugin-provided MetaString resource index,
     * falling back to the environment variable key.
     */
    private static String resolveLabel(String packageName, Resources pluginResources,
                                       JSONObject obj, String key) {
        JSONObject title = obj.optJSONObject("title");
        if (title != null) {
            String resKey = title.optString("key", null);
            if (resKey != null && !resKey.isEmpty()) {
                int id = pluginResources.getIdentifier(resKey, "string", packageName);
                if (id != 0) {
                    try {
                        String resolved = pluginResources.getString(id);
                        if (resolved != null && !resolved.isEmpty()) return resolved;
                    } catch (Throwable ignored) {}
                }
            }
        }
        return key;
    }

    private static Env parseEnv(String packageName, Resources pluginResources, JSONObject obj) {
        if (obj == null) return null;
        String type = obj.optString("type", null);
        String key = obj.optString("key", null);
        if (key == null || key.isEmpty()) return null;
        // The renderer is selected by RenderSpec/MojoExec in this launcher.
        // Exporting the Pojav marker only makes Sodium refuse to start.
        if ("POJAV_RENDERER".equals(key)) return null;
        String label = resolveLabel(packageName, pluginResources, obj, key);

        if ("NormalEnv".equals(type)) {
            String value = obj.isNull("value") ? null : obj.optString("value", null);
            return new NormalEnv(key, label, value);
        } else if ("SelectableEnv".equals(type)) {
            // null check means the launcher always applies this env
            boolean alwaysOn = obj.isNull("check");
            boolean enabledByDefault = obj.optBoolean("check", true);
            JSONObject items = obj.optJSONObject("items");
            String defaultValue = items == null ? null
                    : (items.isNull("defaultValue") ? null : items.optString("defaultValue", null));
            List<String> values = new ArrayList<>();
            if (items != null) {
                JSONArray valuesArray = items.optJSONArray("values");
                if (valuesArray != null) {
                    for (int i = 0; i < valuesArray.length(); i++) {
                        String value = valuesArray.isNull(i) ? null : valuesArray.optString(i, null);
                        if (value != null) values.add(value);
                    }
                }
            }
            return new SelectableEnv(key, label, defaultValue, values, alwaysOn, enabledByDefault);
        } else if ("CustomizableEnv".equals(type)) {
            String defaultValue = obj.isNull("defaultValue") ? null : obj.optString("defaultValue", null);
            return new CustomizableEnv(key, label, defaultValue);
        } else if ("ToggleableEnv".equals(type)) {
            String value = obj.isNull("value") ? null : obj.optString("value", null);
            boolean enabledByDefault = obj.optBoolean("toggle", true);
            return new ToggleableEnv(key, label, value, enabledByDefault);
        }

        return null;
    }

    /**
     * Resolve a path: {@code **|} prefixed paths are relative to the plugin's
     * native library directory; all other paths are returned as-is.
     */
    public String resolvePath(String path) {
        if (path == null) return null;
        if (path.startsWith("**|")) {
            return new File(nativeLibraryDir, path.substring(3)).getAbsolutePath();
        }
        return path;
    }

    public String resolveGlPath() {
        return resolvePath(glPath);
    }

    public String resolveEglPath() {
        return resolvePath(eglPath);
    }

    public String tag() {
        return PluginManager.RENDERER_TAG_PREFIX + packageName;
    }

    /**
     * Check whether this plugin supports the given Minecraft version, based on its
     * {@code minMCVer} / {@code maxMCVer} config fields.
     *
     * @param mcVersion Minecraft version string like {@code "1.20.1"} or {@code "1.21"}
     * @return true when the version is within the plugin's supported range, or when
     *         the plugin declares no bounds
     */
    public boolean compatibleMCVersion(String mcVersion) {
        if (mcVersion == null || mcVersion.isEmpty()) return true;
        String[] parts = mcVersion.split("\\.");
        int major, minor = 0;
        try {
            major = Integer.parseInt(parts[0]);
            if (parts.length > 1) {
                String minorPart = parts[1];
                int digitsEnd = 0;
                while (digitsEnd < minorPart.length()
                        && Character.isDigit(minorPart.charAt(digitsEnd))) digitsEnd++;
                if (digitsEnd > 0) minor = Integer.parseInt(minorPart.substring(0, digitsEnd));
            }
        } catch (NumberFormatException e) {
            return true;
        }
        if (minMCVer != null && !minMCVer.isEmpty()) {
            int[] req = parseVersion(minMCVer);
            if (req != null && (major < req[0] || (major == req[0] && minor < req[1]))) return false;
        }
        if (maxMCVer != null && !maxMCVer.isEmpty()) {
            int[] req = parseVersion(maxMCVer);
            if (req != null && (major > req[0] || (major == req[0] && minor > req[1]))) return false;
        }
        return true;
    }

    private static int[] parseVersion(String v) {
        String[] parts = v.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return new int[]{major, minor};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Typed environment variable from the plugin config. Labels come from the
     * plugin-provided title resource, or the key when no title is set.
     */
    public interface Env {
        String getKey();
        String getLabel();
    }

    /** Fixed key=value environment variable, always applied. */
    public static class NormalEnv implements Env {
        public final String key;
        public final String label;
        public final String value;

        public NormalEnv(String key, String label, String value) {
            this.key = key;
            this.label = label;
            this.value = value;
        }

        @Override public String getKey() { return key; }
        @Override public String getLabel() { return label; }
    }

    /** Dropdown environment variable with preset values. */
    public static class SelectableEnv implements Env {
        public final String key;
        public final String label;
        public final String defaultValue;
        public final List<String> values;
        /** null check in the plugin config, the launcher always applies this env */
        public final boolean alwaysOn;
        public final boolean enabledByDefault;

        public SelectableEnv(String key, String label, String defaultValue, List<String> values,
                             boolean alwaysOn, boolean enabledByDefault) {
            this.key = key;
            this.label = label;
            this.defaultValue = defaultValue;
            this.values = values;
            this.alwaysOn = alwaysOn;
            this.enabledByDefault = enabledByDefault;
        }

        @Override public String getKey() { return key; }
        @Override public String getLabel() { return label; }
    }

    /** User-editable text environment variable. An empty value means the env is not applied. */
    public static class CustomizableEnv implements Env {
        public final String key;
        public final String label;
        public final String defaultValue;

        public CustomizableEnv(String key, String label, String defaultValue) {
            this.key = key;
            this.label = label;
            this.defaultValue = defaultValue;
        }

        @Override public String getKey() { return key; }
        @Override public String getLabel() { return label; }
    }

    /** On/off environment variable applying a fixed value when enabled. */
    public static class ToggleableEnv implements Env {
        public final String key;
        public final String label;
        public final String value;
        public final boolean enabledByDefault;

        public ToggleableEnv(String key, String label, String value, boolean enabledByDefault) {
            this.key = key;
            this.label = label;
            this.value = value;
            this.enabledByDefault = enabledByDefault;
        }

        @Override public String getKey() { return key; }
        @Override public String getLabel() { return label; }
    }
}
