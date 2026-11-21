package git.artdeell.mojo.progresskeeper;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import git.artdeell.mojo.R;
import git.artdeell.mojo.utils.DownloadUtils;

/**
 * Keeps track of the content (mods, resource packs, shader packs, worlds, datapacks) downloads that
 * run at the same time, with what each one is downloading and how fast. Modpacks and the game
 * itself have their own progress and don't go through here.
 * <p>
 * The launcher shows an overview of the tasks in a dialog, and a single summary entry is kept in
 * {@link ProgressKeeper} so the launcher still knows a download is running.
 */
public final class ContentDownloadManager {
    public static final String PROGRESS_KEY = ProgressLayout.DOWNLOAD_CONTENT;

    /** How many content downloads run at once, the others wait for a free slot */
    private static final int PARALLEL_DOWNLOADS = 4;
    private static final long NOTIFY_INTERVAL_MS = 250;
    private static final long SPEED_WINDOW_MS = 600;

    public enum State { WAITING, DOWNLOADING, EXTRACTING }

    public interface Listener {
        /** Called on the main thread when a task starts, ends or makes progress */
        void onDownloadsChanged();
    }

    public static final class Task {
        public final long id;
        public final String title;
        private volatile State mState = State.WAITING;
        private volatile long mDone;
        private volatile long mTotal = -1;
        private volatile float mSpeed;
        private long mSampleTime = -1;
        private long mSampleBytes;

        private Task(long id, String title) {
            this.id = id;
            this.title = title;
        }

        public State getState() { return mState; }
        public long getDone() { return mDone; }
        public long getTotal() { return mTotal; }
        /** @return the speed in bytes per second, 0 when it is not known yet */
        public float getSpeed() { return mState == State.DOWNLOADING ? mSpeed : 0f; }

        /** Reports the bytes copied so far, call it as often as you like */
        public void update(long done, long total) {
            long now = SystemClock.elapsedRealtime();
            synchronized (this) {
                mState = State.DOWNLOADING;
                mDone = done;
                mTotal = total;
                if (mSampleTime < 0) {
                    mSampleTime = now;
                    mSampleBytes = done;
                } else if (now - mSampleTime >= SPEED_WINDOW_MS) {
                    float current = (done - mSampleBytes) * 1000f / (now - mSampleTime);
                    mSpeed = mSpeed == 0f ? current : mSpeed * 0.5f + current * 0.5f;
                    mSampleTime = now;
                    mSampleBytes = done;
                }
            }
            notifyChanged(false);
        }

        /** The file is down and is being unpacked now */
        public void setExtracting() {
            mState = State.EXTRACTING;
            mSpeed = 0f;
            notifyChanged(true);
        }

        /** Removes the task from the list, whether it worked or not */
        public void finish() {
            if (sTasks.remove(this)) notifyChanged(true);
        }
    }

    private static final List<Task> sTasks = new CopyOnWriteArrayList<>();
    private static final List<Listener> sListeners = new CopyOnWriteArrayList<>();
    private static final AtomicLong sIds = new AtomicLong();
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static final Object sNotifyLock = new Object();
    private static long sLastNotify;
    private static boolean sNotifyPending;
    private static boolean sSummaryActive;

    private static final ExecutorService sExecutor;
    static {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(PARALLEL_DOWNLOADS, PARALLEL_DOWNLOADS,
                30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        executor.allowCoreThreadTimeOut(true);
        sExecutor = executor;
    }

    private ContentDownloadManager() {}

    /** Registers a task that shows as waiting until it reports progress */
    @NonNull
    public static Task start(@NonNull String title) {
        Task task = new Task(sIds.incrementAndGet(), title);
        sTasks.add(task);
        notifyChanged(true);
        return task;
    }

    /** Runs the work on one of the download threads, the ones above the limit wait their turn */
    public static void run(@NonNull Runnable work) {
        sExecutor.execute(work);
    }

    /** Downloads a file as a task of its own, the task ends when the download does */
    public static void download(@NonNull String title, @NonNull String url, @NonNull java.io.File target) throws java.io.IOException {
        Task task = start(title);
        try {
            DownloadUtils.downloadFileTracked(url, target, task::update);
        } finally {
            task.finish();
        }
    }

    @NonNull
    public static List<Task> getTasks() {
        return new ArrayList<>(sTasks);
    }

    public static boolean hasTasks() {
        return !sTasks.isEmpty();
    }

    /** @return the speed of all the running downloads together, in bytes per second */
    public static float getTotalSpeed() {
        float speed = 0f;
        for (Task task : sTasks) speed += task.getSpeed();
        return speed;
    }

    public static void addListener(@NonNull Listener listener) {
        if (!sListeners.contains(listener)) sListeners.add(listener);
    }

    public static void removeListener(@NonNull Listener listener) {
        sListeners.remove(listener);
    }

    /** "2.4 MB" / "512 KB" */
    @NonNull
    public static String formatSize(long bytes) {
        if (bytes < 0) bytes = 0;
        if (bytes >= 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576f);
        if (bytes >= 1024L) return String.format(java.util.Locale.US, "%d KB", bytes / 1024L);
        return bytes + " B";
    }

    /** Sends the listeners an update, the frequent progress updates are thinned out */
    private static void notifyChanged(boolean immediately) {
        long now = SystemClock.elapsedRealtime();
        synchronized (sNotifyLock) {
            if (!immediately && now - sLastNotify < NOTIFY_INTERVAL_MS) {
                if (!sNotifyPending) {
                    sNotifyPending = true;
                    sMain.postDelayed(ContentDownloadManager::dispatch, NOTIFY_INTERVAL_MS);
                }
                return;
            }
            sLastNotify = now;
        }
        sMain.post(ContentDownloadManager::dispatch);
    }

    private static void dispatch() {
        synchronized (sNotifyLock) {
            sNotifyPending = false;
            sLastNotify = SystemClock.elapsedRealtime();
        }
        publishSummary();
        for (Listener listener : sListeners) listener.onDownloadsChanged();
    }

    /** Keeps one entry in ProgressKeeper for all the downloads, so the rest of the launcher sees that a task is running */
    private static void publishSummary() {
        if (sTasks.isEmpty()) {
            if (sSummaryActive) {
                sSummaryActive = false;
                ProgressKeeper.submitProgress(PROGRESS_KEY, -1, -1);
            }
            return;
        }
        long done = 0;
        long total = 0;
        for (Task task : sTasks) {
            if (task.getTotal() > 0) {
                done += task.getDone();
                total += task.getTotal();
            }
        }
        int percent = total > 0 ? (int) (done * 100L / total) : 0;
        sSummaryActive = true;
        ProgressKeeper.submitProgress(PROGRESS_KEY, percent, R.string.content_downloads_summary,
                sTasks.size(), formatSize((long) getTotalSpeed()));
    }

    @Nullable
    public static Task find(long id) {
        for (Task task : sTasks) if (task.id == id) return task;
        return null;
    }
}
