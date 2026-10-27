package git.artdeell.mojo.utils.cursor;

import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import git.artdeell.mojo.R;
import git.artdeell.mojo.prefs.LauncherPreferences;

import java.io.File;

/**
 * Resolves the virtual mouse pointer chosen in the cursor store.
 */
public final class CursorStyle {
    public static final String PREF_PATH = "custom_cursor_path";
    public static final String PREF_STYLE_KEY = "custom_cursor_style_key";
    public static final String KEY_STANDARD = "standard";
    public static final String KEY_ANI = "ani";
    public static final String KEY_CUSTOM_ANI = "custom_ani";
    public static final String ASSET_ANI = "01-normal-select.ani";

    private static final int DEFAULT_WIDTH = 36;
    private static final int DEFAULT_HEIGHT = 54;

    private CursorStyle() {}

    /**
     * @return the selected pointer with bounds set to 36 units wide and a height that keeps the
     * aspect ratio of the image. The caller applies the mouse scale.
     */
    public static @NonNull Drawable load(@NonNull Context context) {
        Drawable drawable = loadSelected(context);
        if(drawable == null) {
            drawable = ContextCompat.getDrawable(context, R.drawable.ic_mouse_pointer);
            assert drawable != null;
            drawable = drawable.mutate();
        }
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if(width <= 0 || height <= 0) {
            drawable.setBounds(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        } else {
            drawable.setBounds(0, 0, DEFAULT_WIDTH, Math.max(1, DEFAULT_WIDTH * height / width));
        }
        return drawable;
    }

    private static Drawable loadSelected(Context context) {
        if(LauncherPreferences.DEFAULT_PREF == null) return null;
        String key = LauncherPreferences.DEFAULT_PREF.getString(PREF_STYLE_KEY, KEY_STANDARD);
        String path = LauncherPreferences.DEFAULT_PREF.getString(PREF_PATH, null);
        if(KEY_STANDARD.equals(key)) return null;
        if(KEY_ANI.equals(key)) return new AnimatedCursorDrawable(context, ASSET_ANI, true);
        if(path == null || !new File(path).isFile()) return null;
        if(KEY_CUSTOM_ANI.equals(key)) return new AnimatedCursorDrawable(context, path, false);
        return Drawable.createFromPath(path);
    }
}
