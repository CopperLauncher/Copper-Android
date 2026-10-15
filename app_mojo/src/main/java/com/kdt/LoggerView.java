package com.kdt;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.graphics.ColorUtils;

import git.artdeell.mojo.Logger;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.utils.ThemeColors;

/**
 * A class able to display logs to the user.
 * It has support for the Logger class
 */
public class LoggerView extends ConstraintLayout {
    /**
     * Max number of lines kept in the on-screen buffer. Without a cap, the backing text keeps
     * growing for the whole game session and every single append forces the TextView to re-lay
     * out an ever-larger block of text, so it gets progressively laggier the longer a heavy/
     * modded instance keeps logging. Trimming the oldest lines once this is exceeded keeps each
     * append's cost roughly constant instead of growing without bound.
     */
    private static final int MAX_LOG_LINES = 2000;
    /**
     * Incoming log lines are coalesced and flushed to the TextView on this interval instead of
     * doing one append (and one relayout/scroll) per line. Modded instances can log many lines
     * within a single frame, so batching cuts the number of UI updates drastically under load
     * while still feeling instant to the user.
     */
    private static final long BATCH_INTERVAL_MS = 150L;
    /** Alpha of the console background, so the game stays faintly visible behind the log */
    private static final int CONSOLE_BACKGROUND_ALPHA = 0xE6;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder mPendingLog = new StringBuilder();
    private final Object mPendingLock = new Object();
    private final Runnable mFlushRunnable = this::flushPendingLog;
    private boolean mFlushScheduled = false;
    private int mBufferedLineCount = 0;

    private Logger.eventLogListener mLogListener;
    private CompoundButton mLogToggle;
    private DefocusableScrollView mScrollView;
    private TextView mLogTextView;
    private TextView mEmptyStateView;


    public LoggerView(@NonNull Context context) {
        this(context, null);
    }

    public LoggerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        // Triggers the log view shown state by default when viewing it
        mLogToggle.setChecked(visibility == VISIBLE);
    }

    /**
     * Inflate the layout, and add component behaviors
     */
    private void init(){
        inflate(getContext(), R.layout.view_logger, this);

        // Material 3 surface colors, resolved from the theme so they follow light/dark mode
        // and the dynamic color palette. Translucent so the game stays faintly visible.
        findViewById(R.id.log_content_panel).setBackgroundColor(ColorUtils.setAlphaComponent(
                ThemeColors.get(getContext(), com.google.android.material.R.attr.colorSurfaceContainerLowest),
                CONSOLE_BACKGROUND_ALPHA));

        mLogTextView = findViewById(R.id.content_log_view);
        mLogTextView.setTypeface(Typeface.MONOSPACE);
        mLogTextView.setMaxLines(Integer.MAX_VALUE);
        mLogTextView.setEllipsize(null);
        mLogTextView.setVisibility(GONE);

        mEmptyStateView = findViewById(R.id.content_log_empty);

        // Toggle log visibility
        mLogToggle = findViewById(R.id.content_log_toggle_log);
        mLogToggle.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    mLogTextView.setVisibility(isChecked ? VISIBLE : GONE);
                    mEmptyStateView.setVisibility(isChecked ? GONE : VISIBLE);
                    if(isChecked) {
                        Logger.setLogListener(mLogListener);
                    }else{
                        clearPendingLog();
                        mLogTextView.setText("");
                        Logger.setLogListener(null); // Makes the JNI code be able to skip expensive logger callbacks
                        // NOTE: was tested by rapidly smashing the log on/off button, no sync issues found :)
                    }
                });
        mLogToggle.setChecked(false);

        // Remove the loggerView from the user View
        findViewById(R.id.log_view_cancel).setOnClickListener(view -> LoggerView.this.setVisibility(GONE));

        // Share the log file (mclo.gs upload or raw file share)
        findViewById(R.id.content_log_share).setOnClickListener(view -> Tools.shareLog(getContext()));

        // Set the scroll view
        mScrollView = findViewById(R.id.content_log_scroll);
        mScrollView.setKeepFocusing(true);
        // Jump straight to the bottom instead of an animated smooth-scroll - with a batch of new
        // lines landing every 150ms, an eased scroll never actually finishes before the next
        // batch retriggers it, which just wastes frames.
        mScrollView.setSmoothScrollingEnabled(false);

        //Set up the autoscroll switch
        CompoundButton autoscrollToggle = findViewById(R.id.content_log_toggle_autoscroll);
        autoscrollToggle.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    if(isChecked) mScrollView.fullScroll(View.FOCUS_DOWN);
                    mScrollView.setKeepFocusing(isChecked);
                }
        );
        autoscrollToggle.setChecked(true);

        // Listen to logs. This can be called rapidly from a background thread, so it only ever
        // does cheap buffering here - the actual TextView update is batched in flushPendingLog().
        mLogListener = text -> {
            if(mLogTextView.getVisibility() != VISIBLE) return;
            synchronized (mPendingLock) {
                mPendingLog.append(text).append('\n');
                if (mFlushScheduled) return;
                mFlushScheduled = true;
            }
            mHandler.postDelayed(mFlushRunnable, BATCH_INTERVAL_MS);
        };
    }

    /** Runs on the main thread: appends everything buffered since the last flush in one shot. */
    private void flushPendingLog() {
        String batch;
        synchronized (mPendingLock) {
            mFlushScheduled = false;
            if (mPendingLog.length() == 0) return;
            batch = mPendingLog.toString();
            mPendingLog.setLength(0);
        }
        mBufferedLineCount += countNewlines(batch);
        mLogTextView.append(batch);
        trimExcessLines();
        if (mScrollView.isKeepFocusing()) mScrollView.fullScroll(View.FOCUS_DOWN);
    }

    /** Drops the oldest lines once the buffer grows past {@link #MAX_LOG_LINES}. */
    private void trimExcessLines() {
        int excess = mBufferedLineCount - MAX_LOG_LINES;
        if (excess <= 0) return;
        CharSequence text = mLogTextView.getText();
        if (!(text instanceof Editable)) return;
        Editable editable = (Editable) text;

        int cut = -1, found = 0, len = editable.length();
        for (int i = 0; i < len; i++) {
            if (editable.charAt(i) == '\n') {
                found++;
                if (found == excess) {
                    cut = i + 1;
                    break;
                }
            }
        }
        if (cut > 0) {
            editable.delete(0, cut);
            mBufferedLineCount -= excess;
        }
    }

    /** Cancels any pending flush and drops buffered-but-not-yet-shown lines. */
    private void clearPendingLog() {
        mHandler.removeCallbacks(mFlushRunnable);
        synchronized (mPendingLock) {
            mFlushScheduled = false;
            mPendingLog.setLength(0);
        }
        mBufferedLineCount = 0;
    }

    private static int countNewlines(CharSequence s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') count++;
        }
        return count;
    }

}
