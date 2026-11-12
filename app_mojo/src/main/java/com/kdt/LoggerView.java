package com.kdt;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import git.artdeell.mojo.Logger;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.utils.ThemeColors;

/**
 * A class able to display logs to the user.
 * It has support for the Logger class.
 * Works like a terminal: only the visible lines are laid out, the history is a capped ring
 * buffer and every line is length-limited, so a flood of output can never exhaust memory or
 * grow a single view past the GPU texture limit.
 */
public class LoggerView extends ConstraintLayout {
    /** Max number of lines kept in the buffer. The oldest lines are dropped past this. */
    private static final int MAX_LOG_LINES = 5000;
    /** Longer lines are cut so one huge line cannot blow up a single row. */
    private static final int MAX_LINE_LENGTH = 2000;
    /** Max number of lines taken from one flush. If more arrive, only the newest are kept. */
    private static final int MAX_LINES_PER_FLUSH = MAX_LOG_LINES;
    /** Hard cap on text waiting for the next flush, so a burst cannot eat memory. */
    private static final int MAX_PENDING_LINES = 20000;
    private static final long BATCH_INTERVAL_MS = 100L;
    /** Alpha of the console background, so the game stays faintly visible behind the log */
    private static final int CONSOLE_BACKGROUND_ALPHA = 0xE6;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> mPendingLines = new ArrayDeque<>();
    private final Object mPendingLock = new Object();
    private final Runnable mFlushRunnable = this::flushPendingLog;
    private boolean mFlushScheduled = false;

    private final ArrayDeque<String> mLines = new ArrayDeque<>();
    private final LogAdapter mAdapter = new LogAdapter();
    private boolean mAutoScroll = true;

    private Logger.eventLogListener mLogListener;
    private CompoundButton mLogToggle;
    private RecyclerView mLogList;
    private LinearLayoutManager mLayoutManager;
    private TextView mEmptyStateView;
    private boolean mLogShown = false;


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

        findViewById(R.id.log_content_panel).setBackgroundColor(ColorUtils.setAlphaComponent(
                ThemeColors.get(getContext(), com.google.android.material.R.attr.colorSurfaceContainerLowest),
                CONSOLE_BACKGROUND_ALPHA));

        mLogList = findViewById(R.id.content_log_list);
        mLayoutManager = new LinearLayoutManager(getContext());
        mLogList.setLayoutManager(mLayoutManager);
        mLogList.setAdapter(mAdapter);
        mLogList.setItemAnimator(null);
        mLogList.setHasFixedSize(false);
        mLogList.setVisibility(GONE);

        mEmptyStateView = findViewById(R.id.content_log_empty);

        // Toggle log visibility
        mLogToggle = findViewById(R.id.content_log_toggle_log);
        mLogToggle.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    mLogShown = isChecked;
                    mLogList.setVisibility(isChecked ? VISIBLE : GONE);
                    mEmptyStateView.setVisibility(isChecked ? GONE : VISIBLE);
                    if(isChecked) {
                        Logger.setLogListener(mLogListener);
                    }else{
                        Logger.setLogListener(null); // Makes the JNI code be able to skip expensive logger callbacks
                        clearLog();
                    }
                });
        mLogToggle.setChecked(false);

        // Remove the loggerView from the user View
        findViewById(R.id.log_view_cancel).setOnClickListener(view -> LoggerView.this.setVisibility(GONE));

        // Share the log file (mclo.gs upload or raw file share)
        findViewById(R.id.content_log_share).setOnClickListener(view -> Tools.shareLog(getContext()));

        //Set up the autoscroll switch
        CompoundButton autoscrollToggle = findViewById(R.id.content_log_toggle_autoscroll);
        autoscrollToggle.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    mAutoScroll = isChecked;
                    if(isChecked) scrollToEnd();
                }
        );
        autoscrollToggle.setChecked(true);

        // Listen to logs. This can be called rapidly from a background thread, so it only ever
        // buffers here, and the list update is batched in flushPendingLog().
        mLogListener = text -> {
            if(!mLogShown || text == null) return;
            synchronized (mPendingLock) {
                for (String line : text.split("\n", -1)) {
                    mPendingLines.addLast(limitLine(line));
                    if (mPendingLines.size() > MAX_PENDING_LINES) mPendingLines.pollFirst();
                }
                if (mFlushScheduled) return;
                mFlushScheduled = true;
            }
            mHandler.postDelayed(mFlushRunnable, BATCH_INTERVAL_MS);
        };
    }

    private static String limitLine(String line) {
        if (line.length() <= MAX_LINE_LENGTH) return line;
        return line.substring(0, MAX_LINE_LENGTH) + "…";
    }

    /** Runs on the main thread: moves everything buffered since the last flush into the list. */
    private void flushPendingLog() {
        List<String> batch;
        synchronized (mPendingLock) {
            mFlushScheduled = false;
            if (mPendingLines.isEmpty()) return;
            while (mPendingLines.size() > MAX_LINES_PER_FLUSH) mPendingLines.pollFirst();
            batch = new ArrayList<>(mPendingLines);
            mPendingLines.clear();
        }
        if (!mLogShown) return;

        int removed = 0;
        for (String line : batch) {
            mLines.addLast(line);
            if (mLines.size() > MAX_LOG_LINES) {
                mLines.pollFirst();
                removed++;
            }
        }
        int added = batch.size();
        if (removed > 0) {
            // Everything shifted, rebind what is on screen instead of animating thousands of rows
            mAdapter.dataChanged();
        } else {
            mAdapter.rangeInserted(mLines.size() - added, added);
        }
        if (mAutoScroll) scrollToEnd();
    }

    private void scrollToEnd() {
        int count = mAdapter.getItemCount();
        if (count > 0) mLogList.scrollToPosition(count - 1);
    }

    /** Cancels any pending flush and drops every line, buffered or shown. */
    private void clearLog() {
        mHandler.removeCallbacks(mFlushRunnable);
        synchronized (mPendingLock) {
            mFlushScheduled = false;
            mPendingLines.clear();
        }
        mLines.clear();
        mAdapter.dataChanged();
    }

    private class LogAdapter extends RecyclerView.Adapter<LogAdapter.LineHolder> {
        private String[] mSnapshot = new String[0];

        String get(int position) {
            return mSnapshot[position];
        }

        private void rebuildSnapshot() {
            mSnapshot = mLines.toArray(new String[0]);
        }

        void dataChanged() {
            rebuildSnapshot();
            notifyDataSetChanged();
        }

        void rangeInserted(int positionStart, int itemCount) {
            rebuildSnapshot();
            notifyItemRangeInserted(positionStart, itemCount);
        }

        @NonNull
        @Override
        public LineHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setLineSpacing(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2,
                    parent.getResources().getDisplayMetrics()), 1f);
            tv.setTextColor(ThemeColors.get(parent.getContext(),
                    com.google.android.material.R.attr.colorOnSurface));
            tv.setOnLongClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) v.getContext()
                        .getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("log", ((TextView) v).getText()));
                    Toast.makeText(v.getContext(), android.R.string.copy, Toast.LENGTH_SHORT).show();
                }
                return true;
            });
            return new LineHolder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull LineHolder holder, int position) {
            holder.text.setText(get(position));
        }

        @Override
        public int getItemCount() {
            return mLines.size();
        }

        class LineHolder extends RecyclerView.ViewHolder {
            final TextView text;
            LineHolder(@NonNull TextView itemView) {
                super(itemView);
                text = itemView;
            }
        }
    }
}
