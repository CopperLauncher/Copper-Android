package net.kdt.pojavlaunch.fragments;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.InstalledModAdapter;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ContentType;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;

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
        TextView    title      = view.findViewById(R.id.manage_mods_title);
        RecyclerView recycler  = view.findViewById(R.id.manage_mods_recycler);
        View        emptyState = view.findViewById(R.id.manage_mods_empty);

        backButton.setOnClickListener(v -> requireActivity().onBackPressed());
        mRefreshButton.setOnClickListener(v -> runUpdateCheck(false));
        addButton.setOnClickListener(v -> openModSearch());

        String profileName = getCurrentProfileName();
        String typeLabel = getString(contentTypeLabelRes());
        title.setText(profileName.isEmpty() ? typeLabel : profileName + " - " + typeLabel);

        if (emptyState instanceof TextView) {
            ((TextView) emptyState).setText(contentTypeEmptyLabelRes());
        }

        // Build adapter, inject saved filter (no auto update-check — opt-in via refresh button)
        String profileKey = getCurrentProfileKey();
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE);
        String savedVersion = prefs.getString(KEY_MC_VERSION + profileKey, "");
        String savedLoader  = effectiveLoader(prefs.getString(KEY_LOADER + profileKey, ""));

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
        // there's no filter set yet, it just quietly does nothing until the
        // user configures one via the Manage Content filter icon.
        runUpdateCheck(true);
    }

    /**
     * Runs the Modrinth update check across all installed content for this instance.
     * Triggered automatically when the screen opens (silent = true) and manually
     * via the refresh button (silent = false).
     *
     * @param silent when true, skips the "checking…"/"set a filter first" toasts —
     *               used for the automatic on-open check so it doesn't nag the
     *               user if they haven't configured a version/loader filter yet.
     */
    private void runUpdateCheck(boolean silent) {
        if (mAdapter == null) return;

        String profileKey = getCurrentProfileKey();
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE);
        String version = prefs.getString(KEY_MC_VERSION + profileKey, "");
        String loader  = effectiveLoader(prefs.getString(KEY_LOADER + profileKey, ""));

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
        String profileKey = getCurrentProfileKey();
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE);

        String version = prefs.getString(KEY_MC_VERSION + profileKey, "");
        String loader  = effectiveLoader(prefs.getString(KEY_LOADER + profileKey, ""));

        Bundle args = new Bundle();
        if (!version.isEmpty()) args.putString(ModsSearchFragment.ARG_PRESET_MC_VERSION, version);
        if (!loader.isEmpty())  args.putString(ModsSearchFragment.ARG_PRESET_LOADER,     loader);
        args.putString(ModsSearchFragment.ARG_CONTENT_TYPE, mContentType.name());

        ModsSearchFragment fragment = new ModsSearchFragment();
        fragment.setArguments(args);
        navigateToFragment(fragment, ModsSearchFragment.TAG + ":" + mContentType.name());
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
