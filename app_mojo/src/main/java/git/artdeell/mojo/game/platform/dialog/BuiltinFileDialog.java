package git.artdeell.mojo.game.platform.dialog;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Built-in file manager dialog, the Android counterpart of the tinyfd dialogs
 * on desktop: a plain filesystem browser returning real paths, with the usual
 * open/save/folder flavors. Files can be filtered by extension, multiple files
 * can be selected and save requests get a name field.
 */
public final class BuiltinFileDialog {
    private static final String TAG = "BuiltinFileDialog";

    /** Request code for shared storage access, only triggered when the user picks a shared external root. */
    private static final int REQ_SHARED_STORAGE = 0xC0DE;

    private static WeakReference<Dialog> sCurrentDialog;
    private static WeakReference<ResultCallback> sCurrentCallback;

    /** Pending shared-storage navigation, cleared once the permission request completes. */
    private static Activity pendingSharedHost;
    private static State pendingSharedState;
    private static File pendingSharedDir;

    private BuiltinFileDialog() {}

    /** Receives the picked paths, or null when the dialog was cancelled. */
    public interface ResultCallback {
        void onResult(@Nullable String[] paths);
    }

    private static boolean isSharedExternal(Activity host, File directory) {
        String abs = directory.getAbsolutePath();
        File[] appDirectories = host.getExternalFilesDirs(null);
        if (appDirectories != null) {
            for (File appDirectory : appDirectories) {
                if (appDirectory != null) {
                    String appPath = appDirectory.getAbsolutePath();
                    if (abs.equals(appPath) || abs.startsWith(appPath + File.separator)) return false;
                }
            }
        }
        for (File root : sharedStorageRoots(host)) {
            String rootPath = root.getAbsolutePath();
            if (abs.equals(rootPath) || abs.startsWith(rootPath + File.separator)) return true;
        }
        return false;
    }

    private static boolean sharedStoragePermissionGranted(Activity host) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return android.os.Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(host, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(host, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void requestSharedStorage(Activity host, State state, File directory) {
        pendingSharedHost = host;
        pendingSharedState = state;
        pendingSharedDir = directory;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent appSettings = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + host.getPackageName()));
            try {
                host.startActivity(appSettings);
            } catch (ActivityNotFoundException e) {
                try {
                    host.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (ActivityNotFoundException fallbackError) {
                    fallbackError.addSuppressed(e);
                    clearPendingSharedStorage();
                    Log.e(TAG, "Unable to open all-files access settings", fallbackError);
                    Toast.makeText(host, R.string.file_manager_storage_permission, Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        ActivityCompat.requestPermissions(host,
            new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
            REQ_SHARED_STORAGE);
    }

    /**
     * Show a file manager dialog of the given FileDialogBridge type. The
     * callback is invoked exactly once, from the UI thread.
     */
    public static void show(@NonNull Activity host, int type, @Nullable String title,
                            @Nullable String defaultPath, @Nullable String[] filterPatterns,
                            boolean allowMultiple, @NonNull ResultCallback callback) {
        if (type == FileDialogBridge.DIALOG_SAVE_FILE) allowMultiple = false;
        List<String> extensions = parseFilterPatterns(filterPatterns);
        Dialog dialog = new Dialog(host);
        final boolean[] delivered = {false};
        dialog.setOnDismissListener(d -> {
            // Back button and outside touches read as cancellation.
            if (!delivered[0]) {
                delivered[0] = true;
                clearCurrent(dialog);
                callback.onResult(null);
            }
        });
        View content = buildContent(dialog, host, type, title, defaultPath, extensions,
                allowMultiple, paths -> {
                    delivered[0] = true;
                    clearCurrent(dialog);
                    dialog.dismiss();
                    callback.onResult(paths);
                });
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded);
        }
        sCurrentDialog = new WeakReference<>(dialog);
        sCurrentCallback = new WeakReference<>(callback);
        dialog.show();
        if (window != null) {
            boolean landscape = host.getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            int height = landscape ? WindowManager.LayoutParams.MATCH_PARENT
                    : Math.round(host.getResources().getDisplayMetrics().heightPixels * 0.84f);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height);
        }
    }

    /**
     * Show a tiny color picker dialog returning "#RRGGBB", or null on cancel.
     * The result is delivered through outResult[0] and the latch is counted
     * down exactly once, from the UI thread.
     */
    public static void showColorPicker(@NonNull Activity host, @Nullable String title,
            @Nullable String defaultHex, @NonNull String[] outResult, @NonNull CountDownLatch latch) {
        EditText input = new EditText(host);
        input.setSingleLine(true);
        input.setTextSize(14f);
        input.setText(defaultHex != null ? defaultHex : "");
        input.setSelection(input.getText().length());
        new MaterialAlertDialogBuilder(host)
                .setTitle(title != null && !title.isEmpty() ? title : "Color picker")
                .setMessage("Enter a color as #RRGGBB")
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String hex = input.getText().toString().trim();
                    if (!isValidHex(hex)) {
                        Toast.makeText(host, "Invalid color", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    outResult[0] = normalizeHex(hex);
                    latch.countDown();
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    outResult[0] = null;
                    latch.countDown();
                })
                .setOnCancelListener(d -> {
                    outResult[0] = null;
                    latch.countDown();
                })
                .show();
    }

    private static boolean isValidHex(String hex) {
        if (hex == null) return false;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() != 6) return false;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeHex(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return "#" + h.toUpperCase();
    }

    /**
     * Close the dialog that is currently open, if any, and deliver a null
     * result. Used to release game threads waiting on a dead activity.
     */
    public static void cancelCurrent() {
        Dialog dialog;
        ResultCallback callback;
        clearPendingSharedStorage();
        synchronized (BuiltinFileDialog.class) {
            dialog = sCurrentDialog != null ? sCurrentDialog.get() : null;
            callback = sCurrentCallback != null ? sCurrentCallback.get() : null;
            sCurrentDialog = null;
            sCurrentCallback = null;
        }
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
        if (callback != null) callback.onResult(null);
    }

    /**
     * Host activities must forward {@link Activity#onRequestPermissionsResult} here so the
     * built-in file manager can retry shared external storage navigation after the user
     * grants or denies storage permission.
     */
    public static void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                                  @NonNull int[] grantResults) {
        if (requestCode != REQ_SHARED_STORAGE) return;
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                granted = false;
                break;
            }
        }
        Activity host = pendingSharedHost;
        State state = pendingSharedState;
        File directory = pendingSharedDir;
        clearPendingSharedStorage();
        if (granted && host != null && state != null && directory != null) {
            navigateTo(host, state, directory);
        } else if (host != null) {
            Toast.makeText(host, R.string.file_manager_storage_permission, Toast.LENGTH_LONG).show();
        }
    }

    public static void onHostResumed(@NonNull Activity host) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || pendingSharedHost != host) return;
        State state = pendingSharedState;
        File directory = pendingSharedDir;
        clearPendingSharedStorage();
        if (sharedStoragePermissionGranted(host) && state != null && directory != null) {
            navigateTo(host, state, directory);
        } else {
            Toast.makeText(host, R.string.file_manager_storage_permission, Toast.LENGTH_LONG).show();
        }
    }

    private static void clearPendingSharedStorage() {
        pendingSharedHost = null;
        pendingSharedState = null;
        pendingSharedDir = null;
    }

    private static void clearCurrent(Dialog dialog) {
        synchronized (BuiltinFileDialog.class) {
            if (sCurrentDialog != null && sCurrentDialog.get() == dialog) {
                sCurrentDialog = null;
                sCurrentCallback = null;
            }
        }
    }

    private static View buildContent(Dialog dialog, Activity host, int type, String title,
                                     String defaultPath, List<String> extensions,
                                     boolean allowMultiple, ResultCallback finish) {
        LinearLayout root = new LinearLayout(host);
        State state = new State();
        state.landscapeLayout = host.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int padding = state.landscapeLayout ? dp(host, 4)
                : host.getResources().getDimensionPixelSize(R.dimen.fragment_padding_medium);
        root.setPadding(padding, padding, padding, padding);

        state.type = type;
        state.extensions = extensions;
        state.allowMultiple = allowMultiple;
        state.finish = finish;
        state.currentDir = initialDirectory(host, defaultPath, type);

        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);

        state.compactLayout = !state.landscapeLayout
                && host.getResources().getDisplayMetrics().widthPixels
                / host.getResources().getDisplayMetrics().density < 600f;
        if (state.compactLayout) {
            root.setOrientation(LinearLayout.VERTICAL);
            root.addView(buildSidebar(host, state), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(buildSidebar(host, state), new LinearLayout.LayoutParams(
                    dp(host, state.landscapeLayout ? 112 : 160),
                    ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(content, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }

        TextView titleView = new TextView(host);
        titleView.setText(title != null && !title.isEmpty()
                ? title : defaultDialogTitle(host, type));
        titleView.setTextSize(state.landscapeLayout ? 16f : 18f);
        titleView.setTextColor(MaterialColors.getColor(titleView, R.attr.colorOnSurface));
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setPadding(dp(host, 4), 0, dp(host, 4),
                dp(host, state.landscapeLayout ? 2 : 8));
        content.addView(titleView);

        FrameLayout listContainer = new FrameLayout(host);
        ListView listView = new ListView(host);
        listView.setDivider(null);
        listView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        listContainer.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView emptyView = new TextView(host);
        emptyView.setText(R.string.file_manager_empty);
        emptyView.setTextSize(14f);
        emptyView.setTextColor(MaterialColors.getColor(emptyView, R.attr.colorOnSurfaceVariant));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(host, 16), dp(host, 24), dp(host, 16), dp(host, 24));
        emptyView.setVisibility(View.VISIBLE);
        listContainer.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        EntryAdapter adapter = new EntryAdapter(host, state);
        state.adapter = adapter;
        state.emptyView = emptyView;
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) ->
                adapter.onRowClick(position));
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            File entry = adapter.getItem(position);
            showEntryActions(host, state, entry);
            return true;
        });

        content.addView(buildPathRow(host, state));

        content.addView(listContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content.addView(buildBottomBar(host, state, defaultPath));

        root.post(() -> navigateTo(host, state, state.currentDir));
        return root;
    }

    private static View buildSidebar(Activity host, State state) {
        LinearLayout sidebar = new LinearLayout(host);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(0, 0, state.compactLayout ? 0 : dp(host, 8), dp(host, 4));
        sidebar.setBackgroundColor(MaterialColors.getColor(sidebar, R.attr.colorSurfaceContainerLow));

        TextView heading = new TextView(host);
        heading.setText(R.string.file_manager_roots);
        heading.setTextSize(state.landscapeLayout ? 11f : 12f);
        heading.setTextColor(MaterialColors.getColor(heading, R.attr.colorOnSurfaceVariant));
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setPadding(dp(host, 6), 0, dp(host, 4), dp(host, 4));
        sidebar.addView(heading);

        LinearLayout locations = new LinearLayout(host);
        locations.setOrientation(state.compactLayout
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        state.locations.clear();
        state.locationButtons.clear();
        for (File location : rootCandidates(host)) {
            MaterialButton button = new MaterialButton(host);
            button.setText(rootName(host, location));
            button.setTextSize(state.landscapeLayout ? 11f : 13f);
            button.setAllCaps(false);
            button.setMaxLines(1);
            button.setEllipsize(android.text.TextUtils.TruncateAt.END);
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setIconResource(R.drawable.ic_folder);
            button.setIconSize(dp(host, state.landscapeLayout ? 16 : 18));
            button.setIconPadding(dp(host, 6));
            button.setCornerRadius(dp(host, 12));
            button.setMinWidth(0);
            button.setMinHeight(dp(host, state.landscapeLayout ? 38 : 44));
            button.setInsetTop(0);
            button.setInsetBottom(0);
            button.setPadding(dp(host, state.landscapeLayout ? 6 : 12), 0,
                    dp(host, state.landscapeLayout ? 4 : 12), 0);
            button.setOnClickListener(v -> navigateTo(host, state, location));
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    state.compactLayout ? ViewGroup.LayoutParams.WRAP_CONTENT
                            : ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (state.compactLayout) {
                buttonParams.setMarginEnd(dp(host, 8));
            } else {
                buttonParams.bottomMargin = dp(host, state.landscapeLayout ? 2 : 6);
            }
            locations.addView(button, buttonParams);
            state.locations.add(location);
            state.locationButtons.add(button);
        }
        if (state.compactLayout) {
            HorizontalScrollView scroll = new HorizontalScrollView(host);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipToPadding(false);
            scroll.addView(locations, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            sidebar.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            ScrollView scroll = new ScrollView(host);
            scroll.setFillViewport(true);
            scroll.addView(locations, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            sidebar.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        return sidebar;
    }

    private static LinearLayout buildPathRow(Activity host, State state) {
        LinearLayout pathRow = new LinearLayout(host);
        pathRow.setOrientation(LinearLayout.HORIZONTAL);
        pathRow.setGravity(Gravity.CENTER_VERTICAL);

        MaterialButton upButton = new MaterialButton(host);
        upButton.setIconResource(R.drawable.ic_arrow_back);
        upButton.setIconSize(dp(host, 20));
        upButton.setIconTint(ColorStateList.valueOf(
                MaterialColors.getColor(upButton, R.attr.colorOnSurfaceVariant)));
        upButton.setContentDescription(host.getString(R.string.file_manager_parent_folder));
        upButton.setMinWidth(dp(host, state.landscapeLayout ? 36 : 44));
        upButton.setMinHeight(dp(host, state.landscapeLayout ? 36 : 44));
        upButton.setInsetTop(0);
        upButton.setInsetBottom(0);
        upButton.setPadding(0, 0, 0, 0);
        upButton.setBackgroundTintList(ColorStateList.valueOf(
                MaterialColors.getColor(upButton, R.attr.colorSurfaceContainerLow)));
        upButton.setOnClickListener(v -> {
            File parent = state.currentDir.getParentFile();
            if (parent != null) {
                navigateTo(host, state, parent);
            }
        });
        pathRow.addView(upButton);
        state.upButton = upButton;

        TextView pathView = new TextView(host);
        pathView.setSingleLine(true);
        pathView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        pathView.setTextColor(MaterialColors.getColor(pathView, R.attr.colorOnSurfaceVariant));
        pathView.setTextSize(12f);
        pathView.setPadding(dp(host, 10), dp(host, state.landscapeLayout ? 6 : 10),
                dp(host, 10), dp(host, state.landscapeLayout ? 6 : 10));
        android.graphics.drawable.GradientDrawable pathBackground =
                new android.graphics.drawable.GradientDrawable();
        pathBackground.setColor(MaterialColors.getColor(pathView, R.attr.colorSurfaceContainerLow));
        pathBackground.setCornerRadius(dp(host, 12));
        pathView.setBackground(pathBackground);
        pathRow.addView(pathView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        state.pathView = pathView;

        return pathRow;
    }

    private static View buildBottomBar(Activity host, State state, String defaultPath) {
        LinearLayout bar = new LinearLayout(host);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(host, state.landscapeLayout ? 3 : 8), 0, 0);

        MaterialButton cancelButton = new MaterialButton(host);
        cancelButton.setText(R.string.global_cancel);
        styleTextButton(host, state, cancelButton);
        cancelButton.setOnClickListener(v -> state.finish.onResult(null));
        bar.addView(cancelButton);

        if (state.type != FileDialogBridge.DIALOG_SAVE_FILE) {
            MaterialButton newFolderButton = new MaterialButton(host);
            newFolderButton.setText(R.string.file_manager_new_folder);
            styleTextButton(host, state, newFolderButton);
            newFolderButton.setOnClickListener(v -> promptNewFolder(host, state));
            bar.addView(newFolderButton);
        }

        if (state.type == FileDialogBridge.DIALOG_SAVE_FILE) {
            EditText nameField = new EditText(host);
            nameField.setSingleLine(true);
            nameField.setTextSize(14f);
            nameField.setHint(R.string.file_manager_file_name_hint);
            nameField.setText(defaultSaveName(host, defaultPath));
            nameField.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    state.fileName = s.toString();
                }
            });
            state.fileName = String.valueOf(nameField.getText());
            bar.addView(nameField, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        } else {
            bar.addView(new View(host), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        if (state.type != FileDialogBridge.DIALOG_BROWSE
                && !(state.type == FileDialogBridge.DIALOG_OPEN_FILE && !state.allowMultiple)) {
            MaterialButton okButton = new MaterialButton(host);
            okButton.setText(state.type == FileDialogBridge.DIALOG_SAVE_FILE
                    ? R.string.file_manager_save : R.string.file_manager_select);
            okButton.setOnClickListener(v -> confirm(host, state));
            state.selectionButton = okButton;
            bar.addView(okButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            updateSelectionButton(host, state);
        }
        return bar;
    }

    private static void styleTextButton(Activity host, State state, MaterialButton button) {
        button.setTextSize(13f);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinHeight(dp(host, state.landscapeLayout ? 36 : 44));
        button.setPadding(dp(host, 10), 0, dp(host, 10), 0);
        button.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        button.setTextColor(MaterialColors.getColor(button, R.attr.colorOnSurfaceVariant));
    }

    private static void confirm(Activity host, State state) {
        if (state.type == FileDialogBridge.DIALOG_BROWSE) return;
        if (state.type == FileDialogBridge.DIALOG_OPEN_FOLDER) {
            state.finish.onResult(new String[]{state.currentDir.getAbsolutePath()});
            return;
        }
        if (state.type == FileDialogBridge.DIALOG_SAVE_FILE) {
            String name = state.fileName != null ? state.fileName.trim() : "";
            if (name.isEmpty()) {
                Toast.makeText(host, R.string.file_manager_empty_name, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!name.contains(".") && !state.extensions.isEmpty()) {
                name = name + "." + state.extensions.get(0);
            }
            File target = new File(state.currentDir, name);
            if (target.exists()) {
                new MaterialAlertDialogBuilder(host)
                        .setTitle(R.string.file_manager_overwrite_title)
                        .setMessage(host.getString(R.string.file_manager_overwrite_message, name))
                        .setPositiveButton(R.string.global_yes, (d, w) ->
                                state.finish.onResult(new String[]{target.getAbsolutePath()}))
                        .setNegativeButton(R.string.global_no, null)
                        .show();
                return;
            }
            state.finish.onResult(new String[]{target.getAbsolutePath()});
            return;
        }
        // Open file: multi select needs explicit confirmation, single select
        // is resolved directly on the row click.
        List<File> selected = new ArrayList<>(state.selection);
        if (selected.isEmpty()) {
            Toast.makeText(host, R.string.file_manager_nothing_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] paths = new String[selected.size()];
        for (int i = 0; i < selected.size(); i++) paths[i] = selected.get(i).getAbsolutePath();
        state.finish.onResult(paths);
    }

    /** Prompts for a new folder name and creates it inside the current directory. */
    private static void promptNewFolder(Activity host, State state) {
        EditText input = new EditText(host);
        input.setSingleLine(true);
        input.setTextSize(14f);
        input.setHint(R.string.file_manager_folder_name_hint);
        new MaterialAlertDialogBuilder(host)
                .setTitle(R.string.file_manager_new_folder)
                .setView(input)
                .setPositiveButton(R.string.global_save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(host, R.string.global_error_field_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    createFolder(host, state, name);
                })
                .setNegativeButton(R.string.global_cancel, null)
                .show();
    }

    private static void createFolder(Activity host, State state, String name) {
        File target = new File(state.currentDir, name);
        if (target.exists()) {
            Toast.makeText(host, R.string.file_manager_overwrite_title, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!target.mkdirs()) {
            Toast.makeText(host, R.string.file_manager_overwrite_title, Toast.LENGTH_SHORT).show();
            return;
        }
        navigateTo(host, state, state.currentDir);
    }

    /** Shows rename/delete actions for a file or folder entry. */
    private static void showEntryActions(Activity host, State state, File entry) {
        String[] actions;
        List<String> list = new ArrayList<>();
        list.add(host.getString(R.string.file_manager_rename));
        list.add(host.getString(R.string.global_delete));
        actions = list.toArray(new String[0]);
        new MaterialAlertDialogBuilder(host)
                .setTitle(entry.getName())
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        promptRename(host, state, entry);
                    } else if (which == 1) {
                        confirmDelete(host, state, entry);
                    }
                })
                .setNegativeButton(R.string.global_cancel, null)
                .show();
    }

    /** Prompts for a new name and renames the entry. */
    private static void promptRename(Activity host, State state, File entry) {
        EditText input = new EditText(host);
        input.setSingleLine(true);
        input.setTextSize(14f);
        input.setText(entry.getName());
        new MaterialAlertDialogBuilder(host)
                .setTitle(R.string.file_manager_rename)
                .setView(input)
                .setPositiveButton(R.string.global_save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.equals(entry.getName())) return;
                    renameEntry(host, state, entry, name);
                })
                .setNegativeButton(R.string.global_cancel, null)
                .show();
    }

    private static void renameEntry(Activity host, State state, File entry, String name) {
        File target = new File(state.currentDir, name);
        if (target.exists()) {
            Toast.makeText(host, R.string.file_manager_overwrite_title, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!entry.renameTo(target)) {
            Toast.makeText(host, R.string.file_manager_overwrite_title, Toast.LENGTH_SHORT).show();
            return;
        }
        navigateTo(host, state, state.currentDir);
    }

    /** Confirms deletion then removes the entry (recursively for folders). */
    private static void confirmDelete(Activity host, State state, File entry) {
        new MaterialAlertDialogBuilder(host)
                .setTitle(R.string.global_delete)
                .setMessage(host.getString(R.string.file_manager_delete_confirm, entry.getName()))
                .setPositiveButton(R.string.global_yes, (d, w) -> deleteEntry(host, state, entry))
                .setNegativeButton(R.string.global_no, null)
                .show();
    }

    private static void deleteEntry(Activity host, State state, File entry) {
        boolean ok = deleteRecursively(entry);
        if (!ok) {
            Toast.makeText(host, R.string.file_manager_overwrite_title, Toast.LENGTH_SHORT).show();
        }
        navigateTo(host, state, state.currentDir);
    }

    private static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return file.delete();
    }

    private static void navigateTo(Activity host, State state, File directory) {
        if (directory == null || !directory.isDirectory()) return;
        state.currentDir = directory;
        state.selection.clear();
        updateSelectionButton(host, state);
        state.pathView.setText(directory.getAbsolutePath());
        state.upButton.setEnabled(directory.getParentFile() != null);
        updateLocationSelection(state, directory);
        state.emptyView.setText(R.string.file_manager_empty);
        if (isSharedExternal(host, directory) && !sharedStoragePermissionGranted(host)) {
            state.emptyView.setText(R.string.file_manager_storage_permission);
            state.adapter.setEntries(Collections.emptyList());
            requestSharedStorage(host, state, directory);
            return;
        }
        List<File> entries = new ArrayList<>();
        File[] listed = directory.listFiles();
        if (listed == null && isSharedExternal(host, directory)) {
            if (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
                state.emptyView.setText(R.string.file_manager_storage_permission);
                state.adapter.setEntries(Collections.emptyList());
                requestSharedStorage(host, state, directory);
            } else {
                state.emptyView.setText(R.string.file_manager_unreadable);
                state.adapter.setEntries(Collections.emptyList());
                Toast.makeText(host, R.string.file_manager_storage_permission, Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (listed == null) {
            state.emptyView.setText(R.string.file_manager_unreadable);
            state.adapter.setEntries(Collections.emptyList());
            return;
        }
        entries.addAll(Arrays.asList(listed));
        Collections.sort(entries, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        state.adapter.setEntries(entries);
    }

    private static File[] rootCandidates(Activity host) {
        List<File> roots = new ArrayList<>();
        File gameHome = new File(git.artdeell.mojo.Tools.DIR_GAME_HOME);
        addRoot(roots, gameHome);
        addRoot(roots, host.getExternalFilesDir(null));
        addRoot(roots, host.getFilesDir());
        for (File root : sharedStorageRoots(host)) addRoot(roots, root);
        return roots.toArray(new File[0]);
    }

    private static List<File> sharedStorageRoots(Activity host) {
        LinkedHashSet<File> roots = new LinkedHashSet<>();
        File primary = android.os.Environment.getExternalStorageDirectory();
        if (primary != null && primary.isDirectory()) roots.add(primary);
        File[] appDirectories = host.getExternalFilesDirs(null);
        if (appDirectories != null) {
            for (File appDirectory : appDirectories) {
                File androidDirectory = appDirectory;
                while (androidDirectory != null && !"Android".equals(androidDirectory.getName())) {
                    androidDirectory = androidDirectory.getParentFile();
                }
                File root = androidDirectory != null ? androidDirectory.getParentFile() : null;
                if (root != null && root.isDirectory()) roots.add(root);
            }
        }
        return new ArrayList<>(roots);
    }

    private static void addRoot(List<File> roots, File candidate) {
        if (candidate != null && candidate.isDirectory() && !roots.contains(candidate)) {
            roots.add(candidate);
        }
    }

    private static String rootName(Activity host, File root) {
        if (root.getAbsolutePath().equals(git.artdeell.mojo.Tools.DIR_GAME_HOME)) {
            return host.getString(R.string.file_manager_root_game);
        }
        if (root.equals(host.getExternalFilesDir(null))) {
            return host.getString(R.string.file_manager_root_app);
        }
        File primary = android.os.Environment.getExternalStorageDirectory();
        if (root.equals(primary)) return host.getString(R.string.file_manager_root_shared);
        return root.getName();
    }

    private static void updateLocationSelection(State state, File directory) {
        File selected = null;
        int selectedPathLength = -1;
        String directoryPath = directory.getAbsolutePath();
        for (File location : state.locations) {
            String locationPath = location.getAbsolutePath();
            boolean containsDirectory = directoryPath.equals(locationPath)
                    || directoryPath.startsWith(locationPath.endsWith(File.separator)
                    ? locationPath : locationPath + File.separator);
            if (containsDirectory && locationPath.length() > selectedPathLength) {
                selected = location;
                selectedPathLength = locationPath.length();
            }
        }
        for (int i = 0; i < state.locationButtons.size(); i++) {
            MaterialButton button = state.locationButtons.get(i);
            boolean active = state.locations.get(i).equals(selected);
            int background = MaterialColors.getColor(button, active
                    ? R.attr.colorSecondaryContainer : R.attr.colorSurfaceContainerLow);
            int foreground = MaterialColors.getColor(button, active
                    ? R.attr.colorOnSecondaryContainer : R.attr.colorOnSurfaceVariant);
            button.setBackgroundTintList(ColorStateList.valueOf(background));
            button.setTextColor(foreground);
            button.setIconTint(ColorStateList.valueOf(foreground));
        }
    }

    private static void updateSelectionButton(Activity host, State state) {
        if (state.selectionButton == null) return;
        if (state.type != FileDialogBridge.DIALOG_OPEN_FILE || !state.allowMultiple) {
            state.selectionButton.setEnabled(true);
            return;
        }
        int count = state.selection.size();
        if (count == 0) {
            state.selectionButton.setText(R.string.file_manager_select);
        } else {
            state.selectionButton.setText(host.getString(
                    R.string.file_manager_select_count, count));
        }
        state.selectionButton.setEnabled(count > 0);
    }

    private static File initialDirectory(Activity host, String defaultPath, int type) {
        if (defaultPath != null && !defaultPath.isEmpty()) {
            File candidate = new File(defaultPath);
            if (!candidate.isDirectory()) {
                File parent = candidate.getParentFile();
                if (parent != null && parent.isDirectory()) return parent;
            }
            if (candidate.isDirectory()) return candidate;
        }
        for (File root : rootCandidates(host)) {
            if (root.isDirectory()) return root;
        }
        return new File("/");
    }

    private static String defaultSaveName(Activity host, String defaultPath) {
        if (defaultPath == null || defaultPath.isEmpty()) return "";
        File candidate = new File(defaultPath);
        if (candidate.isDirectory()) return "";
        return candidate.getName();
    }

    // "*.png", "*.jpg;*.jpeg", "png,jpg", "*", "" into a lowercase extension
    // list. An empty result means all files are shown.
    private static List<String> parseFilterPatterns(String[] filterPatterns) {
        List<String> extensions = new ArrayList<>();
        if (filterPatterns != null) {
            for (String pattern : filterPatterns) {
                if (pattern == null) continue;
                for (String extension : pattern.split("[;,]")) {
                    extension = extension.trim().toLowerCase();
                    while (extension.startsWith("*")) extension = extension.substring(1);
                    while (extension.startsWith(".")) extension = extension.substring(1);
                    if (extension.isEmpty() || extension.equals("*")) return new ArrayList<>();
                    if (!extensions.contains(extension)) extensions.add(extension);
                }
            }
        }
        return extensions;
    }

    private static String defaultDialogTitle(Activity host, int type) {
        switch (type) {
            case FileDialogBridge.DIALOG_SAVE_FILE:
                return host.getString(R.string.file_manager_title_save);
            case FileDialogBridge.DIALOG_OPEN_FOLDER:
                return host.getString(R.string.file_manager_title_folder);
            case FileDialogBridge.DIALOG_BROWSE:
                return host.getString(R.string.file_manager_title_browse);
            default:
                return host.getString(R.string.file_manager_title_open);
        }
    }

    private static int dp(Activity host, float value) {
        return Math.round(host.getResources().getDisplayMetrics().density * value);
    }

    private static class State {
        int type;
        boolean allowMultiple;
        boolean compactLayout;
        boolean landscapeLayout;
        List<String> extensions;
        EntryAdapter adapter;
        TextView pathView;
        MaterialButton upButton;
        TextView emptyView;
        MaterialButton selectionButton;
        final List<File> locations = new ArrayList<>();
        final List<MaterialButton> locationButtons = new ArrayList<>();
        ResultCallback finish;
        File currentDir = new File("/");
        final LinkedHashSet<File> selection = new LinkedHashSet<>();
        String fileName = "";
    }

    private static class EntryAdapter extends BaseAdapter {
        private static final int VIEW_FOLDER = 0;
        private static final int VIEW_FILE = 1;
        private static final int VIEW_COUNT = 2;

        private final Activity host;
        private final State state;
        private List<File> entries = new ArrayList<>();

        EntryAdapter(Activity host, State state) {
            this.host = host;
            this.state = state;
        }

        void setEntries(List<File> listed) {
            List<String> extensions = state.extensions;
            entries = new ArrayList<>(listed.size());
            for (File entry : listed) {
                if (entry.isDirectory()
                        || extensions.isEmpty()
                        || extensions.contains(extensionOf(entry.getName()))) {
                    entries.add(entry);
                }
            }
            notifyDataSetChanged();
            state.emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        }

        void onRowClick(int position) {
            File entry = entries.get(position);
            if (entry.isDirectory()) {
                navigateTo(host, state, entry);
                return;
            }
            if (state.type == FileDialogBridge.DIALOG_BROWSE) {
                Tools.openPath(host, entry, false);
                return;
            }
            if (state.type == FileDialogBridge.DIALOG_OPEN_FILE && !state.allowMultiple) {
                state.finish.onResult(new String[]{entry.getAbsolutePath()});
                return;
            }
            if (state.allowMultiple) {
                if (!state.selection.remove(entry)) state.selection.add(entry);
                notifyDataSetChanged();
                updateSelectionButton(host, state);
            }
        }

        @Override public int getCount() {
            return entries.size();
        }

        @Override public File getItem(int position) {
            return entries.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public int getViewTypeCount() {
            return VIEW_COUNT;
        }

        @Override public int getItemViewType(int position) {
            return entries.get(position).isDirectory() ? VIEW_FOLDER : VIEW_FILE;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView nameView;
            ImageView selectionView;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                nameView = row.findViewById(R.id.file_manager_name);
                selectionView = row.findViewById(R.id.file_manager_check);
            } else {
                row = new LinearLayout(host);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int padding = dp(host, 12);
                row.setPadding(padding, dp(host, 8), padding, dp(host, 8));
                row.setMinimumHeight(dp(host, 56));
                TypedValue selectableBackground = new TypedValue();
                if (host.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,
                        selectableBackground, true) && selectableBackground.resourceId != 0) {
                    row.setBackgroundResource(selectableBackground.resourceId);
                }

                ImageView iconView = new ImageView(host);
                iconView.setId(R.id.file_manager_icon);
                iconView.setLayoutParams(new LinearLayout.LayoutParams(dp(host, 28), dp(host, 28)));
                row.addView(iconView);

                nameView = new TextView(host);
                nameView.setId(R.id.file_manager_name);
                nameView.setTextSize(14f);
                nameView.setTextColor(MaterialColors.getColor(nameView, R.attr.colorOnSurface));
                nameView.setSingleLine(true);
                nameView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                nameView.setPadding(dp(host, 12), 0, dp(host, 8), 0);
                row.addView(nameView, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                selectionView = new ImageView(host);
                selectionView.setId(R.id.file_manager_check);
                selectionView.setLayoutParams(new LinearLayout.LayoutParams(dp(host, 20), dp(host, 20)));
                row.addView(selectionView);
            }

            File entry = getItem(position);
            nameView.setText(entry.getName());
            ImageView iconView = row.findViewById(R.id.file_manager_icon);
            boolean selected = state.selection.contains(entry);
            if (entry.isDirectory()) {
                iconView.setImageResource(R.drawable.ic_folder);
                iconView.setColorFilter(MaterialColors.getColor(iconView, R.attr.colorPrimary));
                selectionView.setVisibility(View.INVISIBLE);
            } else {
                iconView.setImageResource(R.drawable.ic_px_file);
                iconView.setColorFilter(MaterialColors.getColor(iconView, R.attr.colorOnSurfaceVariant));
                if (selected) {
                    selectionView.setImageResource(R.drawable.ic_check_circle);
                    selectionView.setColorFilter(MaterialColors.getColor(
                            selectionView, R.attr.colorPrimary));
                    selectionView.setVisibility(View.VISIBLE);
                } else {
                    selectionView.setVisibility(View.INVISIBLE);
                }
            }
            if (selected) {
                row.setBackgroundColor(MaterialColors.getColor(row, R.attr.colorSecondaryContainer));
            } else {
                TypedValue selectableBackground = new TypedValue();
                if (host.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,
                        selectableBackground, true) && selectableBackground.resourceId != 0) {
                    row.setBackgroundResource(selectableBackground.resourceId);
                }
            }
            return row;
        }
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase();
    }
}
