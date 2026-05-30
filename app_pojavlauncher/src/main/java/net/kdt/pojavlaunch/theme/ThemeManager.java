package net.kdt.pojavlaunch.theme;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.StyleRes;
import androidx.palette.graphics.Palette;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.fragments.RightPaneHomeFragment;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;

public class ThemeManager {

    private static final String KEY_THEME = "launcher_theme";

    public static final Preset[] PRESETS = {
        new Preset("Default (Copper)",   R.style.AppTheme),
        new Preset("Midnight Blue",      R.style.AppTheme_MidnightBlue),
        new Preset("Forest Green",       R.style.AppTheme_ForestGreen),
        new Preset("Crimson",            R.style.AppTheme_Crimson),
        new Preset("Amethyst",           R.style.AppTheme_Amethyst),
        new Preset("Arctic",             R.style.AppTheme_Arctic),
    };

    /** Save the chosen preset and return its style res so the caller can recreate. */
    public static void applyPreset(@NonNull Preset preset) {
        LauncherPreferences.DEFAULT_PREF.edit()
            .putInt(KEY_THEME, preset.styleRes)
            .apply();
    }

    public static void resetToDefault() {
        applyPreset(PRESETS[0]);
    }

    /** Call this in Activity.onCreate() BEFORE setContentView(). */
    @StyleRes
    public static int getSavedTheme() {
        return LauncherPreferences.DEFAULT_PREF.getInt(KEY_THEME, R.style.AppTheme);
    }

    /**
     * Use the Palette API to pick the closest built-in preset based on
     * the dominant colour in the custom background image.
     * Returns false if no background file exists or decoding failed.
     */
    public static boolean applyFromCustomBackground() {
        File bgFile = new File(RightPaneHomeFragment.CUSTOM_BG_PATH);
        if (!bgFile.exists()) return false;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 4;
        Bitmap bmp = BitmapFactory.decodeFile(bgFile.getAbsolutePath(), opts);
        if (bmp == null) return false;

        Palette palette = Palette.from(bmp).maximumColorCount(24).generate();
        bmp.recycle();

        // Pick the dominant swatch
        Palette.Swatch dominant = firstNonNull(
            palette.getDarkVibrantSwatch(),
            palette.getVibrantSwatch(),
            palette.getDarkMutedSwatch(),
            palette.getMutedSwatch()
        );

        if (dominant == null) return false;

        // Find the closest preset by hue distance
        float[] dominantHsl = dominant.getHsl();
        Preset best = PRESETS[0];
        float bestDist = Float.MAX_VALUE;

        // Reference hues for each preset accent colour
        float[] presetHues = { 20f, 210f, 120f, 0f, 280f, 185f }; // copper,blue,green,red,purple,cyan

        for (int i = 0; i < PRESETS.length; i++) {
            float dist = Math.abs(dominantHsl[0] - presetHues[i]);
            if (dist > 180) dist = 360 - dist; // wrap around hue circle
            if (dist < bestDist) {
                bestDist = dist;
                best = PRESETS[i];
            }
        }

        applyPreset(best);
        return true;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... items) {
        for (T t : items) if (t != null) return t;
        return null;
    }

    public static final class Preset {
        public final String name;
        public final int styleRes;
        public Preset(String name, int styleRes) {
            this.name = name;
            this.styleRes = styleRes;
        }
    }
}