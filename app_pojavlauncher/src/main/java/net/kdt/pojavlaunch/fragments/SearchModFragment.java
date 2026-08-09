package net.kdt.pojavlaunch.fragments;

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

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.modloaders.modpacks.ModItemAdapter;
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthApi;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.profiles.VersionSelectorDialog;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;

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

    private final SearchFilters mSearchFilters;

    public SearchModFragment(){
        super(R.layout.fragment_mod_search);
        mSearchFilters = new SearchFilters();
        mSearchFilters.isModpack = true;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        boolean disableCurseforge = net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_DISABLE_CURSEFORGE_API;
        String curseforgeApiKey = disableCurseforge
                ? "" : net.kdt.pojavlaunch.prefs.LauncherPreferences.resolveCurseforgeApiKey(context);
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

        searchMods(null);
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
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
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
                ArrayAdapter<String> engineAdapter = new ArrayAdapter<>(
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

            // Set up loader spinner
            final String[] loaderValues = {"", "fabric", "forge", "quilt", "neoforge"};
            if (mLoaderSpinner != null) {
                String[] loaderLabels = {"Any loader", "Fabric", "Forge", "Quilt", "NeoForge"};
                ArrayAdapter<String> loaderAdapter = new ArrayAdapter<>(
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