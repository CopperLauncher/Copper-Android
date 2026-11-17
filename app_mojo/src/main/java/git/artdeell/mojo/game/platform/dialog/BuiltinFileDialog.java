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
import android.view.LayoutInflater;
import android.view.MenuItem;
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
import android.widget.ProgressBar;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.os.SystemClock;
import android.view.inputmethod.EditorInfo;

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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Built-in file manager dialog, the Android counterpart of the tinyfd dialogs
 * on desktop: a plain filesystem browser returning real paths, with the usual
 * open/save/folder flavors. Files can be filtered by extension, multiple files
 * can be selected and save requests get a name field.
 */
public final class BuiltinFileDialog {
    private static final String TAG = "BuiltinFileDialog";
    private static final int DIALOG_IMPORT_FILES = 4;
    private static final ExecutorService FILE_OPERATIONS = Executors.newSingleThreadExecutor();
    private static final ExecutorService DIRECTORY_LISTING = Executors.newFixedThreadPool(2);

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
        // Allow app's own external files directory without shared storage permission check
        File[] appDirectories = host.getExternalFilesDirs(null);
        if (appDirectories != null) {
            for (File appDirectory : appDirectories) {
                if (appDirectory != null) {
                    String appPath = appDirectory.getAbsolutePath();
                    if (abs.equals(appPath) || abs.startsWith(appPath + File.separator)) return false;
                }
            }
        }
        // Only consider truly shared storage roots (like /storage/emulated/0/), not app private dirs
        for (File root : sharedStorageRoots(host)) {
            String rootPath = root.getAbsolutePath();
            // Skip app-private directories that happen to be under shared storage
            if (abs.startsWith(rootPath + File.separator) && abs.contains("/Android/data/")) {
                continue;
            }
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
        show(host, type, title, defaultPath, filterPatterns, null, allowMultiple, callback);
    }

    public static void show(@NonNull Activity host, int type, @Nullable String title,
                            @Nullable String defaultPath, @Nullable String[] filterPatterns,
                            @Nullable String filterDescription, boolean allowMultiple,
                            @NonNull ResultCallback callback) {
        if (type == FileDialogBridge.DIALOG_SAVE_FILE) allowMultiple = false;
        List<String> patterns = parseFilterPatterns(filterPatterns);
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
        View content = buildContent(dialog, host, type, title, defaultPath, patterns,
                filterDescription, allowMultiple, paths -> {
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
            int width = Math.min(dp(host, landscape ? 1200 : 960),
                    host.getResources().getDisplayMetrics().widthPixels - dp(host, 16));
            int screenHeight = host.getResources().getDisplayMetrics().heightPixels;
            int height = landscape ? screenHeight - dp(host, 16)
                    : Math.min(dp(host, 900), Math.round(screenHeight * 0.9f));
            window.setLayout(width, height);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
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
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(host)
                .setTitle(title != null && !title.isEmpty() ? title : "Color picker")
                .setMessage("Enter a color as #RRGGBB")
                .setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    outResult[0] = null;
                    latch.countDown();
                })
                .setOnCancelListener(d -> {
                    outResult[0] = null;
                    latch.countDown();
                });
        Dialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            android.widget.Button positive = ((android.app.AlertDialog) dialog)
                    .getButton(android.app.AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                String hex = input.getText().toString().trim();
                if (!isValidHex(hex)) {
                    Toast.makeText(host, "Invalid color", Toast.LENGTH_SHORT).show();
                    return;
                }
                outResult[0] = normalizeHex(hex);
                latch.countDown();
                dialog.dismiss();
            });
        });
        dialog.show();
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
        clearPendingSharedStorage();
        while (true) {
            Dialog dialog;
            ResultCallback callback;
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
            synchronized (BuiltinFileDialog.class) {
                if (sCurrentDialog == null) return;
            }
        }
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
        boolean pendingUseFolder = state != null && state.pendingUseFolder;
        if (state != null) state.pendingUseFolder = false;
        clearPendingSharedStorage();
        if (granted && host != null && state != null && directory != null) {
            navigateTo(host, state, directory);
            if (pendingUseFolder) confirm(host, state);
        } else if (host != null) {
            Toast.makeText(host, R.string.file_manager_storage_permission, Toast.LENGTH_LONG).show();
        }
    }

    public static void onHostResumed(@NonNull Activity host) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || pendingSharedHost != host) return;
        State state = pendingSharedState;
        File directory = pendingSharedDir;
        boolean pendingUseFolder = state != null && state.pendingUseFolder;
        if (state != null) state.pendingUseFolder = false;
        clearPendingSharedStorage();
        if (sharedStoragePermissionGranted(host) && state != null && directory != null) {
            navigateTo(host, state, directory);
            if (pendingUseFolder) confirm(host, state);
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
                                     String defaultPath, List<String> patterns,
                                     String filterDescription,
                                     boolean allowMultiple, ResultCallback finish) {
        LinearLayout root = new LinearLayout(host);
        root.setFocusableInTouchMode(true);
        State state = new State();
        state.landscapeLayout = host.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int padding = state.landscapeLayout ? dp(host, 8)
                : host.getResources().getDimensionPixelSize(R.dimen.fragment_padding_medium);
        root.setPadding(padding, padding, padding, padding);

        state.type = type;
        state.filterPatterns = patterns;
        state.allowMultiple = allowMultiple;
        state.finish = finish;
        state.dialog = dialog;
        state.currentDir = initialDirectory(host, defaultPath, type);
        state.browseRoot = state.currentDir;

        LinearLayout content = (LinearLayout) LayoutInflater.from(host).inflate(
                R.layout.dialog_builtin_file_manager_content, root, false);

        state.compactLayout = host.getResources().getConfiguration().screenWidthDp < 600;
        int contentHorizontalPadding = state.compactLayout ? 8
                : state.landscapeLayout ? 12 : 16;
        int contentVerticalPadding = state.compactLayout ? 6
                : state.landscapeLayout ? 8 : 12;
        content.setPadding(dp(host, contentHorizontalPadding),
                dp(host, contentVerticalPadding),
                dp(host, contentHorizontalPadding),
                dp(host, contentVerticalPadding));
        if (state.compactLayout) {
            root.setOrientation(LinearLayout.VERTICAL);
            root.addView(buildSidebar(host, state), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(buildSidebar(host, state), new LinearLayout.LayoutParams(
                    dp(host, state.landscapeLayout ? 128 : 160),
                    ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(content, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }

        TextView titleView = content.findViewById(R.id.file_manager_dialog_title);
        titleView.setText(title != null && !title.isEmpty()
                ? title : defaultDialogTitle(host, type, allowMultiple));
        titleView.setTextSize(state.landscapeLayout ? 16f : 18f);
        titleView.setTextColor(MaterialColors.getColor(titleView, R.attr.colorOnSurface));
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setPadding(dp(host, 4), 0, dp(host, 4),
                dp(host, state.landscapeLayout ? 8 : 12));

        TextView hintView = content.findViewById(R.id.file_manager_dialog_hint);
        if (type == FileDialogBridge.DIALOG_OPEN_FILE
                || type == DIALOG_IMPORT_FILES
                || type == FileDialogBridge.DIALOG_OPEN_FOLDER
                || type == FileDialogBridge.DIALOG_BROWSE) {
            int hint = type == FileDialogBridge.DIALOG_OPEN_FOLDER
                    ? R.string.file_manager_folder_hint
                    : type == FileDialogBridge.DIALOG_BROWSE
                    ? R.string.file_manager_manage_hint
                    : type == DIALOG_IMPORT_FILES
                    ? R.string.file_manager_import_hint
                    : R.string.file_manager_open_hint;
            hintView.setText(hint);
            hintView.setTextColor(MaterialColors.getColor(
                    hintView, R.attr.colorOnSurfaceVariant));
            hintView.setVisibility(View.VISIBLE);
        }
        if (filterDescription != null && !filterDescription.trim().isEmpty()) {
            if (hintView.getVisibility() == View.VISIBLE) {
                hintView.append(" · " + filterDescription.trim());
            } else {
                hintView.setText(filterDescription.trim());
                hintView.setTextColor(MaterialColors.getColor(
                        hintView, R.attr.colorOnSurfaceVariant));
                hintView.setVisibility(View.VISIBLE);
            }
        }

        ListView listView = content.findViewById(R.id.file_manager_list);
        View columnsHeader = content.findViewById(R.id.file_manager_columns_header);
        columnsHeader.setVisibility(state.landscapeLayout ? View.VISIBLE : View.GONE);
        columnsHeader.setBackgroundColor(MaterialColors.getColor(
                columnsHeader, R.attr.colorSurfaceContainerLow));
        listView.setDivider(new android.graphics.drawable.ColorDrawable(
                MaterialColors.getColor(listView, R.attr.colorOutlineVariant)));
        listView.setDividerHeight(dp(host, 1));
        TextView emptyView = content.findViewById(R.id.file_manager_empty);
        emptyView.setText(R.string.file_manager_empty);
        emptyView.setTextColor(MaterialColors.getColor(emptyView, R.attr.colorOnSurfaceVariant));

        EntryAdapter adapter = new EntryAdapter(host, state);
        state.adapter = adapter;
        state.emptyView = emptyView;
        listView.setAdapter(adapter);
        listView.setEmptyView(emptyView);
        listView.setOnItemClickListener((parent, view, position, id) ->
                adapter.onRowClick(position));
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            File entry = adapter.getItem(position);
            if (state.type == FileDialogBridge.DIALOG_BROWSE) {
                showEntryActions(host, state, entry);
            } else if ((state.type == FileDialogBridge.DIALOG_OPEN_FILE
                    || state.type == DIALOG_IMPORT_FILES) && !entry.isDirectory()) {
                adapter.toggleSelection(entry);
            }
            return true;
        });

        MaterialButton upButton = content.findViewById(R.id.file_manager_up);
        upButton.setIconSize(dp(host, 20));
        state.upButton = upButton;
        state.pathView = content.findViewById(R.id.file_manager_path);
        state.sortButton = content.findViewById(R.id.file_manager_sort);
        state.searchInput = content.findViewById(R.id.file_manager_search);
        state.pathView.setOnClickListener(v -> promptPath(host, state));
        state.searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                android.view.inputmethod.InputMethodManager inputMethodManager =
                        (android.view.inputmethod.InputMethodManager)
                                host.getSystemService(Activity.INPUT_METHOD_SERVICE);
                if (inputMethodManager != null) {
                    inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
                }
                return true;
            }
            return false;
        });
        state.searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                state.searchQuery = s.toString().trim();
                state.focusedEntry = null;
                state.lastClickedFile = null;
                state.selection.clear();
                adapter.setSearchQuery(state.searchQuery);
                updateSelectionButton(host, state);
                updateEmptyMessage(state);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        state.sortButton.setText(sortLabel(state.sortMode));
        state.sortButton.setOnClickListener(v -> showSortMenu(host, state));
        upButton.setOnClickListener(v -> {
            File parent = state.currentDir.getParentFile();
            if (parent != null) navigateTo(host, state, parent);
        });

        content.addView(buildBottomBar(host, state, defaultPath));

        state.searchInput.clearFocus();
        root.requestFocus();
        dialog.setOnShowListener(d -> navigateTo(host, state, state.currentDir));
        return root;
    }

    private static View buildSidebar(Activity host, State state) {
        LinearLayout sidebar = new LinearLayout(host);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(host, 8), dp(host, 8),
                dp(host, state.compactLayout ? 8 : 12), dp(host, 8));
        sidebar.setBackgroundColor(MaterialColors.getColor(sidebar, R.attr.colorSurfaceContainerLow));

        TextView heading = new TextView(host);
        heading.setText(R.string.file_manager_roots);
        heading.setTextSize(state.landscapeLayout ? 11f : 12f);
        heading.setTextColor(MaterialColors.getColor(heading, R.attr.colorOnSurfaceVariant));
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setPadding(dp(host, 8), 0, dp(host, 4), dp(host, 8));
        sidebar.addView(heading);

        LinearLayout locations = new LinearLayout(host);
        locations.setOrientation(state.compactLayout
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        state.locations.clear();
        state.locationButtons.clear();
        for (File location : Collections.singletonList(state.browseRoot)) {
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

    private static View buildBottomBar(Activity host, State state, String defaultPath) {
        LinearLayout bar = new LinearLayout(host);
        state.bottomBar = bar;
        state.defaultPath = defaultPath;
        refreshBottomBar(host, state);
        if (state.compactLayout && state.type != FileDialogBridge.DIALOG_SAVE_FILE) {
            HorizontalScrollView scroll = new HorizontalScrollView(host);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setFillViewport(true);
            scroll.setClipToPadding(false);
            scroll.addView(bar, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return scroll;
        }
        return bar;
    }

    private static void refreshBottomBar(Activity host, State state) {
        LinearLayout bar = state.bottomBar;
        bar.removeAllViews();
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(host, state.landscapeLayout ? 8 : 12), 0, 0);

        state.selectionButton = null;
        state.fileNameInput = null;

        if (state.type == FileDialogBridge.DIALOG_BROWSE && state.manageMode) {
            TextView selectedCount = new TextView(host);
            selectedCount.setText(host.getString(R.string.file_manager_items_count,
                    state.selection.size()));
            selectedCount.setTextColor(MaterialColors.getColor(
                    selectedCount, R.attr.colorOnSurfaceVariant));
            selectedCount.setPadding(dp(host, 8), 0, dp(host, 8), 0);
            bar.addView(selectedCount);
            addActionButton(host, state, bar, R.string.file_manager_import_here,
                    v -> importFiles(host, state), true);
            if (!state.selection.isEmpty()) {
                addActionButton(host, state, bar, R.string.global_delete,
                        v -> confirmDeleteSelected(host, state), true);
                addActionButton(host, state, bar, R.string.file_manager_move,
                        v -> chooseMoveDestination(host, state), false);
                if (state.selection.size() == 1) {
                    File selected = state.selection.iterator().next();
                    addActionButton(host, state, bar, R.string.file_manager_rename,
                            v -> promptRename(host, state, selected), false);
                }
            }
            addActionButton(host, state, bar, R.string.file_manager_done,
                    v -> {
                        state.manageMode = false;
                        state.selection.clear();
                        refreshCurrentDirectory(host, state);
                        refreshBottomBar(host, state);
                    }, false);
            return;
        }

        MaterialButton cancelButton = new MaterialButton(host);
        cancelButton.setText(R.string.global_cancel);
        cancelButton.setAllCaps(false);
        styleTextButton(host, state, cancelButton);
        cancelButton.setTextColor(MaterialColors.getColor(
                cancelButton, R.attr.colorOnSurfaceVariant));
        cancelButton.setOnClickListener(v -> state.finish.onResult(null));
        bar.addView(cancelButton);

        if (state.type == FileDialogBridge.DIALOG_SAVE_FILE) {
            EditText nameField = new EditText(host);
            nameField.setSingleLine(true);
            nameField.setTextSize(14f);
            nameField.setHint(R.string.file_manager_file_name_hint);
            state.fileNameInput = nameField;
            if (state.fileName == null) state.fileName = defaultSaveName(host, state.defaultPath);
            nameField.setText(state.fileName);
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
            addActionButton(host, state, bar, R.string.file_manager_save,
                    v -> confirm(host, state), true);
            return;
        }

        if (state.type == FileDialogBridge.DIALOG_OPEN_FILE
                || state.type == DIALOG_IMPORT_FILES) {
            bar.addView(new View(host), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            addActionButton(host, state, bar,
                    state.type == DIALOG_IMPORT_FILES
                            ? R.string.file_manager_import_selected : R.string.file_manager_open,
                    v -> confirm(host, state), true);
            state.selectionButton = (MaterialButton) bar.getChildAt(bar.getChildCount() - 1);
            updateSelectionButton(host, state);
            return;
        }

        if (state.type == FileDialogBridge.DIALOG_OPEN_FOLDER) {
            bar.addView(new View(host), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            addActionButton(host, state, bar, R.string.file_manager_use_folder,
                    v -> confirm(host, state), true);
            return;
        }

        if (state.type == FileDialogBridge.DIALOG_BROWSE) {
            if (state.compactLayout) {
                addActionButton(host, state, bar, R.string.file_manager_import_here,
                        v -> importFiles(host, state), true);
                addActionButton(host, state, bar, R.string.file_manager_new_folder,
                        v -> promptNewFolder(host, state), false);
                addActionButton(host, state, bar, R.string.file_manager_manage,
                        v -> {
                            state.manageMode = true;
                            state.selection.clear();
                            refreshCurrentDirectory(host, state);
                            refreshBottomBar(host, state);
                        }, false);
                return;
            }
            addActionButton(host, state, bar, R.string.file_manager_new_folder,
                    v -> promptNewFolder(host, state), false);
            addActionButton(host, state, bar, R.string.file_manager_manage,
                    v -> {
                        state.manageMode = true;
                        state.selection.clear();
                        refreshCurrentDirectory(host, state);
                        refreshBottomBar(host, state);
                    }, false);
            bar.addView(new View(host), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            addActionButton(host, state, bar, R.string.file_manager_import_here,
                    v -> importFiles(host, state), true);
        }
    }

    private static void addActionButton(Activity host, State state, LinearLayout bar, int label,
                                        View.OnClickListener listener, boolean primary) {
        MaterialButton button = new MaterialButton(host);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinWidth(0);
        button.setMinHeight(dp(host, state.landscapeLayout ? 38 : 44));
        button.setPadding(dp(host, 12), 0, dp(host, 12), 0);
        if (!primary) styleTextButton(host, state, button);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(host, 3));
        params.setMarginEnd(dp(host, 3));
        bar.addView(button, params);
    }

    private static void styleTextButton(Activity host, State state, MaterialButton button) {
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setMinWidth(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinHeight(dp(host, state.landscapeLayout ? 36 : 44));
        button.setPadding(dp(host, 10), 0, dp(host, 10), 0);
        button.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        button.setTextColor(MaterialColors.getColor(button, R.attr.colorPrimary));
    }

    private static void confirm(Activity host, State state) {
        if (state.type == FileDialogBridge.DIALOG_BROWSE) return;
        if (state.type == FileDialogBridge.DIALOG_OPEN_FOLDER) {
            File selectedDirectory = state.focusedEntry != null
                    && state.focusedEntry.isDirectory() ? state.focusedEntry : state.currentDir;
            if (isSharedExternal(host, selectedDirectory)
                    && !sharedStoragePermissionGranted(host)) {
                state.pendingUseFolder = true;
                requestSharedStorage(host, state, selectedDirectory);
                return;
            }
            if (!selectedDirectory.canRead() || !selectedDirectory.canWrite()) {
                Toast.makeText(host, R.string.file_manager_folder_access_failed,
                        Toast.LENGTH_LONG).show();
                return;
            }
            state.finish.onResult(new String[]{selectedDirectory.getAbsolutePath()});
            return;
        }
        if (state.type == FileDialogBridge.DIALOG_SAVE_FILE) {
            if (isSharedExternal(host, state.currentDir)
                    && !sharedStoragePermissionGranted(host)) {
                state.pendingUseFolder = true;
                requestSharedStorage(host, state, state.currentDir);
                return;
            }
            if (!state.currentDir.canRead() || !state.currentDir.canWrite()) {
                Toast.makeText(host, R.string.file_manager_folder_access_failed,
                        Toast.LENGTH_LONG).show();
                return;
            }
            String name = state.fileName != null ? state.fileName.trim() : "";
            if (!isValidEntryName(name)) {
                Toast.makeText(host, R.string.file_manager_name_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            String extension = firstSaveExtension(state.filterPatterns);
            if (!name.contains(".") && extension != null) {
                name = name + "." + extension;
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
        List<File> selected = new ArrayList<>(state.selection);
        if (selected.size() != 1 && !state.allowMultiple) {
            Toast.makeText(host, R.string.file_manager_nothing_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] paths = new String[selected.size()];
        for (int i = 0; i < selected.size(); i++) {
            File entry = selected.get(i);
            if (!entry.isFile() || !entry.canRead()) {
                Toast.makeText(host, R.string.file_manager_unreadable, Toast.LENGTH_LONG).show();
                return;
            }
            paths[i] = entry.getAbsolutePath();
        }
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
        if (!isValidEntryName(name)) {
            Toast.makeText(host, R.string.file_manager_name_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        File target = new File(state.currentDir, name);
        if (target.exists()) {
            Toast.makeText(host, R.string.file_manager_operation_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!target.mkdirs()) {
            Toast.makeText(host, R.string.file_manager_operation_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        navigateTo(host, state, state.currentDir);
    }

    /** Shows file operations for a file or folder entry. */
    private static void showEntryActions(Activity host, State state, File entry) {
        boolean selected = state.selection.contains(entry);
        String[] actions = {
                host.getString(selected ? R.string.file_manager_deselect
                        : R.string.file_manager_select),
                host.getString(R.string.file_manager_move),
                host.getString(R.string.file_manager_rename),
                host.getString(R.string.global_delete)
        };
        new MaterialAlertDialogBuilder(host)
                .setTitle(entry.getName())
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        state.manageMode = true;
                        if (state.selection.contains(entry)) {
                            state.selection.remove(entry);
                            state.adapter.notifyDataSetChanged();
                            updateSelectionButton(host, state);
                            refreshBottomBar(host, state);
                        } else {
                            state.adapter.toggleSelection(entry);
                            refreshBottomBar(host, state);
                        }
                    } else if (which == 1) {
                        state.manageMode = true;
                        if (!state.selection.contains(entry)) {
                            state.selection.clear();
                            state.selection.add(entry);
                            state.adapter.notifyDataSetChanged();
                        }
                        refreshBottomBar(host, state);
                        chooseMoveDestination(host, state);
                    } else if (which == 2) {
                        promptRename(host, state, entry);
                    } else {
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
        if (!isValidEntryName(name)) {
            Toast.makeText(host, R.string.file_manager_name_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        File target = new File(state.currentDir, name);
        if (target.exists()) {
            Toast.makeText(host, R.string.file_manager_operation_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!entry.renameTo(target)) {
            Toast.makeText(host, R.string.file_manager_operation_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        navigateTo(host, state, state.currentDir);
    }

    private static boolean isValidEntryName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
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

    private static void importFiles(Activity host, State parent) {
        File destination = parent.currentDir;
        show(host, DIALOG_IMPORT_FILES,
                host.getString(R.string.file_manager_import_title),
                parent.browseRoot.getAbsolutePath(), null, true, paths -> {
                    restoreParentDialog(parent);
                    if (paths == null || paths.length == 0) return;
                    List<File> sources = new ArrayList<>();
                    for (String path : paths) {
                        if (path != null) sources.add(new File(path));
                    }
                    if (sources.isEmpty()) return;
                    confirmConflicts(host, parent, sources, destination, false);
                });
    }

    private static void chooseMoveDestination(Activity host, State parent) {
        List<File> sources = new ArrayList<>(parent.selection);
        show(host, FileDialogBridge.DIALOG_OPEN_FOLDER,
                host.getString(R.string.file_manager_select_destination),
                parent.browseRoot.getAbsolutePath(), null, false, paths -> {
                    restoreParentDialog(parent);
                    if (paths == null || paths.length == 0) return;
                    confirmConflicts(host, parent, sources, new File(paths[0]), true);
                });
    }

    private static void restoreParentDialog(State parent) {
        if (parent.dialog == null || !parent.dialog.isShowing()) return;
        synchronized (BuiltinFileDialog.class) {
            sCurrentDialog = new WeakReference<>(parent.dialog);
            sCurrentCallback = new WeakReference<>(parent.finish);
        }
    }

    private static void confirmConflicts(Activity host, State state, List<File> sources,
                                         File destination, boolean move) {
        File conflict = null;
        for (File source : sources) {
            File target = new File(destination, source.getName());
            if (target.exists()) {
                conflict = target;
                break;
            }
        }
        if (conflict == null) {
            transferFiles(host, state, sources, destination, move, false);
            return;
        }
        File firstConflict = conflict;
        new MaterialAlertDialogBuilder(host)
                .setTitle(move ? R.string.file_manager_move_title
                        : R.string.file_manager_import_title)
                .setMessage(host.getString(R.string.file_manager_conflict_message,
                        firstConflict.getName()))
                .setPositiveButton(R.string.global_yes, (dialog, which) ->
                        transferFiles(host, state, sources, destination, move, true))
                .setNegativeButton(R.string.global_no, null)
                .show();
    }

    private static void transferFiles(Activity host, State state, List<File> sources,
                                      File destination, boolean move, boolean replace) {
        ProgressBar progressBar = new ProgressBar(host);
        Dialog progress = new MaterialAlertDialogBuilder(host)
                .setTitle(move ? R.string.file_manager_move_title
                        : R.string.file_manager_import_title)
                .setView(progressBar)
                .setCancelable(false)
                .create();
        progress.show();
        FILE_OPERATIONS.execute(() -> {
            List<File> failed = new ArrayList<>();
            for (File source : sources) {
                File target = new File(destination, source.getName());
                if (source.equals(target) || isInside(source, destination)) {
                    failed.add(source);
                    continue;
                }
                boolean cleanupTarget = !target.exists();
                try {
                    if (target.exists()) {
                        if (!replace || !deleteRecursively(target)) {
                            throw new IOException("Unable to replace destination");
                        }
                        cleanupTarget = true;
                    }
                    if (move && source.renameTo(target)) continue;
                    if (move) {
                        copyRecursively(source, target);
                        if (!deleteRecursively(source)) {
                            deleteRecursively(target);
                            throw new IOException("Unable to remove source after copy");
                        }
                    } else {
                        copyRecursively(source, target);
                    }
                } catch (IOException | SecurityException e) {
                    if (cleanupTarget) deleteRecursively(target);
                    Log.e(TAG, "File transfer failed: " + source, e);
                    failed.add(source);
                }
            }
            host.runOnUiThread(() -> {
                progress.dismiss();
                if (!failed.isEmpty()) {
                    Toast.makeText(host, host.getString(
                            R.string.file_manager_operation_failures,
                            failed.size(), failed.get(0).getName()),
                            Toast.LENGTH_LONG).show();
                }
                if (!host.isFinishing() && !host.isDestroyed()
                        && state.dialog != null && state.dialog.isShowing()) {
                    state.selection.clear();
                    refreshCurrentDirectory(host, state);
                    refreshBottomBar(host, state);
                }
            });
        });
    }

    private static boolean isInside(File source, File destination) {
        try {
            String sourcePath = source.getCanonicalPath();
            String destinationPath = destination.getCanonicalPath();
            return destinationPath.startsWith(sourcePath + File.separator);
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "Unable to resolve transfer paths", e);
            return true;
        }
    }

    private static void copyRecursively(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            if (!destination.mkdirs() && !destination.isDirectory()) {
                throw new IOException("Unable to create destination directory");
            }
            File[] children = source.listFiles();
            if (children == null) throw new IOException("Unable to list source directory");
            for (File child : children) {
                copyRecursively(child, new File(destination, child.getName()));
            }
            return;
        }
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Unable to create destination parent");
        }
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
    }

    private static void confirmDeleteSelected(Activity host, State state) {
        List<File> selected = new ArrayList<>(state.selection);
        new MaterialAlertDialogBuilder(host)
                .setTitle(R.string.global_delete)
                .setMessage(host.getString(R.string.file_manager_delete_selected_confirm,
                        selected.size()))
                .setPositiveButton(R.string.global_yes, (dialog, which) -> {
                    ProgressBar progressBar = new ProgressBar(host);
                    Dialog progress = new MaterialAlertDialogBuilder(host)
                            .setTitle(R.string.global_delete)
                            .setView(progressBar)
                            .setCancelable(false)
                            .create();
                    progress.show();
                    FILE_OPERATIONS.execute(() -> {
                        List<File> failed = new ArrayList<>();
                        for (File entry : selected) {
                            if (!deleteRecursively(entry)) failed.add(entry);
                        }
                        host.runOnUiThread(() -> {
                            progress.dismiss();
                            if (!failed.isEmpty()) {
                                Toast.makeText(host, host.getString(
                                        R.string.file_manager_operation_failures,
                                        failed.size(), failed.get(0).getName()),
                                        Toast.LENGTH_LONG).show();
                            }
                            if (!host.isFinishing() && !host.isDestroyed() && state.dialog != null
                                    && state.dialog.isShowing()) {
                                state.selection.clear();
                                refreshCurrentDirectory(host, state);
                                refreshBottomBar(host, state);
                            }
                        });
                    });
                })
                .setNegativeButton(R.string.global_no, null)
                .show();
    }

    private static void navigateTo(Activity host, State state, File directory) {
        if (directory == null || !directory.isDirectory()) return;
        if (!isWithinBrowseRoot(state, directory) || !isAccessibleDirectory(host, directory)) {
            Log.w(TAG, "Blocked navigation outside the dialog's starting directory: "
                    + directory.getAbsolutePath());
            return;
        }
        state.currentDir = directory;
        state.directoryEntryCount = -1;
        state.lastClickedFile = null;
        state.focusedEntry = null;
        if (!state.searchQuery.isEmpty()) {
            state.searchInput.setText("");
        }
        state.selection.clear();
        if (state.type == FileDialogBridge.DIALOG_BROWSE && state.manageMode) {
            state.manageMode = false;
        }
        updateSelectionButton(host, state);
        state.pathView.setText(directory.getAbsolutePath());
        state.pathView.setContentDescription(directory.getAbsolutePath()
                + ". " + host.getString(R.string.file_manager_path_title));
        state.sortButton.setText(sortLabel(state.sortMode));
        File parent = directory.getParentFile();
        state.upButton.setEnabled(parent != null && isWithinBrowseRoot(state, parent)
                && isAccessibleDirectory(host, parent));
        updateLocationSelection(state, directory);
        state.emptyView.setText(R.string.file_manager_no_results);
        refreshBottomBar(host, state);
        refreshCurrentDirectory(host, state);
    }

    private static void refreshCurrentDirectory(Activity host, State state) {
        File directory = state.currentDir;
        String path = directory.getAbsolutePath();
        long requestId = ++state.listingRequestId;
        state.emptyView.setText(R.string.file_manager_loading);
        state.adapter.setEntries(Collections.emptyList());
        if (isSharedExternal(host, directory) && !sharedStoragePermissionGranted(host)) {
            state.emptyView.setText(R.string.file_manager_storage_permission);
            requestSharedStorage(host, state, directory);
            return;
        }
        Log.i(TAG, "Starting directory scan: " + path);
        DIRECTORY_LISTING.execute(() -> {
            File[] listed = null;
            RuntimeException failure = null;
            try {
                listed = directory.listFiles();
            } catch (RuntimeException e) {
                failure = e;
            }
            Log.i(TAG, "Directory scan finished: " + path
                    + ", entries=" + (listed != null ? listed.length : "unavailable"));
            File[] result = listed;
            RuntimeException listingFailure = failure;
            host.runOnUiThread(() -> {
                if (requestId != state.listingRequestId || !directory.equals(state.currentDir)
                    || host.isFinishing() || host.isDestroyed()) {
                    return;
                }
                if (listingFailure != null) {
                    Log.w(TAG, "Directory scan failed for " + path, listingFailure);
                    state.emptyView.setText(R.string.file_manager_unreadable);
                    return;
                }
                if (result == null && isSharedExternal(host, directory)) {
                    if (Build.VERSION.SDK_INT >= 30
                            && !android.os.Environment.isExternalStorageManager()) {
                        state.emptyView.setText(R.string.file_manager_storage_permission);
                        requestSharedStorage(host, state, directory);
                    } else {
                        Log.w(TAG, "Shared storage listing failed for " + path
                                + " (exists=" + directory.exists()
                                + ", readable=" + directory.canRead()
                                + ", allFilesAccess="
                                + (Build.VERSION.SDK_INT >= 30
                                && android.os.Environment.isExternalStorageManager()) + ")");
                        state.emptyView.setText(R.string.file_manager_unreadable);
                    }
                    return;
                }
                if (result == null) {
                    Log.w(TAG, "listFiles returned null for " + path
                            + " (exists=" + directory.exists()
                            + ", readable=" + directory.canRead()
                            + ", writable=" + directory.canWrite() + ")");
                    state.emptyView.setText(R.string.file_manager_unreadable);
                    return;
                }
                try {
                    state.adapter.setEntries(Arrays.asList(result));
                    updateEmptyMessage(state);
                    Log.i(TAG, "Listed " + result.length + " entries at " + path
                            + "; visible entries=" + state.adapter.getCount());
                } catch (SecurityException e) {
                    Log.w(TAG, "Permission denied while reading entries in " + path, e);
                    state.emptyView.setText(R.string.file_manager_unreadable);
                    state.adapter.setEntries(Collections.emptyList());
                }
            });
        });
    }

    private static File[] rootCandidates(Activity host) {
        List<File> roots = new ArrayList<>();
        File gameHome = new File(git.artdeell.mojo.Tools.DIR_GAME_HOME);
        addRoot(roots, gameHome);
        addRoot(roots, host.getExternalFilesDir(null));
        for (File root : sharedStorageRoots(host)) addRoot(roots, root);
        return roots.toArray(new File[0]);
    }

    private static boolean isAccessibleDirectory(Activity host, File directory) {
        for (File root : rootCandidates(host)) {
            try {
                String path = directory.getCanonicalPath();
                String rootPath = root.getCanonicalPath();
                if (path.equals(rootPath) || path.startsWith(rootPath + File.separator)) {
                    return true;
                }
            } catch (IOException e) {
                Log.w(TAG, "Unable to resolve file manager location", e);
            }
        }
        return false;
    }

    private static boolean isWithinBrowseRoot(State state, File directory) {
        try {
            String rootPath = state.browseRoot.getCanonicalPath();
            String directoryPath = directory.getCanonicalPath();
            return directoryPath.equals(rootPath)
                    || directoryPath.startsWith(rootPath + File.separator);
        } catch (IOException e) {
            Log.w(TAG, "Unable to check dialog navigation boundary", e);
            return false;
        }
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
        int count = state.selection.size();
        if (count == 0) {
            int label = state.type == FileDialogBridge.DIALOG_OPEN_FILE
                    ? R.string.file_manager_open
                    : state.type == DIALOG_IMPORT_FILES
                    ? R.string.file_manager_import_selected : R.string.file_manager_select;
            state.selectionButton.setText(label);
        } else if (state.type == DIALOG_IMPORT_FILES) {
            state.selectionButton.setText(host.getString(
                    R.string.file_manager_import_count, count));
        } else {
            state.selectionButton.setText(host.getString(R.string.file_manager_open_count, count));
        }
        state.selectionButton.setEnabled(count > 0);
    }

    private static void updateEmptyMessage(State state) {
        if (state.adapter.getCount() > 0) {
            state.emptyView.setText(R.string.file_manager_no_results);
        } else if (state.searchQuery.length() > 0
                || (state.type != FileDialogBridge.DIALOG_OPEN_FOLDER
                && !state.filterPatterns.isEmpty())) {
            state.emptyView.setText(R.string.file_manager_no_results);
        } else if (state.directoryEntryCount == 0) {
            state.emptyView.setText(R.string.file_manager_empty);
        } else if (state.type == FileDialogBridge.DIALOG_OPEN_FOLDER) {
            state.emptyView.setText(R.string.file_manager_no_folders);
        } else {
            state.emptyView.setText(R.string.file_manager_no_results);
        }
    }

    private static void showSortMenu(Activity host, State state) {
        PopupMenu menu = new PopupMenu(host, state.sortButton);
        int[] labels = {R.string.file_manager_sort_name, R.string.file_manager_sort_date,
                R.string.file_manager_sort_size};
        for (int i = 0; i < labels.length; i++) {
            MenuItem item = menu.getMenu().add(0, i, i, labels[i]);
            item.setCheckable(true);
            item.setChecked(state.sortMode == i);
        }
        menu.setOnMenuItemClickListener(item -> {
            state.sortMode = item.getItemId();
            state.sortButton.setText(sortLabel(state.sortMode));
            state.adapter.setSortMode(state.sortMode);
            return true;
        });
        menu.show();
    }

    private static int sortLabel(int mode) {
        switch (mode) {
            case 1:
                return R.string.file_manager_sort_date;
            case 2:
                return R.string.file_manager_sort_size;
            default:
                return R.string.file_manager_sort_name;
        }
    }

    private static void promptPath(Activity host, State state) {
        EditText input = new EditText(host);
        input.setSingleLine(true);
        input.setHint(R.string.file_manager_path_hint);
        input.setText(state.currentDir.getAbsolutePath());
        input.setSelection(input.length());
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(host)
                .setTitle(R.string.file_manager_path_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.global_cancel, null);
        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                File target = new File(input.getText().toString().trim());
                if (!target.isAbsolute() || !target.isDirectory()
                        || !isWithinBrowseRoot(state, target)
                        || !isAccessibleDirectory(host, target)) {
                    Toast.makeText(host, R.string.file_manager_path_invalid,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                navigateTo(host, state, target);
            });
        });
        dialog.show();
    }

    private static File initialDirectory(Activity host, String defaultPath, int type) {
        if (defaultPath != null && !defaultPath.isEmpty()) {
            File candidate = new File(defaultPath);
            if (!candidate.isDirectory()) {
                File parent = candidate.getParentFile();
                if (parent != null && parent.isDirectory()
                        && isAccessibleDirectory(host, parent)) return parent;
            }
            if (candidate.isDirectory() && isAccessibleDirectory(host, candidate)) {
                return candidate;
            }
        }
        for (File root : rootCandidates(host)) {
            if (root.isDirectory()) return root;
        }
        return host.getFilesDir();
    }

    private static String defaultSaveName(Activity host, String defaultPath) {
        if (defaultPath == null || defaultPath.isEmpty()) return "";
        File candidate = new File(defaultPath);
        if (candidate.isDirectory()) return "";
        return candidate.getName();
    }

    // Converts TinyFD filters such as "*.png", "*.tar.gz" and "png,jpg" to
    // case-insensitive filename patterns. An empty result means all files.
    private static List<String> parseFilterPatterns(String[] filterPatterns) {
        List<String> patterns = new ArrayList<>();
        if (filterPatterns != null) {
            for (String pattern : filterPatterns) {
                if (pattern == null) continue;
                for (String filter : pattern.split("[;,]")) {
                    filter = filter.trim().toLowerCase(Locale.ROOT);
                    if (filter.isEmpty()) continue;
                    if (filter.equals("*") || filter.equals("*.*")) return new ArrayList<>();
                    if (filter.indexOf('*') < 0 && filter.indexOf('?') < 0) {
                        while (filter.startsWith(".")) filter = filter.substring(1);
                        if (filter.isEmpty()) continue;
                        filter = "*." + filter;
                    }
                    if (!patterns.contains(filter)) patterns.add(filter);
                }
            }
        }
        return patterns;
    }

    @Nullable
    private static String firstSaveExtension(List<String> patterns) {
        if (patterns.isEmpty()) return null;
        String pattern = patterns.get(0);
        if (!pattern.startsWith("*.") || pattern.indexOf('*', 2) >= 0
                || pattern.indexOf('?') >= 0) return null;
        return pattern.substring(2);
    }

    private static boolean matchesFileFilter(String fileName, List<String> patterns) {
        if (patterns.isEmpty()) return true;
        String candidate = fileName.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < pattern.length(); i++) {
                char character = pattern.charAt(i);
                if (character == '*') {
                    regex.append(".*");
                } else if (character == '?') {
                    regex.append('.');
                } else {
                    regex.append(Pattern.quote(String.valueOf(character)));
                }
            }
            regex.append('$');
            if (candidate.matches(regex.toString())) return true;
        }
        return false;
    }

    private static String defaultDialogTitle(Activity host, int type, boolean allowMultiple) {
        switch (type) {
            case FileDialogBridge.DIALOG_SAVE_FILE:
                return host.getString(R.string.file_manager_title_save);
            case FileDialogBridge.DIALOG_OPEN_FOLDER:
                return host.getString(R.string.file_manager_title_folder);
            case FileDialogBridge.DIALOG_BROWSE:
                return host.getString(R.string.file_manager_title_browse);
            case DIALOG_IMPORT_FILES:
                return host.getString(R.string.file_manager_import_title);
            default:
                return host.getString(allowMultiple
                        ? R.string.file_manager_title_open_multiple
                        : R.string.file_manager_title_open);
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
        List<String> filterPatterns;
        EntryAdapter adapter;
        TextView pathView;
        MaterialButton upButton;
        TextView emptyView;
        MaterialButton selectionButton;
        LinearLayout bottomBar;
        final List<File> locations = new ArrayList<>();
        final List<MaterialButton> locationButtons = new ArrayList<>();
        ResultCallback finish;
        Dialog dialog;
        File currentDir = new File("/");
        File browseRoot = new File("/");
        final LinkedHashSet<File> selection = new LinkedHashSet<>();
        boolean manageMode;
        boolean pendingUseFolder;
        long listingRequestId;
        int sortMode;
        int directoryEntryCount = -1;
        String defaultPath;
        String searchQuery = "";
        EditText fileNameInput;
        EditText searchInput;
        MaterialButton sortButton;
        String fileName;
        File lastClickedFile;
        long lastClickTime;
        File focusedEntry;
    }

    private static class EntryAdapter extends BaseAdapter {
        private static final int VIEW_FOLDER = 0;
        private static final int VIEW_FILE = 1;
        private static final int VIEW_COUNT = 2;

        private final Activity host;
        private final State state;
        private List<File> allEntries = new ArrayList<>();
        private List<File> entries = new ArrayList<>();

        EntryAdapter(Activity host, State state) {
            this.host = host;
            this.state = state;
        }

        void setEntries(List<File> listed) {
            allEntries = new ArrayList<>(listed);
            state.directoryEntryCount = listed.size();
            updateEntries();
        }

        void setSearchQuery(String query) {
            state.searchQuery = query;
            updateEntries();
        }

        void setSortMode(int sortMode) {
            state.sortMode = sortMode;
            updateEntries();
        }

        private void updateEntries() {
            entries = new ArrayList<>(allEntries.size());
            String query = state.searchQuery.toLowerCase(Locale.ROOT);
            for (File entry : allEntries) {
                if (entry.isDirectory()
                        || (state.type != FileDialogBridge.DIALOG_OPEN_FOLDER
                        && matchesFileFilter(entry.getName(), state.filterPatterns))) {
                    if (query.isEmpty()
                            || entry.getName().toLowerCase(Locale.ROOT)
                                    .contains(query)) {
                        entries.add(entry);
                    }
                }
            }
            Collections.sort(entries, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                int comparison;
                if (state.sortMode == 1) {
                    comparison = Long.compare(b.lastModified(), a.lastModified());
                } else if (state.sortMode == 2 && !a.isDirectory()) {
                    comparison = Long.compare(b.length(), a.length());
                } else {
                    comparison = 0;
                }
                if (comparison == 0) {
                    comparison = a.getName().compareToIgnoreCase(b.getName());
                }
                return comparison;
            });
            notifyDataSetChanged();
        }

        void onRowClick(int position) {
            File entry = entries.get(position);
            if (entry.isDirectory()) {
                if (state.type == FileDialogBridge.DIALOG_BROWSE && state.manageMode) {
                    toggleSelection(entry);
                    return;
                }
                if (isDoubleClick(entry)) {
                    navigateTo(host, state, entry);
                } else {
                    state.focusedEntry = entry;
                    notifyDataSetChanged();
                }
                return;
            }
            if (state.type == FileDialogBridge.DIALOG_SAVE_FILE) {
                state.fileName = entry.getName();
                if (state.fileNameInput != null) {
                    state.fileNameInput.setText(state.fileName);
                    state.fileNameInput.setSelection(state.fileName.length());
                }
                return;
            }
            if (state.type == FileDialogBridge.DIALOG_BROWSE) {
                if (state.manageMode) {
                    toggleSelection(entry);
                    return;
                }
                if (isDoubleClick(entry)) {
                    Tools.openPath(host, entry, false);
                } else {
                    state.focusedEntry = entry;
                    notifyDataSetChanged();
                }
                return;
            }
            if (state.type == FileDialogBridge.DIALOG_OPEN_FILE
                    || state.type == DIALOG_IMPORT_FILES) {
                toggleSelection(entry);
            }
        }

        private boolean isDoubleClick(File entry) {
            long now = SystemClock.elapsedRealtime();
            boolean doubleClick = entry.equals(state.lastClickedFile)
                    && now - state.lastClickTime < 450;
            state.lastClickedFile = doubleClick ? null : entry;
            state.lastClickTime = doubleClick ? 0 : now;
            return doubleClick;
        }

        void toggleSelection(File entry) {
            if (!state.allowMultiple && (state.type == FileDialogBridge.DIALOG_OPEN_FILE
                    || state.type == DIALOG_IMPORT_FILES)) {
                state.selection.clear();
                state.selection.add(entry);
            } else if (!state.selection.remove(entry)) {
                state.selection.add(entry);
            }
            state.focusedEntry = null;
            notifyDataSetChanged();
            updateSelectionButton(host, state);
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
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = (LinearLayout) LayoutInflater.from(host).inflate(
                        R.layout.item_file_manager_entry, parent, false);
            }

            TextView nameView = row.findViewById(R.id.file_manager_name);
            TextView detailView = row.findViewById(R.id.file_manager_detail);
            TextView sizeView = row.findViewById(R.id.file_manager_size);
            TextView modifiedView = row.findViewById(R.id.file_manager_modified);
            ImageView iconView = row.findViewById(R.id.file_manager_icon);
            ImageView selectionView = row.findViewById(R.id.file_manager_check);
            File entry = getItem(position);
            nameView.setText(entry.getName());
            boolean selected = state.selection.contains(entry) || entry.equals(state.focusedEntry);
            DateFormat dateFormat = DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT);
            sizeView.setVisibility(state.landscapeLayout ? View.VISIBLE : View.GONE);
            modifiedView.setVisibility(state.landscapeLayout ? View.VISIBLE : View.GONE);
            detailView.setVisibility(state.landscapeLayout ? View.GONE : View.VISIBLE);
            if (entry.isDirectory()) {
                detailView.setText(R.string.file_manager_folder_detail);
                sizeView.setText("");
            } else {
                detailView.setText(host.getString(
                        R.string.file_manager_file_detail, readableSize(entry.length())));
                sizeView.setText(readableSize(entry.length()));
            }
            modifiedView.setText(dateFormat.format(new Date(entry.lastModified())));
            sizeView.setTextColor(MaterialColors.getColor(sizeView, selected
                    ? R.attr.colorOnPrimaryContainer : R.attr.colorOnSurfaceVariant));
            modifiedView.setTextColor(MaterialColors.getColor(modifiedView, selected
                    ? R.attr.colorOnPrimaryContainer : R.attr.colorOnSurfaceVariant));
            nameView.setTextColor(MaterialColors.getColor(nameView, selected
                    ? R.attr.colorOnPrimaryContainer : R.attr.colorOnSurface));
            detailView.setTextColor(MaterialColors.getColor(detailView, selected
                    ? R.attr.colorOnPrimaryContainer : R.attr.colorOnSurfaceVariant));
            if (entry.isDirectory()) {
                iconView.setImageResource(R.drawable.ic_folder);
                iconView.setColorFilter(MaterialColors.getColor(iconView, selected
                        ? R.attr.colorOnPrimaryContainer : R.attr.colorPrimary));
                if (selected) {
                    selectionView.setImageResource(R.drawable.ic_check_circle);
                    selectionView.setColorFilter(MaterialColors.getColor(
                            selectionView, R.attr.colorPrimary));
                    selectionView.setVisibility(View.VISIBLE);
                } else {
                    selectionView.setVisibility(View.INVISIBLE);
                }
            } else {
                iconView.setImageResource(R.drawable.ic_px_file);
                iconView.setColorFilter(MaterialColors.getColor(iconView, selected
                        ? R.attr.colorOnPrimaryContainer : R.attr.colorOnSurfaceVariant));
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
                row.setBackgroundColor(MaterialColors.getColor(row, R.attr.colorPrimaryContainer));
            } else {
                TypedValue selectableBackground = new TypedValue();
                if (host.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,
                        selectableBackground, true) && selectableBackground.resourceId != 0) {
                    row.setBackgroundResource(selectableBackground.resourceId);
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                row.setOnContextClickListener(v -> {
                    if (state.type != FileDialogBridge.DIALOG_BROWSE) return false;
                    showEntryActions(host, state, entry);
                    return true;
                });
            }
            return row;
        }
    }

    private static String readableSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(java.util.Locale.ROOT,
                "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT,
                "%.1f MB", size / (1024.0 * 1024));
        return String.format(java.util.Locale.ROOT,
                "%.1f GB", size / (1024.0 * 1024 * 1024));
    }

}
