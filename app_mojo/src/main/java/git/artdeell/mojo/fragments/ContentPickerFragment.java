package git.artdeell.mojo.fragments;

import git.artdeell.mojo.instances.Instances;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.google.android.material.chip.ChipGroup;
import git.artdeell.mojo.modding.modpacks.models.Constants;
import git.artdeell.mojo.utils.profiles.VersionSelectorDialog;

import git.artdeell.mojo.R;
import git.artdeell.mojo.modding.modpacks.models.ContentType;
import git.artdeell.mojo.prefs.LauncherPreferences;

/**
 * Landscape two-pane replacement for the old {@code dialog_content_picker} AlertDialog.
 * Lives in {@link MainMenuFragment}'s {@code left_pane_container}, replacing the normal
 * sidebar, while the chosen content type (mods / resource packs / shader packs) is shown
 * in the right pane — same as it worked before the dialog existed. Stays visible/selectable
 * so switching between content types doesn't require reopening a dialog each time.
 */
public class ContentPickerFragment extends Fragment {
    public static final String TAG = "ContentPickerFragment";
    public static final String ARG_MANAGE = "manage";

    public ContentPickerFragment() {
        super(R.layout.fragment_content_picker);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        boolean manage = getArguments() != null && getArguments().getBoolean(ARG_MANAGE, false);

        TextView title = view.findViewById(R.id.content_picker_title);
        if (title != null) {
            title.setText(manage ? R.string.content_picker_title_manage
                                  : R.string.content_picker_title_browse);
        }

        ImageButton filterButton = view.findViewById(R.id.content_picker_filter);
        if (filterButton != null) {
            filterButton.setVisibility(View.VISIBLE);
            if (manage) {
                filterButton.setOnClickListener(v -> {
                    String profileKey = Instances.getSelectedInstanceKey();
                    ContentFilterDialog.show(requireContext(), profileKey, (version, loader) -> {
                        Toast.makeText(requireContext(),
                                getString(R.string.manage_mods_filter_active, version, loader),
                                Toast.LENGTH_SHORT).show();
                    });
                });
            } else {
                // Browse mode: the filters live inline below, as cards
                filterButton.setVisibility(View.GONE);
                setupFilterPanel(view);
            }
        }

        View backButton = view.findViewById(R.id.content_picker_back);

        Fragment parent = getParentFragment();
        if (!(parent instanceof MainMenuFragment)) return;
        MainMenuFragment mainMenuFragment = (MainMenuFragment) parent;

        if (backButton != null) backButton.setOnClickListener(
                v -> mainMenuFragment.clearRightPane());

        View modsButton = view.findViewById(R.id.content_picker_mods);
        View resourcepacksButton = view.findViewById(R.id.content_picker_resourcepacks);
        View shaderpacksButton = view.findViewById(R.id.content_picker_shaderpacks);

        if (modsButton != null) modsButton.setOnClickListener(
                v -> mainMenuFragment.selectContentType(manage, ContentType.MOD));
        if (resourcepacksButton != null) resourcepacksButton.setOnClickListener(
                v -> mainMenuFragment.selectContentType(manage, ContentType.RESOURCE_PACK));
        if (shaderpacksButton != null) shaderpacksButton.setOnClickListener(
                v -> mainMenuFragment.selectContentType(manage, ContentType.SHADER_PACK));
    }

    // ── Inline filter cards (browse mode, landscape) ─────────────────────────

    private static final int[] LOADER_CHIPS = {
            R.id.pf_loader_any, R.id.pf_loader_fabric, R.id.pf_loader_forge,
            R.id.pf_loader_quilt, R.id.pf_loader_neoforge};
    private static final String[] LOADER_VALUES = {"", "fabric", "forge", "quilt", "neoforge"};

    private View mLoaderCard;
    private View mEngineCard;
    private TextView mEngineValue, mVersionValue, mLoaderValue;
    private ChipGroup mEngineChips, mLoaderChips;
    private View mVersionClear;
    private boolean mRefreshing;
    private FragmentManager.FragmentLifecycleCallbacks mSearchCallbacks;

    @Nullable
    private ModsSearchFragment findSearch() {
        Fragment parent = getParentFragment();
        if (!(parent instanceof MainMenuFragment)) return null;
        Fragment right = parent.getChildFragmentManager().findFragmentById(R.id.right_pane_container);
        return right instanceof ModsSearchFragment ? (ModsSearchFragment) right : null;
    }

    private void setupFilterPanel(View view) {
        view.findViewById(R.id.content_picker_filters).setVisibility(View.VISIBLE);
        mEngineCard = view.findViewById(R.id.pf_engine_card);
        mLoaderCard = view.findViewById(R.id.pf_loader_card);
        mEngineValue = view.findViewById(R.id.pf_engine_value);
        mVersionValue = view.findViewById(R.id.pf_version_value);
        mLoaderValue = view.findViewById(R.id.pf_loader_value);
        mEngineChips = view.findViewById(R.id.pf_engine_chips);
        mLoaderChips = view.findViewById(R.id.pf_loader_chips);
        mVersionClear = view.findViewById(R.id.pf_version_clear);

        boolean curseforgeDisabled = LauncherPreferences.DEFAULT_PREF.getBoolean("disableCurseforgeApi", false);
        if (curseforgeDisabled) mEngineCard.setVisibility(View.GONE);

        // The whole card is the button: tapping anywhere on it expands its choices or opens the picker
        mEngineCard.setOnClickListener(v -> toggle(mEngineChips, view, R.id.pf_engine_chevron));
        mLoaderCard.setOnClickListener(v -> toggle(mLoaderChips, view, R.id.pf_loader_chevron));
        view.findViewById(R.id.pf_version_card).setOnClickListener(v -> VersionSelectorDialog.open(
                v.getContext(), true, (id, snapshot) -> applyChange(-1, id == null ? "" : id, null)));
        mVersionClear.setOnClickListener(v -> applyChange(-1, "", null));

        mEngineChips.setOnCheckedStateChangeListener((group, ids) -> {
            if (mRefreshing || ids.isEmpty()) return;
            int id = ids.get(0);
            applyChange(id == R.id.pf_engine_curseforge ? Constants.ENGINE_CURSEFORGE
                    : id == R.id.pf_engine_both ? Constants.ENGINE_BOTH : Constants.ENGINE_MODRINTH, null, null);
        });
        mLoaderChips.setOnCheckedStateChangeListener((group, ids) -> {
            if (mRefreshing || ids.isEmpty()) return;
            for (int i = 0; i < LOADER_CHIPS.length; i++) {
                if (LOADER_CHIPS[i] == ids.get(0)) { applyChange(-1, null, LOADER_VALUES[i]); return; }
            }
        });
        // curseforge off: "Both" / CurseForge chips make no sense
        if (curseforgeDisabled) {
            view.findViewById(R.id.pf_engine_curseforge).setVisibility(View.GONE);
            view.findViewById(R.id.pf_engine_both).setVisibility(View.GONE);
        }

        // The search fragment is replaced whenever the content type changes, resync each time
        Fragment parent = getParentFragment();
        if (parent != null) {
            mSearchCallbacks = new FragmentManager.FragmentLifecycleCallbacks() {
                @Override
                public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                                                  @NonNull View v, @Nullable Bundle savedInstanceState) {
                    if (f instanceof ModsSearchFragment) v.post(ContentPickerFragment.this::refreshFilterCards);
                }
            };
            parent.getChildFragmentManager().registerFragmentLifecycleCallbacks(mSearchCallbacks, false);
        }
        view.post(this::refreshFilterCards);
    }

    private void toggle(ChipGroup chips, View root, int chevronId) {
        boolean open = chips.getVisibility() != View.VISIBLE;
        chips.setVisibility(open ? View.VISIBLE : View.GONE);
        root.findViewById(chevronId).setRotation(open ? 180f : 0f);
    }

    /** @param engine -1 to keep it, a null string to keep the version or loader */
    private void applyChange(int engine, @Nullable String version, @Nullable String loader) {
        ModsSearchFragment search = findSearch();
        if (search == null) return;
        search.applyFilters(engine >= 0 ? engine : search.getEngine(),
                version != null ? version : search.getMcVersion(),
                loader != null ? loader : search.getModLoader());
        refreshFilterCards();
    }

    private void refreshFilterCards() {
        ModsSearchFragment search = findSearch();
        if (search == null || mEngineValue == null || getView() == null) return;
        mRefreshing = true;
        int engine = search.getEngine();
        mEngineValue.setText(engine == Constants.ENGINE_CURSEFORGE ? R.string.search_mod_engine_curseforge
                : engine == Constants.ENGINE_BOTH ? R.string.search_mod_engine_both
                : R.string.search_mod_engine_modrinth);
        mEngineChips.check(engine == Constants.ENGINE_CURSEFORGE ? R.id.pf_engine_curseforge
                : engine == Constants.ENGINE_BOTH ? R.id.pf_engine_both : R.id.pf_engine_modrinth);

        String version = search.getMcVersion();
        if (version.isEmpty()) mVersionValue.setText(R.string.filter_any_version);
        else mVersionValue.setText(version);
        mVersionClear.setVisibility(version.isEmpty() ? View.GONE : View.VISIBLE);

        mLoaderCard.setVisibility(search.supportsLoaderFilter() ? View.VISIBLE : View.GONE);
        String loader = search.getModLoader();
        mLoaderChips.check(R.id.pf_loader_any);
        mLoaderValue.setText(R.string.search_mod_any_loader);
        for (int i = 0; i < LOADER_VALUES.length; i++) {
            if (LOADER_VALUES[i].equals(loader)) {
                mLoaderChips.check(LOADER_CHIPS[i]);
                if (i > 0) mLoaderValue.setText(((TextView) getView().findViewById(LOADER_CHIPS[i])).getText());
                break;
            }
        }
        mRefreshing = false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Fragment parent = getParentFragment();
        if (parent != null && mSearchCallbacks != null) {
            parent.getChildFragmentManager().unregisterFragmentLifecycleCallbacks(mSearchCallbacks);
        }
        mSearchCallbacks = null;
        mEngineValue = null;
    }
}
