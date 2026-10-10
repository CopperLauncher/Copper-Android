package git.artdeell.mojo.fragments;

import git.artdeell.mojo.utils.AnimationManager;
import static git.artdeell.mojo.Tools.openPath;
import static git.artdeell.mojo.Tools.shareLog;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kdt.mcgui.mcVersionSpinner;

import git.artdeell.mojo.CustomControlsActivity;
import git.artdeell.mojo.R;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.contracts.OpenDocumentWithExtension;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.modding.modpacks.models.ContentType;
import git.artdeell.mojo.progresskeeper.ProgressKeeper;
import git.artdeell.mojo.utils.FileUtils;

import java.io.File;

/**
 * The main menu. In portrait it is a single column and every screen replaces it.
 * In landscape it is a two-pane layout: the actions stay in the left sidebar and every screen
 * opened with {@link Tools#swapFragment} shows up in the right pane, which has its own back stack.
 */
public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private mcVersionSpinner mVersionSpinner;
    /* The two-pane views, null in portrait */
    private FrameLayout mRightPane;
    private View mBottomBar;
    /* The sidebar and the pane that replaces it while the content picker is open, null in portrait */
    private View mLeftSidebar;
    private FrameLayout mLeftPaneContainer;
    /* Intercepts back when the right pane shows something above the home screen */
    private OnBackPressedCallback mRightPaneBackCallback;

    private final ActivityResultLauncher<Object> mModInstallerLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("jar"), (data)->{
                if(data != null) Tools.launchModInstaller(requireContext(), data);
            });

    private final FragmentManager.OnBackStackChangedListener mBackStackListener = () -> {
        mRightPaneBackCallback.setEnabled(isRightPaneActive());
        updateBottomBar();
        updateLeftPaneVisibility();
        // Back at the home screen: instances may have been created, renamed or deleted
        if (mVersionSpinner != null && getChildFragmentManager().getBackStackEntryCount() == 0) {
            mVersionSpinner.reloadProfiles();
        }
    };

    public MainMenuFragment(){
        super(R.layout.fragment_launcher);
    }

    // ─── Two-pane helpers ────────────────────────────────────────────────────

    /** @return whether the two-pane landscape layout is active */
    public boolean isTwoPane() {
        return mRightPane != null;
    }

    /** @return whether the right pane shows a screen above its home screen */
    public boolean isRightPaneActive() {
        return isTwoPane() && getChildFragmentManager().getBackStackEntryCount() > 0;
    }

    /**
     * Opens a screen in the right pane. Does nothing in portrait.
     * @return whether the screen was opened, false means the caller has to show it another way
     */
    public boolean openInPane(@NonNull Class<? extends Fragment> fragmentClass,
                              @Nullable String tag, @Nullable Bundle args) {
        if (!isTwoPane()) return false;
        String entryName = tag != null ? tag : fragmentClass.getName();
        FragmentManager manager = getChildFragmentManager();
        int count = manager.getBackStackEntryCount();
        // Already showing it, ignore the double tap
        if (count > 0 && entryName.equals(manager.getBackStackEntryAt(count - 1).getName())) return true;
        manager.beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.right_pane_container, fragmentClass, args, tag)
                .addToBackStack(entryName)
                .commit();
        return true;
    }

    /** Pops one screen off the right pane */
    public void popRightPane() {
        if (isRightPaneActive()) getChildFragmentManager().popBackStack();
    }

    /** Pops every screen off the right pane, so its home screen shows again */
    public void clearRightPane() {
        FragmentManager manager = getChildFragmentManager();
        if (manager.getBackStackEntryCount() == 0) return;
        manager.popBackStack(manager.getBackStackEntryAt(0).getId(), FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    /** The bottom bar (instance and play) only belongs to the home screen, the other screens get the full height */
    private void updateBottomBar() {
        if (mBottomBar == null) return;
        boolean atHome = getChildFragmentManager().getBackStackEntryCount() == 0;
        mBottomBar.setVisibility(atHome ? View.VISIBLE : View.GONE);
    }

    // ─── Browse / Manage Content ─────────────────────────────────────────────

    /** Opens a screen in the right pane, or full screen in portrait */
    private void openPane(@NonNull Class<? extends Fragment> fragmentClass, @NonNull String tag,
                          @Nullable Bundle args) {
        if (!openInPane(fragmentClass, tag, args)) {
            Tools.swapFragment(requireActivity(), fragmentClass, tag, args);
        }
    }

    /**
     * Shows the Browse Content / Manage Content picker: mods, resource packs or shader packs.
     * The manage variant also exposes the per-instance version/loader filter.
     * Landscape docks it in the left pane, portrait shows it as a dialog.
     */
    private void showContentPicker(boolean manage) {
        if (isTwoPane()) openContentPickerPane(manage);
        else showContentPickerDialog(manage);
    }

    /**
     * Landscape: replaces the sidebar with ContentPickerFragment inside left_pane_container and
     * loads the Mods section into the right pane, all in a single back stack entry, so one Back
     * press undoes the whole action.
     */
    private void openContentPickerPane(boolean manage) {
        if (isTagAlreadyOnTop(ContentPickerFragment.TAG)) return;

        Bundle pickerArgs = new Bundle();
        pickerArgs.putBoolean(ContentPickerFragment.ARG_MANAGE, manage);

        Class<? extends Fragment> defaultFragmentClass;
        Bundle defaultArgs = new Bundle();
        String defaultTag;
        if (manage) {
            defaultFragmentClass = ManageModsFragment.class;
            defaultArgs.putString(ManageModsFragment.ARG_CONTENT_TYPE, ContentType.MOD.name());
            defaultTag = ManageModsFragment.TAG + ":" + ContentType.MOD.name();
        } else {
            defaultFragmentClass = ModsSearchFragment.class;
            populateModStoreArgs(defaultArgs, ContentType.MOD);
            defaultTag = ModsSearchFragment.TAG + ":" + ContentType.MOD.name();
        }

        getChildFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.left_pane_container, ContentPickerFragment.class, pickerArgs, ContentPickerFragment.TAG)
                .replace(R.id.right_pane_container, defaultFragmentClass, defaultArgs, defaultTag)
                .addToBackStack(ContentPickerFragment.TAG)
                .commit();
        // Visibility is updated by mBackStackListener once the transaction lands
    }

    /**
     * Called by {@link ContentPickerFragment} when one of its mods / resource packs / shader packs
     * buttons is tapped. The picker itself stays in the left pane.
     */
    public void selectContentType(boolean manage, ContentType contentType) {
        onContentTypeChosen(manage, contentType);
    }

    /** Shows the sidebar or the content picker in the left pane, depending on which is active */
    private void updateLeftPaneVisibility() {
        if (!isTwoPane() || mLeftSidebar == null || mLeftPaneContainer == null) return;
        Fragment leftFragment = getChildFragmentManager().findFragmentById(R.id.left_pane_container);
        boolean pickerActive = leftFragment instanceof ContentPickerFragment;
        mLeftSidebar.setVisibility(pickerActive ? View.GONE : View.VISIBLE);
        mLeftPaneContainer.setVisibility(pickerActive ? View.VISIBLE : View.GONE);
    }

    private void showContentPickerDialog(boolean manage) {
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(R.layout.dialog_content_picker)
                .create();

        dialog.setOnShowListener(di -> {
            TextView title = dialog.findViewById(R.id.content_picker_title);
            ImageButton filterButton = dialog.findViewById(R.id.content_picker_filter);
            View modsButton = dialog.findViewById(R.id.content_picker_mods);
            View resourcepacksButton = dialog.findViewById(R.id.content_picker_resourcepacks);
            View shaderpacksButton = dialog.findViewById(R.id.content_picker_shaderpacks);

            if (title != null) {
                title.setText(manage ? R.string.content_picker_title_manage
                                     : R.string.content_picker_title_browse);
            }

            if (filterButton != null) {
                if (manage) {
                    filterButton.setVisibility(View.VISIBLE);
                    filterButton.setOnClickListener(v ->
                            ContentFilterDialog.show(requireContext(), Instances.getSelectedInstanceKey(),
                                    (version, loader) -> Toast.makeText(requireContext(),
                                            getString(R.string.manage_mods_filter_active, version, loader),
                                            Toast.LENGTH_SHORT).show()));
                } else {
                    filterButton.setVisibility(View.GONE);
                }
            }

            if (modsButton != null) modsButton.setOnClickListener(v -> {
                dialog.dismiss();
                onContentTypeChosen(manage, ContentType.MOD);
            });
            if (resourcepacksButton != null) resourcepacksButton.setOnClickListener(v -> {
                dialog.dismiss();
                onContentTypeChosen(manage, ContentType.RESOURCE_PACK);
            });
            if (shaderpacksButton != null) shaderpacksButton.setOnClickListener(v -> {
                dialog.dismiss();
                onContentTypeChosen(manage, ContentType.SHADER_PACK);
            });
        });

        dialog.show();
    }

    private void onContentTypeChosen(boolean manage, ContentType contentType) {
        if (manage) {
            Bundle args = new Bundle();
            args.putString(ManageModsFragment.ARG_CONTENT_TYPE, contentType.name());
            openPane(ManageModsFragment.class, ManageModsFragment.TAG + ":" + contentType.name(), args);
        } else {
            Bundle args = new Bundle();
            populateModStoreArgs(args, contentType);
            openPane(ModsSearchFragment.class, ModsSearchFragment.TAG + ":" + contentType.name(), args);
        }
    }

    /**
     * Builds the arguments for ModsSearchFragment with the saved per-instance filter pre-seeded.
     * The loader filter is only passed for MOD, resource packs and shader packs aren't loader specific.
     */
    private void populateModStoreArgs(Bundle args, ContentType contentType) {
        String instanceKey = Instances.getSelectedInstanceKey();
        SharedPreferences prefs = requireContext()
                .getSharedPreferences("mod_filters", Context.MODE_PRIVATE);

        String version = prefs.getString("mc_version_" + instanceKey, "");
        String loader  = prefs.getString("loader_" + instanceKey, "");

        // Nothing saved for this instance yet: default to the version/loader it runs, so the
        // results are already relevant without a filter-then-apply step
        if (version.isEmpty() && loader.isEmpty()) {
            InstanceVersionResolver.Info info = InstanceVersionResolver.resolve(instanceKey);
            if (info.mcVersion != null) version = info.mcVersion;
            loader = info.loader;
        }

        args.putString(ModsSearchFragment.ARG_CONTENT_TYPE, contentType.name());
        if (!version.isEmpty()) args.putString(ModsSearchFragment.ARG_PRESET_MC_VERSION, version);
        if (contentType == ContentType.MOD && !loader.isEmpty()) {
            args.putString(ModsSearchFragment.ARG_PRESET_LOADER, loader);
        }
    }

    /** @return whether the tag is the top entry of the right pane back stack, ie already showing */
    private boolean isTagAlreadyOnTop(String tag) {
        if (!isTwoPane()) return false;
        FragmentManager manager = getChildFragmentManager();
        int count = manager.getBackStackEntryCount();
        if (count == 0) return false;
        return tag.equals(manager.getBackStackEntryAt(count - 1).getName());
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The owner is this fragment, so the callback is removed with it
        mRightPaneBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (isRightPaneActive()) getChildFragmentManager().popBackStackImmediate();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, mRightPaneBackCallback);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Portrait only
        Button mNewsButton = view.findViewById(R.id.news_button);
        Button mDiscordButton = view.findViewById(R.id.social_media_button);
        // Both
        Button mCustomControlButton = view.findViewById(R.id.custom_control_button);
        Button mInstallJarButton = view.findViewById(R.id.install_jar_button);
        Button mShareLogsButton = view.findViewById(R.id.share_logs_button);
        Button mManageContentButton = view.findViewById(R.id.open_files_button);
        Button mOpenDirectoryButton = view.findViewById(R.id.open_directory_button);
        Button mModStoreButton = view.findViewById(R.id.mod_store_button);

        ImageButton mEditProfileButton = view.findViewById(R.id.edit_profile_button);
        Button mPlayButton = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);

        mRightPane = view.findViewById(R.id.right_pane_container);
        mBottomBar = view.findViewById(R.id.bottom_bar);
        mLeftSidebar = view.findViewById(R.id.left_sidebar);
        mLeftPaneContainer = view.findViewById(R.id.left_pane_container);
        getChildFragmentManager().addOnBackStackChangedListener(mBackStackListener);

        FragmentManager childManager = getChildFragmentManager();
        if (isTwoPane()) {
            // Checked by presence and not by savedInstanceState, so a rotation keeps working
            if (childManager.findFragmentById(R.id.right_pane_container) == null) {
                childManager.beginTransaction()
                        .setReorderingAllowed(true)
                        // Not on the back stack, the home screen is the base and not a destination
                        .replace(R.id.right_pane_container, RightPaneHomeFragment.class, null,
                                RightPaneHomeFragment.TAG)
                        .commit();
            }
            mBackStackListener.onBackStackChanged();
        } else if (childManager.getBackStackEntryCount() > 0) {
            // The pane the screens were in is gone after rotating to portrait
            childManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }

        ViewGroup staggerParent = isTwoPane()
                ? view.findViewById(R.id.left_sidebar_content)
                : (mNewsButton != null ? (ViewGroup) mNewsButton.getParent() : null);
        AnimationManager.staggerIn(staggerParent);

        if (mNewsButton != null) {
            mNewsButton.setOnClickListener(v -> Tools.openURL(requireActivity(), Tools.URL_HOME));
            mNewsButton.setOnLongClickListener((v)->{
                Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
                return true;
            });
        }
        if (mDiscordButton != null)
            mDiscordButton.setOnClickListener(v -> Tools.openURL(requireActivity(), getString(R.string.social_media_invite)));

        mCustomControlButton.setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation());
        mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));

        mPlayButton.setOnClickListener(v -> ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true));

        mShareLogsButton.setOnClickListener((v) -> shareLog(requireContext()));

        mModStoreButton.setOnClickListener(v -> showContentPicker(false));
        mManageContentButton.setOnClickListener(v -> showContentPicker(true));
        mOpenDirectoryButton.setOnClickListener((v)-> openGameDirectory(v.getContext()));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        getChildFragmentManager().removeOnBackStackChangedListener(mBackStackListener);
        mRightPane = null;
        mBottomBar = null;
        mLeftSidebar = null;
        mLeftPaneContainer = null;
        mVersionSpinner = null;
    }

    private void openGameDirectory(Context context) {
        Instance instance = Instances.loadSelectedInstance();
        if(instance == null) {
            Toast.makeText(context, R.string.no_instance, Toast.LENGTH_LONG).show();
            return;
        }
        File gameDirectory = instance.getGameDirectory();
        if(FileUtils.ensureDirectorySilently(gameDirectory)) {
            openPath(context, gameDirectory, false);
        }else {
            Toast.makeText(context, R.string.gamedir_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        ExtraCore.setValue(ExtraConstants.REFRESH_ACCOUNT_SPINNER, true);
        // Runs after the task listeners, which could have changed the bar
        if (mBottomBar != null) mBottomBar.post(this::updateBottomBar);
    }

    private void runInstallerWithConfirmation() {
        if (ProgressKeeper.getTaskCount() == 0) {
            mModInstallerLauncher.launch(null);
        } else Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }
}
