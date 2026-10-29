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

import git.artdeell.mojo.R;
import git.artdeell.mojo.modding.modpacks.models.ContentType;

/**
 * Landscape two-pane replacement for the old {@code dialog_content_picker} AlertDialog.
 * Lives in {@link MainMenuFragment}'s {@code left_pane_container}, replacing the normal
 * sidebar, while the chosen content type (mods, resource packs, shader packs, worlds,
 * datapacks, and screenshots when managing) is shown
 * in the right pane — same as it worked before the dialog existed. Stays visible/selectable
 * so switching between content types doesn't require reopening a dialog each time.
 */
public class ContentPickerFragment extends Fragment {
    public static final String TAG = "ContentPickerFragment";
    public static final String ARG_MANAGE = "manage";
    /** Browse picker that was opened from Manage Content, its Back returns there instead of home */
    public static final String ARG_FROM_MANAGE = "from_manage";

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
                // Browse mode: same button, same place, it opens the filters of the open browser
                filterButton.setOnClickListener(v -> {
                    Fragment results = getParentFragmentManager().findFragmentById(R.id.right_pane_container);
                    if (results instanceof ModsSearchFragment) {
                        ((ModsSearchFragment) results).displayFilterDialog();
                    } else {
                        Toast.makeText(requireContext(), R.string.content_picker_filter_pick_type,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }

        View backButton = view.findViewById(R.id.content_picker_back);

        Fragment parent = getParentFragment();
        if (!(parent instanceof MainMenuFragment)) return;
        MainMenuFragment mainMenuFragment = (MainMenuFragment) parent;

        boolean fromManage = getArguments() != null && getArguments().getBoolean(ARG_FROM_MANAGE, false);
        if (backButton != null) backButton.setOnClickListener(
                v -> {
                    if (fromManage) mainMenuFragment.leaveBrowseFromManage();
                    else mainMenuFragment.clearRightPane();
                });

        View modsButton = view.findViewById(R.id.content_picker_mods);
        View resourcepacksButton = view.findViewById(R.id.content_picker_resourcepacks);
        View shaderpacksButton = view.findViewById(R.id.content_picker_shaderpacks);

        if (modsButton != null) modsButton.setOnClickListener(
                v -> mainMenuFragment.selectContentType(manage, ContentType.MOD));
        if (resourcepacksButton != null) resourcepacksButton.setOnClickListener(
                v -> mainMenuFragment.selectContentType(manage, ContentType.RESOURCE_PACK));
        if (shaderpacksButton != null) shaderpacksButton.setOnClickListener(
                v -> mainMenuFragment.selectContentType(manage, ContentType.SHADER_PACK));

        View worldsButton = view.findViewById(R.id.content_picker_worlds);
        View datapacksButton = view.findViewById(R.id.content_picker_datapacks);
        View screenshotsButton = view.findViewById(R.id.content_picker_screenshots);

        if (worldsButton != null) worldsButton.setOnClickListener(
                v -> mainMenuFragment.selectContentType(manage, ContentType.WORLD));
        if (datapacksButton != null) datapacksButton.setOnClickListener(
                v -> mainMenuFragment.selectContentType(manage, ContentType.DATAPACK));
        // Screenshots can only be managed, there is nothing to browse
        if (screenshotsButton != null) {
            screenshotsButton.setVisibility(manage ? View.VISIBLE : View.GONE);
            screenshotsButton.setOnClickListener(
                    v -> mainMenuFragment.selectContentType(true, ContentType.SCREENSHOT));
        }
    }
}
