package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.hasNoOnlineProfileDialog;
import static net.kdt.pojavlaunch.Tools.hasOnlineProfile;
import static net.kdt.pojavlaunch.Tools.openPath;
import static net.kdt.pojavlaunch.Tools.shareLog;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;

public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private mcVersionSpinner mVersionSpinner;
    // Non-null only in landscape — the right content pane
    private FrameLayout mRightPane;
    // Intercepts back press when right pane has a back stack
    private OnBackPressedCallback mRightPaneBackCallback;

    /** Returns true when the two-pane landscape layout is active. */
    private boolean isTwoPane() {
        return mRightPane != null;
    }

    /**
     * Called from LauncherActivity: opens a fragment in the right pane when in landscape,
     * otherwise does nothing and returns false so the caller can fall back.
     */
    public boolean tryOpenInRightPane(Class<? extends Fragment> fragmentClass, String tag, @Nullable Bundle args) {
        if (!isTwoPane()) return false;
        openPane(fragmentClass, tag, args);
        return true;
    }

    /**
     * In landscape: loads a fragment into the right pane via child fragment manager
     * so the left sidebar stays visible.
     * In portrait: falls back to the standard full-screen activity swap.
     */
    private void openPane(Class<? extends Fragment> fragmentClass, String tag, @Nullable Bundle args) {
        if (isTwoPane()) {
            getChildFragmentManager()
                    .beginTransaction()
                    .setReorderingAllowed(true)
                    .replace(R.id.right_pane_container, fragmentClass, args, tag)
                    .addToBackStack(tag)
                    .commit();
        } else {
            Tools.swapFragment(requireActivity(), fragmentClass, tag, args);
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Register a back-press interceptor that only activates when the right pane
        // has something on its back stack, so Back clears the pane before exiting.
        mRightPaneBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                getChildFragmentManager().popBackStack();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, mRightPaneBackCallback);
        getChildFragmentManager().addOnBackStackChangedListener(() ->
                mRightPaneBackCallback.setEnabled(
                        getChildFragmentManager().getBackStackEntryCount() > 0));
    }

    public MainMenuFragment() {
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mNewsButton           = view.findViewById(R.id.news_button);
        Button mDiscordButton        = view.findViewById(R.id.discord_button);
        Button mCustomControlButton  = view.findViewById(R.id.custom_control_button);
        Button mInstallJarButton     = view.findViewById(R.id.install_jar_button);
        Button mShareLogsButton      = view.findViewById(R.id.share_logs_button);
        Button mManageModsButton     = view.findViewById(R.id.open_files_button);
        Button mOpenDirectoryButton  = view.findViewById(R.id.open_directory_button);
        Button mModStoreButton       = view.findViewById(R.id.mod_store_button);

        ImageButton mEditProfileButton = view.findViewById(R.id.edit_profile_button);
        Button mPlayButton = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);

        // Detect two-pane landscape layout
        mRightPane = view.findViewById(R.id.right_pane_container);

        // Wiki
        mNewsButton.setOnClickListener(v -> Tools.openURL(requireActivity(), Tools.URL_HOME));

        // Discord
        mDiscordButton.setOnClickListener(v -> Tools.openURL(requireActivity(), getString(R.string.discord_invite)));

        // Custom controls
        mCustomControlButton.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), CustomControlsActivity.class)));

        // Mod Store → use right pane in landscape, full-swap in portrait
        if (mModStoreButton != null)
            mModStoreButton.setOnClickListener(v ->
                    openPane(ModsSearchFragment.class, ModsSearchFragment.TAG, null));

        // Execute .jar
        if (hasOnlineProfile()) {
            mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation(false));
            mInstallJarButton.setOnLongClickListener(v -> {
                runInstallerWithConfirmation(true);
                return true;
            });
        } else {
            mInstallJarButton.setOnClickListener(v -> hasNoOnlineProfileDialog(requireActivity()));
        }

        // Share logs
        if (mShareLogsButton != null)
            mShareLogsButton.setOnClickListener(v -> shareLog(requireContext()));

        // Manage Mods & Tools → right pane in landscape, full-swap in portrait
        mManageModsButton.setOnClickListener(v ->
                openPane(ManageModsFragment.class, ManageModsFragment.TAG, null));

        // Open game directory (original behaviour)
        if (mOpenDirectoryButton != null) {
            mOpenDirectoryButton.setOnClickListener(v -> {
                if (Tools.isDemoProfile(v.getContext())) {
                    hasNoOnlineProfileDialog(getActivity(),
                            getString(R.string.demo_unsupported), getString(R.string.change_account));
                } else if (!hasOnlineProfile()) {
                    hasNoOnlineProfileDialog(requireActivity());
                } else {
                    openPath(v.getContext(), getCurrentProfileDirectory(), false);
                }
            });
        }

        // Edit profile
        mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));

        // Play
        mPlayButton.setOnClickListener(v -> ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true));

        // Long-press wiki → gamepad mapper (hidden)
        mNewsButton.setOnLongClickListener(v -> {
            Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
            return true;
        });
    }

    private File getCurrentProfileDirectory() {
        String currentProfile = LauncherPreferences.DEFAULT_PREF
                .getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        if (!Tools.isValidString(currentProfile)) return new File(Tools.DIR_GAME_NEW);
        LauncherProfiles.load();
        MinecraftProfile profileObject = LauncherProfiles.mainProfileJson.profiles.get(currentProfile);
        if (profileObject == null) return new File(Tools.DIR_GAME_NEW);
        return Tools.getGameDirPath(profileObject);
    }

    @Override
    public void onResume() {
        super.onResume();
        mVersionSpinner.reloadProfiles();
    }

    private void runInstallerWithConfirmation(boolean isCustomArgs) {
        if (ProgressKeeper.getTaskCount() == 0)
            Tools.installMod(requireActivity(), isCustomArgs);
        else
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }
}