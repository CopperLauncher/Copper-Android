package net.kdt.pojavlaunch.prefs.screens;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreference;
import androidx.preference.SwitchPreferenceCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.CustomSeekBarPreference;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.prefs.RendererListPreferenceDialogFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * Fragment for any settings video related
 */
public class LauncherPreferenceVideoFragment extends LauncherPreferenceFragment {
    @Override
    public void onCreatePreferences(Bundle b, String str) {
        addPreferencesFromResource(R.xml.pref_video);
        int resolution = (int) (LauncherPreferences.PREF_SCALE_FACTOR * 100);

        //Disable notch checking behavior on android 8.1 and below.
        requirePreference("ignoreNotch").setVisible(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            LauncherPreferences.PREF_NOTCH_SIZE > 0
        );

        CustomSeekBarPreference resolutionSeekbar = requirePreference(
            "resolutionRatio",
            CustomSeekBarPreference.class
        );
        resolutionSeekbar.setSuffix(" %");

        // #724 bug fix
        if (resolution < 25) {
            resolutionSeekbar.setValue(100);
        } else {
            resolutionSeekbar.setValue(resolution);
        }

        // Sustained performance is only available since Nougat
        SwitchPreference sustainedPerfSwitch = requirePreference(
            "sustainedPerformance",
            SwitchPreference.class
        );
        sustainedPerfSwitch.setVisible(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        );
        sustainedPerfSwitch.setChecked(
            LauncherPreferences.PREF_SUSTAINED_PERFORMANCE
        );

        requirePreference(
            "alternate_surface",
            SwitchPreferenceCompat.class
        ).setChecked(LauncherPreferences.PREF_USE_ALTERNATE_SURFACE);
        requirePreference("force_vsync", SwitchPreferenceCompat.class).setChecked(
            LauncherPreferences.PREF_FORCE_VSYNC
        );
        ListPreference rendererListPreference = requirePreference(
            "renderer",
            ListPreference.class
        );
        Tools.RenderersList renderersList = Tools.getCompatibleRenderers(
            getContext()
        );
        // Copy rendererIds before appending the "download more" entry - the source list is
        // cached by Tools and reused elsewhere (Profile Editor, MainActivity), so it must not be
        // mutated here.
        List<String> entryValues = new ArrayList<>(renderersList.rendererIds);
        String[] entries = RendererListPreferenceDialogFragment.withDownloadEntry(
            renderersList.rendererDisplayNames, entryValues, requireContext()
        );
        rendererListPreference.setEntries(entries);
        rendererListPreference.setEntryValues(entryValues.toArray(new String[0]));
        // Selecting the "download more" entry should open the renderer plugin download page
        // instead of actually being saved as the renderer preference value.
        rendererListPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            if (RendererListPreferenceDialogFragment.DOWNLOAD_MORE_RENDERERS_VALUE.equals(newValue)) {
                Tools.openURL(requireActivity(), getString(R.string.renderer_plugin_download_url));
                return false;
            }
            return true;
        });

        computeVisibility();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String s) {
        super.onSharedPreferenceChanged(p, s);
        computeVisibility();
    }

    @Override
    public void onDisplayPreferenceDialog(@NonNull Preference preference) {
        // The renderer list needs a "Refresh" button to re-scan for renderer plugins, so it
        // gets a custom dialog instead of the default ListPreference one.
        if ("renderer".equals(preference.getKey())) {
            RendererListPreferenceDialogFragment fragment =
                    RendererListPreferenceDialogFragment.newInstance(preference.getKey());
            fragment.setTargetFragment(this, 0);
            fragment.show(getParentFragmentManager(), "androidx.preference.PreferenceFragment.DIALOG");
            return;
        }
        super.onDisplayPreferenceDialog(preference);
    }

    private void computeVisibility() {
        requirePreference("force_vsync", SwitchPreferenceCompat.class).setVisible(
            LauncherPreferences.PREF_USE_ALTERNATE_SURFACE
        );
        String currentRenderer = LauncherPreferences.DEFAULT_PREF.getString(
            "renderer",
            "opengles2"
        );
        boolean isMobileGluesRenderer = "opengles_mobileglues".equals(
            currentRenderer
        );
        requirePreference("renderer_settings", Preference.class).setVisible(
            isMobileGluesRenderer
        );
    }
}