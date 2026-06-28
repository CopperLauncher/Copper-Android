package net.kdt.pojavlaunch.modloaders;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.ImageReceiver;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.ModIconCache;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.Murmur2;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class InstalledModAdapter extends RecyclerView.Adapter<InstalledModAdapter.ModViewHolder> {

    private static final String TAG = "ModAdapter";
    private static final String MODRINTH_API   = "https://api.modrinth.com/v2";
    private static final String CURSEFORGE_API  = "https://api.curseforge.com/v1";

    public interface EmptyStateListener {
        void onEmptyStateChanged(boolean isEmpty);
    }

    private final List<ModEntry> mMods = new ArrayList<>();
    private final EmptyStateListener mEmptyListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // Local-extraction icon cache — keyed by absolute file path. Avoids re-extracting
    // from the jar (and blanking the ImageView) every time the row is rebound, e.g.
    // during an update check which calls notifyItemChanged/notifyDataSetChanged.
    private final java.util.Map<String, Bitmap> mIconCache = new java.util.HashMap<>();
    // Marks jars where local extraction found nothing AND the remote lookup (Modrinth/
    // CurseForge) also found nothing — these permanently show the placeholder glyph.
    private final java.util.Set<String> mIconCheckedNoResult = new java.util.HashSet<>();

    // Jars currently being resolved (extraction + remote lookup in flight). Without
    // this, a notifyItemChanged() that lands mid-resolution (e.g. from an update
    // check completing on a different mod and rebinding this row too) would see no
    // cache entry yet and kick off a second, redundant resolveIcon() — and briefly
    // re-show the placeholder even though the first resolution was already in
    // progress. This is what caused icons to "blink" away during update checks.
    private final java.util.Set<String> mIconResolving = new java.util.HashSet<>();

    // Disk-backed cache for icons fetched from Modrinth/CurseForge, keyed by a tag
    // derived from the file path so each mod's remote icon is cached independently.
    private final ModIconCache mRemoteIconCache = new ModIconCache();

    // Friendly mod-name cache, keyed by absolute file path — avoids re-opening
    // the jar on every rebind. An empty string means "looked, found nothing
    // embedded," so we stop retrying and just keep using the filename fallback.
    private final java.util.Map<String, String> mModNameCache = new java.util.HashMap<>();
    // Jars currently being read for their embedded name, to avoid kicking off
    // a duplicate extraction if a rebind lands mid-resolution.
    private final java.util.Set<String> mModNameResolving = new java.util.HashSet<>();

    private final Context mContext;
    private final String  mCurseforgeApiKey;

    // Per-instance filter — set by ManageModsFragment before triggering update check
    private String mFilterMcVersion = "";
    private String mFilterLoader    = "";

    // Dedicated pool for update-check network calls (Modrinth version_file + project
    // lookups, two sequential requests per mod). checkForUpdates() used to submit
    // these straight onto PojavApplication.sExecutorService — an app-wide 4-thread
    // pool that this very adapter also uses for icon extraction (resolveIcon) and
    // that's shared by virtually every other background task in the app. With more
    // than a handful of mods, the flood of slow, blocking update-check tasks could
    // occupy all 4 threads for the whole duration of the check, so any icon
    // extraction queued behind them (e.g. for rows bound/rebound while the check was
    // running) just sat waiting — mod icons wouldn't appear until the check finished.
    // Giving update checks their own small pool keeps them from blocking icon
    // loading (or anything else routed through sExecutorService) while they run.
    private static final ExecutorService sUpdateCheckExecutor = Executors.newFixedThreadPool(3);

    public InstalledModAdapter(Context context, File modsDir, EmptyStateListener listener) {
        mContext = context.getApplicationContext();
        mCurseforgeApiKey = context.getString(R.string.curseforge_api_key);
        mEmptyListener = listener;
        if (modsDir != null && modsDir.isDirectory()) {
            File[] files = modsDir.listFiles(f -> f.isFile() &&
                    (f.getName().endsWith(".jar") || f.getName().endsWith(".jar.disabled")));
            if (files != null) {
                Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File f : files) mMods.add(new ModEntry(f));
            }
        }
        notifyEmptyState();
    }

    /** Called by ManageModsFragment to inject the saved per-instance filter. */
    public void setFilter(String mcVersion, String loader) {
        mFilterMcVersion = mcVersion != null ? mcVersion : "";
        mFilterLoader    = loader    != null ? loader    : "";
    }

    // ── Update checking ───────────────────────────────────────────────────

    /**
     * Kicks off a background update check for every mod in the list.
     * For each mod: SHA1 hash the jar → ask Modrinth /version_file/{hash} for the
     * project id → ask /project/{id}/version filtered by mcVersion+loader → compare
     * the latest version file name to the current filename. If different, set the
     * update URL on the entry and show the update button.
     *
     * @param onComplete called on main thread when all checks are done, with the
     *                   count of mods that have updates available.
     */
    public void checkForUpdates(Runnable onComplete) {
        // Clear any previous update state. Only notify rows that actually had
        // an update flag set — avoids a blanket notifyDataSetChanged() which
        // would rebind every visible row and (without the icon cache) cause
        // icons to flash. The icon cache makes this safe either way now, but
        // this is still cheaper.
        for (int i = 0; i < mMods.size(); i++) {
            ModEntry e = mMods.get(i);
            if (e.updateUrl != null || e.updateFileName != null) {
                e.updateUrl      = null;
                e.updateFileName = null;
                notifyItemChanged(i);
            }
        }

        if (mFilterMcVersion.isEmpty() && mFilterLoader.isEmpty()) {
            // No filter — can't meaningfully check; caller should warn the user
            mMainHandler.post(onComplete);
            return;
        }

        final int total = mMods.size();
        if (total == 0) {
            mMainHandler.post(onComplete);
            return;
        }

        final int[] done = {0};

        for (int i = 0; i < total; i++) {
            final int index = i;
            final ModEntry entry = mMods.get(i);

            sUpdateCheckExecutor.execute(() -> {
                boolean updateFound = false;
                try {
                    checkUpdateForEntry(entry);
                    updateFound = entry.updateUrl != null;
                } catch (Exception e) {
                    Log.w(TAG, "Update check failed for " + entry.displayName() + ": " + e.getMessage());
                } finally {
                    boolean finalUpdateFound = updateFound;
                    mMainHandler.post(() -> {
                        // Only rebind this row if it actually changed — avoids
                        // rebinding every mod's row (and its icon) for checks
                        // that found nothing.
                        if (finalUpdateFound && index < mMods.size()) notifyItemChanged(index);
                        done[0]++;
                        if (done[0] >= total) onComplete.run();
                    });
                }
            });
        }
    }

    private void checkUpdateForEntry(ModEntry entry) throws Exception {
        // 1. SHA1 hash the jar file
        String sha1 = sha1Hex(entry.file);
        if (sha1 == null) return;

        ApiHandler api = new ApiHandler(MODRINTH_API);

        // 2. Look up which project+version this file belongs to
        //    GET /version_file/{hash}?algorithm=sha1
        java.util.HashMap<String, Object> hashParams = new java.util.HashMap<>();
        hashParams.put("algorithm", "sha1");
        JsonObject fileVersion = api.get("version_file/" + sha1, hashParams, JsonObject.class);
        if (fileVersion == null) return; // Not on Modrinth

        String projectId = fileVersion.has("project_id")
                ? fileVersion.get("project_id").getAsString() : null;
        if (projectId == null) return;

        // The version this exact jar belongs to, per Modrinth. This is the
        // canonical identity we compare against below — NOT the file name.
        // Mods installed from a modpack are saved under whatever file name the
        // pack's modrinth.index.json recorded, which can legitimately differ
        // from Modrinth's current "primary file" name for that same version
        // (the content/hash is identical, only the on-disk name differs). A
        // name-only comparison flags those as having an update every time,
        // and clicking "update" just re-downloads the identical version under
        // Modrinth's canonical name — after which the name matches and the
        // false positive disappears, i.e. it only happens once per mod.
        String currentVersionId = fileVersion.has("id")
                ? fileVersion.get("id").getAsString() : null;

        // 3. Get all versions of the project filtered by our mc version + loader
        java.util.HashMap<String, Object> params = new java.util.HashMap<>();
        if (!mFilterMcVersion.isEmpty()) params.put("game_versions", "[\"" + mFilterMcVersion + "\"]");
        if (!mFilterLoader.isEmpty())    params.put("loaders",        "[\"" + mFilterLoader    + "\"]");

        JsonArray versions = api.get("project/" + projectId + "/version", params, JsonArray.class);
        if (versions == null || versions.size() == 0) return;

        // Modrinth returns newest first — index 0 is the latest
        JsonObject latest = versions.get(0).getAsJsonObject();

        // If the installed file is already the latest version, there's
        // nothing to do — regardless of what its file name happens to be.
        String latestVersionId = latest.has("id") ? latest.get("id").getAsString() : null;
        if (currentVersionId != null && currentVersionId.equals(latestVersionId)) return;

        // 4. Get the latest version's primary file name
        JsonArray files = latest.getAsJsonArray("files");
        if (files == null || files.size() == 0) return;

        // Find the primary file (primary=true) or fall back to first
        JsonObject primaryFile = null;
        for (int i = 0; i < files.size(); i++) {
            JsonObject f = files.get(i).getAsJsonObject();
            if (f.has("primary") && f.get("primary").getAsBoolean()) {
                primaryFile = f;
                break;
            }
        }
        if (primaryFile == null) primaryFile = files.get(0).getAsJsonObject();

        String latestUrl      = primaryFile.get("url").getAsString();
        String latestFileName = latestUrl.substring(latestUrl.lastIndexOf('/') + 1);
        if (latestFileName.contains("?")) latestFileName = latestFileName.substring(0, latestFileName.indexOf('?'));

        // 5. Reaching here means the early-return above didn't fire, i.e. the
        // installed file's version id is missing or genuinely differs from
        // the latest version id — a real update, independent of file naming.
        entry.updateUrl      = latestUrl;
        entry.updateFileName = latestFileName;
    }

    /** Downloads the update, replaces the existing jar, refreshes the entry. */
    private void applyUpdate(Context context, ModEntry entry, int position) {
        if (entry.updateUrl == null) return;

        String updateUrl  = entry.updateUrl;
        String updateName = entry.updateFileName;

        // Determine target file (keep disabled state)
        boolean wasDisabled = entry.file.getName().endsWith(".disabled");
        String  targetName  = wasDisabled ? updateName + ".disabled" : updateName;
        File    targetFile  = new File(entry.file.getParent(), targetName);

        Toast.makeText(context,
                context.getString(R.string.mod_updating, entry.displayName()),
                Toast.LENGTH_SHORT).show();

        sUpdateCheckExecutor.execute(() -> {
            try {
                // Download to a temp file first so we never leave a half-written jar
                File tmpFile = new File(entry.file.getParent(), targetName + ".tmp");
                DownloadUtils.downloadFile(updateUrl, tmpFile);

                // Delete old file, rename temp to final
                entry.file.delete();
                tmpFile.renameTo(targetFile);

                mMainHandler.post(() -> {
                    entry.file        = targetFile;
                    entry.enabled     = !wasDisabled;
                    entry.updateUrl   = null;
                    entry.updateFileName = null;
                    if (position < mMods.size()) notifyItemChanged(position);
                    Toast.makeText(context,
                            context.getString(R.string.mod_update_done, entry.displayName()),
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Update download failed: " + e.getMessage());
                mMainHandler.post(() ->
                        Toast.makeText(context,
                                context.getString(R.string.mod_update_failed, entry.displayName()),
                                Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ── Switch version ───────────────────────────────────────────────────────

    /**
     * Opens a Modrinth-style "Switch version" dialog for the given mod: resolves
     * its Modrinth project id from the installed jar's SHA1 hash, fetches every
     * version of that project (not just ones matching the instance's mc
     * version/loader, so the user can also see — and optionally show —
     * incompatible versions), and lets them tap any version to switch to it.
     */
    private void showSwitchVersionDialog(Context context, ModEntry entry, int position) {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_switch_mod_version, null);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .create();

        TextView titleView          = dialogView.findViewById(R.id.switch_version_title);
        ImageButton closeButton     = dialogView.findViewById(R.id.switch_version_close);
        EditText searchBox          = dialogView.findViewById(R.id.switch_version_search);
        ProgressBar progressBar     = dialogView.findViewById(R.id.switch_version_progress);
        View errorLayout            = dialogView.findViewById(R.id.switch_version_error_layout);
        TextView errorTextView      = dialogView.findViewById(R.id.switch_version_error_textview);
        Button retryButton          = dialogView.findViewById(R.id.switch_version_retry_button);
        RecyclerView listView       = dialogView.findViewById(R.id.switch_version_list);
        TextView toggleIncompatible = dialogView.findViewById(R.id.switch_version_toggle_incompatible);
        Button cancelButton         = dialogView.findViewById(R.id.switch_version_cancel);

        titleView.setText(context.getString(R.string.switch_mod_version_title) + " — " + entry.displayName());
        closeButton.setOnClickListener(v -> dialog.dismiss());
        cancelButton.setOnClickListener(v -> dialog.dismiss());

        listView.setLayoutManager(new LinearLayoutManager(context));
        VersionRowAdapter adapter = new VersionRowAdapter(chosen -> {
            String message = chosen.isCurrent
                    ? context.getString(R.string.switch_mod_version_confirm_reinstall_message, entry.displayName())
                    : context.getString(R.string.switch_mod_version_confirm_message, entry.displayName(), chosen.versionNumber);
            new AlertDialog.Builder(context)
                    .setTitle(R.string.switch_mod_version_confirm_title)
                    .setMessage(message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        dialog.dismiss();
                        applySwitchVersion(context, entry, position, chosen.fileUrl, chosen.fileName, chosen.versionNumber);
                    })
                    .show();
        });
        listView.setAdapter(adapter);

        final boolean[] showIncompatible = {false};
        toggleIncompatible.setOnClickListener(v -> {
            showIncompatible[0] = !showIncompatible[0];
            toggleIncompatible.setText(showIncompatible[0]
                    ? R.string.switch_mod_version_hide_incompatible
                    : R.string.switch_mod_version_show_incompatible);
            adapter.setShowIncompatible(showIncompatible[0]);
        });

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { adapter.setSearchQuery(s.toString()); }
        });

        Runnable loadVersions = () -> {
            progressBar.setVisibility(View.VISIBLE);
            errorLayout.setVisibility(View.GONE);
            listView.setVisibility(View.GONE);

            sUpdateCheckExecutor.execute(() -> {
                List<VersionRow> rows = null;
                boolean projectNotFound = false;
                try {
                    String sha1 = sha1Hex(entry.file);
                    ApiHandler api = new ApiHandler(MODRINTH_API);
                    String projectId = null;
                    String currentVersionId = null;

                    if (sha1 != null) {
                        java.util.HashMap<String, Object> hashParams = new java.util.HashMap<>();
                        hashParams.put("algorithm", "sha1");
                        JsonObject fileVersion = api.get("version_file/" + sha1, hashParams, JsonObject.class);
                        if (fileVersion != null) {
                            if (fileVersion.has("project_id")) projectId = fileVersion.get("project_id").getAsString();
                            if (fileVersion.has("id")) currentVersionId = fileVersion.get("id").getAsString();
                        }
                    }

                    if (projectId == null) {
                        projectNotFound = true;
                    } else {
                        JsonArray versions = api.get("project/" + projectId + "/version", JsonArray.class);
                        if (versions != null && versions.size() > 0) {
                            rows = new ArrayList<>();
                            for (int i = 0; i < versions.size(); i++) {
                                VersionRow row = parseVersionRow(versions.get(i).getAsJsonObject(), currentVersionId);
                                if (row != null) rows.add(row);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Switch-version fetch failed for " + entry.displayName() + ": " + e.getMessage());
                }

                final List<VersionRow> finalRows = rows;
                final boolean finalNotFound = projectNotFound;
                mMainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    if (finalRows == null || finalRows.isEmpty()) {
                        errorLayout.setVisibility(View.VISIBLE);
                        listView.setVisibility(View.GONE);
                        errorTextView.setText(finalNotFound
                                ? R.string.switch_mod_version_not_found
                                : R.string.switch_mod_version_no_versions);
                    } else {
                        errorLayout.setVisibility(View.GONE);
                        listView.setVisibility(View.VISIBLE);
                        adapter.setVersions(finalRows);
                    }
                });
            });
        };

        retryButton.setOnClickListener(v -> loadVersions.run());
        loadVersions.run();
        dialog.show();
    }

    /** Parses one entry of Modrinth's GET /project/{id}/version response. */
    @Nullable
    private VersionRow parseVersionRow(JsonObject v, @Nullable String currentVersionId) {
        try {
            if (!v.has("id") || v.get("id").isJsonNull()) return null;
            String id = v.get("id").getAsString();

            JsonArray files = v.has("files") ? v.getAsJsonArray("files") : null;
            JsonObject primaryFile = null;
            if (files != null) {
                for (int i = 0; i < files.size(); i++) {
                    JsonObject f = files.get(i).getAsJsonObject();
                    if (f.has("primary") && f.get("primary").getAsBoolean()) { primaryFile = f; break; }
                }
                if (primaryFile == null && files.size() > 0) primaryFile = files.get(0).getAsJsonObject();
            }
            if (primaryFile == null || !primaryFile.has("url")) return null;

            String fileUrl = primaryFile.get("url").getAsString();
            String fileName = fileUrl.substring(fileUrl.lastIndexOf('/') + 1);
            if (fileName.contains("?")) fileName = fileName.substring(0, fileName.indexOf('?'));

            VersionRow row = new VersionRow();
            row.id = id;
            row.versionNumber = v.has("version_number") && !v.get("version_number").isJsonNull()
                    ? v.get("version_number").getAsString() : id;
            row.releaseType = v.has("version_type") && !v.get("version_type").isJsonNull()
                    ? v.get("version_type").getAsString() : "release";
            row.datePublished = v.has("date_published") && !v.get("date_published").isJsonNull()
                    ? v.get("date_published").getAsString() : null;
            row.fileUrl = fileUrl;
            row.fileName = fileName;

            row.gameVersions = new ArrayList<>();
            if (v.has("game_versions")) {
                JsonArray arr = v.getAsJsonArray("game_versions");
                for (int i = 0; i < arr.size(); i++) row.gameVersions.add(arr.get(i).getAsString());
            }
            row.loaders = new ArrayList<>();
            if (v.has("loaders")) {
                JsonArray arr = v.getAsJsonArray("loaders");
                for (int i = 0; i < arr.size(); i++) row.loaders.add(arr.get(i).getAsString());
            }

            row.isCurrent = id.equals(currentVersionId);
            boolean mcOk = mFilterMcVersion.isEmpty() || row.gameVersions.contains(mFilterMcVersion);
            boolean loaderOk = mFilterLoader.isEmpty() || containsIgnoreCase(row.loaders, mFilterLoader);
            row.isCompatible = mcOk && loaderOk;
            return row;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String s : list) if (s.equalsIgnoreCase(value)) return true;
        return false;
    }

    /** Downloads the chosen version and swaps it in, mirroring applyUpdate(). */
    private void applySwitchVersion(Context context, ModEntry entry, int position,
                                     String url, String fileName, String versionLabel) {
        boolean wasDisabled = entry.file.getName().endsWith(".disabled");
        String  targetName  = wasDisabled ? fileName + ".disabled" : fileName;
        File    targetFile  = new File(entry.file.getParent(), targetName);
        final File oldFile  = entry.file;

        Toast.makeText(context,
                context.getString(R.string.switch_mod_version_switching, versionLabel),
                Toast.LENGTH_SHORT).show();

        sUpdateCheckExecutor.execute(() -> {
            try {
                File tmpFile = new File(entry.file.getParent(), targetName + ".tmp");
                DownloadUtils.downloadFile(url, tmpFile);

                oldFile.delete();
                tmpFile.renameTo(targetFile);

                mMainHandler.post(() -> {
                    entry.file        = targetFile;
                    entry.enabled     = !wasDisabled;
                    entry.updateUrl   = null;
                    entry.updateFileName = null;

                    // The file path changed — drop cache entries keyed by the old
                    // path so the icon/name get freshly re-resolved for the new jar.
                    String oldPath = oldFile.getAbsolutePath();
                    mIconCache.remove(oldPath);
                    mModNameCache.remove(oldPath);
                    mIconCheckedNoResult.remove(oldPath);
                    mIconResolving.remove(oldPath);
                    mModNameResolving.remove(oldPath);

                    if (position < mMods.size()) notifyItemChanged(position);
                    Toast.makeText(context,
                            context.getString(R.string.switch_mod_version_done, entry.displayName(), versionLabel),
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Version switch failed: " + e.getMessage());
                mMainHandler.post(() ->
                        Toast.makeText(context,
                                context.getString(R.string.switch_mod_version_failed, entry.displayName()),
                                Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** Lightweight model for one row in the switch-version list. */
    private static class VersionRow {
        String id, versionNumber, releaseType, datePublished, fileUrl, fileName;
        List<String> gameVersions = new ArrayList<>();
        List<String> loaders = new ArrayList<>();
        boolean isCurrent;
        boolean isCompatible;
    }

    /** RecyclerView adapter backing the switch-version dialog's list. */
    private static class VersionRowAdapter extends RecyclerView.Adapter<VersionRowAdapter.RowHolder> {

        interface OnVersionClickListener {
            void onVersionClick(VersionRow row);
        }

        private final OnVersionClickListener mListener;
        private List<VersionRow> mAllRows = new ArrayList<>();
        private List<VersionRow> mVisibleRows = new ArrayList<>();
        private boolean mShowIncompatible = false;
        private String mQuery = "";

        VersionRowAdapter(OnVersionClickListener listener) {
            mListener = listener;
        }

        @SuppressLint("NotifyDataSetChanged")
        void setVersions(List<VersionRow> rows) {
            mAllRows = rows;
            applyFilter();
        }

        @SuppressLint("NotifyDataSetChanged")
        void setShowIncompatible(boolean show) {
            mShowIncompatible = show;
            applyFilter();
        }

        @SuppressLint("NotifyDataSetChanged")
        void setSearchQuery(String query) {
            mQuery = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
            applyFilter();
        }

        @SuppressLint("NotifyDataSetChanged")
        private void applyFilter() {
            List<VersionRow> visible = new ArrayList<>();
            for (VersionRow row : mAllRows) {
                // Always keep the currently installed version visible, even if it
                // no longer matches the instance's filter (e.g. mc version changed).
                if (!mShowIncompatible && !row.isCompatible && !row.isCurrent) continue;
                if (!mQuery.isEmpty()
                        && !row.versionNumber.toLowerCase(java.util.Locale.ROOT).contains(mQuery)
                        && !row.fileName.toLowerCase(java.util.Locale.ROOT).contains(mQuery)) continue;
                visible.add(row);
            }
            mVisibleRows = visible;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_mod_version_row, parent, false);
            return new RowHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RowHolder holder, int position) {
            holder.bind(mVisibleRows.get(position), mListener);
        }

        @Override
        public int getItemCount() {
            return mVisibleRows.size();
        }

        static class RowHolder extends RecyclerView.ViewHolder {
            final View root;
            final TextView typeBadge, nameView, subtitleView, currentPill;
            final ImageView incompatibleIcon;

            RowHolder(@NonNull View itemView) {
                super(itemView);
                root             = itemView.findViewById(R.id.version_row_root);
                typeBadge        = itemView.findViewById(R.id.version_row_type_badge);
                nameView         = itemView.findViewById(R.id.version_row_name);
                subtitleView     = itemView.findViewById(R.id.version_row_subtitle);
                currentPill      = itemView.findViewById(R.id.version_row_current_pill);
                incompatibleIcon = itemView.findViewById(R.id.version_row_incompatible_icon);
            }

            void bind(VersionRow row, OnVersionClickListener listener) {
                nameView.setText(row.versionNumber);
                subtitleView.setText(buildSubtitle(row));

                String badgeLetter;
                int badgeColorRes;
                if ("beta".equalsIgnoreCase(row.releaseType)) {
                    badgeLetter = "B"; badgeColorRes = R.color.mod_version_beta;
                } else if ("alpha".equalsIgnoreCase(row.releaseType)) {
                    badgeLetter = "A"; badgeColorRes = R.color.mod_version_alpha;
                } else {
                    badgeLetter = "R"; badgeColorRes = R.color.mod_version_release;
                }
                typeBadge.setText(badgeLetter);
                // mutate() first — this drawable resource is shared across rows,
                // and without it setTint() would tint every badge at once.
                typeBadge.getBackground().mutate()
                        .setTint(ContextCompat.getColor(itemView.getContext(), badgeColorRes));

                if (row.isCurrent) {
                    currentPill.setVisibility(View.VISIBLE);
                    incompatibleIcon.setVisibility(View.GONE);
                    root.setBackgroundResource(R.drawable.bg_version_row_current);
                } else {
                    currentPill.setVisibility(View.GONE);
                    incompatibleIcon.setVisibility(row.isCompatible ? View.GONE : View.VISIBLE);
                    root.setBackgroundResource(R.drawable.bg_version_row);
                }

                itemView.setOnClickListener(v -> listener.onVersionClick(row));
            }

            private static String buildSubtitle(VersionRow row) {
                StringBuilder sb = new StringBuilder();
                if (!row.loaders.isEmpty()) sb.append(capitalize(row.loaders.get(0)));
                if (!row.gameVersions.isEmpty()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(row.gameVersions.get(row.gameVersions.size() - 1));
                }
                String date = formatDate(row.datePublished);
                if (date != null) {
                    if (sb.length() > 0) sb.append(" • ");
                    sb.append(date);
                }
                return sb.toString();
            }

            private static String capitalize(String s) {
                if (s == null || s.isEmpty()) return s;
                return s.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + s.substring(1);
            }

            @Nullable
            private static String formatDate(@Nullable String iso) {
                if (iso == null || iso.length() < 10) return iso;
                try {
                    String datePart = iso.substring(0, 10);
                    java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                    java.util.Date date = in.parse(datePart);
                    java.text.SimpleDateFormat out = new java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault());
                    return date != null ? out.format(date) : datePart;
                } catch (Exception e) {
                    return iso.substring(0, 10);
                }
            }
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    @NonNull
    @Override
    public ModViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_installed_mod, parent, false);
        return new ModViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ModViewHolder holder, int position) {
        holder.bind(mMods.get(position));
    }

    @Override
    public void onViewRecycled(@NonNull ModViewHolder holder) {
        holder.icon.setTag(null);
        holder.icon.setImageResource(R.drawable.ic_mod_placeholder);
        holder.name.setTag(null);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() { return mMods.size(); }

    private void notifyEmptyState() {
        if (mEmptyListener != null) mEmptyListener.onEmptyStateChanged(mMods.isEmpty());
    }

    // ── ViewHolder ────────────────────────────────────────────────────────

    class ModViewHolder extends RecyclerView.ViewHolder {
        final ImageView   icon;
        final TextView    name, version;
        final SwitchCompat toggle;
        final android.widget.Button update;
        final ImageButton delete;
        final ImageButton switchVersion;

        ModViewHolder(@NonNull View itemView) {
            super(itemView);
            icon   = itemView.findViewById(R.id.installed_mod_icon);
            name   = itemView.findViewById(R.id.installed_mod_name);
            version= itemView.findViewById(R.id.installed_mod_version);
            toggle = itemView.findViewById(R.id.installed_mod_toggle);
            update = itemView.findViewById(R.id.installed_mod_update);
            delete = itemView.findViewById(R.id.installed_mod_delete);
            switchVersion = itemView.findViewById(R.id.installed_mod_switch_version);
        }

        void bind(ModEntry entry) {
            final String path = entry.file.getAbsolutePath();

            // Mod name — show the embedded display name (e.g. "Sodium") once
            // resolved, falling back to the cleaned-up file name until then.
            name.setTag(path);
            String cachedName = mModNameCache.get(path);
            if (cachedName != null) {
                entry.metaName = cachedName.isEmpty() ? null : cachedName;
                name.setText(entry.displayName());
            } else {
                name.setText(entry.displayName());
                if (!mModNameResolving.contains(path)) {
                    mModNameResolving.add(path);
                    resolveModName(entry, name, path);
                }
            }

            version.setText(entry.file.getName());

            icon.setTag(path);

            // Cache hit — apply immediately, no flash, no re-read from disk
            Bitmap cached = mIconCache.get(path);
            if (cached != null) {
                icon.setImageBitmap(cached);
            } else if (mIconCheckedNoResult.contains(path)) {
                // Already exhausted local extraction AND remote lookup for this jar —
                // genuinely no icon exists anywhere. Show the missing-icon glyph and
                // stop retrying on every rebind.
                icon.setImageResource(R.drawable.ic_mod_placeholder);
            } else if (mIconResolving.contains(path)) {
                // Resolution already running for this jar (kicked off by an earlier
                // bind) — don't start a second one, and don't stomp whatever's
                // showing; leave the placeholder that's already there alone.
                icon.setImageResource(R.drawable.ic_mod_placeholder);
            } else {
                // First time seeing this mod — show the placeholder while we resolve.
                icon.setImageResource(R.drawable.ic_mod_placeholder);
                mIconResolving.add(path);
                resolveIcon(entry, icon, path);
            }


            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(entry.enabled);
            toggle.setOnCheckedChangeListener((btn, checked) -> entry.setEnabled(checked));

            // Update button — visible only when an update is available
            if (entry.updateUrl != null) {
                update.setVisibility(View.VISIBLE);
                update.setOnClickListener(v -> {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) applyUpdate(v.getContext(), entry, pos);
                });
            } else {
                update.setVisibility(View.GONE);
                update.setOnClickListener(null);
            }

            switchVersion.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) showSwitchVersionDialog(v.getContext(), entry, pos);
            });

            delete.setOnClickListener(v -> {
                Context ctx = v.getContext();
                new AlertDialog.Builder(ctx)
                        .setTitle(ctx.getString(R.string.manage_mods_delete_confirm, entry.displayName()))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (d, i) -> {
                            entry.file.delete();
                            int p = getBindingAdapterPosition();
                            if (p != RecyclerView.NO_POSITION) {
                                mMods.remove(p);
                                notifyItemRemoved(p);
                                notifyEmptyState();
                            }
                        })
                        .show();
            });
        }
    }

    // ── ModEntry ──────────────────────────────────────────────────────────

    static class ModEntry {
        File   file;
        boolean enabled;
        @Nullable String updateUrl;
        @Nullable String updateFileName;
        // Friendly name read out of the jar's own metadata (fabric.mod.json,
        // mods.toml, etc.) once resolved. Null/empty until resolved or if
        // the jar simply has no name field — displayName() falls back to
        // the cleaned-up file name in either case. This NEVER renames the
        // actual .jar on disk; it only changes what's shown in the list.
        @Nullable String metaName;

        ModEntry(File f) {
            this.file    = f;
            this.enabled = !f.getName().endsWith(".disabled");
        }

        String displayName() {
            if (metaName != null && !metaName.isEmpty()) return metaName;
            String n = file.getName();
            if (n.endsWith(".jar.disabled")) n = n.substring(0, n.length() - 13);
            else if (n.endsWith(".jar"))     n = n.substring(0, n.length() - 4);
            return n;
        }

        void setEnabled(boolean enable) {
            if (enable == this.enabled) return;
            File target = enable
                    ? new File(file.getParent(), file.getName().replace(".jar.disabled", ".jar"))
                    : new File(file.getParent(), file.getName() + ".disabled");
            if (file.renameTo(target)) {
                file = target;
                this.enabled = enable;
            }
        }
    }

    // ── Icon resolution chain ───────────────────────────────────────────────

    /**
     * Resolves an icon for a mod that wasn't found in the in-memory cache yet.
     * Order: (1) extract embedded icon from the jar itself, which covers most
     * Fabric/Quilt mods and modern Forge mods that ship fabric.mod.json/
     * mods.toml icon refs; (2) if that fails — common for old Forge 1.7-1.12
     * mods and other jars with no embedded icon at all — hash the file and
     * ask Modrinth for its project icon_url; (3) if Modrinth doesn't recognise
     * the file, hash it with CurseForge's murmur2 fingerprint and ask
     * CurseForge for its logo thumbnail. Whichever step succeeds wins; if all
     * three fail, the jar is marked as having no resolvable icon at all.
     */
    private void resolveIcon(ModEntry entry, ImageView iconView, String path) {
        final String expectedTag = path;
        final WeakReference<ImageView> iconRef = new WeakReference<>(iconView);
        final File jarFile = entry.file;

        PojavApplication.sExecutorService.execute(() -> {
            Bitmap bmp = extractModIcon(jarFile);

            if (bmp != null) {
                cacheAndApply(path, bmp, expectedTag, iconRef);
                return;
            }

            // Not found locally — try Modrinth, then CurseForge, by file hash.
            String remoteUrl = resolveRemoteIconUrl(jarFile);
            if (remoteUrl == null) {
                // Genuinely nothing found anywhere.
                mMainHandler.post(() -> {
                    mIconCheckedNoResult.add(path);
                    mIconResolving.remove(path);
                });
                return;
            }

            // Fetch (and disk-cache) the remote icon via the existing ModIconCache,
            // tagged by the mod's file path so different mods don't collide.
            String tag = "installed_" + path.hashCode();
            mRemoteIconCache.getImage(bitmap -> {
                if (bitmap != null) {
                    cacheAndApply(path, bitmap, expectedTag, iconRef);
                } else {
                    mMainHandler.post(() -> {
                        mIconCheckedNoResult.add(path);
                        mIconResolving.remove(path);
                    });
                }
            }, tag, remoteUrl);
        });
    }

    private void cacheAndApply(String path, Bitmap bmp, String expectedTag,
                                WeakReference<ImageView> iconRef) {
        mMainHandler.post(() -> {
            mIconCache.put(path, bmp);
            mIconResolving.remove(path);
            ImageView iv = iconRef.get();
            if (iv != null && expectedTag.equals(iv.getTag())) {
                iv.setImageBitmap(bmp);
            }
        });
    }

    /**
     * Resolves the mod's embedded display name (e.g. "Sodium" instead of
     * "sodium-fabric-0.5.8+mc1.20.1.jar") by reading the jar's own metadata.
     * Purely local — no network call needed, since virtually every mod
     * ships its display name inside fabric.mod.json/quilt.mod.json/
     * mods.toml/mcmod.info. Falls back permanently to the cleaned-up file
     * name (handled by ModEntry#displayName) if nothing is found, without
     * ever touching the actual .jar file on disk.
     */
    private void resolveModName(ModEntry entry, TextView nameView, String path) {
        final String expectedTag = path;
        final WeakReference<TextView> nameRef = new WeakReference<>(nameView);
        final File jarFile = entry.file;

        PojavApplication.sExecutorService.execute(() -> {
            String resolved = extractModName(jarFile);
            mMainHandler.post(() -> {
                mModNameCache.put(path, resolved != null ? resolved : "");
                mModNameResolving.remove(path);
                entry.metaName = resolved;
                TextView tv = nameRef.get();
                if (tv != null && expectedTag.equals(tv.getTag())) {
                    tv.setText(entry.displayName());
                }
            });
        });
    }

    /**
     * Looks up the project icon URL for a jar that has no embedded icon, trying
     * Modrinth first (SHA1-based, simpler/faster), then CurseForge (murmur2
     * fingerprint-based) as a fallback for mods only distributed there.
     * Runs on a background thread; performs blocking network I/O.
     */
    @Nullable
    private String resolveRemoteIconUrl(File jarFile) {
        // 1. Modrinth — SHA1 hash → version_file → project → icon_url
        try {
            String sha1 = sha1Hex(jarFile);
            if (sha1 != null) {
                ApiHandler modrinth = new ApiHandler(MODRINTH_API);
                java.util.HashMap<String, Object> hashParams = new java.util.HashMap<>();
                hashParams.put("algorithm", "sha1");
                JsonObject fileVersion = modrinth.get("version_file/" + sha1, hashParams, JsonObject.class);
                if (fileVersion != null && fileVersion.has("project_id")) {
                    String projectId = fileVersion.get("project_id").getAsString();
                    JsonObject project = modrinth.get("project/" + projectId, JsonObject.class);
                    if (project != null && project.has("icon_url") && !project.get("icon_url").isJsonNull()) {
                        String url = project.get("icon_url").getAsString();
                        if (url != null && !url.isEmpty()) return url;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Modrinth icon lookup failed for " + jarFile.getName() + ": " + e.getMessage());
        }

        // 2. CurseForge — murmur2 fingerprint → fingerprints/fuzzy match → logo.thumbnailUrl
        //    This is the main path for old Forge mods (1.7–1.12) that predate
        //    Modrinth entirely and were only ever uploaded to CurseForge.
        if (mCurseforgeApiKey == null || mCurseforgeApiKey.isEmpty()) return null;
        try {
            long fingerprint = Murmur2.hashFile(jarFile);

            JsonArray fingerprints = new JsonArray();
            fingerprints.add(fingerprint);
            JsonObject body = new JsonObject();
            body.add("fingerprints", fingerprints);

            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("x-api-key", mCurseforgeApiKey);
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "application/json");

            String responseRaw = ApiHandler.postRaw(headers,
                    CURSEFORGE_API + "/fingerprints", body.toString());
            if (responseRaw == null) return null;

            JsonObject response = JsonParser.parseString(responseRaw).getAsJsonObject();
            if (!response.has("data")) return null;
            JsonObject data = response.getAsJsonObject("data");

            JsonArray exactMatches = data.has("exactMatches") ? data.getAsJsonArray("exactMatches") : null;
            if (exactMatches == null || exactMatches.size() == 0) return null;

            JsonObject match = exactMatches.get(0).getAsJsonObject();
            if (!match.has("file")) return null;
            JsonObject file = match.getAsJsonObject("file");
            if (!file.has("modId")) return null;
            int modId = file.get("modId").getAsInt();

            JsonObject modResponse = new ApiHandler(CURSEFORGE_API, mCurseforgeApiKey)
                    .get("mods/" + modId, JsonObject.class);
            if (modResponse == null || !modResponse.has("data")) return null;
            JsonObject modData = modResponse.getAsJsonObject("data");
            if (!modData.has("logo") || modData.get("logo").isJsonNull()) return null;
            JsonObject logo = modData.getAsJsonObject("logo");
            if (!logo.has("thumbnailUrl") || logo.get("thumbnailUrl").isJsonNull()) return null;

            String url = logo.get("thumbnailUrl").getAsString();
            return (url != null && !url.isEmpty()) ? url : null;
        } catch (Exception e) {
            Log.w(TAG, "CurseForge icon lookup failed for " + jarFile.getName() + ": " + e.getMessage());
            return null;
        }
    }

    // ── SHA1 ──────────────────────────────────────────────────────────────

    @Nullable
    private static String sha1Hex(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[8192];
            try (FileInputStream fis = new FileInputStream(file)) {
                int read;
                while ((read = fis.read(buf)) != -1) md.update(buf, 0, read);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "SHA1 failed for " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    // ── Icon extraction ───────────────────────────────────────────────────

    @Nullable
    private static Bitmap extractModIcon(File jarFile) {
        try (ZipFile zip = new ZipFile(jarFile)) {
            String iconPath = resolveIconPath(zip);
            if (iconPath != null) {
                Bitmap bmp = loadEntryAsBitmap(zip, iconPath);
                if (bmp != null) return bmp;
            }
            for (String fallback : new String[]{"pack.png", "icon.png", "logo.png"}) {
                Bitmap bmp = loadEntryAsBitmap(zip, fallback);
                if (bmp != null) return bmp;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to open JAR: " + jarFile.getName() + " — " + e.getMessage());
        }
        return null;
    }

    /**
     * Reads the mod's own display name straight out of its metadata file,
     * mirroring resolveIconPath's loader-by-loader fallback chain. Returns
     * null if the jar has no parsable metadata or no name field at all
     * (e.g. very old/bare Forge 1.7-era jars) — callers fall back to the
     * cleaned-up file name in that case.
     */
    @Nullable
    private static String extractModName(File jarFile) {
        try (ZipFile zip = new ZipFile(jarFile)) {
            String content = readEntry(zip, "fabric.mod.json");
            if (content != null) {
                try {
                    JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
                    if (obj.has("name") && !obj.get("name").isJsonNull()) {
                        String name = obj.get("name").getAsString().trim();
                        if (!name.isEmpty()) return name;
                    }
                } catch (Exception ignored) {}
            }
            content = readEntry(zip, "quilt.mod.json");
            if (content != null) {
                try {
                    JsonObject root = JsonParser.parseString(content).getAsJsonObject();
                    JsonObject ql = root.has("quilt_loader") ? root.getAsJsonObject("quilt_loader") : null;
                    if (ql != null && ql.has("metadata")) {
                        JsonObject meta = ql.getAsJsonObject("metadata");
                        if (meta.has("name") && !meta.get("name").isJsonNull()) {
                            String name = meta.get("name").getAsString().trim();
                            if (!name.isEmpty()) return name;
                        }
                    }
                } catch (Exception ignored) {}
            }
            content = readEntry(zip, "mcmod.info");
            if (content != null) {
                try {
                    JsonArray arr = JsonParser.parseString(content).getAsJsonArray();
                    if (arr.size() > 0 && arr.get(0).isJsonObject()) {
                        JsonObject mod = arr.get(0).getAsJsonObject();
                        if (mod.has("name") && !mod.get("name").isJsonNull()) {
                            String name = mod.get("name").getAsString().trim();
                            if (!name.isEmpty()) return name;
                        }
                    }
                } catch (Exception ignored) {}
            }
            for (String toml : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
                content = readEntry(zip, toml);
                if (content != null) {
                    String displayName = tomlStringField(content, "displayName");
                    if (displayName != null && !displayName.isEmpty()) return displayName.trim();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read mod name from JAR: " + jarFile.getName() + " — " + e.getMessage());
        }
        return null;
    }

    @Nullable
    private static String resolveIconPath(ZipFile zip) {
        String content = readEntry(zip, "fabric.mod.json");
        if (content != null) {
            try {
                JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
                if (obj.has("icon")) {
                    JsonElement iconEl = obj.get("icon");
                    if (iconEl.isJsonPrimitive()) return iconEl.getAsString();
                    if (iconEl.isJsonObject()) {
                        JsonObject sizeMap = iconEl.getAsJsonObject();
                        String best = null; int bestSize = 0;
                        for (String key : sizeMap.keySet()) {
                            try {
                                int sz = Integer.parseInt(key);
                                if (sz > bestSize) { bestSize = sz; best = sizeMap.get(key).getAsString(); }
                            } catch (NumberFormatException ignored) {
                                best = sizeMap.get(key).getAsString();
                            }
                        }
                        if (best != null) return best;
                    }
                }
            } catch (Exception ignored) {}
        }
        content = readEntry(zip, "quilt.mod.json");
        if (content != null) {
            try {
                JsonObject root = JsonParser.parseString(content).getAsJsonObject();
                JsonObject ql = root.has("quilt_loader") ? root.getAsJsonObject("quilt_loader") : null;
                if (ql != null && ql.has("metadata")) {
                    JsonObject meta = ql.getAsJsonObject("metadata");
                    if (meta.has("icon") && meta.get("icon").isJsonPrimitive())
                        return meta.get("icon").getAsString();
                }
            } catch (Exception ignored) {}
        }
        content = readEntry(zip, "mcmod.info");
        if (content != null) {
            try {
                JsonArray arr = JsonParser.parseString(content).getAsJsonArray();
                if (arr.size() > 0 && arr.get(0).isJsonObject()) {
                    JsonObject mod = arr.get(0).getAsJsonObject();
                    if (mod.has("logoFile")) { String logo = mod.get("logoFile").getAsString(); if (!logo.isEmpty()) return logo; }
                }
            } catch (Exception ignored) {}
        }
        for (String toml : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
            content = readEntry(zip, toml);
            if (content != null) {
                String logo = tomlStringField(content, "logoFile");
                if (logo != null && !logo.isEmpty()) return logo;
            }
        }
        return null;
    }

    @Nullable
    private static Bitmap loadEntryAsBitmap(ZipFile zip, String entryPath) {
        ZipEntry entry = zip.getEntry(entryPath);
        if (entry == null) {
            String lower = entryPath.toLowerCase();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.getName().toLowerCase().equals(lower)) { entry = e; break; }
            }
        }
        if (entry == null) return null;
        try (InputStream is = zip.getInputStream(entry)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int read;
            while ((read = is.read(buf)) != -1) baos.write(buf, 0, read);
            byte[] bytes = baos.toByteArray();
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) { return null; }
    }

    @Nullable
    private static String readEntry(ZipFile zip, String entryPath) {
        ZipEntry entry = zip.getEntry(entryPath);
        if (entry == null) return null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(zip.getInputStream(entry), "UTF-8"))) {
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    @Nullable
    private static String tomlStringField(String toml, String field) {
        for (String line : toml.split("\n")) {
            line = line.trim();
            if (line.startsWith(field + " ") || line.startsWith(field + "=")) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String val = line.substring(eq + 1).trim();
                if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
                if (!val.isEmpty()) return val;
            }
        }
        return null;
    }
}