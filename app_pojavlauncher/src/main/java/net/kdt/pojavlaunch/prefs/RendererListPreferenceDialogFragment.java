package net.kdt.pojavlaunch.prefs;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.ListPreferenceDialogFragmentCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.plugins.RendererPlugin;

/**
 * A {@link ListPreferenceDialogFragmentCompat} for the "renderer" preference.
 * <p>
 * Adds a plain text "Refresh" button next to Cancel (no icon, styled the same way as the
 * dialog's built-in buttons) which re-scans installed renderer plugin APKs and reopens the
 * dialog with the updated list, without needing to leave this settings screen.
 */
public class RendererListPreferenceDialogFragment extends ListPreferenceDialogFragmentCompat {

    public static RendererListPreferenceDialogFragment newInstance(String key) {
        RendererListPreferenceDialogFragment fragment = new RendererListPreferenceDialogFragment();
        Bundle bundle = new Bundle(1);
        bundle.putString(ARG_KEY, key);
        fragment.setArguments(bundle);
        return fragment;
    }

    @Override
    protected void onPrepareDialogBuilder(@NonNull AlertDialog.Builder builder) {
        super.onPrepareDialogBuilder(builder);
        // Listener is attached after the dialog is shown (see onCreateDialog below) so that
        // clicking it doesn't just dismiss the dialog like a normal button would.
        builder.setNeutralButton(R.string.renderer_refresh_button, null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        if (dialog instanceof AlertDialog) {
            AlertDialog alertDialog = (AlertDialog) dialog;
            alertDialog.setOnShowListener(d -> {
                Button neutralButton = alertDialog.getButton(DialogInterface.BUTTON_NEUTRAL);
                if (neutralButton != null) {
                    neutralButton.setOnClickListener(v -> refreshRendererPlugins(neutralButton));
                }
            });
        }
        return dialog;
    }

    /** Explicitly re-scans installed renderer plugin APKs and reopens the dialog with the
     *  refreshed list. This is user-triggered on purpose: the underlying scan is a device-wide
     *  PackageManager query, too slow to run implicitly every time this dialog is opened or the
     *  app launches. */
    private void refreshRendererPlugins(@NonNull Button neutralButton) {
        neutralButton.setEnabled(false);
        neutralButton.setText(R.string.renderer_refreshing);

        RendererPlugin.discoverAsync(requireContext().getApplicationContext(), () -> {
            if (!isAdded()) return; // Dialog may have been closed while the scan was running

            Tools.releaseRenderersCache();
            Tools.RenderersList renderersList = Tools.getCompatibleRenderers(requireContext());

            ListPreference preference = (ListPreference) getPreference();
            preference.setEntries(renderersList.rendererDisplayNames);
            preference.setEntryValues(renderersList.rendererIds.toArray(new String[0]));

            Toast.makeText(requireContext(), R.string.pedit_renderer_plugins_checked, Toast.LENGTH_SHORT).show();

            // ListPreferenceDialogFragmentCompat snapshots its entries/entryValues once in
            // onCreate(), so the only reliable way to show the refreshed list is to reopen the
            // dialog with a fresh fragment instance.
            String key = preference.getKey();
            dismiss();
            RendererListPreferenceDialogFragment refreshed = RendererListPreferenceDialogFragment.newInstance(key);
            refreshed.setTargetFragment(getTargetFragment(), 0);
            refreshed.show(getParentFragmentManager(), "androidx.preference.PreferenceFragment.DIALOG");
        });
    }
}
