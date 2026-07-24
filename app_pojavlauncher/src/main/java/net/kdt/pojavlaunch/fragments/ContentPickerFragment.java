package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ContentType;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

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
                    String profileKey = LauncherPreferences.DEFAULT_PREF
                            .getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "default");
                    ContentFilterDialog.show(requireContext(), profileKey, (version, loader) -> {
                        Toast.makeText(requireContext(),
                                getString(R.string.manage_mods_filter_active, version, loader),
                                Toast.LENGTH_SHORT).show();
                    });
                });
            } else {
                // Browse mode: this button replaces the search_mod_filter button that used
                // to sit inside the search box in the right pane (see the layout-land
                // override of fragment_mod_search.xml). Forward the tap to whichever
                // ModsSearchFragment is currently showing there.
                filterButton.setOnClickListener(v -> {
                    Fragment parentFragment = getParentFragment();
                    if (!(parentFragment instanceof MainMenuFragment)) return;
                    Fragment rightPaneFragment = ((MainMenuFragment) parentFragment)
                            .getChildFragmentManager().findFragmentById(R.id.right_pane_container);
                    if (rightPaneFragment instanceof ModsSearchFragment) {
                        ((ModsSearchFragment) rightPaneFragment).displayFilterDialog();
                    }
                });
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
}
