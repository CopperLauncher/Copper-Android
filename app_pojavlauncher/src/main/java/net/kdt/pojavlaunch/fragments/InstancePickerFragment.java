package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.profiles.ProfileIconCache;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;
import net.kdt.pojavlaunch.Tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shown in the right pane (landscape) when the user taps the instance spinner.
 * Styled like the mod search card list. Tap an item to select it and go home.
 */
public class InstancePickerFragment extends Fragment {

    public static final String TAG = "InstancePickerFragment";

    public InstancePickerFragment() {
        super(R.layout.fragment_instance_picker);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        RecyclerView recycler = view.findViewById(R.id.instance_picker_recycler);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));

        LauncherProfiles.load();
        Map<String, MinecraftProfile> profiles = LauncherProfiles.mainProfileJson.profiles;
        List<String> keys = new ArrayList<>(profiles.keySet());
        String selected = LauncherPreferences.DEFAULT_PREF
                .getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");

        recycler.setAdapter(new InstanceAdapter(keys, profiles, selected, profileKey -> {
            // Delegate to MainMenuFragment which owns the spinner + clearRightPane
            Fragment parentFrag = getParentFragment();
            if (parentFrag instanceof MainMenuFragment) {
                ((MainMenuFragment) parentFrag).selectInstance(profileKey);
            }
        }));
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    interface OnInstanceSelected {
        void onSelected(String profileKey);
    }

    static class InstanceAdapter extends RecyclerView.Adapter<InstanceAdapter.VH> {

        private final List<String> mKeys;
        private final Map<String, MinecraftProfile> mProfiles;
        private String mSelectedKey;
        private final OnInstanceSelected mCallback;

        InstanceAdapter(List<String> keys, Map<String, MinecraftProfile> profiles,
                        String selectedKey, OnInstanceSelected callback) {
            mKeys        = keys;
            mProfiles    = profiles;
            mSelectedKey = selectedKey;
            mCallback    = callback;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_instance_card, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            String key     = mKeys.get(position);
            MinecraftProfile p = mProfiles.get(key);

            // Icon
            h.icon.setImageDrawable(
                    ProfileIconCache.fetchIcon(h.icon.getResources(), key,
                            p != null ? p.icon : null));

            // Name — fall back to key if blank or "New"
            String name = (p != null && Tools.isValidString(p.name)
                    && !"New".equalsIgnoreCase(p.name)) ? p.name : key;
            h.name.setText(name);

            // Version
            String ver = p != null ? p.lastVersionId : "";
            if (MinecraftProfile.LATEST_RELEASE.equalsIgnoreCase(ver))
                ver = h.itemView.getContext().getString(R.string.profiles_latest_release);
            else if (MinecraftProfile.LATEST_SNAPSHOT.equalsIgnoreCase(ver))
                ver = h.itemView.getContext().getString(R.string.profiles_latest_snapshot);
            h.version.setText(ver);

            // Selected badge
            h.badge.setVisibility(key.equals(mSelectedKey) ? View.VISIBLE : View.GONE);

            // Click
            h.itemView.setOnClickListener(v -> {
                String prev = mSelectedKey;
                mSelectedKey = key;
                notifyItemChanged(mKeys.indexOf(prev));
                notifyItemChanged(position);
                mCallback.onSelected(key);
            });
        }

        @Override
        public int getItemCount() { return mKeys.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final ImageView icon, badge;
            final TextView name, version;
            VH(@NonNull View v) {
                super(v);
                icon    = v.findViewById(R.id.instance_icon);
                badge   = v.findViewById(R.id.instance_selected_badge);
                name    = v.findViewById(R.id.instance_name);
                version = v.findViewById(R.id.instance_version);
            }
        }
    }
}