package net.kdt.pojavlaunch;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

/**
 * Drives the "Waiting for a game window to appear…" overlay (view_game_loading_overlay.xml)
 * that's shown while the game's JVM process is starting up, loading assets/mods, and creating
 * its render window.
 *
 * There's no native signal for "the window is now visible", so this estimates progress and ETA
 * from a rolling average of how long previous launches took (persisted in SharedPreferences),
 * and additionally listens to the game log for a line that reliably shows up right around when
 * the window comes up, dismissing itself early if one is seen.
 */
public class GameLoadingOverlay {
    private static final String PREF_KEY_AVG_LOAD_MS = "gameLoadingAvgDurationMs";
    /** Used only for the very first launch ever, before we have any real data. */
    private static final long DEFAULT_ESTIMATE_MS = 25_000L;
    private static final long TICK_INTERVAL_MS = 200L;
    /** Never show 100% from the estimate alone; only an actual "ready" signal should complete it. */
    private static final int MAX_ESTIMATED_PROGRESS = 92;

    /** Log lines that reliably show up right around when the game window becomes visible. */
    private static final String[] WINDOW_READY_MARKERS = {
            "openal initialized",
            "sound engine started",
            "reloading resourcemanager"
    };

    private final View mRootView;
    private final ProgressBar mProgressBar;
    private final TextView mEtaText;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private long mStartTime = 0L;
    private long mEstimatedDurationMs = DEFAULT_ESTIMATE_MS;
    private boolean mShowing = false;

    private final Logger.eventLogListener mLogListener = this::onLogLine;

    private final Runnable mTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mShowing) return;
            tick();
            mHandler.postDelayed(this, TICK_INTERVAL_MS);
        }
    };

    public GameLoadingOverlay(View overlayRoot) {
        mRootView = overlayRoot;
        mProgressBar = overlayRoot.findViewById(R.id.game_loading_progressbar);
        mEtaText = overlayRoot.findViewById(R.id.game_loading_eta);
    }

    /** Show the overlay and start estimating progress/ETA based on past launch times. */
    public void show() {
        mEstimatedDurationMs = LauncherPreferences.DEFAULT_PREF.getLong(PREF_KEY_AVG_LOAD_MS, DEFAULT_ESTIMATE_MS);
        mStartTime = System.currentTimeMillis();
        mShowing = true;

        mRootView.animate().cancel();
        mRootView.setAlpha(1f);
        mRootView.setVisibility(View.VISIBLE);
        mProgressBar.setProgress(0);
        mEtaText.setText(R.string.game_loading_eta_estimating);

        Logger.addLogListener(mLogListener);
        mHandler.removeCallbacks(mTickRunnable);
        mHandler.post(mTickRunnable);
    }

    /** Hide the overlay, recording how long the launch actually took to improve future estimates. */
    public void hide() {
        if (!mShowing) return;
        mShowing = false;
        mHandler.removeCallbacks(mTickRunnable);
        Logger.removeLogListener(mLogListener);

        long elapsed = System.currentTimeMillis() - mStartTime;
        // Ignore implausibly short launches (e.g. resuming an already-running game) so they
        // don't skew the estimate used for real launches.
        if (elapsed > 1000) saveNewEstimate(elapsed);

        mProgressBar.setProgress(100);
        mRootView.animate()
                .alpha(0f)
                .setDuration(200)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mRootView.setVisibility(View.GONE);
                    }
                }).start();
    }

    /** Hide immediately, without animating and without recording a duration (used on error/exit). */
    public void hideImmediately() {
        if (!mShowing && mRootView.getVisibility() == View.GONE) return;
        mShowing = false;
        mHandler.removeCallbacks(mTickRunnable);
        Logger.removeLogListener(mLogListener);
        mRootView.animate().cancel();
        mRootView.setVisibility(View.GONE);
    }

    private void tick() {
        long elapsed = System.currentTimeMillis() - mStartTime;
        int percent = (int) Math.min(MAX_ESTIMATED_PROGRESS, (elapsed * 100) / Math.max(1, mEstimatedDurationMs));
        mProgressBar.setProgress(percent);

        long remainingMs = mEstimatedDurationMs - elapsed;
        if (remainingMs <= 0) {
            mEtaText.setText(R.string.game_loading_eta_almost_there);
        } else {
            long remainingSec = Math.max(1, remainingMs / 1000);
            mEtaText.setText(mRootView.getContext().getString(R.string.game_loading_eta_seconds, remainingSec));
        }
    }

    private void onLogLine(String line) {
        if (line == null) return;
        String lower = line.toLowerCase();
        for (String marker : WINDOW_READY_MARKERS) {
            if (lower.contains(marker)) {
                mHandler.post(this::hide);
                return;
            }
        }
    }

    private void saveNewEstimate(long elapsedMs) {
        // Simple exponential moving average, so one unusually slow/fast launch doesn't swing
        // the next ETA too hard.
        long previous = LauncherPreferences.DEFAULT_PREF.getLong(PREF_KEY_AVG_LOAD_MS, elapsedMs);
        long updated = Math.round(previous * 0.7 + elapsedMs * 0.3);
        LauncherPreferences.DEFAULT_PREF.edit().putLong(PREF_KEY_AVG_LOAD_MS, updated).apply();
    }
}
