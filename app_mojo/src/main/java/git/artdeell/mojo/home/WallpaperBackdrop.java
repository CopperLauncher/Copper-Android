package git.artdeell.mojo.home;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A small, already blurred copy of the wallpaper. The tiles draw the part of it that is behind
 * them, which gives the frosted glass look without blurring what is behind a view for real.
 */
public final class WallpaperBackdrop {
    public interface Listener {
        void onBackdropChanged();
    }

    private static final List<Listener> sListeners = new CopyOnWriteArrayList<>();
    private static volatile Bitmap sBitmap;
    private static WeakReference<View> sHost;
    private static final int[] sHostLocation = new int[2];
    private static final int[] sViewLocation = new int[2];

    private WallpaperBackdrop() {}

    /** @param host the wallpaper view, the bitmap covers it the same way the wallpaper does */
    public static void set(@Nullable Bitmap bitmap, @Nullable View host) {
        sBitmap = bitmap;
        sHost = host == null ? null : new WeakReference<>(host);
        for (Listener listener : sListeners) listener.onBackdropChanged();
    }

    public static void addListener(@NonNull Listener listener) {
        sListeners.add(listener);
    }

    public static void removeListener(@NonNull Listener listener) {
        sListeners.remove(listener);
    }

    @Nullable
    public static Bitmap getBitmap() {
        return sBitmap;
    }

    /**
     * Where the backdrop is drawn, in the coordinates of the view, so that it lines up with the wallpaper.
     * @return false when the wallpaper isn't on screen
     */
    public static boolean mapTo(@NonNull View view, @NonNull RectF out) {
        Bitmap bitmap = sBitmap;
        View host = sHost == null ? null : sHost.get();
        if (bitmap == null || host == null || host.getVisibility() != View.VISIBLE) return false;
        int hostW = host.getWidth(), hostH = host.getHeight();
        if (hostW <= 0 || hostH <= 0) return false;
        host.getLocationInWindow(sHostLocation);
        view.getLocationInWindow(sViewLocation);
        float scale = Math.max(hostW / (float) bitmap.getWidth(), hostH / (float) bitmap.getHeight());
        float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
        float left = (hostW - width) / 2f + sHostLocation[0] - sViewLocation[0];
        float top = (hostH - height) / 2f + sHostLocation[1] - sViewLocation[1];
        out.set(left, top, left + width, top + height);
        return true;
    }
}
