package git.artdeell.mojo.home;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

/** The look of the tiles, edited in Launcher appearance */
public final class HomeTileStyle {
    /** Opacity of the tile background in percent, 0 to 100 */
    public static final String PREF_OPACITY = "home_tile_opacity";
    /** Shows a blurred copy of the wallpaper behind the tile background */
    public static final String PREF_BLUR = "home_tile_blur";

    /** Lets the home screen scroll, so there is no end to the tiles that can be added to it */
    public static final String PREF_INFINITE = "home_tiles_infinite";

    private HomeTileStyle() {}

    public static boolean isInfinite(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_INFINITE, false);
    }

    public static int getOpacity(@NonNull Context context) {
        int opacity = PreferenceManager.getDefaultSharedPreferences(context).getInt(PREF_OPACITY, 100);
        return Math.max(0, Math.min(100, opacity));
    }

    public static boolean isFrosted(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_BLUR, false);
    }
}
