package net.kdt.pojavlaunch.theme;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.palette.graphics.Palette;

import net.kdt.pojavlaunch.fragments.RightPaneHomeFragment;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;

/**
 * Manages launcher colour theming.
 *
 * Colours are stored as ints in DEFAULT_PREF under the KEY_* constants below.
 * Call the get*() methods wherever a @color/xxx resource would normally be used.
 */
public class ThemeManager {

    // ── SharedPref keys ───────────────────────────────────────────────────────
    public static final String KEY_BG_APP          = "theme_color_background_app";
    public static final String KEY_BG_BOTTOM_BAR   = "theme_color_background_bottom_bar";
    public static final String KEY_BG_STATUS_BAR   = "theme_color_background_status_bar";
    public static final String KEY_DIVIDER         = "theme_color_divider";
    public static final String KEY_ACCENT          = "theme_color_accent";
    public static final String KEY_TEXT_PRIMARY    = "theme_color_primary_text";
    public static final String KEY_TEXT_SECONDARY  = "theme_color_secondary_text";

    // ── Built-in presets ──────────────────────────────────────────────────────
    public static final Preset[] PRESETS = {
        new Preset("Default (Copper)",
            0xFF181818, 0xFF272727, 0xFF232323,
            0xFF464646, 0xFFff5900,
            0xFFF5F5F5, 0xFFB8B8B8),

        new Preset("Midnight Blue",
            0xFF0D1B2A, 0xFF1B2A3B, 0xFF0A1522,
            0xFF1E3A5F, 0xFF1E90FF,
            0xFFE8EFF5, 0xFF8BAABF),

        new Preset("Forest Green",
            0xFF0F1F0F, 0xFF1A2E1A, 0xFF0C1A0C,
            0xFF2D4A2D, 0xFF4CAF50,
            0xFFE8F5E9, 0xFF9CCC9C),

        new Preset("Crimson",
            0xFF1A0A0A, 0xFF2A1010, 0xFF150808,
            0xFF4A1A1A, 0xFFE53935,
            0xFFFFF0F0, 0xFFCCA0A0),

        new Preset("Amethyst",
            0xFF13091A, 0xFF1E1028, 0xFF0F0714,
            0xFF3A1F52, 0xFF9C27B0,
            0xFFF3E5F5, 0xFFCBA8D4),

        new Preset("Arctic",
            0xFF0E1A20, 0xFF162530, 0xFF0B1418,
            0xFF1E3A47, 0xFF00BCD4,
            0xFFE0F7FA, 0xFF80C8D4),
    };

    // ── Defaults (mirrors the Default preset above) ────────────────────────
    private static final int DEF_BG_APP         = 0xFF181818;
    private static final int DEF_BG_BOTTOM_BAR  = 0xFF272727;
    private static final int DEF_BG_STATUS_BAR  = 0xFF232323;
    private static final int DEF_DIVIDER        = 0xFF464646;
    private static final int DEF_ACCENT         = 0xFFff5900;
    private static final int DEF_TEXT_PRIMARY   = 0xFFF5F5F5;
    private static final int DEF_TEXT_SECONDARY = 0xFFB8B8B8;

    // ─────────────────────────────────────────────────────────────────────────

    /** Save and apply one of the built-in presets. */
    public static void applyPreset(@NonNull Preset preset) {
        LauncherPreferences.DEFAULT_PREF.edit()
            .putInt(KEY_BG_APP,         preset.bgApp)
            .putInt(KEY_BG_BOTTOM_BAR,  preset.bgBottomBar)
            .putInt(KEY_BG_STATUS_BAR,  preset.bgStatusBar)
            .putInt(KEY_DIVIDER,        preset.divider)
            .putInt(KEY_ACCENT,         preset.accent)
            .putInt(KEY_TEXT_PRIMARY,   preset.textPrimary)
            .putInt(KEY_TEXT_SECONDARY, preset.textSecondary)
            .apply();
    }

    /** Reset every colour to the Default (Copper) preset. */
    public static void resetToDefault() {
        applyPreset(PRESETS[0]);
    }

    /**
     * Read the custom background file, run it through the Palette API, derive
     * a colour scheme, and save it.
     * @return false if no custom background exists or decoding failed.
     */
    public static boolean applyFromCustomBackground() {
        File bgFile = new File(RightPaneHomeFragment.CUSTOM_BG_PATH);
        if (!bgFile.exists()) return false;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 4; // quarter size is plenty for colour sampling
        Bitmap bmp = BitmapFactory.decodeFile(bgFile.getAbsolutePath(), opts);
        if (bmp == null) return false;

        Palette palette = Palette.from(bmp).maximumColorCount(24).generate();
        bmp.recycle();

        Palette.Swatch darkVibrant = palette.getDarkVibrantSwatch();
        Palette.Swatch vibrant     = palette.getVibrantSwatch();
        Palette.Swatch darkMuted   = palette.getDarkMutedSwatch();
        Palette.Swatch muted       = palette.getMutedSwatch();

        // Pick swatches with priority, fall back gracefully
        Palette.Swatch primary  = firstNonNull(darkVibrant, darkMuted, muted, vibrant);
        Palette.Swatch secondary= firstNonNull(darkMuted, darkVibrant, muted, vibrant);
        Palette.Swatch accentSw = firstNonNull(vibrant, darkVibrant, muted, darkMuted);

        int bgApp        = primary   != null ? darken(primary.getRgb(),    0.85f) : DEF_BG_APP;
        int bgBottomBar  = secondary != null ? darken(secondary.getRgb(),  0.75f) : DEF_BG_BOTTOM_BAR;
        int bgStatusBar  = primary   != null ? darken(primary.getRgb(),    0.70f) : DEF_BG_STATUS_BAR;
        int divider      = secondary != null ? lighten(secondary.getRgb(), 0.15f) : DEF_DIVIDER;
        int accent       = accentSw  != null ? accentSw.getRgb()                 : DEF_ACCENT;
        int textPrimary  = primary   != null ? primary.getTitleTextColor()       : DEF_TEXT_PRIMARY;
        int textSecondary= primary   != null ? primary.getBodyTextColor()        : DEF_TEXT_SECONDARY;

        LauncherPreferences.DEFAULT_PREF.edit()
            .putInt(KEY_BG_APP,         bgApp)
            .putInt(KEY_BG_BOTTOM_BAR,  bgBottomBar)
            .putInt(KEY_BG_STATUS_BAR,  bgStatusBar)
            .putInt(KEY_DIVIDER,        divider)
            .putInt(KEY_ACCENT,         accent)
            .putInt(KEY_TEXT_PRIMARY,   textPrimary)
            .putInt(KEY_TEXT_SECONDARY, textSecondary)
            .apply();

        return true;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    @ColorInt public static int getBgApp()          { return get(KEY_BG_APP,         DEF_BG_APP); }
    @ColorInt public static int getBgBottomBar()    { return get(KEY_BG_BOTTOM_BAR,  DEF_BG_BOTTOM_BAR); }
    @ColorInt public static int getBgStatusBar()    { return get(KEY_BG_STATUS_BAR,  DEF_BG_STATUS_BAR); }
    @ColorInt public static int getDivider()        { return get(KEY_DIVIDER,        DEF_DIVIDER); }
    @ColorInt public static int getAccent()         { return get(KEY_ACCENT,         DEF_ACCENT); }
    @ColorInt public static int getTextPrimary()    { return get(KEY_TEXT_PRIMARY,   DEF_TEXT_PRIMARY); }
    @ColorInt public static int getTextSecondary()  { return get(KEY_TEXT_SECONDARY, DEF_TEXT_SECONDARY); }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static int get(String key, int def) {
        return LauncherPreferences.DEFAULT_PREF.getInt(key, def);
    }

    @ColorInt
    private static int darken(@ColorInt int color, float factor) {
        int a = (color >> 24) & 0xFF;
        int r = Math.round(((color >> 16) & 0xFF) * factor);
        int g = Math.round(((color >>  8) & 0xFF) * factor);
        int b = Math.round( (color        & 0xFF) * factor);
        return (a << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    @ColorInt
    private static int lighten(@ColorInt int color, float add) {
        int a = (color >> 24) & 0xFF;
        int r = ((color >> 16) & 0xFF) + Math.round(255 * add);
        int g = ((color >>  8) & 0xFF) + Math.round(255 * add);
        int b =  (color        & 0xFF) + Math.round(255 * add);
        return (a << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    @SafeVarargs
    private static <T> T firstNonNull(T... items) {
        for (T t : items) if (t != null) return t;
        return null;
    }

    // ── Preset data class ─────────────────────────────────────────────────────

    public static final class Preset {
        public final String name;
        public final int bgApp, bgBottomBar, bgStatusBar, divider, accent, textPrimary, textSecondary;

        public Preset(String name,
                      int bgApp, int bgBottomBar, int bgStatusBar,
                      int divider, int accent,
                      int textPrimary, int textSecondary) {
            this.name          = name;
            this.bgApp         = bgApp;
            this.bgBottomBar   = bgBottomBar;
            this.bgStatusBar   = bgStatusBar;
            this.divider       = divider;
            this.accent        = accent;
            this.textPrimary   = textPrimary;
            this.textSecondary = textSecondary;
        }
    }
}