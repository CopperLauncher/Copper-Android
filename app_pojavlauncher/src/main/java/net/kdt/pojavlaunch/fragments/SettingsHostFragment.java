package net.kdt.pojavlaunch.fragments;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.prefs.screens.LauncherPreferenceFragment;
import net.kdt.pojavlaunch.prefs.screens.LauncherPreferenceRendererSettingsFragment;

/**
 * Landscape two-pane settings screen. {@code pref_main} (the root settings list) lives in
 * the left pane and never leaves it; the right pane defaults to the Renderers screen and
 * switches to whichever category (Video/Controls/Java/Misc/Appearance/Experimental) the user
 * taps — a {@code Preference} with {@code android:fragment} set.
 *
 * <p>This is a separate host from {@link MainMenuFragment}'s own two-pane layout — opening
 * Settings replaces the whole main-menu screen (via {@code Tools.swapFragment}), it doesn't
 * borrow the main menu's right pane the way it used to.
 *
 * <p>Implements {@link PreferenceFragmentCompat.OnPreferenceStartFragmentCallback} so that
 * clicks on {@code android:fragment} preferences inside the left pane's
 * {@link LauncherPreferenceFragment} route here instead of falling through unhandled —
 * PreferenceFragmentCompat looks up the parent fragment chain for this callback before
 * falling back to the hosting Context/Activity, and since pref_main is a child fragment of
 * this one, it's found here first.
 */
public class SettingsHostFragment extends Fragment
        implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    public static final String TAG = "SettingsHostFragment";
    private static final String LEFT_PANE_TAG = "SETTINGS_LEFT_PANE_MAIN";
    private static final String DEFAULT_RIGHT_PANE_TAG = "SETTINGS_RIGHT_PANE_DEFAULT_RENDERERS";

    private OnBackPressedCallback mRightPaneBackCallback;

    public SettingsHostFragment() {
        super(R.layout.fragment_settings_host);
    }

    /** True when the two-pane landscape layout is active (see fragment_settings_host land/no-land variants). */
    private boolean isTwoPane() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /** True when the right pane has a sub-screen on the back stack. */
    public boolean isRightPaneActive() {
        return getChildFragmentManager().getBackStackEntryCount() > 0;
    }

    /** Pops one entry off the right pane back stack. Called from LauncherActivity.onBackPressed(). */
    public void popRightPane() {
        if (getChildFragmentManager().getBackStackEntryCount() > 0) {
            getChildFragmentManager().popBackStack();
        }
    }

    /** Pops everything off the right pane back stack, leaving it empty again. */
    public void clearRightPane() {
        int count = getChildFragmentManager().getBackStackEntryCount();
        if (count > 0) {
            getChildFragmentManager().popBackStack(
                    getChildFragmentManager().getBackStackEntryAt(0).getName(),
                    FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRightPaneBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (getChildFragmentManager().getBackStackEntryCount() > 0) {
                    getChildFragmentManager().popBackStackImmediate();
                }
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, mRightPaneBackCallback);
        getChildFragmentManager().addOnBackStackChangedListener(
                () -> mRightPaneBackCallback.setEnabled(isRightPaneActive()));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Fragment existingLeft = getChildFragmentManager().findFragmentById(R.id.settings_left_pane_container);
        Fragment existingRight = getChildFragmentManager().findFragmentById(R.id.settings_right_pane_container);
        if (existingLeft == null && existingRight == null) {
            // Both panes are populated in one transaction, NOT added to the back
            // stack — this is the settings screen's "home" state (Renderers shown
            // by default), same pattern as RightPaneHomeFragment being the main
            // menu's un-stacked base state. Only explicit taps on a pref_main
            // category (onPreferenceStartFragment, below) get their own back-stack
            // entry; if the default screen were stacked too, leaving Settings would
            // wrongly take two Back presses instead of one.
            getChildFragmentManager()
                    .beginTransaction()
                    .setReorderingAllowed(true)
                    .replace(R.id.settings_left_pane_container, LauncherPreferenceFragment.class, null, LEFT_PANE_TAG)
                    .replace(R.id.settings_right_pane_container, LauncherPreferenceRendererSettingsFragment.class, null, DEFAULT_RIGHT_PANE_TAG)
                    .commit();
        }
    }

    /**
     * Called by androidx.preference when a {@code Preference} with {@code android:fragment}
     * set is tapped. Portrait doesn't reach here (see class docs) — this fragment is only
     * ever hosted in landscape — but as a defensive fallback if a rotation lands us in the
     * no-land layout, the same right-pane container is reused (it's just collapsed to 0dp
     * there); it will simply be invisible until the user rotates back.
     */
    @Override
    public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller,
                                              @NonNull Preference pref) {
        String fragmentClassName = pref.getFragment();
        if (fragmentClassName == null) return false;

        String tag = "SETTINGS_RIGHT_PANE:" + fragmentClassName;
        FragmentManager fm = getChildFragmentManager();
        int count = fm.getBackStackEntryCount();
        if (count > 0 && tag.equals(fm.getBackStackEntryAt(count - 1).getName())) {
            return true; // already showing — swallow the duplicate tap
        }

        Fragment fragment = fm.getFragmentFactory().instantiate(
                requireContext().getClassLoader(), fragmentClassName);
        fragment.setArguments(pref.getExtras());

        fm.beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.settings_right_pane_container, fragment, tag)
                .addToBackStack(tag)
                .commit();
        return true;
    }
}
