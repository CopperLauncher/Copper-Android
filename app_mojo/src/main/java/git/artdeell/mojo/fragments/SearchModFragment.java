package git.artdeell.mojo.fragments;

import static git.artdeell.mojo.Tools.runOnUiThread;
import git.artdeell.mojo.modding.modpacks.api.LocalModpackImporter;
import git.artdeell.mojo.progresskeeper.TaskCountListener;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.result.ActivityResultLauncher;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.math.MathUtils;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.R;
import git.artdeell.mojo.modding.modpacks.ModItemAdapter;
import git.artdeell.mojo.modding.modpacks.api.CommonApi;
import git.artdeell.mojo.modding.modpacks.api.ModpackApi;
import git.artdeell.mojo.modding.modpacks.api.ModrinthApi;
import git.artdeell.mojo.modding.modpacks.models.Constants;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.modding.modpacks.models.SearchFilters;
import git.artdeell.mojo.utils.profiles.VersionSelectorDialog;
import git.artdeell.mojo.progresskeeper.ProgressKeeper;

public class SearchModFragment extends Fragment implements ModItemAdapter.SearchResultCallback {

    public static final String TAG = "SearchModFragment";
    private View mOverlay;
    private float mOverlayTopCache; // Padding cache reduce resource lookup

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

    private ModpackApi modpackApi;

    private Button mImportButton;
    private TaskCountListener mTaskCountListener;

    private final ActivityResultLauncher<String> mImportLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                LocalModpackImporter.importAsync(requireContext(), uri, modpackApi);
            });

    private final SearchFilters mSearchFilters;

    public SearchModFragment(){
        super(R.layout.fragment_mod_search);
        mSearchFilters = new SearchFilters();
        mSearchFilters.isModpack = true;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        boolean disableCurseforge = git.artdeell.mojo.prefs.LauncherPreferences.PREF_DISABLE_CURSEFORGE_API;
        String curseforgeApiKey = disableCurseforge
                ? "" : git.artdeell.mojo.prefs.LauncherPreferences.resolveCurseforgeApiKey(context);
        modpackApi = new ModpackSearchApi(curseforgeApiKey, disableCurseforge, mSearchFilters);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // You can only access resources after attaching to current context
        mModItemAdapter = new ModItemAdapter(getResources(), modpackApi, this);
        ProgressKeeper.addTaskCountListener(mModItemAdapter);
        mOverlayTopCache = getResources().getDimension(R.dimen.fragment_padding_medium);

        mOverlay = view.findViewById(R.id.search_mod_overlay);
        mSearchEditText = view.findViewById(R.id.search_mod_edittext);
        mSearchProgressBar = view.findViewById(R.id.search_mod_progressbar);
        mRecyclerview = view.findViewById(R.id.search_mod_list);
        mStatusTextView = view.findViewById(R.id.search_mod_status_text);
        mFilterButton = view.findViewById(R.id.search_mod_filter);
        // layout-land/fragment_mod_search.xml sets this button's visibility to
        // GONE by default — that's meant for ModsSearchFragment (mod/resource/
        // /shader search), which is hosted inside ContentPickerFragment's two-pane
        // picker and has its own left-pane filter button in landscape instead.
        // This fragment (the standalone modpack search/installer) has no such
        // host or alternate filter entry point, so force it back on here.
        mFilterButton.setVisibility(View.VISIBLE);

        mDefaultTextColor = mStatusTextView.getTextColors();

        mRecyclerview.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerview.setAdapter(mModItemAdapter);

        mRecyclerview.addOnScrollListener(mOverlayPositionListener);

        mSearchEditText.setOnEditorActionListener((v, actionId, event) -> {
            searchMods(mSearchEditText.getText().toString());
            mSearchEditText.clearFocus();
            return false;
        });

        mOverlay.post(()->{
           int overlayHeight = mOverlay.getHeight();
           mRecyclerview.setPadding(mRecyclerview.getPaddingLeft(),
                   mRecyclerview.getPaddingTop() + overlayHeight,
                   mRecyclerview.getPaddingRight(),
                   mRecyclerview.getPaddingBottom());
        });
        mFilterButton.setOnClickListener(v -> displayFilterDialog());

        // Installing a modpack the user already has on their device
        mImportButton = view.findViewById(R.id.mineButton_import_local_modpack);
        mImportButton.setVisibility(View.VISIBLE);
        mImportButton.setOnClickListener(v -> mImportLauncher.launch("*/*"));
        mTaskCountListener = taskCount -> {
            runOnUiThread(() -> mImportButton.setEnabled(taskCount == 0));
            return false;
        };
        ProgressKeeper.addTaskCountListener(mTaskCountListener);

        searchMods(null);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        ProgressKeeper.removeTaskCountListener(mModItemAdapter);
        mRecyclerview.removeOnScrollListener(mOverlayPositionListener);
        if (mTaskCountListener != null) ProgressKeeper.removeTaskCountListener(mTaskCountListener);
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
                mStatusTextView.setText(R.string.search_modpack_error);
                break;
            case ERROR_NO_RESULTS:
                mStatusTextView.setTextColor(mDefaultTextColor);
                mStatusTextView.setText(R.string.search_modpack_no_result);
                break;
        }
    }

    private void searchMods(String name) {
        mSearchProgressBar.setVisibility(View.VISIBLE);
        mSearchFilters.name = name == null ? "" : name;
        mModItemAdapter.performSearchQuery(mSearchFilters);
    }

    private void displayFilterDialog() {
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(R.layout.dialog_mod_filters)
                .create();

        // setup the view behavior
        dialog.setOnShowListener(dialogInterface -> {
            TextView mSelectedVersion = dialog.findViewById(R.id.search_mod_selected_mc_version_textview);
            Button mSelectVersionButton = dialog.findViewById(R.id.search_mod_mc_version_button);
            Button mApplyButton = dialog.findViewById(R.id.search_mod_apply_filters);
            Spinner mLoaderSpinner = dialog.findViewById(R.id.search_mod_loader_spinner);
            Spinner mEngineSpinner = dialog.findViewById(R.id.search_mod_engine_spinner);

            assert mSelectVersionButton != null;
            assert mSelectedVersion != null;
            assert mApplyButton != null;

            // Set up the "Modrinth / CurseForge / Both" engine picker. If CurseForge is
            // disabled in experimental settings, only Modrinth is offered and the filter
            // is pinned to it, since a CurseForge-only or Both search would otherwise
            // silently return nothing.
            boolean curseforgeDisabled = git.artdeell.mojo.prefs.LauncherPreferences.DEFAULT_PREF
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
                ArrayAdapter<String> engineAdapter = new ArrayAdapter<>(
                        requireContext(), R.layout.spinner_item_m3, engineLabels);
                engineAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_m3);
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

            // Set up loader spinner
            final String[] loaderValues = {"", "fabric", "forge", "quilt", "neoforge"};
            if (mLoaderSpinner != null) {
                String[] loaderLabels = {"Any loader", "Fabric", "Forge", "Quilt", "NeoForge"};
                ArrayAdapter<String> loaderAdapter = new ArrayAdapter<>(
                        requireContext(), R.layout.spinner_item_m3, loaderLabels);
                loaderAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_m3);
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
            mSelectedVersion.setText(mSearchFilters.mcVersion);

            mApplyButton.setOnClickListener(v -> {
                if (mEngineSpinner != null) {
                    mSearchFilters.engine = engineValues[mEngineSpinner.getSelectedItemPosition()];
                }
                if (mLoaderSpinner != null) {
                    mSearchFilters.modLoader = loaderValues[mLoaderSpinner.getSelectedItemPosition()];
                }
                mSearchFilters.mcVersion = mSelectedVersion.getText().toString();
                searchMods(mSearchEditText.getText().toString());
                dialogInterface.dismiss();
            });
        });

        dialog.show();
    }

    // ── ModpackSearchApi ──────────────────────────────────────────────────────

    private static class ModpackSearchApi extends CommonApi {
        private final SearchFilters mFilters;
        private final ModrinthApi mModrinthApi = new ModrinthApi();

        ModpackSearchApi(String curseforgeApiKey, boolean disableCurseforge, SearchFilters filters) {
            super(curseforgeApiKey, disableCurseforge);
            mFilters = filters;
        }

        /**
         * Override getModDetails so the version dropdown only shows versions
         * matching the selected MC version and loader filter.
         */
        @Override
        public ModDetail getModDetails(ModItem item) {
            if (item.apiSource == Constants.SOURCE_MODRINTH) {
                String filterVer = (mFilters.mcVersion != null && !mFilters.mcVersion.isEmpty())
                        ? mFilters.mcVersion : null;
                String filterLoader = (mFilters.modLoader != null && !mFilters.modLoader.isEmpty())
                        ? mFilters.modLoader : null;
                return mModrinthApi.getModDetails(item, filterVer, filterLoader);
            }
            // CurseForge: delegate normally (CF search already filters by version/loader)
            return super.getModDetails(item);
        }
    }
}