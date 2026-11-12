package com.kdt.mcgui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.home.WallpaperBackdrop;
import git.artdeell.mojo.utils.LauncherBackground;
import git.artdeell.mojo.utils.ThemeColors;

import java.io.File;

/**
 * The custom launcher background, drawn behind the whole launcher: an image, an animated GIF or a
 * looping video, optionally blurred. Android 12+ blurs everything with a render effect. Older
 * versions blur still images by scaling them down and dim videos and animated images instead.
 */
public class LauncherWallpaperView extends FrameLayout {
    private static final float MAX_BLUR_RADIUS_PX = 40f;
    private static final float MAX_DIM_ALPHA = 0.6f;
    /** Blur in percent of every screen that covers the home screen, whatever the setting is */
    private static final int UNFOCUSED_BLUR = 60;
    private static final int BACKDROP_WIDTH = 48;

    private final ImageView mImage;
    private final CenterCropVideoView mVideo;
    private final View mScrim;

    private Drawable mDecoded;
    private boolean mHasMedia;
    private boolean mIsVideo;
    private boolean mActive = true;
    /** The home screen is in front, the wallpaper is blurred only as much as the setting says */
    private boolean mFocused = true;
    /** The view of the right pane (the wallpaper isn't fullscreen) and not the one behind the whole launcher */
    private boolean mPane;
    private boolean mWindowVisible = true;
    /** The blurred copy the frosted tiles draw, null for videos */
    private Bitmap mBackdrop;
    private boolean mPublished;
    private int mLoadId;
    private String mStamp;

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefListener = (prefs, key) -> {
        if (LauncherBackground.PREF_BLUR.equals(key)) {
            post(this::applyBlur);
        } else if (LauncherBackground.PREF_KIND.equals(key)) {
            post(this::reloadIfChanged);
        } else if (LauncherBackground.PREF_FULLSCREEN.equals(key)
                || LauncherBackground.PREF_OTHER_SCREENS.equals(key)) {
            post(() -> {
                reloadIfChanged();
                applyBlur();
                applyState();
            });
        }
    };

    public LauncherWallpaperView(@NonNull Context context) {
        this(context, null);
    }

    public LauncherWallpaperView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mImage = new ImageView(context);
        mImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mImage.setVisibility(GONE);
        mVideo = new CenterCropVideoView(context);
        mVideo.setVisibility(GONE);
        mVideo.setErrorListener(this::clear);
        mScrim = new View(context);
        mScrim.setBackgroundColor(Color.BLACK);
        mScrim.setAlpha(0f);
        LayoutParams match = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        addView(mImage, match);
        addView(mVideo, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(mScrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setVisibility(GONE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        PreferenceManager.getDefaultSharedPreferences(getContext())
                .registerOnSharedPreferenceChangeListener(mPrefListener);
        reloadIfChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        PreferenceManager.getDefaultSharedPreferences(getContext())
                .unregisterOnSharedPreferenceChangeListener(mPrefListener);
        mLoadId++;
        mVideo.release();
        mStamp = null;
        unpublishBackdrop();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        mWindowVisible = visibility == VISIBLE;
        applyState();
    }

    /**
     * This is the view of the right pane: it only shows the wallpaper when it isn't fullscreen.
     * The view behind the whole launcher is the other way around.
     */
    public void setPane(boolean pane) {
        if (mPane == pane) return;
        mPane = pane;
        reloadIfChanged();
        applyBlur();
        applyState();
    }

    /** Fullscreen is for the view behind the launcher, the pane view is for the wallpaper that isn't fullscreen */
    private boolean modeMatches() {
        return LauncherBackground.isFullscreen(getContext()) != mPane;
    }

    /** The pane is always its home screen, the view behind the launcher may be asked to hide on the other screens */
    private boolean visibleWhenUnfocused() {
        return mPane || LauncherBackground.showOnOtherScreens(getContext());
    }

    private void unpublishBackdrop() {
        if (!mPublished) return;
        mPublished = false;
        WallpaperBackdrop.set(null, null);
    }

    /** Only the main menu shows the wallpaper, the other screens cover it, so nothing has to play behind them */
    public void setActive(boolean active) {
        if (mActive == active) return;
        mActive = active;
        if (active) reloadIfChanged();
        applyState();
    }

    /** Every other screen than the home screen gets the wallpaper blurred, even when the blur is off */
    public void setFocused(boolean focused) {
        if (mFocused == focused) return;
        mFocused = focused;
        applyBlur();
        // Whether the wallpaper shows at all depends on it: the other screens may be set not to show it
        applyState();
    }

    private String currentStamp() {
        String kind = LauncherBackground.getKind(getContext());
        if (kind == null) return "none";
        return kind + ":" + LauncherBackground.getFile().lastModified();
    }

    /** Loads the background again if it was replaced or removed since it was loaded */
    public void reloadIfChanged() {
        if (!modeMatches()) {
            // The other one of the two views shows the wallpaper, this one doesn't even load it
            if (mHasMedia) clear();
            mLoadId++;
            mStamp = null;
            return;
        }
        if (!currentStamp().equals(mStamp)) reload();
    }

    private void reload() {
        final int loadId = ++mLoadId;
        mStamp = currentStamp();
        String kind = LauncherBackground.getKind(getContext());
        if (kind == null) {
            clear();
            return;
        }
        File file = LauncherBackground.getFile();
        if (LauncherBackground.KIND_VIDEO.equals(kind)) {
            mDecoded = null;
            mImage.setImageDrawable(null);
            mImage.setVisibility(GONE);
            mVideo.setVisibility(VISIBLE);
            // There is no frame to blur for the tiles, they stay translucent only
            mBackdrop = null;
            mVideo.setVideoPath(file.getAbsolutePath());
            mIsVideo = true;
            mHasMedia = true;
            applyBlur();
            applyState();
            return;
        }
        mVideo.release();
        mVideo.setVisibility(GONE);
        // Decoding a big image takes a while, keep it off the UI thread
        MojoApplication.sExecutorService.execute(() -> {
            Drawable drawable = LauncherBackground.decodeImage(file);
            Bitmap backdrop = drawable == null ? null : buildBackdrop(drawable);
            post(() -> {
                if (loadId != mLoadId) return;
                if (drawable == null) {
                    clear();
                    return;
                }
                mBackdrop = backdrop;
                if (mPublished) WallpaperBackdrop.set(backdrop, this);
                mDecoded = drawable;
                mIsVideo = false;
                mHasMedia = true;
                mImage.setVisibility(VISIBLE);
                applyBlur();
                applyState();
            });
        });
    }

    private void clear() {
        unpublishBackdrop();
        mBackdrop = null;
        stopAnimation();
        mDecoded = null;
        mHasMedia = false;
        mIsVideo = false;
        mImage.setImageDrawable(null);
        mImage.setVisibility(GONE);
        mVideo.release();
        mVideo.setVisibility(GONE);
        setVisibility(GONE);
    }

    private void applyState() {
        boolean show = mHasMedia && mActive && mWindowVisible && modeMatches() && (mFocused || visibleWhenUnfocused());
        // The launcher's window has no background of its own, so something has to paint behind every
        // screen. The view behind the launcher therefore stays, as a plain color, while the wallpaper
        // is hidden: a hidden wallpaper would leave stale pixels that stack up on every redraw.
        boolean keepBase = !mPane && mHasMedia && modeMatches();
        setBackgroundColor(keepBase ? ThemeColors.surface(getContext()) : Color.TRANSPARENT);
        setVisibility(show || keepBase ? VISIBLE : GONE);
        mImage.setVisibility(show && !mIsVideo ? VISIBLE : GONE);
        mVideo.setVisibility(show && mIsVideo ? VISIBLE : GONE);
        mScrim.setVisibility(show ? VISIBLE : GONE);
        // Only the view that is showing is the one the frosted tiles take the wallpaper from
        if (show && !mPublished) {
            mPublished = true;
            WallpaperBackdrop.set(mBackdrop, this);
        } else if (!show) {
            unpublishBackdrop();
        }
        if (mIsVideo) {
            if (show) mVideo.resumePlayback();
            else mVideo.pausePlayback();
        } else if (show) {
            startAnimation();
        } else {
            stopAnimation();
        }
    }

    private void applyBlur() {
        if (!mHasMedia) return;
        int setting = LauncherBackground.getBlur(getContext());
        int blur = mFocused || mPane ? setting : Math.max(setting, UNFOCUSED_BLUR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RenderEffect effect = null;
            if (blur > 0) {
                float radius = blur / 100f * MAX_BLUR_RADIUS_PX;
                effect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP);
            }
            mImage.setRenderEffect(effect);
            mVideo.setRenderEffect(effect);
            if (!mIsVideo) mImage.setImageDrawable(mDecoded);
            mScrim.setAlpha(0f);
            return;
        }
        if (!mIsVideo && mDecoded instanceof BitmapDrawable) {
            Bitmap source = ((BitmapDrawable) mDecoded).getBitmap();
            mImage.setImageDrawable(blur > 0 && source != null
                    ? new BitmapDrawable(getResources(), downscaleBlur(source, blur)) : mDecoded);
            mScrim.setAlpha(0f);
            return;
        }
        // Videos and animated images can't be blurred here, the setting dims them instead. What
        // the other screens add on their own never dims: there is no dark cover unless it is asked for
        if (!mIsVideo) mImage.setImageDrawable(mDecoded);
        mScrim.setAlpha(setting / 100f * MAX_DIM_ALPHA);
    }

    /** A tiny copy of the wallpaper, drawn stretched behind the frosted tiles. Null if there is no frame to take */
    @Nullable
    private static Bitmap buildBackdrop(@NonNull Drawable drawable) {
        try {
            Bitmap source;
            if (drawable instanceof BitmapDrawable) {
                source = ((BitmapDrawable) drawable).getBitmap();
            } else if (drawable.getIntrinsicWidth() > 0 && drawable.getIntrinsicHeight() > 0) {
                // An animated image: its current frame
                int width = Math.min(drawable.getIntrinsicWidth(), 256);
                int height = Math.max(1, Math.round(drawable.getIntrinsicHeight() * (width / (float) drawable.getIntrinsicWidth())));
                source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                drawable.setBounds(0, 0, width, height);
                drawable.draw(new Canvas(source));
            } else {
                return null;
            }
            if (source == null) return null;
            Bitmap small = downscaleToWidth(source, BACKDROP_WIDTH);
            return small == source ? small.copy(Bitmap.Config.ARGB_8888, false) : small;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap downscaleToWidth(Bitmap source, int width) {
        int height = Math.max(1, Math.round(source.getHeight() * (width / (float) source.getWidth())));
        Bitmap current = source;
        while (current.getWidth() / 2 > width) {
            Bitmap next = Bitmap.createScaledBitmap(current, current.getWidth() / 2, Math.max(1, current.getHeight() / 2), true);
            if (current != source) current.recycle();
            current = next;
        }
        Bitmap result = Bitmap.createScaledBitmap(current, width, height, true);
        if (current != source && current != result) current.recycle();
        return result;
    }

    /** Scales the image down, the ImageView scales it back up smoothly, which looks like a blur */
    private static Bitmap downscaleBlur(Bitmap source, int blur) {
        float factor = 1f + blur / 100f * 15f;
        int targetW = Math.max(8, Math.round(source.getWidth() / factor));
        int targetH = Math.max(8, Math.round(source.getHeight() / factor));
        Bitmap current = source;
        // Halving step by step keeps the result smooth, a single big step would alias
        while (current.getWidth() / 2 > targetW && current.getHeight() / 2 > targetH) {
            Bitmap next = Bitmap.createScaledBitmap(current, current.getWidth() / 2, current.getHeight() / 2, true);
            if (current != source) current.recycle();
            current = next;
        }
        Bitmap result = Bitmap.createScaledBitmap(current, targetW, targetH, true);
        if (current != source && current != result) current.recycle();
        return result;
    }

    private void startAnimation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        Drawable drawable = mImage.getDrawable();
        if (drawable instanceof AnimatedImageDrawable) {
            AnimatedImageDrawable animated = (AnimatedImageDrawable) drawable;
            animated.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
            animated.start();
        }
    }

    private void stopAnimation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        Drawable drawable = mImage.getDrawable();
        if (drawable instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) drawable).stop();
    }
}
