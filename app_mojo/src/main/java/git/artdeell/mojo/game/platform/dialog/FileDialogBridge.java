package git.artdeell.mojo.game.platform.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.media.ToneGenerator;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.mojo.sdl.SDLActivity;

import java.util.concurrent.CountDownLatch;
import java.lang.ref.WeakReference;

/**
 * Bridges desktop file/message dialogs (LWJGL tinyfd, LWJGL NFD, SDL3 file
 * dialogs) with the launcher UI.
 *
 * File requests are served by the built-in file manager
 * ({@link BuiltinFileDialog}), the Android counterpart of the tinyfd dialogs
 * on desktop: the caller receives real filesystem paths directly, so no
 * copying into the cache or write-back handling is needed. Message and input
 * dialogs are plain themed alerts.
 */
@Keep
public class FileDialogBridge {
    private static final String TAG = "FileDialogBridge";

    /** Must match the dialog types used by git.mojo.sdl.SDLActivity and SDL C code. */
    public static final int DIALOG_OPEN_FILE = 0;
    public static final int DIALOG_SAVE_FILE = 1;
    public static final int DIALOG_OPEN_FOLDER = 2;
    public static final int DIALOG_BROWSE = 3;

    /** tinyfd messageBox results. */
    public static final int MESSAGE_OK_YES_RETRY = 1;
    public static final int MESSAGE_NO = 2;
    public static final int MESSAGE_CANCEL = 0;

    private static WeakReference<Activity> sHostActivity;

    private FileDialogBridge() {
    }

    /** Called from native code on the Android runtime side to cache JNI references. */
    public static native void nativeInit();

    /**
     * Set the activity used to host file dialogs. Called from Platform init,
     * cleared implicitly by holding only a weak reference.
     */
    public static void setHostActivity(Activity activity) {
        sHostActivity = new WeakReference<>(activity);
    }

    /**
     * Close any dialog that is currently open so game threads waiting on a
     * dead activity do not hang forever. Called when the host activity is
     * destroyed.
     */
    public static void cancelAll() {
        BuiltinFileDialog.cancelCurrent();
    }

    public static void browseDirectory(String path) {
        Activity host = getHostActivity();
        if (host == null) {
            Log.w(TAG, "No host activity for file browser");
            return;
        }
        Tools.runOnUiThread(() -> {
            try {
                if (host.isFinishing()) return;
                BuiltinFileDialog.show(host, DIALOG_BROWSE, null, path, null, false, paths -> {});
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to show file browser", e);
            }
        });
    }

    // region Blocking entry points called from native code (tinyfd and NFD bridges)

    // Returns picked paths joined with '|', or null when cancelled.
    public static String openFileDialog(String title, String defaultPath,
                                        String[] filterPatterns, String singleFilterDescription,
                                        boolean allowMultiple) {
        String[] paths = awaitDialog(DIALOG_OPEN_FILE, title, defaultPath,
                filterPatterns, allowMultiple);
        if (paths == null || paths.length == 0) return null;
        return joinPaths(paths);
    }

    // Returns the path the game should write to, or null when cancelled.
    public static String saveFileDialog(String title, String defaultPath,
                                        String[] filterPatterns, String singleFilterDescription) {
        String[] paths = awaitDialog(DIALOG_SAVE_FILE, title, defaultPath,
                filterPatterns, false);
        if (paths == null || paths.length == 0) return null;
        return paths[0];
    }

    // Returns the picked directory path, or null when cancelled.
    public static String selectFolderDialog(String title, String defaultPath) {
        String[] paths = awaitDialog(DIALOG_OPEN_FOLDER, title, defaultPath,
                null, false);
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

    // Returns the picked color as "#RRGGBB", or null when cancelled.
    public static String colorPicker(String title, String defaultHex) {
        Activity host = getHostActivity();
        if (host == null) return null;
        String[] result = new String[1];
        CountDownLatch latch = new CountDownLatch(1);
        Tools.runOnUiThread(() -> {
            try {
                BuiltinFileDialog.showColorPicker(host, title, defaultHex, result, latch);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to show color picker", t);
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
     * Show a built-in file dialog for an SDL dialog request. The result is
     * reported through SDLActivity.onNativeFileDialog once ready.
     *
     * @param nativeRequestCode request code assigned by SDL native code
     * @return true when the dialog was shown
     */
    public static boolean launchSdlDialog(String[] filterPatterns, boolean allowMultiple,
                                          int type, String initialPath, int nativeRequestCode) {
        Activity host = getHostActivity();
        if (host == null) {
            Log.w(TAG, "No host activity for SDL file dialog");
            return false;
        }
        Tools.runOnUiThread(() -> {
            try {
                BuiltinFileDialog.show(host, type, null, initialPath, filterPatterns,
                        allowMultiple, paths -> {
                            String[] result = paths != null ? paths : new String[0];
                            try {
                                SDLActivity.onNativeFileDialog(nativeRequestCode, result, -1);
                            } catch (Throwable t) {
                                Log.e(TAG, "Failed to report SDL dialog result", t);
                            }
                        });
            } catch (Throwable t) {
                Log.e(TAG, "Failed to show SDL file dialog", t);
                reportSdlFailure(nativeRequestCode);
            }
        });
        return true;
    }

    // endregion

    // Tell SDL that a dialog never produced a result, so a new one can be
    // requested later. An empty list reads as a cancellation.
    private static void reportSdlFailure(int nativeRequestCode) {
        try {
            SDLActivity.onNativeFileDialog(nativeRequestCode, new String[0], -1);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to report SDL dialog failure", t);
        }
    }

    // Run a file dialog on the UI thread and wait for the result. A null
    // result means the dialog was cancelled.
    @Nullable
    private static String[] awaitDialog(int type, String title, String defaultPath,
                                        String[] filterPatterns, boolean allowMultiple) {
        Activity host = getHostActivity();
        if (host == null) {
            Log.w(TAG, "No host activity for file dialog");
            return null;
        }
        String[][] resultBox = new String[1][];
        CountDownLatch latch = new CountDownLatch(1);
        Tools.runOnUiThread(() -> {
            try {
                if (host.isFinishing()) throw new IllegalStateException("Host activity is finishing");
                BuiltinFileDialog.show(host, type, title, defaultPath, filterPatterns,
                        allowMultiple, paths -> {
                            resultBox[0] = paths;
                            latch.countDown();
                        });
            } catch (Throwable t) {
                Log.e(TAG, "Failed to show file dialog", t);
                latch.countDown();
            }
        });
        awaitLatch(latch);
        return resultBox[0];
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

    private static String joinPaths(String[] paths) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < paths.length; i++) {
            if (i != 0) builder.append('|');
            builder.append(paths[i]);
        }
        return builder.toString();
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
