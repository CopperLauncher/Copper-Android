package git.artdeell.mojo.fragments;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import git.artdeell.mojo.R;
import git.artdeell.mojo.utils.profiles.VersionSelectorDialog;

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

        SearchFilterDialog.Options options = new SearchFilterDialog.Options();
        options.showEngine = false;
        options.mcVersion = savedVersion;
        options.loader = savedLoader;
        options.subtitle = R.string.filter_dialog_subtitle_manage;
        SearchFilterDialog.show(context, options, (engine, newVersion, newLoader) -> {
            prefs.edit()
                    .putString(KEY_MC_VERSION + profileKey, newVersion)
                    .putString(KEY_LOADER      + profileKey, newLoader)
                    .apply();
            if (onApplied != null) onApplied.onApplied(newVersion, newLoader);
        });
    }
}
