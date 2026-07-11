package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.math.MathUtils;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.ModItemAdapter;
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthApi;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ContentType;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.profiles.VersionSelectorDialog;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Searches and installs individual mods into the current instance's mods folder.
 * - Version filter: when an MC version is selected, only versions matching it are shown.
 * - Dependency dialog: shown before download, matching the ModBundle UI.
 */
public class ModsSearchFragment extends Fragment implements ModItemAdapter.SearchResultCallback {

    public static final String TAG = "ModsSearchFragment";

    /** Bundle key: pre-seed the MC version filter (e.g. "1.20.1"). */
    public static final String ARG_PRESET_MC_VERSION = "preset_mc_version";
    /** Bundle key: pre-seed the mod loader filter ("fabric","forge","quilt","neoforge"). */
    public static final String ARG_PRESET_LOADER     = "preset_loader";
    /** Bundle key: which kind of content to browse. Value is a ContentType enum
     *  name(); defaults to MOD when absent. */
    public static final String ARG_CONTENT_TYPE      = "content_type";

    private View mOverlay;
    private float mOverlayTopCache;

    private final RecyclerView.OnScrollListener mOverlayPositionListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            mOverlay.setY(MathUtils.clamp(mOverlay.getY() - dy, -mOverlay.getHeight(), mOverlayTopCache));
        }
    };

    private EditText mSearchEditText;
    private ImageButton mFilterButton;
    private RecyclerView mRecyclerview;
    private ModItemAdapter mModItemAdapter;
    private ProgressBar mSearchProgressBar;
    private TextView mStatusTextView;
    private ColorStateList mDefaultTextColor;

    private ModpackApi mModpackApi;
    private final SearchFilters mSearchFilters;

    public ModsSearchFragment() {
        super(R.layout.fragment_mod_search);
        mSearchFilters = new SearchFilters();
        mSearchFilters.isModpack = false;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        String curseforgeApiKey = LauncherPreferences.PREF_DISABLE_CURSEFORGE_API
                ? null : LauncherPreferences.resolveCurseforgeApiKey(context);
        mModpackApi = new ModsInstallApi(curseforgeApiKey, mSearchFilters);
        ((ModsInstallApi) mModpackApi).mActivityContext = context;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mModItemAdapter = new ModItemAdapter(getResources(), mModpackApi, this);
        ProgressKeeper.addTaskCountListener(mModItemAdapter);
        mOverlayTopCache = getResources().getDimension(R.dimen.fragment_padding_medium);

        mOverlay           = view.findViewById(R.id.search_mod_overlay);
        mSearchEditText    = view.findViewById(R.id.search_mod_edittext);
        mSearchProgressBar = view.findViewById(R.id.search_mod_progressbar);
        mRecyclerview      = view.findViewById(R.id.search_mod_list);
        mStatusTextView    = view.findViewById(R.id.search_mod_status_text);
        mFilterButton      = view.findViewById(R.id.search_mod_filter);

        mDefaultTextColor = mStatusTextView.getTextColors();

        mRecyclerview.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerview.setAdapter(mModItemAdapter);
        mRecyclerview.addOnScrollListener(mOverlayPositionListener);

        mSearchEditText.setOnEditorActionListener((v, actionId, event) -> {
            searchMods(mSearchEditText.getText().toString());
            mSearchEditText.clearFocus();
            return false;
        });

        mOverlay.post(() -> {
            int overlayHeight = mOverlay.getHeight();
            mRecyclerview.setPadding(
                    mRecyclerview.getPaddingLeft(),
                    mRecyclerview.getPaddingTop() + overlayHeight,
                    mRecyclerview.getPaddingRight(),
                    mRecyclerview.getPaddingBottom());
        });

        mFilterButton.setOnClickListener(v -> displayFilterDialog());

        // Apply any pre-seeded filters passed in from the Manage Content picker
        applyPresetArgs();

        mSearchEditText.setHint(searchHintRes());

        searchMods(null);
    }

    private int searchHintRes() {
        switch (mSearchFilters.contentType) {
            case RESOURCE_PACK: return R.string.hint_search_resourcepack;
            case SHADER_PACK:   return R.string.hint_search_shaderpack;
            default:            return R.string.hint_search_mod;
        }
    }

    /**
     * Reads ARG_CONTENT_TYPE / ARG_PRESET_MC_VERSION / ARG_PRESET_LOADER from the
     * fragment's arguments and seeds mSearchFilters before the first search. This
     * lets ManageModsFragment (via the Manage Content picker) pass in the current
     * instance's content type + version/loader so the search opens already filtered.
     *
     * The loader preset is only applied for MOD — resource packs and shader packs
     * aren't loader-specific, so a saved loader filter is ignored for those, both
     * here and in the filter dialog itself.
     */
    private void applyPresetArgs() {
        Bundle args = getArguments();
        if (args == null) return;

        String contentTypeName = args.getString(ARG_CONTENT_TYPE, null);
        if (contentTypeName != null) {
            mSearchFilters.contentType = ContentType.valueOf(contentTypeName);
        }

        String presetVersion = args.getString(ARG_PRESET_MC_VERSION, null);
        String presetLoader  = args.getString(ARG_PRESET_LOADER,     null);

        if (presetVersion != null && !presetVersion.isEmpty()) {
            mSearchFilters.mcVersion = presetVersion;
        }
        if (mSearchFilters.contentType == ContentType.MOD
                && presetLoader != null && !presetLoader.isEmpty()) {
            mSearchFilters.modLoader = presetLoader;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        ProgressKeeper.removeTaskCountListener(mModItemAdapter);
        mRecyclerview.removeOnScrollListener(mOverlayPositionListener);
    }

    @Override
    public void onSearchFinished() {
        mSearchProgressBar.setVisibility(View.GONE);
        mStatusTextView.setVisibility(View.GONE);
    }

    @Override
    public void onSearchError(int error) {
        mSearchProgressBar.setVisibility(View.GONE);
        mStatusTextView.setVisibility(View.VISIBLE);
        switch (error) {
            case ERROR_INTERNAL:
                mStatusTextView.setTextColor(Color.RED);
                mStatusTextView.setText(R.string.search_mod_error);
                break;
            case ERROR_NO_RESULTS:
                mStatusTextView.setTextColor(mDefaultTextColor);
                mStatusTextView.setText(R.string.search_mod_no_result);
                break;
        }
    }

    private void searchMods(String name) {
        mSearchProgressBar.setVisibility(View.VISIBLE);
        mSearchFilters.name = name == null ? "" : name;
        mModItemAdapter.performSearchQuery(mSearchFilters);
    }

    private void displayFilterDialog() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(R.layout.dialog_mod_filters)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            TextView mSelectedVersion = dialog.findViewById(R.id.search_mod_selected_mc_version_textview);
            Button mSelectVersionButton = dialog.findViewById(R.id.search_mod_mc_version_button);
            Button mApplyButton = dialog.findViewById(R.id.search_mod_apply_filters);
            android.widget.Spinner mLoaderSpinner = dialog.findViewById(R.id.search_mod_loader_spinner);
            android.widget.Spinner mEngineSpinner = dialog.findViewById(R.id.search_mod_engine_spinner);

            assert mSelectedVersion != null;
            assert mSelectVersionButton != null;
            assert mApplyButton != null;

            // Set up the "Modrinth / CurseForge / Both" engine picker. If CurseForge is
            // disabled in experimental settings, only Modrinth is offered and the filter
            // is pinned to it, since a CurseForge-only or Both search would otherwise
            // silently return nothing.
            boolean curseforgeDisabled = net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF
                    .getBoolean("disableCurseforgeApi", false);
            final int[] engineValues = curseforgeDisabled
                    ? new int[]{Constants.ENGINE_MODRINTH}
                    : new int[]{Constants.ENGINE_MODRINTH, Constants.ENGINE_CURSEFORGE, Constants.ENGINE_BOTH};
            if (mEngineSpinner != null) {
                String[] engineLabels = curseforgeDisabled
                        ? new String[]{getString(R.string.search_mod_engine_modrinth)}
                        : new String[]{getString(R.string.search_mod_engine_modrinth),
                                        getString(R.string.search_mod_engine_curseforge),
                                        getString(R.string.search_mod_engine_both)};
                android.widget.ArrayAdapter<String> engineAdapter = new android.widget.ArrayAdapter<>(
                        requireContext(), android.R.layout.simple_spinner_item, engineLabels);
                engineAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                mEngineSpinner.setAdapter(engineAdapter);

                if (curseforgeDisabled) {
                    mSearchFilters.engine = Constants.ENGINE_MODRINTH;
                    mEngineSpinner.setSelection(0);
                    mEngineSpinner.setEnabled(false);
                } else {
                    mEngineSpinner.setEnabled(true);
                    for (int i = 0; i < engineValues.length; i++) {
                        if (engineValues[i] == mSearchFilters.engine) {
                            mEngineSpinner.setSelection(i);
                            break;
                        }
                    }
                }
            }

            // Set up loader spinner — only meaningful for mods. Resource packs and
            // shader packs aren't loader-specific, so this whole row is hidden and
            // any previously-set loader filter is simply not applied to the search.
            TextView mLoaderLabel = dialog.findViewById(R.id.search_mod_loader_textview);
            final String[] loaderValues = {"", "fabric", "forge", "quilt", "neoforge"};
            boolean showLoaderFilter = mSearchFilters.contentType == ContentType.MOD;
            if (mLoaderLabel != null) mLoaderLabel.setVisibility(showLoaderFilter ? View.VISIBLE : View.GONE);
            if (mLoaderSpinner != null) {
                mLoaderSpinner.setVisibility(showLoaderFilter ? View.VISIBLE : View.GONE);
                String[] loaderLabels = {getString(R.string.search_mod_any_loader), "Fabric", "Forge", "Quilt", "NeoForge"};
                android.widget.ArrayAdapter<String> loaderAdapter = new android.widget.ArrayAdapter<>(
                        requireContext(), android.R.layout.simple_spinner_item, loaderLabels);
                loaderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                mLoaderSpinner.setAdapter(loaderAdapter);

                // Restore current selection
                String currentLoader = mSearchFilters.modLoader != null ? mSearchFilters.modLoader : "";
                for (int i = 0; i < loaderValues.length; i++) {
                    if (loaderValues[i].equals(currentLoader)) {
                        mLoaderSpinner.setSelection(i);
                        break;
                    }
                }
            }

            mSelectVersionButton.setOnClickListener(v ->
                    VersionSelectorDialog.open(v.getContext(), true,
                            (id, snapshot) -> mSelectedVersion.setText(id)));

            // If nothing's been picked yet (no preset args, filter never touched
            // before), default to the current instance's own version/loader
            // instead of leaving the filter blank.
            if ((mSearchFilters.mcVersion == null || mSearchFilters.mcVersion.isEmpty())
                    && (mSearchFilters.modLoader == null || mSearchFilters.modLoader.isEmpty())) {
                String profileKey = net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF
                        .getString(net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
                if (profileKey != null) {
                    InstanceVersionResolver.Info info = InstanceVersionResolver.resolve(profileKey);
                    if (info.mcVersion != null) mSearchFilters.mcVersion = info.mcVersion;
                    if (showLoaderFilter && !info.loader.isEmpty()) mSearchFilters.modLoader = info.loader;
                    if (mLoaderSpinner != null) {
                        for (int i = 0; i < loaderValues.length; i++) {
                            if (loaderValues[i].equals(mSearchFilters.modLoader)) {
                                mLoaderSpinner.setSelection(i);
                                break;
                            }
                        }
                    }
                }
            }
            mSelectedVersion.setText(mSearchFilters.mcVersion);

            mApplyButton.setOnClickListener(v -> {
                if (mEngineSpinner != null) {
                    mSearchFilters.engine = engineValues[mEngineSpinner.getSelectedItemPosition()];
                }
                if (showLoaderFilter && mLoaderSpinner != null) {
                    mSearchFilters.modLoader = loaderValues[mLoaderSpinner.getSelectedItemPosition()];
                } else {
                    mSearchFilters.modLoader = "";
                }
                mSearchFilters.mcVersion = mSelectedVersion.getText().toString();
                searchMods(mSearchEditText.getText().toString());
                dialogInterface.dismiss();
            });
        });

        dialog.show();
    }

    // ── ModsInstallApi ────────────────────────────────────────────────────────

    private static class ModsInstallApi extends CommonApi {

        private final SearchFilters mFilters;
        private final ModrinthApi mModrinthApi = new ModrinthApi();
        private final Handler mMainHandler = new Handler(Looper.getMainLooper());
        private Context mActivityContext;
        @Nullable
        private final net.kdt.pojavlaunch.modloaders.modpacks.api.CurseforgeApi mCurseforgeApi;

        /** @param curseforgeApiKey null when CurseForge is disabled in experimental settings */
        ModsInstallApi(@Nullable String curseforgeApiKey, SearchFilters filters) {
            super(curseforgeApiKey != null ? curseforgeApiKey : "", curseforgeApiKey == null);
            mFilters = filters;
            mCurseforgeApi = curseforgeApiKey != null
                    ? new net.kdt.pojavlaunch.modloaders.modpacks.api.CurseforgeApi(curseforgeApiKey)
                    : null;
        }

        /**
         * Override getModDetails to filter versions by the selected MC version.
         * Only versions matching the filter are shown in the version dropdown.
         * Also resolves which (if any) of the returned versions is already
         * installed in the current instance's mods folder, so the install
         * button can show Install / Installed / Update / Downgrade.
         */
        @Override
        public ModDetail getModDetails(ModItem item) {
            ModDetail detail;
            if (item.apiSource == net.kdt.pojavlaunch.modloaders.modpacks.models.Constants.SOURCE_MODRINTH) {
                String filterVer = (mFilters.mcVersion != null && !mFilters.mcVersion.isEmpty())
                        ? mFilters.mcVersion : null;
                String filterLoader = (mFilters.modLoader != null && !mFilters.modLoader.isEmpty())
                        ? mFilters.modLoader : null;
                detail = mModrinthApi.getModDetails(item, filterVer, filterLoader);
            } else if (item.apiSource == net.kdt.pojavlaunch.modloaders.modpacks.models.Constants.SOURCE_CURSEFORGE) {
                if (mCurseforgeApi == null) return null; // disabled in experimental settings
                String filterVer = (mFilters.mcVersion != null && !mFilters.mcVersion.isEmpty())
                        ? mFilters.mcVersion : null;
                String filterLoader = (mFilters.modLoader != null && !mFilters.modLoader.isEmpty())
                        ? mFilters.modLoader : null;
                detail = mCurseforgeApi.getModDetails(item, filterVer, filterLoader);
            } else {
                detail = super.getModDetails(item);
            }

            if (detail != null && !detail.isModpack) {
                resolveInstalledVersionIndex(detail);
            }
            return detail;
        }

        /**
         * Hashes every jar currently in the mods folder (enabled or disabled)
         * and matches against detail.versionHashes to find which version, if
         * any, is already installed. Sets detail.installedVersionIndex
         * accordingly (-1 if this mod isn't installed at all).
         */
        private void resolveInstalledVersionIndex(ModDetail detail) {
            detail.installedVersionIndex = -1;
            detail.installedFilePath = null;
            if (detail.versionHashes == null || detail.versionHashes.length == 0) return;

            File modsDir = getModsDir();
            File[] files = modsDir.listFiles(f -> f.isFile() &&
                    (f.getName().endsWith(mFilters.contentType.fileExtension) ||
                            f.getName().endsWith(mFilters.contentType.fileExtension + ".disabled")));
            if (files == null || files.length == 0) return;

            java.util.Map<String, File> installedHashToFile = new java.util.HashMap<>();
            for (File f : files) {
                String hash = sha1Hex(f);
                if (hash != null) installedHashToFile.put(hash, f);
            }
            if (installedHashToFile.isEmpty()) return;

            for (int i = 0; i < detail.versionHashes.length; i++) {
                String hash = detail.versionHashes[i];
                if (hash == null) continue;
                File match = installedHashToFile.get(hash.toLowerCase(java.util.Locale.ROOT));
                if (match != null) {
                    detail.installedVersionIndex = i;
                    detail.installedFilePath = match.getAbsolutePath();
                    break;
                }
            }
        }

        private static String sha1Hex(File file) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
                byte[] buffer = new byte[8192];
                try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                    int read;
                    while ((read = fis.read(buffer)) != -1) digest.update(buffer, 0, read);
                }
                byte[] bytes = digest.digest();
                StringBuilder sb = new StringBuilder(bytes.length * 2);
                for (byte b : bytes) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void handleInstallation(Context context, ModDetail modDetail, int selectedVersion) {
            if (modDetail.isModpack) {
                super.handleInstallation(context, modDetail, selectedVersion);
                return;
            }

            String url = modDetail.versionUrls[selectedVersion];

            // Check if this is a CF-restricted mod using the flag set during search
            boolean isCfRestricted = modDetail.apiSource == Constants.SOURCE_CURSEFORGE
                    && (modDetail.isRestricted || url == null || url.isEmpty());

            if (isCfRestricted) {
                String cfUrl = (modDetail.websiteUrl != null && !modDetail.websiteUrl.isEmpty())
                        ? modDetail.websiteUrl
                        : "https://www.curseforge.com/minecraft/mc-mods/" + modDetail.id;
                Context dialogCtx = mActivityContext != null ? mActivityContext : context;
                mMainHandler.post(() ->
                    new AlertDialog.Builder(dialogCtx)
                        .setTitle(modDetail.title)
                        .setMessage("This mod restricts third-party downloads.\n\nDownload it manually from CurseForge and place it in your mods folder:\n\n" + cfUrl)
                        .setPositiveButton("Open CurseForge", (d, w) ->
                            Tools.openURL((android.app.Activity) dialogCtx, cfUrl))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                );
                return;
            }

            if (url == null || url.isEmpty()) {
                Tools.showErrorRemote(context, R.string.modpack_install_download_failed,
                        new IOException("No download URL available for this mod"));
                return;
            }

            // Extract filename
            String rawName = url.substring(url.lastIndexOf('/') + 1);
            if (rawName.contains("?")) rawName = rawName.substring(0, rawName.indexOf('?'));
            final String fileName = rawName.endsWith(mFilters.contentType.fileExtension) ? rawName : rawName + mFilters.contentType.fileExtension;

            // If a different version of this same mod is already installed under
            // a different file name (the Update/Downgrade case), remember its path
            // so downloadMod() can remove it once the new file is safely down —
            // otherwise both versions end up sitting in the mods folder together.
            final String oldFilePath = modDetail.installedFilePath;

            // Check if this version has dependencies
            String[] depIds   = (modDetail.versionDependencyIds   != null) ? modDetail.versionDependencyIds[selectedVersion]   : null;
            String[] depTypes = (modDetail.versionDependencyTypes != null) ? modDetail.versionDependencyTypes[selectedVersion] : null;

            if (depIds == null || depIds.length == 0) {
                // No deps — download directly
                downloadMod(context, url, fileName, new String[0], new String[0], oldFilePath);
                return;
            }

            // Drop any dependency that's already sitting in the mods folder (as any
            // version, not just the one this mod happens to ask for) — otherwise every
            // reinstall/update of a mod with common dependencies (Fabric API, Cloth
            // Config, etc.) nags you to "install" something you already have.
            PojavApplication.sExecutorService.execute(() -> {
                java.util.Set<String> installedProjectIds = getInstalledModrinthProjectIds();
                List<String> remainingIds = new ArrayList<>();
                List<String> remainingTypes = new ArrayList<>();
                for (int i = 0; i < depIds.length; i++) {
                    if (depIds[i] != null && installedProjectIds.contains(depIds[i])) continue;
                    remainingIds.add(depIds[i]);
                    remainingTypes.add((depTypes != null && i < depTypes.length) ? depTypes[i] : "required");
                }

                if (remainingIds.isEmpty()) {
                    // Every dependency is already installed — no need to prompt at all
                    mMainHandler.post(() ->
                            downloadMod(context, url, fileName, new String[0], new String[0], oldFilePath));
                    return;
                }

                mMainHandler.post(() -> promptForDependencies(context, url, fileName,
                        remainingIds.toArray(new String[0]), remainingTypes.toArray(new String[0]), oldFilePath));
            });
        }

        /** Fetches display names for the (already filtered to not-yet-installed) dependency
         *  list, then shows the install dialog once every name has resolved.
         *
         *  The filter that ran before this only drops dependencies whose exact jar hash
         *  matches something already in the mods folder on Modrinth's own database — so a
         *  dependency that's installed but came from CurseForge, or was placed manually,
         *  has a completely different file hash and slips right through, appearing in the
         *  dialog as "required"/"optional" even though it's already there. To catch that,
         *  we additionally read the mod id directly out of every installed jar's own
         *  metadata (fabric.mod.json / quilt.mod.json / mods.toml) — this is source-agnostic
         *  and needs no network — and cross-check it against each dependency's Modrinth slug. */
        private void promptForDependencies(Context context, String url, String fileName,
                                            String[] depIds, String[] depTypes, String oldFilePath) {
            final java.util.Set<String> installedModIds = getInstalledModIds();

            String[] labels = new String[depIds.length];
            String[] slugs = new String[depIds.length];
            final boolean[] checkedDefaults = new boolean[depIds.length];
            AtomicInteger remaining = new AtomicInteger(depIds.length);

            for (int i = 0; i < depIds.length; i++) {
                final int idx = i;
                final String type = (depTypes != null && idx < depTypes.length) ? depTypes[idx] : "required";
                final String prefix;
                switch (type) {
                    case "required":      prefix = "Required: ";     break;
                    case "incompatible":  prefix = "Incompatible: "; break;
                    case "embedded":      prefix = "Embedded: ";     break;
                    default:              prefix = "Optional: ";     break;
                }
                checkedDefaults[idx] = "required".equals(type);

                final String projectId = depIds[idx];
                PojavApplication.sExecutorService.execute(() -> {
                    // Fetch project title + slug from Modrinth
                    String[] details = fetchProjectDetails(projectId);
                    String name = details != null ? details[0] : null;
                    slugs[idx] = details != null ? details[1] : null;
                    labels[idx] = prefix + (name != null ? name : projectId);
                    if (remaining.decrementAndGet() == 0) {
                        mMainHandler.post(() -> finishDependencyPrompt(context, url, fileName,
                                depIds, depTypes, labels, slugs, checkedDefaults, installedModIds, oldFilePath));
                    }
                });
            }
        }

        /** Drops any dependency whose slug matches a mod id already found in the mods
         *  folder (see promptForDependencies), then shows the dialog with what's left —
         *  or skips it entirely and downloads directly if nothing's left to ask about. */
        private void finishDependencyPrompt(Context context, String url, String fileName,
                                             String[] depIds, String[] depTypes, String[] labels,
                                             String[] slugs, boolean[] checkedDefaults,
                                             java.util.Set<String> installedModIds, String oldFilePath) {
            List<String> keptIds = new ArrayList<>();
            List<String> keptTypes = new ArrayList<>();
            List<String> keptLabels = new ArrayList<>();
            List<Boolean> keptChecked = new ArrayList<>();
            for (int i = 0; i < depIds.length; i++) {
                String slug = slugs[i];
                if (slug != null && installedModIds.contains(slug.toLowerCase(java.util.Locale.ROOT))) continue;
                keptIds.add(depIds[i]);
                keptTypes.add((depTypes != null && i < depTypes.length) ? depTypes[i] : "required");
                keptLabels.add(labels[i]);
                keptChecked.add(checkedDefaults[i]);
            }

            if (keptIds.isEmpty()) {
                downloadMod(context, url, fileName, new String[0], new String[0], oldFilePath);
                return;
            }

            boolean[] checkedArr = new boolean[keptChecked.size()];
            for (int i = 0; i < checkedArr.length; i++) checkedArr[i] = keptChecked.get(i);

            showDepsDialog(context, url, fileName,
                    keptIds.toArray(new String[0]), keptTypes.toArray(new String[0]),
                    keptLabels.toArray(new String[0]), checkedArr, oldFilePath);
        }

        private void showDepsDialog(Context context, String url, String fileName,
                                    String[] depIds, String[] depTypes,
                                    String[] labels, boolean[] checkedDefaults, String oldFilePath) {
            // context here is getApplicationContext() from ModItemAdapter — no window token.
            // Use the stored Activity reference instead.
            Context dialogCtx = mActivityContext != null ? mActivityContext : context;
            boolean[] selected = checkedDefaults.clone();

            new AlertDialog.Builder(dialogCtx)
                    .setTitle(R.string.mod_deps_title)
                    .setMultiChoiceItems(labels, selected,
                            (dialog, which, isChecked) -> selected[which] = isChecked)
                    .setPositiveButton(R.string.mod_deps_install_selected, (d, w) -> {
                        List<String> selectedIds = new ArrayList<>();
                        for (int i = 0; i < depIds.length; i++) {
                            if (selected[i]) selectedIds.add(depIds[i]);
                        }
                        downloadMod(context, url, fileName,
                                selectedIds.toArray(new String[0]), depTypes, oldFilePath);
                    })
                    .setNeutralButton(R.string.mod_deps_install_without,
                            (d, w) -> downloadMod(context, url, fileName, new String[0], new String[0], oldFilePath))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void downloadMod(Context context, String url, String fileName,
                                  String[] depIds, String[] depTypes, @Nullable String oldFilePath) {
            File modsDir = getModsDir();
            if (!modsDir.exists()) modsDir.mkdirs();

            final File targetFile = new File(modsDir, fileName);

            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.global_waiting);
            PojavApplication.sExecutorService.execute(() -> {
                try {
                    // Download main mod
                    DownloadUtils.downloadFile(url, targetFile);

                    // This is an update/downgrade of an already-installed mod under a
                    // different file name — remove the old jar now that the new one
                    // downloaded successfully, so both versions don't end up sitting
                    // in the mods folder side-by-side.
                    if (oldFilePath != null) {
                        File oldFile = new File(oldFilePath);
                        if (oldFile.exists() && !oldFile.getAbsolutePath().equals(targetFile.getAbsolutePath())) {
                            oldFile.delete();
                        }
                    }

                    // Download selected dependencies
                    for (String depId : depIds) {
                        if (depId == null || depId.isEmpty()) continue;
                        downloadDependency(depId, modsDir);
                    }

                    ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                    Tools.runOnUiThread(() ->
                            Toast.makeText(context,
                                    context.getString(R.string.mod_install_success, fileName),
                                    Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                    Tools.showErrorRemote(context, R.string.modpack_install_download_failed, e);
                }
            });
        }

        private void downloadDependency(String projectId, File modsDir) {
            // Fetch latest version for the current MC version/loader filter
            try {
                String filterVer = (mFilters.mcVersion != null && !mFilters.mcVersion.isEmpty())
                        ? mFilters.mcVersion : "";
                String filterLoader = (mFilters.modLoader != null && !mFilters.modLoader.isEmpty())
                        ? mFilters.modLoader : null;
                ModItem depItem = new ModItem(
                        net.kdt.pojavlaunch.modloaders.modpacks.models.Constants.SOURCE_MODRINTH,
                        false, projectId, projectId, "", "");
                ModDetail depDetail = mModrinthApi.getModDetails(depItem, filterVer.isEmpty() ? null : filterVer, filterLoader);
                if (depDetail == null || depDetail.versionUrls == null || depDetail.versionUrls.length == 0) return;

                String depUrl = depDetail.versionUrls[0];
                String depName = depUrl.substring(depUrl.lastIndexOf('/') + 1);
                if (depName.contains("?")) depName = depName.substring(0, depName.indexOf('?'));
                if (!depName.endsWith(mFilters.contentType.fileExtension)) depName += mFilters.contentType.fileExtension;

                DownloadUtils.downloadFile(depUrl, new File(modsDir, depName));
            } catch (Exception e) {
                Log.w(TAG, "Failed to download dependency " + projectId + ": " + e.getMessage());
            }
        }

        /**
         * Hashes every jar currently in the mods folder and bulk-resolves them against
         * Modrinth's version_files endpoint to find which Modrinth project each one
         * belongs to — used to filter "install dependencies" prompts down to ones that
         * aren't already installed under some other version/file name.
         */
        private java.util.Set<String> getInstalledModrinthProjectIds() {
            java.util.Set<String> projectIds = new java.util.HashSet<>();
            File modsDir = getModsDir();
            File[] files = modsDir.listFiles(f -> f.isFile() &&
                    (f.getName().endsWith(mFilters.contentType.fileExtension) ||
                            f.getName().endsWith(mFilters.contentType.fileExtension + ".disabled")));
            if (files == null || files.length == 0) return projectIds;

            List<String> hashes = new ArrayList<>();
            for (File f : files) {
                String hash = sha1Hex(f);
                if (hash != null) hashes.add(hash);
            }
            if (hashes.isEmpty()) return projectIds;

            try {
                com.google.gson.JsonObject body = new com.google.gson.JsonObject();
                com.google.gson.JsonArray hashArray = new com.google.gson.JsonArray();
                for (String hash : hashes) hashArray.add(hash);
                body.add("hashes", hashArray);
                body.addProperty("algorithm", "sha1");

                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("Content-Type", "application/json");
                headers.put("Accept", "application/json");

                String responseRaw = net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler.postRaw(
                        headers, "https://api.modrinth.com/v2/version_files", body.toString());
                if (responseRaw == null) return projectIds;

                com.google.gson.JsonObject response = com.google.gson.JsonParser.parseString(responseRaw).getAsJsonObject();
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : response.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    com.google.gson.JsonObject version = entry.getValue().getAsJsonObject();
                    if (version.has("project_id") && !version.get("project_id").isJsonNull()) {
                        projectIds.add(version.get("project_id").getAsString());
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to bulk-resolve installed mod hashes: " + e.getMessage());
            }
            return projectIds;
        }

        /** Fetches a Modrinth project's title and slug in one call. Returns {title, slug}
         *  (either element may be null), or null entirely if the lookup failed. */
        private String[] fetchProjectDetails(String projectId) {
            try {
                net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler handler =
                        new net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler("https://api.modrinth.com/v2");
                com.google.gson.JsonObject obj = handler.get("project/" + projectId,
                        com.google.gson.JsonObject.class);
                if (obj == null) return null;
                String title = (obj.has("title") && !obj.get("title").isJsonNull())
                        ? obj.get("title").getAsString() : null;
                String slug = (obj.has("slug") && !obj.get("slug").isJsonNull())
                        ? obj.get("slug").getAsString() : null;
                return new String[]{title, slug};
            } catch (Exception ignored) {}
            return null;
        }

        /**
         * Reads the mod id (not the display name) embedded in every jar currently in the
         * mods folder — straight from fabric.mod.json / quilt.mod.json / mods.toml /
         * mcmod.info. Unlike {@link #getInstalledModrinthProjectIds()} this needs no
         * network call and doesn't care where the jar originally came from, so it also
         * catches dependencies that were installed via CurseForge or dropped in manually
         * — cases the Modrinth-hash lookup can never see since those jars simply don't
         * have a Modrinth file hash to match against.
         */
        private java.util.Set<String> getInstalledModIds() {
            java.util.Set<String> ids = new java.util.HashSet<>();
            File modsDir = getModsDir();
            File[] files = modsDir.listFiles(f -> f.isFile() &&
                    (f.getName().endsWith(mFilters.contentType.fileExtension) ||
                            f.getName().endsWith(mFilters.contentType.fileExtension + ".disabled")));
            if (files == null) return ids;
            for (File f : files) {
                String id = extractModId(f);
                if (id != null && !id.isEmpty()) ids.add(id.toLowerCase(java.util.Locale.ROOT));
            }
            return ids;
        }

        private static String extractModId(File jarFile) {
            try (ZipFile zip = new ZipFile(jarFile)) {
                String content = readZipEntry(zip, "fabric.mod.json");
                if (content != null) {
                    try {
                        com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(content).getAsJsonObject();
                        if (obj.has("id") && !obj.get("id").isJsonNull()) {
                            String id = obj.get("id").getAsString().trim();
                            if (!id.isEmpty()) return id;
                        }
                    } catch (Exception ignored) {}
                }
                content = readZipEntry(zip, "quilt.mod.json");
                if (content != null) {
                    try {
                        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(content).getAsJsonObject();
                        com.google.gson.JsonObject ql = root.has("quilt_loader") ? root.getAsJsonObject("quilt_loader") : null;
                        if (ql != null && ql.has("id") && !ql.get("id").isJsonNull()) {
                            String id = ql.get("id").getAsString().trim();
                            if (!id.isEmpty()) return id;
                        }
                    } catch (Exception ignored) {}
                }
                for (String toml : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
                    content = readZipEntry(zip, toml);
                    if (content != null) {
                        String modId = tomlStringField(content, "modId");
                        if (modId != null && !modId.isEmpty()) return modId.trim();
                    }
                }
                content = readZipEntry(zip, "mcmod.info");
                if (content != null) {
                    try {
                        com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(content).getAsJsonArray();
                        if (arr.size() > 0 && arr.get(0).isJsonObject()) {
                            com.google.gson.JsonObject mod = arr.get(0).getAsJsonObject();
                            if (mod.has("modid") && !mod.get("modid").isJsonNull()) {
                                String id = mod.get("modid").getAsString().trim();
                                if (!id.isEmpty()) return id;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to read mod id from JAR: " + jarFile.getName() + " - " + e.getMessage());
            }
            return null;
        }

        private static String readZipEntry(ZipFile zip, String entryPath) {
            ZipEntry entry = zip.getEntry(entryPath);
            if (entry == null) return null;
            try (InputStream is = zip.getInputStream(entry)) {
                return Tools.read(is);
            } catch (Exception e) {
                return null;
            }
        }

        /** Minimal `field = "value"` line lookup — enough for the modId line in mods.toml. */
        private static String tomlStringField(String content, String field) {
            Matcher m = Pattern.compile(field + "\\s*=\\s*\"([^\"]*)\"").matcher(content);
            return m.find() ? m.group(1) : null;
        }

        private File getModsDir() {
            try {
                String key = LauncherPreferences.DEFAULT_PREF
                        .getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
                if (key != null && !key.isEmpty()) {
                    LauncherProfiles.load();
                    MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(key);
                    if (profile != null) return new File(Tools.getGameDirPath(profile), mFilters.contentType.folderName);
                }
            } catch (Exception ignored) {}
            return new File(Tools.DIR_GAME_NEW, mFilters.contentType.folderName);
        }
    }
}