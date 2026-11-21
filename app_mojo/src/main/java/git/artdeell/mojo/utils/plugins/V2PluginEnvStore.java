package git.artdeell.mojo.utils.plugins;

import android.util.Log;

import androidx.annotation.Nullable;

import git.artdeell.mojo.prefs.LauncherPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Launcher-side user configuration for V2 renderer plugin environment variables.
 * Config is stored globally per plugin package in the launcher's default
 * SharedPreferences as a JSON object mapping env keys to their stored state.
 */
public final class V2PluginEnvStore {
    private static final String TAG = "V2PluginEnvStore";

    private V2PluginEnvStore() {}

    /** Stored user state for a single env entry. */
    public static class Entry {
        @Nullable public String value;
        /** null when the user never touched the on/off state */
        @Nullable public Boolean enabled;
    }

    /**
     * Load the stored user config for a plugin, keyed by env key.
     */
    public static Map<String, Entry> load(String packageName) {
        Map<String, Entry> result = new HashMap<>();
        if (LauncherPreferences.DEFAULT_PREF == null || packageName == null) return result;
        String json = LauncherPreferences.DEFAULT_PREF.getString(prefKey(packageName), null);
        if (json == null || json.isEmpty()) return result;
        try {
            JSONObject obj = new JSONObject(json);
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String envKey = keys.next();
                JSONObject state = obj.optJSONObject(envKey);
                if (state == null) continue;
                Entry entry = new Entry();
                entry.value = state.isNull("value") ? null : state.optString("value", null);
                entry.enabled = state.isNull("enabled") ? null : state.optBoolean("enabled");
                result.put(envKey, entry);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse stored env config for " + packageName, e);
        }
        return result;
    }

    /**
     * Persist the user config for a plugin. An empty map removes the stored config,
     * letting the plugin defaults apply again.
     */
    public static void save(String packageName, Map<String, Entry> config) {
        if (LauncherPreferences.DEFAULT_PREF == null || packageName == null) return;
        JSONObject obj = new JSONObject();
        try {
            for (Map.Entry<String, Entry> e : config.entrySet()) {
                JSONObject state = new JSONObject();
                if (e.getValue().value != null) state.put("value", e.getValue().value);
                if (e.getValue().enabled != null) state.put("enabled", e.getValue().enabled.booleanValue());
                obj.put(e.getKey(), state);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to serialize env config for " + packageName, e);
            return;
        }
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(prefKey(packageName), config.isEmpty() ? null : obj.toString())
                .apply();
    }

    /**
     * Compute the environment variables to apply at launch from the plugin defaults
     * and the stored user config.
     */
    public static Map<String, String> resolveEnv(RendererV2Plugin plugin, Map<String, Entry> config) {
        Map<String, String> result = new HashMap<>();
        for (RendererV2Plugin.Env env : plugin.env) {
            String key = env.getKey();
            Entry user = config.get(key);
            if (env instanceof RendererV2Plugin.NormalEnv) {
                String value = ((RendererV2Plugin.NormalEnv) env).value;
                if (value != null && !value.isEmpty()) result.put(key, value);
            } else if (env instanceof RendererV2Plugin.SelectableEnv) {
                RendererV2Plugin.SelectableEnv sel = (RendererV2Plugin.SelectableEnv) env;
                String value = user != null && user.value != null ? user.value : sel.defaultValue;
                boolean used = sel.alwaysOn ? true
                        : user != null && user.enabled != null ? user.enabled : sel.enabledByDefault;
                if (used && value != null && !value.isEmpty()) result.put(key, value);
            } else if (env instanceof RendererV2Plugin.CustomizableEnv) {
                RendererV2Plugin.CustomizableEnv custom = (RendererV2Plugin.CustomizableEnv) env;
                String value = user != null && user.value != null ? user.value : custom.defaultValue;
                if (value != null && !value.isEmpty()) result.put(key, value);
            } else if (env instanceof RendererV2Plugin.ToggleableEnv) {
                RendererV2Plugin.ToggleableEnv toggle = (RendererV2Plugin.ToggleableEnv) env;
                boolean used = user != null && user.enabled != null ? user.enabled : toggle.enabledByDefault;
                if (used && toggle.value != null && !toggle.value.isEmpty()) result.put(key, toggle.value);
            }
        }
        return result;
    }

    private static String prefKey(String packageName) {
        return "v2env_" + packageName;
    }
}
