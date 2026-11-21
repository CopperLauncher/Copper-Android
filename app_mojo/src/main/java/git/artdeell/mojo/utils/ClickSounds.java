package git.artdeell.mojo.utils;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Bundle;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

import git.artdeell.mojo.R;
import git.artdeell.mojo.awt.AWTActivity;
import git.artdeell.mojo.LauncherActivity;
import git.artdeell.mojo.game.GameActivity;

/**
 * Plays the Nokor startup chime and a short click sound when a button or another clickable view is tapped in the launcher's
 * screens. The game and the AWT screens are left alone, and nothing plays when the system's own
 * "touch sounds" setting is off.
 */
public final class ClickSounds {
    private static SoundPool sPool;
    private static int sClickId;
    private static volatile boolean sLoaded;
    private static boolean sStartupPlayed;

    private ClickSounds() {}

    public static void init(Application application) {
        if (sPool != null) return;
        sPool = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        sPool.setOnLoadCompleteListener((pool, id, status) -> sLoaded = status == 0);
        sClickId = sPool.load(application, R.raw.nokor_click, 1);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { install(activity); }
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {
                if (activity instanceof LauncherActivity) playStartup(activity);
            }
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    private static void install(Activity activity) {
        if (activity instanceof GameActivity || activity instanceof AWTActivity) return;
        final Window window = activity.getWindow();
        final Window.Callback original = window.getCallback();
        if (original == null) return;
        final GestureDetector detector = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                View hit = findClickable(window.getDecorView(), (int) e.getRawX(), (int) e.getRawY());
                if (hit != null) {
                    // The view's own system click sound would play on top of ours
                    hit.setSoundEffectsEnabled(false);
                    play(activity);
                }
                return false;
            }
        });
        window.setCallback((Window.Callback) Proxy.newProxyInstance(
                Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class},
                (proxy, method, args) -> {
                    if ("dispatchTouchEvent".equals(method.getName()) && args != null
                            && args.length == 1 && args[0] instanceof MotionEvent) {
                        detector.onTouchEvent((MotionEvent) args[0]);
                    }
                    try {
                        return method.invoke(original, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }));
    }

    private static View findClickable(View view, int rawX, int rawY) {
        if (view == null || !view.isShown()) return null;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        if (rawX < loc[0] || rawX >= loc[0] + view.getWidth()
                || rawY < loc[1] || rawY >= loc[1] + view.getHeight()) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View found = findClickable(group.getChildAt(i), rawX, rawY);
                if (found != null) return found;
            }
        }
        return view.isClickable() && view.isEnabled() ? view : null;
    }

    private static void play(Context context) {
        if (!sLoaded || !SoundSettings.isEnabled(context)) return;
        if (Settings.System.getInt(context.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) == 0) return;
        float volume = SoundSettings.volume(context);
        if (volume <= 0f) return;
        sPool.play(sClickId, volume, volume, 1, 0, 1f);
    }

    /** The chime heard once, when the launcher's screen first shows up after the app starts */
    private static void playStartup(Context context) {
        if (sStartupPlayed) return;
        sStartupPlayed = true;
        if (!SoundSettings.isEnabled(context)) return;
        float volume = SoundSettings.volume(context);
        if (volume <= 0f) return;
        try {
            MediaPlayer player = MediaPlayer.create(context.getApplicationContext(), R.raw.nokor_startup,
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    android.media.AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (player == null) return;
            player.setVolume(volume, volume);
            player.setOnCompletionListener(MediaPlayer::release);
            player.setOnErrorListener((mp, what, extra) -> { mp.release(); return true; });
            player.start();
        } catch (RuntimeException ignored) {
            // A missing sound must never stop the launcher from opening
        }
    }
}
