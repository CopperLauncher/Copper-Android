package net.kdt.pojavlaunch.fragments;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension;
import net.kdt.pojavlaunch.modloaders.InstalledModAdapter;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ContentType;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Manages installed content (mods, resource packs, or shader packs — see
 * {@link #ARG_CONTENT_TYPE}) for the current instance: list, enable/disable,
 * delete, and check for updates. The version/loader filter used for update
 * checking now lives in the "Manage Content" picker (see MainMenuFragment /
 * ContentFilterDialog) rather than a button on this screen, since it's a
 * single per-instance filter shared across all three content types.
 *
 * The loader half of that filter only ever applies to mods — resource packs
 * and shader packs aren't loader-specific, so update checks/searches for
 * those two content types always ignore the saved loader value even if one
 * is set (see {@link #effectiveLoader(String)}).
 */
public class ManageModsFragment extends Fragment {

    public static final String TAG = "ManageModsFragment";

    /** Bundle key: which kind of content this screen manages. Value is a
     *  ContentType enum name(); defaults to MOD when absent. */
    public static final String ARG_CONTENT_TYPE = "content_type";

    private static final String PREF_FILE      = "mod_filters";
    private static final String KEY_MC_VERSION = "mc_version_";
    private static final String KEY_LOADER     = "loader_";

    private ImageButton mRefreshButton;
    private ProgressBar mUpdateProgress;
    private InstalledModAdapter mAdapter;
    private ContentType mContentType = ContentType.MOD;

    // Registered unconditionally as a field initializer (must happen before the
    // fragment reaches STARTED) — mContentType isn't resolved from arguments yet
    // at construction time, so this accepts either supported extension and
    // onImportFilePicked() checks the picked file against mContentType itself.
    private final ActivityResultLauncher<Object> mImportLauncher =
            registerForActivityResult(new OpenDocumentWithExtension(new String[]{"jar", "zip"}),
                    this::onImportFilePicked);

    public ManageModsFragment() {
        super(R.layout.fragment_manage_mods);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        String typeName = args != null ? args.getString(ARG_CONTENT_TYPE, null) : null;
        mContentType = typeName != null ? ContentType.valueOf(typeName) : ContentType.MOD;

        ImageButton backButton = view.findViewById(R.id.manage_mods_back);
        mRefreshButton         = view.findViewById(R.id.manage_mods_refresh);
        mUpdateProgress        = view.findViewById(R.id.manage_mods_update_progress);
        ImageButton addButton  = view.findViewById(R.id.manage_mods_add);
        ImageButton importButton = view.findViewById(R.id.manage_mods_import);
        TextView    title      = view.findViewById(R.id.manage_mods_title);
        RecyclerView recycler  = view.findViewById(R.id.manage_mods_recycler);
        View        emptyState = view.findViewById(R.id.manage_mods_empty);

        // In landscape this screen is docked in MainMenuFragment's right pane, and the
        // content picker's own back button (left pane) already covers "leave this
        // screen" — a second one here was redundant. Portrait opens this full-screen
        // with no picker alongside it, so it keeps its own back button there.
        if (getParentFragment() instanceof MainMenuFragment) {
            backButton.setVisibility(View.GONE);
        } else {
            backButton.setOnClickListener(v -> requireActivity().onBackPressed());
        }
        mRefreshButton.setOnClickListener(v -> runUpdateCheck(false));
        addButton.setOnClickListener(v -> openModSearch());
        importButton.setOnClickListener(v -> mImportLauncher.launch(null));

        String profileName = getCurrentProfileName();
        String typeLabel = getString(contentTypeLabelRes());
        title.setText(profileName.isEmpty() ? typeLabel : profileName + " - " + typeLabel);

        if (emptyState instanceof TextView) {
            ((TextView) emptyState).setText(contentTypeEmptyLabelRes());
        }

        // Build adapter, inject saved (or instance-detected) filter
        String profileKey = getCurrentProfileKey();
        String[] filter = resolveFilter(profileKey);
        String savedVersion = filter[0];
        String savedLoader  = filter[1];

        mAdapter = new InstalledModAdapter(requireContext(), getContentDir(), mContentType, isEmpty -> {
            recycler.setVisibility(isEmpty ? View.GONE  : View.VISIBLE);
            emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        });
        mAdapter.setFilter(savedVersion, savedLoader);

        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(mAdapter);

        // Auto-check for updates as soon as the screen opens, in addition to
        // the manual refresh button. Silent (no "checking…" toast, and no
        // "set a filter first" toast) since this fires automatically — if
        // there's genuinely no version to go on (couldn't even detect one from
        // the instance itself), it just quietly does nothing.
        runUpdateCheck(true);
    }

    /**
     * Runs the Modrinth update check across all installed content for this instance.
     * Triggered automatically when the screen opens (silent = true) and manually
     * via the refresh button (silent = false).
     *
     * @param silent when true, skips the "checking…"/"set a filter first" toasts —
     *               used for the automatic on-open check so it doesn't nag the
     *               user if a version genuinely couldn't be determined.
     */
    private void runUpdateCheck(boolean silent) {
        if (mAdapter == null) return;

        String[] filter = resolveFilter(getCurrentProfileKey());
        String version = filter[0];
        String loader  = filter[1];

        if (version.isEmpty() && loader.isEmpty()) {
            if (!silent) {
                Toast.makeText(requireContext(),
                        R.string.mod_update_no_filter, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        mAdapter.setFilter(version, loader);

        // While checking: hide the refresh button and show just a spinner in
        // its place. No per-mod update button appears until its own check
        // resolves, so this spinner is the only thing indicating progress.
        mRefreshButton.setEnabled(false);
        mRefreshButton.setVisibility(View.GONE);
        if (mUpdateProgress != null) mUpdateProgress.setVisibility(View.VISIBLE);

        if (!silent) {
            Toast.makeText(requireContext(), R.string.mod_update_checking, Toast.LENGTH_SHORT).show();
        }

        mAdapter.checkForUpdates(() -> {
            mRefreshButton.setEnabled(true);
            mRefreshButton.setVisibility(View.VISIBLE);
            if (mUpdateProgress != null) mUpdateProgress.setVisibility(View.GONE);
        });
    }

    private void openModSearch() {
        String[] filter = resolveFilter(getCurrentProfileKey());
        String version = filter[0];
        String loader  = filter[1];

        Bundle args = new Bundle();
        if (!version.isEmpty()) args.putString(ModsSearchFragment.ARG_PRESET_MC_VERSION, version);
        if (!loader.isEmpty())  args.putString(ModsSearchFragment.ARG_PRESET_LOADER,     loader);
        args.putString(ModsSearchFragment.ARG_CONTENT_TYPE, mContentType.name());

        ModsSearchFragment fragment = new ModsSearchFragment();
        fragment.setArguments(args);
        navigateToFragment(fragment, ModsSearchFragment.TAG + ":" + mContentType.name());
    }

    /**
     * Handles a file picked via {@link #mImportLauncher} (e.g. from Downloads):
     * validates its extension against the content type this screen manages,
     * copies it into that content type's folder for the current instance, and
     * inserts it into the list. Runs the copy off the main thread since the
     * source may be a slow content provider (cloud storage, etc.).
     */
    private void onImportFilePicked(Uri uri) {
        if (uri == null || mAdapter == null) return;

        String requiredExtension = mContentType.fileExtension;
        String pickedName = queryDisplayName(uri);
        if (pickedName == null || !pickedName.toLowerCase().endsWith(requiredExtension)) {
            Toast.makeText(requireContext(),
                    getString(R.string.content_import_wrong_type, requiredExtension),
                    Toast.LENGTH_LONG).show();
            return;
        }

        File contentDir = getContentDir();
        File destination = uniqueDestination(contentDir, pickedName);

        PojavApplication.sExecutorService.execute(() -> {
            boolean success = copyToFile(uri, contentDir, destination);
            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                if (success) {
                    mAdapter.addContentFile(destination);
                    Toast.makeText(requireContext(),
                            getString(R.string.content_import_success, destination.getName()),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(),
                            R.string.content_import_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    @Nullable
    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = requireContext().getContentResolver()
                .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception ignored) {}
        String path = uri.getLastPathSegment();
        return path != null ? path.substring(path.lastIndexOf('/') + 1) : null;
    }

    /** Appends " (1)", " (2)", etc. before the extension if a file of that name already exists. */
    private File uniqueDestination(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;

        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int count = 1;
        do {
            candidate = new File(dir, base + " (" + count + ")" + ext);
            count++;
        } while (candidate.exists());
        return candidate;
    }

    private boolean copyToFile(Uri source, File contentDir, File destination) {
        if (!contentDir.isDirectory() && !contentDir.mkdirs()) return false;
        try (InputStream input = requireContext().getContentResolver().openInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) return false;
            byte[] buffer = new byte[262144];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
            return true;
        } catch (IOException e) {
            destination.delete();
            return false;
        }
    }

    /**
     * The version/loader filter to actually use: whatever's saved for this profile,
     * or — if nothing's been saved yet — whatever the instance itself is detected as
     * running. This has to match what the filter dialog *shows* as its default (see
     * ContentFilterDialog), otherwise the dialog can look like a filter is already
     * set while everything that actually reads from SharedPreferences (like this
     * screen's update check) still sees nothing and does nothing — which is exactly
     * what used to happen, since the dialog's auto-fill was only ever visual until
     * Apply was pressed.
     */
    private String[] resolveFilter(String profileKey) {
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE);
        String version = prefs.getString(KEY_MC_VERSION + profileKey, "");
        String loader  = effectiveLoader(prefs.getString(KEY_LOADER + profileKey, ""));

        if (version.isEmpty() && loader.isEmpty()) {
            InstanceVersionResolver.Info info = InstanceVersionResolver.resolve(profileKey);
            if (info.mcVersion != null) version = info.mcVersion;
            loader = effectiveLoader(info.loader);
        }
        return new String[]{version, loader};
    }

    /** Loader filters only make sense for mods — a saved loader preference is
     *  simply ignored when managing/searching resource packs or shader packs. */
    private String effectiveLoader(String savedLoader) {
        return mContentType == ContentType.MOD ? savedLoader : "";
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private int contentTypeLabelRes() {
        switch (mContentType) {
            case RESOURCE_PACK: return R.string.mcl_content_resourcepacks;
            case SHADER_PACK:   return R.string.mcl_content_shaderpacks;
            default:            return R.string.mcl_content_mods;
        }
    }

    private int contentTypeEmptyLabelRes() {
        switch (mContentType) {
            case RESOURCE_PACK: return R.string.manage_resourcepacks_empty;
            case SHADER_PACK:   return R.string.manage_shaderpacks_empty;
            default:            return R.string.manage_mods_empty;
        }
    }

    @NonNull
    private String getCurrentProfileKey() {
        String key = LauncherPreferences.DEFAULT_PREF
                .getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        return key != null ? key : "default";
    }

    private String getCurrentProfileName() {
        try {
            String key = getCurrentProfileKey();
            LauncherProfiles.load();
            MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(key);
            if (profile == null) return "";
            return profile.name != null ? profile.name : key;
        } catch (Exception e) {
            return "";
        }
    }

    private File getContentDir() {
        try {
            String key = getCurrentProfileKey();
            LauncherProfiles.load();
            MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(key);
            if (profile != null) {
                File gameDir = Tools.getGameDirPath(profile);
                return new File(gameDir, mContentType.folderName);
            }
        } catch (Exception ignored) {}
        return new File(Tools.DIR_GAME_NEW, mContentType.folderName);
    }

    private void navigateToFragment(Fragment fragment, String tag) {
        Fragment parent = getParentFragment();
        if (parent != null) {
            parent.getChildFragmentManager()
                    .beginTransaction()
                    .setReorderingAllowed(true)
                    .replace(R.id.right_pane_container, fragment, tag)
                    .addToBackStack(tag)
                    .commit();
        } else {
            requireActivity().getSupportFragmentManager()
                    .beginTransaction()
                    .setReorderingAllowed(true)
                    .replace(R.id.container_fragment, fragment, tag)
                    .addToBackStack(tag)
                    .commit();
        }
    }
}