package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.profiles.ProfileAdapter;
import net.kdt.pojavlaunch.profiles.ProfileAdapterExtra;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;

/**
 * Shown in the right pane (landscape) when the user taps the instance spinner.
 * Selecting an instance saves it, reloads the spinner, and pops back to home.
 */
public class InstancePickerFragment extends Fragment {

    public static final String TAG = "InstancePickerFragment";

    public InstancePickerFragment() {
        super(R.layout.fragment_instance_picker);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ProfileAdapter adapter = new ProfileAdapter(new ProfileAdapterExtra[0]);
        ListView list = view.findViewById(R.id.instance_picker_list);
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, v, position, id) -> {
            Object item = adapter.getItem(position);
            if (!(item instanceof String)) return;

            String profileKey = (String) item;

            // Save selection
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                    .apply();

            // Tell the spinner to refresh — ExtraCore signals it on next resume
            ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, profileKey);

            // Pop back to home pane — parent is MainMenuFragment
            Fragment parent = getParentFragment();
            if (parent instanceof MainMenuFragment) {
                ((MainMenuFragment) parent).clearRightPane();
            }
        });
    }
}
