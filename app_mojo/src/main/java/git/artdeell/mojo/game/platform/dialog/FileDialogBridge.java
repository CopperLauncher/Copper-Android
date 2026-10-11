package git.artdeell.mojo.game.platform.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.FileObserver;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Log;
import android.util.SparseArray;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.mojo.sdl.SDLActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bridges desktop file/message dialogs (LWJGL tinyfd, SDL3 file dialogs) with
 * the Android file manager (Storage Access Framework intents) and platform UI.
 *
 * Games expect POSIX paths, while SAF hands out content:// Uris. Open requests
 * therefore copy the picked documents into the app cache and hand the game the
 * copy paths. Save requests hand the game a scratch file and copy it back to
 * the user-picked Uri once the game closes it.
 */
@Keep
public class FileDialogBridge {
    private static final String TAG = "FileDialogBridge";

    /** Must match the dialog types used by git.mojo.sdl.SDLActivity and SDL C code. */
    public static final int DIALOG_OPEN_FILE = 0;
    public static final int DIALOG_SAVE_FILE = 1;
    public static final int DIALOG_OPEN_FOLDER = 2;

    /** tinyfd messageBox results. */
    public static final int MESSAGE_OK_YES_RETRY = 1;
    public static final int MESSAGE_NO = 2;
    public static final int MESSAGE_CANCEL = 0;

    private static final int REQUEST_CODE_BASE = 0xD100;
    private static final int REQUEST_CODE_MAX = 0xD1FF;

    private static WeakReference<Activity> sHostActivity;
    private static int sNextRequestCode = REQUEST_CODE_BASE;
    private static final Object sPendingLock = new Object();
    private static final SparseArray<PendingDialog> sPendingDialogs = new SparseArray<>();
    private static final ExecutorService sIoExecutor = Executors.newCachedThreadPool();
    /** Keeps save-dialog observers alive until they copy the file back. */
    private static final ArrayList<FileObserver> sSaveObservers = new ArrayList<>();

    private FileDialogBridge() {
    }

    /** Called from native code on the Android runtime side to cache JNI references. */
    public static native void nativeInit();

    /**
     * Set the activity used to launch file manager intents. Called from Platform
     * init, cleared implicitly by holding only a weak reference.
     */
    public static void setHostActivity(Activity activity) {
        sHostActivity = new WeakReference<>(activity);
    }

    /**
     * Release every thread waiting on a dialog. Called when the host activity is
     * destroyed so game threads cannot wait forever on a dead activity.
     */
    public static void cancelAll() {
        synchronized (sPendingLock) {
            for (int i = 0; i < sPendingDialogs.size(); i++) {
                PendingDialog pending = sPendingDialogs.valueAt(i);
                pending.cancelled = true;
                if (pending.latch != null) pending.latch.countDown();
            }
            sPendingDialogs.clear();
        }
    }

    // Called from GameActivity.onActivityResult. Returns true when the result
    // belongs to a bridged dialog.
    public static boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        PendingDialog pending;
        synchronized (sPendingLock) {
            pending = sPendingDialogs.get(requestCode);
            if (pending == null) return false;
            sPendingDialogs.remove(requestCode);
        }
        if (pending.latch != null) {
            // Blocking caller (tinyfd): copy off the UI thread, then wake it.
            sIoExecutor.execute(() -> {
                pending.resultUris = extractResultUris(pending, resultCode, data);
                pending.latch.countDown();
            });
        } else {
            // Async caller (SDL): copy, then report back through SDL bindings
            // on the UI thread, like stock SDL does.
            sIoExecutor.execute(() -> {
                String[] paths = resolveResultPaths(pending, extractResultUris(pending, resultCode, data));
                Tools.runOnUiThread(() -> {
                    try {
                        SDLActivity.onNativeFileDialog(pending.nativeRequestCode, paths, -1);
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed to report SDL dialog result", t);
                    }
                });
            });
        }
        return true;
    }

    // region Blocking entry points called from native code (tinyfd bridge)

    // Returns picked paths joined with '|', or null when cancelled.
    public static String openFileDialog(String title, String defaultPath,
                                        String[] filterPatterns, String singleFilterDescription,
                                        boolean allowMultiple) {
        PendingDialog pending = launchDialog(DIALOG_OPEN_FILE, title, defaultPath,
                filterPatterns, singleFilterDescription, allowMultiple, true, -1);
        if (pending == null) return null;
        String[] paths = resolveResultPaths(pending, awaitResult(pending));
        if (paths == null || paths.length == 0) return null;
        return joinPaths(paths);
    }

    // Returns the scratch path the game should write to, or null when cancelled.
    public static String saveFileDialog(String title, String defaultPath,
                                        String[] filterPatterns, String singleFilterDescription) {
        PendingDialog pending = launchDialog(DIALOG_SAVE_FILE, title, defaultPath,
                filterPatterns, singleFilterDescription, false, true, -1);
        if (pending == null) return null;
        String[] paths = resolveResultPaths(pending, awaitResult(pending));
        if (paths == null || paths.length == 0) return null;
        return paths[0];
    }

    // Returns a scratch directory path, or null when cancelled.
    public static String selectFolderDialog(String title, String defaultPath) {
        PendingDialog pending = launchDialog(DIALOG_OPEN_FOLDER, title, defaultPath,
                null, null, false, true, -1);
        if (pending == null) return null;
        String[] paths = resolveResultPaths(pending, awaitResult(pending));
        if (paths == null || paths.length == 0) return null;
        return paths[0];
    }

    public static int messageBox(String title, String message, String dialogType,
                                 String iconType, int defaultButton) {
        Activity host = getHostActivity();
        if (host == null) return MESSAGE_CANCEL;
        int[] result = new int[]{MESSAGE_CANCEL};
        CountDownLatch latch = new CountDownLatch(1);
        Tools.runOnUiThread(() -> {
            try {
                showMessageBox(host, title, message, dialogType, iconType, defaultButton, result, latch);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to show message box", t);
                latch.countDown();
            }
        });
        awaitLatch(latch);
        return result[0];
    }

    // Returns the entered text, or null when cancelled.
    public static String inputBox(String title, String message, String defaultInput, boolean password) {
        Activity host = getHostActivity();
        if (host == null) return null;
        String[] result = new String[1];
        CountDownLatch latch = new CountDownLatch(1);
        Tools.runOnUiThread(() -> {
            try {
                showInputBox(host, title, message, defaultInput, password, result, latch);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to show input box", t);
                latch.countDown();
            }
        });
        awaitLatch(latch);
        return result[0];
    }

    public static void notifyPopup(String title, String message, String iconType) {
        Activity host = getHostActivity();
        if (host == null) return;
        String text = message != null ? message : title;
        if (text == null) return;
        final String toastText = title != null && message != null ? title + ": " + message : text;
        Tools.runOnUiThread(() -> Toast.makeText(host, toastText, Toast.LENGTH_LONG).show());
    }

    public static void beep() {
        try {
            ToneGenerator toneGenerator = new ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100);
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
            toneGenerator.release();
        } catch (Throwable t) {
            Log.w(TAG, "Beep failed", t);
        }
    }

    // endregion

    // region Async entry point used by SDL file dialogs

    /**
     * Launch a file manager intent for an SDL dialog request. The result is
     * reported through SDLActivity.onNativeFileDialog once ready.
     *
     * @param nativeRequestCode request code assigned by SDL native code
     * @return true when the file manager intent was launched
     */
    public static boolean launchSdlDialog(String[] filterPatterns, boolean allowMultiple,
                                          int type, String initialPath, int nativeRequestCode) {
        PendingDialog pending = launchDialog(type, null, initialPath, filterPatterns,
                null, allowMultiple, false, nativeRequestCode);
        return pending != null;
    }

    // endregion

    private static class PendingDialog {
        final int type;
        final CountDownLatch latch;
        final int nativeRequestCode;
        volatile Uri[] resultUris;
        volatile boolean cancelled;

        PendingDialog(int type, boolean blocking, int nativeRequestCode) {
            this.type = type;
            this.latch = blocking ? new CountDownLatch(1) : null;
            this.nativeRequestCode = nativeRequestCode;
        }
    }

    // Launch the SAF intent on the UI thread. Returns the pending request, or
    // null when there is no host activity. For blocking callers the caller is
    // expected to wait via awaitResult(). Async (SDL) launch failures are
    // reported back through onNativeFileDialog so SDL does not get stuck
    // waiting for a dialog that will never answer.
    private static PendingDialog launchDialog(int type, String title, String defaultPath,
                                              String[] filterPatterns, String singleFilterDescription,
                                              boolean allowMultiple, boolean blocking,
                                              int nativeRequestCode) {
        Activity host = getHostActivity();
        if (host == null) {
            Log.w(TAG, "No host activity for file dialog");
            return null;
        }
        PendingDialog pending = new PendingDialog(type, blocking, nativeRequestCode);
        int requestCode;
        synchronized (sPendingLock) {
            requestCode = sNextRequestCode;
            sNextRequestCode++;
            if (sNextRequestCode > REQUEST_CODE_MAX) sNextRequestCode = REQUEST_CODE_BASE;
            sPendingDialogs.put(requestCode, pending);
        }
        final int dialogRequestCode = requestCode;
        Tools.runOnUiThread(() -> {
            try {
                if (host.isFinishing()) throw new IllegalStateException("Host activity is finishing");
                Intent intent = buildDialogIntent(type, defaultPath, filterPatterns, allowMultiple);
                if (intent == null) throw new IllegalStateException("Unsupported dialog type " + type);
                // The file manager has no title slot, so surface it as a hint.
                if (title != null && !title.isEmpty()) {
                    Toast.makeText(host, title, Toast.LENGTH_LONG).show();
                }
                host.startActivityForResult(intent, dialogRequestCode);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to launch file manager", t);
                synchronized (sPendingLock) {
                    sPendingDialogs.remove(dialogRequestCode);
                }
                pending.cancelled = true;
                if (pending.latch != null) {
                    pending.latch.countDown();
                } else if (pending.nativeRequestCode >= 0) {
                    reportSdlFailure(pending.nativeRequestCode);
                }
            }
        });
        return pending;
    }

    // Tell SDL that an async dialog never made it to the screen, so a new one
    // can be requested later. An empty list reads as a cancellation.
    private static void reportSdlFailure(int nativeRequestCode) {
        try {
            SDLActivity.onNativeFileDialog(nativeRequestCode, new String[0], -1);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to report SDL dialog failure", t);
        }
    }

    private static Uri[] awaitResult(PendingDialog pending) {
        if (pending.latch == null) return null;
        awaitLatch(pending.latch);
        if (pending.cancelled) return null;
        return pending.resultUris;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Activity getHostActivity() {
        return sHostActivity != null ? sHostActivity.get() : null;
    }

    private static Intent buildDialogIntent(int type, String defaultPath,
                                            String[] filterPatterns, boolean allowMultiple) {
        String action;
        switch (type) {
            case DIALOG_OPEN_FILE:
                action = Intent.ACTION_OPEN_DOCUMENT;
                break;
            case DIALOG_SAVE_FILE:
                action = Intent.ACTION_CREATE_DOCUMENT;
                allowMultiple = false;
                break;
            case DIALOG_OPEN_FOLDER:
                action = Intent.ACTION_OPEN_DOCUMENT_TREE;
                break;
            default:
                return null;
        }
        Intent intent = new Intent(action);
        if (type == DIALOG_OPEN_FOLDER) {
            return intent;
        }
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);
        String[] mimeTypes = patternsToMimeTypes(filterPatterns);
        if (mimeTypes.length == 0) {
            intent.setType("*/*");
        } else if (mimeTypes.length == 1) {
            intent.setType(mimeTypes[0]);
        } else {
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        }
        if (type == DIALOG_SAVE_FILE && defaultPath != null && !defaultPath.isEmpty()) {
            String fileName = defaultPath;
            int separator = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
            if (separator >= 0) fileName = fileName.substring(separator + 1);
            if (!fileName.isEmpty()) {
                intent.putExtra(Intent.EXTRA_TITLE, fileName);
                String extension = getExtension(fileName);
                if (extension != null) {
                    String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                    if (mime != null) intent.setType(mime);
                }
            }
        }
        return intent;
    }

    // Convert tinyfd/SDL filter patterns ("*.png", "*.jpg;*.jpeg", "*", "png")
    // into MIME types. Unknown extensions are skipped.
    private static String[] patternsToMimeTypes(String[] filterPatterns) {
        if (filterPatterns == null || filterPatterns.length == 0) return new String[0];
        MimeTypeMap mimeTypeMap = MimeTypeMap.getSingleton();
        ArrayList<String> mimeTypes = new ArrayList<>();
        for (String pattern : filterPatterns) {
            if (pattern == null) continue;
            for (String extension : pattern.split(";")) {
                extension = extension.trim().toLowerCase();
                while (extension.startsWith("*")) extension = extension.substring(1);
                while (extension.startsWith(".")) extension = extension.substring(1);
                if (extension.isEmpty() || extension.equals("*")) {
                    if (!mimeTypes.contains("*/*")) mimeTypes.add("*/*");
                    continue;
                }
                String mime = mimeTypeMap.getMimeTypeFromExtension(extension);
                if (mime != null && !mimeTypes.contains(mime)) mimeTypes.add(mime);
            }
        }
        return mimeTypes.toArray(new String[0]);
    }

    private static Uri[] extractResultUris(PendingDialog pending, int resultCode, Intent data) {
        if (pending.cancelled || data == null || resultCode != Activity.RESULT_OK) {
            return null;
        }
        try {
            Uri singleUri = data.getData();
            if (singleUri != null) {
                takePersistablePermission(pending, singleUri);
                return new Uri[]{singleUri};
            }
            ClipData clipData = data.getClipData();
            if (clipData == null) return null;
            ArrayList<Uri> uris = new ArrayList<>(clipData.getItemCount());
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri == null) continue;
                takePersistablePermission(pending, uri);
                uris.add(uri);
            }
            if (uris.isEmpty()) return null;
            return uris.toArray(new Uri[0]);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to parse file manager result", t);
            return null;
        }
    }

    private static void takePersistablePermission(PendingDialog pending, Uri uri) {
        if (pending.type == DIALOG_OPEN_FILE) return;
        Activity host = getHostActivity();
        if (host == null) return;
        try {
            host.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable t) {
            Log.w(TAG, "Persistable permission not granted for " + uri, t);
        }
    }

    // Copy picked documents into the app cache and return their paths. Save and
    // folder requests get scratch locations instead (see below).
    private static String[] resolveResultPaths(PendingDialog pending, Uri[] uris) {
        if (pending.cancelled || uris == null || uris.length == 0) return new String[0];
        Activity host = getHostActivity();
        if (host == null) return new String[0];
        if (pending.type == DIALOG_SAVE_FILE) {
            String path = prepareSaveFile(host, uris[0]);
            return path != null ? new String[]{path} : new String[0];
        }
        if (pending.type == DIALOG_OPEN_FOLDER) {
            String path = prepareFolder(host, uris[0]);
            return path != null ? new String[]{path} : new String[0];
        }
        ArrayList<String> paths = new ArrayList<>(uris.length);
        for (Uri uri : uris) {
            String path = copyToCache(host, uri);
            if (path != null) paths.add(path);
        }
        return paths.toArray(new String[0]);
    }

    private static String joinPaths(String[] paths) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < paths.length; i++) {
            if (i != 0) builder.append('|');
            builder.append(paths[i]);
        }
        return builder.toString();
    }

    private static File getDialogCacheDir(Activity host) {
        File dir = new File(host.getCacheDir(), "filedialog");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.e(TAG, "Failed to create file dialog cache dir");
            return null;
        }
        return dir;
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "picked_file";
        return name.replace('/', '_').replace('\\', '_').replace(File.separatorChar, '_');
    }

    private static String copyToCache(Activity host, Uri uri) {
        File dir = getDialogCacheDir(host);
        if (dir == null) return null;
        ContentResolver resolver = host.getContentResolver();
        String displayName = sanitizeFileName(queryDisplayName(host, uri));
        File target = uniqueFile(dir, System.currentTimeMillis() + "_" + displayName);
        try (InputStream in = resolver.openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                Log.e(TAG, "Cannot read picked file " + uri);
                if (!target.delete()) Log.w(TAG, "Failed to delete empty copy " + target);
                return null;
            }
            copyStream(in, out);
            return target.getAbsolutePath();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to copy picked file " + uri, t);
            if (!target.delete()) Log.w(TAG, "Failed to delete partial copy " + target);
            return null;
        }
    }

    // Hand the game a scratch file and copy it back to the user-picked Uri once
    // the game closes it. Returns null when the scratch file cannot be made.
    private static String prepareSaveFile(Activity host, Uri uri) {
        File dir = getDialogCacheDir(host);
        if (dir == null) return null;
        String displayName = sanitizeFileName(queryDisplayName(host, uri));
        if (displayName.equals("picked_file")) displayName = "saved_file";
        File target = uniqueFile(dir, "save_" + System.currentTimeMillis() + "_" + displayName);
        try {
            if (!target.createNewFile()) {
                Log.e(TAG, "Save scratch file already exists " + target);
                return null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to create save scratch file " + target, e);
            return null;
        }
        watchSaveFile(host, target, uri);
        return target.getAbsolutePath();
    }

    private static void watchSaveFile(Activity host, File target, Uri uri) {
        ContentResolver resolver = host.getContentResolver();
        final FileObserver[] observerRef = new FileObserver[1];
        FileObserver observer = new FileObserver(target.getAbsolutePath(), FileObserver.CLOSE_WRITE) {
            @Override
            public void onEvent(int event, @Nullable String path) {
                if (event != FileObserver.CLOSE_WRITE) return;
                boolean copied = false;
                try (InputStream in = new FileInputStream(target);
                     OutputStream out = resolver.openOutputStream(uri, "wt")) {
                    if (out == null) {
                        Log.e(TAG, "Cannot open picked save location " + uri);
                        return;
                    }
                    copyStream(in, out);
                    copied = true;
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to write back save file to " + uri, t);
                }
                // Games usually write a save path once; stop watching so the
                // observer and its inotify watch do not leak.
                if (copied) stopSaveObserver(observerRef[0]);
            }
        };
        observerRef[0] = observer;
        observer.startWatching();
        synchronized (sSaveObservers) {
            sSaveObservers.add(observer);
        }
    }

    private static void stopSaveObserver(FileObserver observer) {
        if (observer == null) return;
        observer.stopWatching();
        synchronized (sSaveObservers) {
            sSaveObservers.remove(observer);
        }
    }

    // Folder picks cannot be represented as plain paths without an expensive
    // recursive copy, so hand the game an empty scratch directory.
    private static String prepareFolder(Activity host, Uri uri) {
        File dir = getDialogCacheDir(host);
        if (dir == null) return null;
        File target = uniqueFile(dir, "folder_" + System.currentTimeMillis());
        if (!target.mkdir()) {
            Log.e(TAG, "Failed to create folder scratch dir " + target);
            return null;
        }
        return target.getAbsolutePath();
    }

    private static File uniqueFile(File dir, String name) {
        File file = new File(dir, name);
        int counter = 1;
        while (file.exists()) {
            file = new File(dir, name + "_" + counter);
            counter++;
        }
        return file;
    }

    private static String queryDisplayName(Activity host, Uri uri) {
        try (Cursor cursor = host.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column != -1) {
                    String name = cursor.getString(column);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to query display name for " + uri, t);
        }
        String segment = uri.getLastPathSegment();
        return segment != null ? segment : "picked_file";
    }

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        return fileName.substring(dot + 1).toLowerCase();
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    private static void showMessageBox(Activity host, String title, String message,
                                       String dialogType, String iconType, int defaultButton,
                                       int[] result, CountDownLatch latch) {
        AlertDialog.Builder builder = new AlertDialog.Builder(host);
        if (title != null) builder.setTitle(title);
        if (message != null) builder.setMessage(message);
        int icon = messageBoxIcon(iconType);
        if (icon != 0) builder.setIcon(icon);
        String type = dialogType != null ? dialogType.toLowerCase() : "ok";
        switch (type) {
            case "okcancel":
                builder.setPositiveButton(android.R.string.ok, (d, w) -> finishMessageBox(MESSAGE_OK_YES_RETRY, result, latch));
                builder.setNegativeButton(android.R.string.cancel, (d, w) -> finishMessageBox(MESSAGE_CANCEL, result, latch));
                break;
            case "yesno":
                builder.setPositiveButton(R.string.global_yes, (d, w) -> finishMessageBox(MESSAGE_OK_YES_RETRY, result, latch));
                builder.setNegativeButton(R.string.global_no, (d, w) -> finishMessageBox(MESSAGE_NO, result, latch));
                break;
            case "yesnocancel":
                builder.setPositiveButton(R.string.global_yes, (d, w) -> finishMessageBox(MESSAGE_OK_YES_RETRY, result, latch));
                builder.setNegativeButton(R.string.global_no, (d, w) -> finishMessageBox(MESSAGE_NO, result, latch));
                builder.setNeutralButton(android.R.string.cancel, (d, w) -> finishMessageBox(MESSAGE_CANCEL, result, latch));
                break;
            case "retrycancel":
                builder.setPositiveButton(R.string.global_retry, (d, w) -> finishMessageBox(MESSAGE_OK_YES_RETRY, result, latch));
                builder.setNegativeButton(android.R.string.cancel, (d, w) -> finishMessageBox(MESSAGE_CANCEL, result, latch));
                break;
            case "ok":
            default:
                builder.setPositiveButton(android.R.string.ok, (d, w) -> finishMessageBox(MESSAGE_OK_YES_RETRY, result, latch));
                break;
        }
        builder.setCancelable(true);
        builder.setOnCancelListener(d -> finishMessageBox(MESSAGE_CANCEL, result, latch));
        builder.show();
    }

    private static void finishMessageBox(int value, int[] result, CountDownLatch latch) {
        result[0] = value;
        latch.countDown();
    }

    private static int messageBoxIcon(String iconType) {
        if (iconType == null) return 0;
        switch (iconType.toLowerCase()) {
            case "warning":
                return android.R.drawable.ic_dialog_alert;
            case "error":
                return android.R.drawable.ic_dialog_alert;
            case "question":
                return android.R.drawable.ic_dialog_info;
            case "info":
                return android.R.drawable.ic_dialog_info;
            default:
                return 0;
        }
    }

    private static void showInputBox(Activity host, String title, String message,
                                     String defaultInput, boolean password,
                                     String[] result, CountDownLatch latch) {
        AlertDialog.Builder builder = new AlertDialog.Builder(host);
        if (title != null) builder.setTitle(title);
        if (message != null) builder.setMessage(message);
        EditText input = new EditText(host);
        input.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        if (defaultInput != null) {
            input.setText(defaultInput);
            input.setSelection(defaultInput.length());
        }
        builder.setView(input);
        builder.setPositiveButton(android.R.string.ok, (d, w) -> {
            result[0] = input.getText().toString();
            latch.countDown();
        });
        builder.setNegativeButton(android.R.string.cancel, (d, w) -> latch.countDown());
        builder.setCancelable(true);
        builder.setOnCancelListener(d -> latch.countDown());
        builder.show();
    }
}
