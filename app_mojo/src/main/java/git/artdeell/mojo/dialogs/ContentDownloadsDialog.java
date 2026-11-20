package git.artdeell.mojo.dialogs;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import git.artdeell.mojo.R;
import git.artdeell.mojo.progresskeeper.ContentDownloadManager;
import git.artdeell.mojo.progresskeeper.ContentDownloadManager.Task;
import git.artdeell.mojo.progresskeeper.ProgressKeeper;

/**
 * Lists what the launcher is working on: the game and the runtime that are downloading or
 * unpacking, modpack installs, and the content downloads with their size and speed
 */
public final class ContentDownloadsDialog implements ContentDownloadManager.Listener, Runnable {
    private static final long REFRESH_INTERVAL_MS = 250;

    private final Context mContext;
    private final View mView;
    private final TextView mTotalSpeed;
    private final TextView mEmpty;
    private final LinearLayout mList;
    private final Map<String, View> mRows = new HashMap<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mRefreshPending;

    private ContentDownloadsDialog(Context context) {
        mContext = context;
        mView = LayoutInflater.from(context).inflate(R.layout.dialog_content_downloads, null);
        mTotalSpeed = mView.findViewById(R.id.downloads_total_speed);
        mEmpty = mView.findViewById(R.id.downloads_empty);
        mList = mView.findViewById(R.id.downloads_list);
    }

    public static void show(@NonNull Context context) {
        ContentDownloadsDialog content = new ContentDownloadsDialog(context);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.content_downloads_title)
                .setView(content.mView)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnDismissListener(d -> {
            ContentDownloadManager.removeListener(content);
            ProgressKeeper.removeAnyProgressListener(content);
            content.mHandler.removeCallbacksAndMessages(null);
        });
        content.onDownloadsChanged();
        ContentDownloadManager.addListener(content);
        ProgressKeeper.addAnyProgressListener(content);
        dialog.show();
    }

    /** Called from the thread that reports progress, the refreshes are thinned out and run on the main thread */
    @Override
    public void run() {
        mHandler.post(() -> {
            if (mRefreshPending) return;
            mRefreshPending = true;
            mHandler.postDelayed(() -> {
                mRefreshPending = false;
                onDownloadsChanged();
            }, REFRESH_INTERVAL_MS);
        });
    }

    @Override
    public void onDownloadsChanged() {
        List<Task> tasks = ContentDownloadManager.getTasks();
        List<ProgressKeeper.Entry> entries = new ArrayList<>();
        for (ProgressKeeper.Entry entry : ProgressKeeper.getEntries()) {
            // The content downloads are listed one by one below, this entry only sums them up
            if (!ContentDownloadManager.PROGRESS_KEY.equals(entry.key)) entries.add(entry);
        }
        boolean nothing = tasks.isEmpty() && entries.isEmpty();
        mTotalSpeed.setText(mContext.getString(R.string.content_downloads_speed,
                ContentDownloadManager.formatSize((long) ContentDownloadManager.getTotalSpeed())));
        mTotalSpeed.setVisibility(tasks.isEmpty() ? View.GONE : View.VISIBLE);
        mEmpty.setVisibility(nothing ? View.VISIBLE : View.GONE);

        Set<String> alive = new HashSet<>();
        for (ProgressKeeper.Entry entry : entries) alive.add("k:" + entry.key);
        for (Task task : tasks) alive.add("t:" + task.id);
        for (String id : new ArrayList<>(mRows.keySet())) {
            if (!alive.contains(id)) mList.removeView(mRows.remove(id));
        }
        for (ProgressKeeper.Entry entry : entries) {
            View row = row("k:" + entry.key);
            bindEntry(row, entry);
        }
        for (Task task : tasks) {
            View row = row("t:" + task.id);
            ((TextView) row.findViewById(R.id.download_title)).setText(task.title);
            bind(row, task);
        }
    }

    private View row(String id) {
        View row = mRows.get(id);
        if (row == null) {
            row = LayoutInflater.from(mContext).inflate(R.layout.item_content_download, mList, false);
            mRows.put(id, row);
            mList.addView(row);
        }
        return row;
    }

    /** A task of the launcher itself, like downloading the game */
    private void bindEntry(View row, ProgressKeeper.Entry entry) {
        TextView title = row.findViewById(R.id.download_title);
        TextView info = row.findViewById(R.id.download_info);
        LinearProgressIndicator progress = row.findViewById(R.id.download_progress);
        String text;
        if (entry.resid != -1) text = mContext.getString(entry.resid, entry.args);
        else if (entry.args.length > 0 && entry.args[0] != null) text = String.valueOf(entry.args[0]);
        else text = "";
        title.setText(text);
        boolean known = entry.progress >= 0;
        info.setVisibility(known ? View.VISIBLE : View.GONE);
        if (known) info.setText(mContext.getString(R.string.content_downloads_percent, Math.min(entry.progress, 100)));
        setIndeterminate(progress, !known, Math.min(Math.max(entry.progress, 0), 100));
    }

    private void bind(View row, Task task) {
        TextView info = row.findViewById(R.id.download_info);
        LinearProgressIndicator progress = row.findViewById(R.id.download_progress);
        long total = task.getTotal();
        switch (task.getState()) {
            case WAITING:
                info.setText(R.string.content_downloads_waiting);
                setIndeterminate(progress, true, 0);
                break;
            case EXTRACTING:
                info.setText(R.string.content_downloads_extracting);
                setIndeterminate(progress, true, 0);
                break;
            default:
                String size = total > 0
                        ? mContext.getString(R.string.content_downloads_size,
                                ContentDownloadManager.formatSize(task.getDone()), ContentDownloadManager.formatSize(total))
                        : mContext.getString(R.string.content_downloads_size_unknown,
                                ContentDownloadManager.formatSize(task.getDone()));
                float speed = task.getSpeed();
                String line = speed > 0f
                        ? size + " · " + mContext.getString(R.string.content_downloads_speed,
                                ContentDownloadManager.formatSize((long) speed))
                        : size;
                info.setText(line);
                setIndeterminate(progress, total <= 0, total > 0 ? (int) (task.getDone() * 100L / total) : 0);
                break;
        }
    }

    private static void setIndeterminate(LinearProgressIndicator progress, boolean indeterminate, int percent) {
        if (progress.isIndeterminate() != indeterminate) progress.setIndeterminate(indeterminate);
        if (!indeterminate) progress.setProgressCompat(percent, true);
    }
}
