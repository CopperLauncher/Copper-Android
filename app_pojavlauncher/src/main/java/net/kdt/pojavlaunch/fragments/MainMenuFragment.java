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
    // Intercepts Back when the right pane has something above home
    private OnBackPressedCallback mRightPaneBackCallback;

    // ─── Two-pane helpers ────────────────────────────────────────────────────

    /** True when the two-pane landscape layout is active. */
    private boolean isTwoPane() {
        return mRightPane != null;
    }

    /**
     * True when the right pane has a non-home fragment on the back stack.
     * Used by LauncherActivity to decide gear = home vs gear = settings.
     */
    public boolean isRightPaneActive() {
        return isTwoPane() && getChildFragmentManager().getBackStackEntryCount() > 0;
    }

    /**
     * Pops everything off the right pane back stack so the home fragment shows again.
     * Safe to call even if back stack is empty.
     */
    public void clearRightPane() {
        if (!isTwoPane()) return;
        int count = getChildFragmentManager().getBackStackEntryCount();
        if (count > 0) {
            getChildFragmentManager().popBackStack(
                    getChildFragmentManager().getBackStackEntryAt(0).getName(),
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
    }

    /**
     * Opens a fragment in the right pane (landscape) or full-screen (portrait).
     * Called from LauncherActivity for Settings / Add Account.
     * Returns true if the pane was used.
     */
    public boolean tryOpenInRightPane(Class<? extends Fragment> fragmentClass, String tag,
                                      @Nullable Bundle args) {
        if (!isTwoPane()) return false;
        openPane(fragmentClass, tag, args);
        return true;
    }

    /**
     * Internal navigation: right pane in landscape, full-screen swap in portrait.
     */
    private void openPane(Class<? extends Fragment> fragmentClass, String tag,
                          @Nullable Bundle args) {
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

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    public MainMenuFragment() {
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Back-press callback — only enabled while right pane has content above home
        mRightPaneBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                // Safety check: only pop if we're still attached
                if (isAdded() && getChildFragmentManager().getBackStackEntryCount() > 0) {
                    getChildFragmentManager().popBackStack();
                }
            }
        };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(this, mRightPaneBackCallback);
        getChildFragmentManager().addOnBackStackChangedListener(() ->
                mRightPaneBackCallback.setEnabled(isRightPaneActive()));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mNewsButton          = view.findViewById(R.id.news_button);
        Button mDiscordButton       = view.findViewById(R.id.discord_button);
        Button mCustomControlButton = view.findViewById(R.id.custom_control_button);
        Button mInstallJarButton    = view.findViewById(R.id.install_jar_button);
        Button mShareLogsButton     = view.findViewById(R.id.share_logs_button);
        Button mManageModsButton    = view.findViewById(R.id.open_files_button);
        Button mOpenDirectoryButton = view.findViewById(R.id.open_directory_button);
        Button mModStoreButton      = view.findViewById(R.id.mod_store_button);

        ImageButton mEditProfileButton = view.findViewById(R.id.edit_profile_button);
        Button mPlayButton = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);

        // Detect two-pane landscape layout
        mRightPane = view.findViewById(R.id.right_pane_container);

        // ── Load the home fragment into the right pane (landscape only) ──────
        // Only inflate it once; savedInstanceState != null means it's already there
        if (isTwoPane() && savedInstanceState == null) {
            getChildFragmentManager()
                    .beginTransaction()
                    .setReorderingAllowed(true)
                    .replace(R.id.right_pane_container, RightPaneHomeFragment.class, null,
                            RightPaneHomeFragment.TAG)
                    // NOT added to back stack — home is the base, not a destination
                    .commit();
        }

        // ── Sidebar buttons that are hidden in landscape (stubs kept for safety) ──
        // Wiki / Discord are moved to RightPaneHomeFragment in landscape;
        // they stay in the sidebar on portrait via fragment_launcher.xml (no-land).
        if (mNewsButton != null)
            mNewsButton.setOnClickListener(
                    v -> Tools.openURL(requireActivity(), Tools.URL_HOME));
        if (mDiscordButton != null)
            mDiscordButton.setOnClickListener(
                    v -> Tools.openURL(requireActivity(), getString(R.string.discord_invite)));

        // Custom controls (always opens as Activity — can't be in the pane)
        mCustomControlButton.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), CustomControlsActivity.class)));

        // Mod Store
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
            mInstallJarButton.setOnClickListener(
                    v -> hasNoOnlineProfileDialog(requireActivity()));
        }

        // Share logs
        if (mShareLogsButton != null)
            mShareLogsButton.setOnClickListener(v -> shareLog(requireContext()));

        // Manage Mods
        mManageModsButton.setOnClickListener(v ->
                openPane(ManageModsFragment.class, ManageModsFragment.TAG, null));

        // Open game directory
        if (mOpenDirectoryButton != null) {
            mOpenDirectoryButton.setOnClickListener(v -> {
                if (Tools.isDemoProfile(v.getContext())) {
                    hasNoOnlineProfileDialog(getActivity(),
                            getString(R.string.demo_unsupported),
                            getString(R.string.change_account));
                } else if (!hasOnlineProfile()) {
                    hasNoOnlineProfileDialog(requireActivity());
                } else {
                    openPath(v.getContext(), getCurrentProfileDirectory(), false);
                }
            });
        }

        // Edit profile
        mEditProfileButton.setOnClickListener(
                v -> mVersionSpinner.openProfileEditor(requireActivity()));

        // Play
        mPlayButton.setOnClickListener(
                v -> ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true));

        // Long-press wiki → gamepad mapper (hidden feature)
        if (mNewsButton != null)
            mNewsButton.setOnLongClickListener(v -> {
                Tools.swapFragment(requireActivity(), GamepadMapperFragment.class,
                        GamepadMapperFragment.TAG, null);
                return true;
            });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Null out the view reference so isTwoPane() returns false after view is gone
        mRightPane = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        mVersionSpinner.reloadProfiles();
    }

    // ─── Private helpers ────────────────────────────────────────────────────

    private File getCurrentProfileDirectory() {
        String currentProfile = LauncherPreferences.DEFAULT_PREF
                .getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        if (!Tools.isValidString(currentProfile)) return new File(Tools.DIR_GAME_NEW);
        LauncherProfiles.load();
        MinecraftProfile profileObject =
                LauncherProfiles.mainProfileJson.profiles.get(currentProfile);
        if (profileObject == null) return new File(Tools.DIR_GAME_NEW);
        return Tools.getGameDirPath(profileObject);
    }

    private void runInstallerWithConfirmation(boolean isCustomArgs) {
        if (ProgressKeeper.getTaskCount() == 0)
            Tools.installMod(requireActivity(), isCustomArgs);
        else
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }
}