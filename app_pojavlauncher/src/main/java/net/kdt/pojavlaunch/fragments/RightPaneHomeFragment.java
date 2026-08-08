package net.kdt.pojavlaunch.fragments;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.CenterCropVideoView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.io.IOException;

/**
 * Default content of the right pane in landscape two-pane mode.
 * Shows a custom background (if set) — a static or animated image, or a looping muted
 * video — otherwise a plain transparent pane. Wiki and Discord buttons are pinned at
 * the top.
 */
public class RightPaneHomeFragment extends Fragment {

    public static final String TAG = "RightPaneHomeFragment";
    /** File path where the custom launcher background media is stored. */
    public static final String CUSTOM_BG_PATH = Tools.DIR_DATA + "/custom_launcher_bg";

    /** SharedPreferences key: which kind of media CUSTOM_BG_PATH holds. */
    public static final String CUSTOM_BG_KIND_KEY = "custom_launcher_bg_kind";
    public static final String BG_KIND_IMAGE = "image";
    public static final String BG_KIND_VIDEO = "video";

    private CenterCropVideoView mVideoView;

    public RightPaneHomeFragment() {
        super(R.layout.fragment_right_pane_home);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        view.findViewById(R.id.news_button_pane).setOnClickListener(
                v -> Tools.openURL(requireActivity(), Tools.URL_HOME));

        view.findViewById(R.id.discord_button_pane).setOnClickListener(
                v -> Tools.openURL(requireActivity(), getString(R.string.discord_invite)));

        mVideoView = view.findViewById(R.id.right_pane_wallpaper_video);
        loadBackground(view);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // VideoView holds a MediaPlayer — release it explicitly, don't rely on GC.
        if (mVideoView != null) mVideoView.stopPlayback();
        mVideoView = null;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mVideoView != null) mVideoView.pause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mVideoView != null && isVideoBackgroundActive()) mVideoView.start();
    }

    private boolean isVideoBackgroundActive() {
        return mVideoView != null && mVideoView.getVisibility() == View.VISIBLE;
    }

    /**
     * Called after saving or removing a custom background so the pane
     * refreshes without needing a full fragment recreate.
     */
    public void reloadBackground() {
        View v = getView();
        if (v != null) loadBackground(v);
    }

    private void loadBackground(@NonNull View view) {
        ImageView wallpaper = view.findViewById(R.id.right_pane_wallpaper);
        File bgFile = new File(CUSTOM_BG_PATH);

        if (bgFile.exists()) {
            String kind = LauncherPreferences.DEFAULT_PREF
                    .getString(CUSTOM_BG_KIND_KEY, BG_KIND_IMAGE);
            if (BG_KIND_VIDEO.equals(kind)) {
                showVideoBackground(wallpaper, bgFile);
                return;
            }
            if (showImageBackground(wallpaper, bgFile)) {
                stopVideoBackground();
                return;
            }
            // Decoding failed (corrupt/unsupported file) — fall through to the
            // no-background state below instead of leaving a broken image up.
        }

        // No custom bg — show the gradient drawable as the pane background if gradient is on,
        // otherwise stay transparent (root fragment_launcher bg shows through).
        stopVideoBackground();
        loadNoBackgroundFallback(view);
    }

    /** Returns true if the image (static or animated) was decoded and shown successfully. */
    private boolean showImageBackground(@NonNull ImageView wallpaper, @NonNull File bgFile) {
        Drawable d = decodeImage(bgFile);
        if (d == null) return false;

        wallpaper.setImageDrawable(d);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaper.setBackground(null);
        wallpaper.setVisibility(View.VISIBLE);

        // ImageDecoder returns AnimatedImageDrawable paused — has to be started explicitly,
        // unlike Drawable.createFromPath's static-only result which needs no such call.
        // AnimatedImageDrawable is API 28+ (decodeImage() only ever produces one at P+,
        // via ImageDecoder) — the SDK check below is required, not just the instanceof,
        // because merely referencing the class on older API levels throws
        // NoClassDefFoundError as soon as this method runs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) d).start();
        }
        return true;
    }

    /**
     * Decodes a static OR animated image (GIF/WebP/etc). On API 28+, ImageDecoder is used,
     * which auto-detects animated content and returns a self-playing AnimatedImageDrawable
     * (once started). Below API 28, there's no built-in animated decoder, so this falls back
     * to Drawable.createFromPath, which only ever shows the first frame — a real limitation
     * on old devices without pulling in a third-party GIF library.
     */
    @Nullable
    private Drawable decodeImage(@NonNull File bgFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(bgFile);
                return ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                    // Wallpaper doesn't need alpha/scale tricks — default decode is fine.
                });
            } catch (IOException e) {
                return null;
            }
        }
        return Drawable.createFromPath(bgFile.getAbsolutePath());
    }

    private void showVideoBackground(@NonNull ImageView wallpaper, @NonNull File bgFile) {
        wallpaper.setVisibility(View.GONE);
        wallpaper.setImageDrawable(null);
        if (mVideoView == null) return;

        Uri videoUri = Uri.fromFile(bgFile);
        mVideoView.setVisibility(View.VISIBLE);
        mVideoView.setVideoURI(videoUri);
        mVideoView.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            mp.setVolume(0f, 0f); // background wallpaper — always muted
            mVideoView.setVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
            mVideoView.start();
        });
        mVideoView.setOnErrorListener((mp, what, extra) -> {
            // Corrupt/unsupported video — bail out to the no-background state
            // rather than leaving a broken black rectangle up.
            stopVideoBackground();
            View v = getView();
            if (v != null) loadNoBackgroundFallback(v);
            return true;
        });
    }

    private void stopVideoBackground() {
        if (mVideoView == null) return;
        mVideoView.stopPlayback();
        mVideoView.setVisibility(View.GONE);
    }

    private void loadNoBackgroundFallback(@NonNull View view) {
        ImageView wallpaper = view.findViewById(R.id.right_pane_wallpaper);
        wallpaper.setImageDrawable(null);
        TypedValue tv = new TypedValue();
        view.getContext().getTheme().resolveAttribute(R.attr.bgMainDrawable, tv, true);
        if (tv.resourceId != 0) {
            wallpaper.setBackgroundResource(tv.resourceId);
            wallpaper.setVisibility(View.VISIBLE);
        } else {
            wallpaper.setBackground(null);
            wallpaper.setVisibility(View.GONE);
        }
    }
}
