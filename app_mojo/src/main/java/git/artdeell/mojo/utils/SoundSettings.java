package git.artdeell.mojo.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

/** The launcher's sound options: one switch for every Nokor sound, and a volume for the in-app ones. */
public final class SoundSettings {
    public static final String KEY_ENABLED = "sounds_enabled";
    public static final String KEY_VOLUME = "sounds_volume";
    private static final int DEFAULT_VOLUME = 70;

    private SoundSettings() {}

    public static boolean isEnabled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean(KEY_ENABLED, true);
    }

    /** @return the volume of the in-app sounds, from 0 (silent) to 1 */
    public static float volume(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int percent = prefs.getInt(KEY_VOLUME, DEFAULT_VOLUME);
        return Math.max(0, Math.min(100, percent)) / 100f;
    }
}
