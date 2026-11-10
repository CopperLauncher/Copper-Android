package com.kdt.mcgui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
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
import git.artdeell.mojo.utils.LauncherBackground;

import java.io.File;

/**
 * The custom launcher background, drawn behind the whole launcher: an image, an animated GIF or a
 * looping video, optionally blurred. Android 12+ blurs everything with a render effect. Older
 * versions blur still images by scaling them down and dim videos and animated images instead.
 */
public class LauncherWallpaperView extends FrameLayout {
    private static final float MAX_BLUR_RADIUS_PX = 40f;
    private static final float MAX_DIM_ALPHA = 0.6f;

    private final ImageView mImage;
    private final CenterCropVideoView mVideo;
    private final View mScrim;

    private Drawable mDecoded;
    private boolean mHasMedia;
    private boolean mIsVideo;
    private boolean mActive = true;
    private int mLoadId;
    private String mStamp;

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefListener = (prefs, key) -> {
        if (LauncherBackground.PREF_BLUR.equals(key)) post(this::applyBlur);
        else if (LauncherBackground.PREF_KIND.equals(key)) post(this::reloadIfChanged);
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
    }

    /** Only the main menu shows the wallpaper, the other screens cover it, so nothing has to play behind them */
    public void setActive(boolean active) {
        if (mActive == active) return;
        mActive = active;
        if (active) reloadIfChanged();
        applyState();
    }

    private String currentStamp() {
        String kind = LauncherBackground.getKind(getContext());
        if (kind == null) return "none";
        return kind + ":" + LauncherBackground.getFile().lastModified();
    }

    /** Loads the background again if it was replaced or removed since it was loaded */
    public void reloadIfChanged() {
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
            post(() -> {
                if (loadId != mLoadId) return;
                if (drawable == null) {
                    clear();
                    return;
                }
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
        boolean show = mHasMedia && mActive;
        setVisibility(show ? VISIBLE : GONE);
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
        int blur = LauncherBackground.getBlur(getContext());
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
        if (!mIsVideo) mImage.setImageDrawable(mDecoded);
        mScrim.setAlpha(blur / 100f * MAX_DIM_ALPHA);
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
