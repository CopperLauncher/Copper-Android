package git.artdeell.mojo.progresskeeper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ProgressKeeper {
    private static final HashMap<String, List<ProgressListener>> sProgressListeners = new HashMap<>();
    private static final HashMap<String, ProgressState> sProgressStates = new HashMap<>();
    private static final List<TaskCountListener> sTaskCountListeners = new ArrayList<>();
    private static final List<Runnable> sAnyProgressListeners = new CopyOnWriteArrayList<>();

    /** A task that reports its progress here, as it stands now */
    public static final class Entry {
        public final String key;
        /** 0 to 100, or -1 when it does not know how far it is */
        public final int progress;
        /** String resource of the text, -1 when the first of the arguments is the text */
        public final int resid;
        public final Object[] args;

        private Entry(String key, ProgressState state) {
            this.key = key;
            this.progress = state.progress;
            this.resid = state.resid;
            this.args = state.varArg == null ? new Object[0] : state.varArg;
        }
    }

    /** @return the tasks that run now, in no particular order */
    public static synchronized List<Entry> getEntries() {
        List<Entry> entries = new ArrayList<>();
        for(java.util.Map.Entry<String, ProgressState> state : sProgressStates.entrySet()) {
            entries.add(new Entry(state.getKey(), state.getValue()));
        }
        return entries;
    }

    /** The listener runs, on the thread that reports it, whenever any task starts, ends or makes progress */
    public static void addAnyProgressListener(Runnable listener) {
        if(!sAnyProgressListeners.contains(listener)) sAnyProgressListeners.add(listener);
    }

    public static void removeAnyProgressListener(Runnable listener) {
        sAnyProgressListeners.remove(listener);
    }

    public static synchronized void submitProgress(String progressRecord, int progress, int resid, Object... va) {
        ProgressState progressState = sProgressStates.get(progressRecord);
        boolean shouldCallStarted = progressState == null;
        boolean shouldCallEnded = resid == -1 && progress == -1;
        if(shouldCallEnded) {
            shouldCallStarted = false;
            sProgressStates.remove(progressRecord);
        }else if(shouldCallStarted){
            sProgressStates.put(progressRecord, (progressState = new ProgressState()));
        }
        if(shouldCallEnded || shouldCallStarted) updateTaskCount(sProgressStates.size());
        if(progressState != null) {
            progressState.progress = progress;
            progressState.resid = resid;
            progressState.varArg = va;
        }

        List<ProgressListener> progressListeners = sProgressListeners.get(progressRecord);
        if(progressListeners != null)
            for(ProgressListener listener : progressListeners) {
                    if(shouldCallStarted) listener.onProgressStarted();
                    else if(shouldCallEnded) listener.onProgressEnded();
                    else listener.onProgressUpdated(progress, resid, va);
            }
        for(Runnable listener : sAnyProgressListeners) listener.run();
    }

    private static void updateTaskCount(int count) {
        synchronized (sTaskCountListeners) {
            Iterator<TaskCountListener> iterator = sTaskCountListeners.iterator();
            while(iterator.hasNext()) {
                if(iterator.next().onUpdateTaskCount(count)) iterator.remove();
            }
        }
    }

    public static synchronized boolean hasProgressKey(String key) {
        return sProgressStates.get(key) != null;
    }

    public static synchronized void addListener(String progressRecord, ProgressListener listener) {
        ProgressState state = sProgressStates.get(progressRecord);
        if(state != null && (state.resid != -1 || state.progress != -1)) {
            listener.onProgressStarted();
            listener.onProgressUpdated(state.progress, state.resid, state.varArg);
        }else{
            listener.onProgressEnded();
        }
        List<ProgressListener> listenerWeakReferenceList = sProgressListeners.get(progressRecord);
        if(listenerWeakReferenceList == null) sProgressListeners.put(progressRecord, (listenerWeakReferenceList = new ArrayList<>()));
        listenerWeakReferenceList.add(listener);
    }

    public static synchronized void removeListener(String progressRecord, ProgressListener listener) {
        List<ProgressListener> listenerWeakReferenceList = sProgressListeners.get(progressRecord);
        if(listenerWeakReferenceList != null) listenerWeakReferenceList.remove(listener);
    }

    public static void addTaskCountListener(TaskCountListener listener) {
        addTaskCountListener(listener, true);
    }
    public static void addTaskCountListener(TaskCountListener listener, boolean runUpdate) {
        if(runUpdate) synchronized (ProgressKeeper.class) {
            listener.onUpdateTaskCount(sProgressStates.size());
        }
        synchronized (sTaskCountListeners) {
            if(!sTaskCountListeners.contains(listener)) sTaskCountListeners.add(listener);
        }
    }
    public static void removeTaskCountListener(TaskCountListener listener) {
        synchronized (sTaskCountListeners) {
            sTaskCountListeners.remove(listener);
        }
    }

    /**
     * Waits until all tasks are done and runs the runnable, or if there were no pending process remaining
     * The runnable runs from the thread that updated the task count last, and it might be the UI thread,
     * so don't put long running processes in it
     * @param runnable the runnable to run when no tasks are remaining
     */
    public static void waitUntilDone(final Runnable runnable) {
        // If we do it the other way the listener would be removed before it was added, which will cause a listener object leak
        if(getTaskCount() == 0) {
            runnable.run();
            return;
        }
        TaskCountListener listener = taskCount -> {
            if(taskCount == 0) {
                runnable.run();
                return true;
            }
            return false;
        };
        addTaskCountListener(listener);
    }

    /**
     * @param taskCount a task count that was reported by this class
     * @return the count without the content downloads. They run next to everything else, so the
     * screens that browse content must not lock themselves while one runs
     */
    public static synchronized int withoutContentDownloads(int taskCount) {
        return sProgressStates.containsKey(ContentDownloadManager.PROGRESS_KEY) ? taskCount - 1 : taskCount;
    }

    public static synchronized int getTaskCount() {
        return sProgressStates.size();
    }

    public static boolean hasOngoingTasks() {
        return getTaskCount() > 0;
    }
}
