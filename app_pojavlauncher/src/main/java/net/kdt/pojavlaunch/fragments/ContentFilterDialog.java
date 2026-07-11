package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.profiles.VersionSelectorDialog;

/**
 * The per-instance Minecraft-version/loader filter used to drive update
 * checking in "Manage Content" (mods, resource packs, and shader packs all
 * share this one filter). Originally a button inside Manage Mods; now lives
 * on the "Manage Content" picker sheet since it applies across all three
 * content types rather than just mods.
 */
public final class ContentFilterDialog {

    private static final String PREF_FILE      = "mod_filters";
    private static final String KEY_MC_VERSION = "mc_version_";
    private static final String KEY_LOADER     = "loader_";

    private ContentFilterDialog() {}

    public interface OnApplied {
        void onApplied(String version, String loader);
    }

    /** Whether a version or loader filter is currently set for this profile —
     *  used to tint the filter icon so it's obvious a filter is active. */
    public static boolean isActive(Context context, String profileKey) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        return !prefs.getString(KEY_MC_VERSION + profileKey, "").isEmpty()
                || !prefs.getString(KEY_LOADER + profileKey, "").isEmpty();
    }

    public static void show(Context context, String profileKey, @Nullable OnApplied onApplied) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        String storedVersion = prefs.getString(KEY_MC_VERSION + profileKey, "");
        String storedLoader  = prefs.getString(KEY_LOADER      + profileKey, "");

        // Nothing set yet for this instance — default to whatever version/loader
        // the instance itself is actually running, rather than showing blank.
        final String savedVersion;
        final String savedLoader;
        if (storedVersion.isEmpty() && storedLoader.isEmpty()) {
            InstanceVersionResolver.Info info = InstanceVersionResolver.resolve(profileKey);
            savedVersion = info.mcVersion != null ? info.mcVersion : "";
            savedLoader  = info.loader;
        } else {
            savedVersion = storedVersion;
            savedLoader  = storedLoader;
        }

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(R.layout.dialog_mod_filters)
                .create();

        dialog.setOnShowListener(di -> {
            TextView versionText   = dialog.findViewById(R.id.search_mod_selected_mc_version_textview);
            Button   selectVersion = dialog.findViewById(R.id.search_mod_mc_version_button);
            Button   applyButton   = dialog.findViewById(R.id.search_mod_apply_filters);
            Spinner  loaderSpinner = dialog.findViewById(R.id.search_mod_loader_spinner);

            // This dialog is shared with the mod/modpack search screens, which also
            // show a Modrinth/CurseForge/Both engine picker — not applicable here,
            // since this just filters already-installed content by version/loader
            // for update checking, it doesn't pick a search engine.
            View engineLabel   = dialog.findViewById(R.id.search_mod_engine_textview);
            View engineSpinner = dialog.findViewById(R.id.search_mod_engine_spinner);
            if (engineLabel != null) engineLabel.setVisibility(View.GONE);
            if (engineSpinner != null) engineSpinner.setVisibility(View.GONE);

            if (versionText == null || selectVersion == null || applyButton == null) return;

            versionText.setText(savedVersion);

            final String[] loaderValues = { "", "fabric", "forge", "quilt", "neoforge" };
            if (loaderSpinner != null) {
                String[] loaderLabels = {
                        context.getString(R.string.search_mod_any_loader),
                        "Fabric", "Forge", "Quilt", "NeoForge"
                };
                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                        context, android.R.layout.simple_spinner_item, loaderLabels);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                loaderSpinner.setAdapter(adapter);
                for (int i = 0; i < loaderValues.length; i++) {
                    if (loaderValues[i].equals(savedLoader)) {
                        loaderSpinner.setSelection(i);
                        break;
                    }
                }
            }

            selectVersion.setOnClickListener(v ->
                    VersionSelectorDialog.open(v.getContext(), true,
                            (id, snapshot) -> versionText.setText(id)));

            applyButton.setOnClickListener(v -> {
                String newVersion = versionText.getText().toString().trim();
                String newLoader  = (loaderSpinner != null)
                        ? loaderValues[loaderSpinner.getSelectedItemPosition()]
                        : "";

                prefs.edit()
                        .putString(KEY_MC_VERSION + profileKey, newVersion)
                        .putString(KEY_LOADER      + profileKey, newLoader)
                        .apply();

                if (onApplied != null) onApplied.onApplied(newVersion, newLoader);
                di.dismiss();
            });
        });

        dialog.show();
    }
}
