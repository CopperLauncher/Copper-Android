package net.kdt.pojavlaunch.prefs.screens;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.fragments.MainMenuFragment;
import net.kdt.pojavlaunch.fragments.RightPaneHomeFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

public class LauncherPreferenceExperimentalFragment extends LauncherPreferenceFragment {

    /** Picks an image from the gallery; result handled in mImagePickerLauncher. */
    private final ActivityResultLauncher<String> mImagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) copyImageToBgFile(uri);
            });

    @Override
    public void onCreatePreferences(Bundle b, String str) {
        addPreferencesFromResource(R.xml.pref_experimental);
        setupForceLandscape();
        setupCustomBackground();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Force landscape
    // ────────────────────────────────────────────────────────────────────────────

    private void setupForceLandscape() {
        SwitchPreferenceCompat pref = requirePreference("force_landscape", SwitchPreferenceCompat.class);
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean force = Boolean.TRUE.equals(newValue);
            Activity activity = requireActivity();
            activity.setRequestedOrientation(
                    force ? SCREEN_ORIENTATION_SENSOR_LANDSCAPE : SCREEN_ORIENTATION_UNSPECIFIED);
            return true; // allow the switch to actually flip
        });
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Custom background
    // ────────────────────────────────────────────────────────────────────────────

    private void setupCustomBackground() {
        Preference setPref    = requirePreference("set_custom_launcher_bg");
        Preference removePref = requirePreference("remove_custom_launcher_bg");

        setPref.setOnPreferenceClickListener(preference -> {
            mImagePickerLauncher.launch("image/*");
            return true;
        });

        removePref.setOnPreferenceClickListener(preference -> {
            File bgFile = new File(RightPaneHomeFragment.CUSTOM_BG_PATH);
            if (bgFile.exists()) bgFile.delete();
            notifyHomeFragmentBgChanged();
            Toast.makeText(requireContext(),
                    R.string.preference_custom_bg_removed, Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    /** Copies the user-picked URI into the custom background file slot. */
    private void copyImageToBgFile(@NonNull Uri uri) {
        // Quick sanity check: make sure the drawable can be decoded before committing.
        Drawable test = Drawable.createFromPath(uri.getPath());
        // createFromPath may return null for content:// URIs — that's fine, we
        // just validate after writing by trying to open an InputStream.
        File bgFile = new File(RightPaneHomeFragment.CUSTOM_BG_PATH);
        try (InputStream in  = requireContext().getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(bgFile)) {
            if (in == null) throw new Exception("Cannot open URI");
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            notifyHomeFragmentBgChanged();
            Toast.makeText(requireContext(),
                    R.string.preference_custom_bg_set_success, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            // Delete partial file if write failed
            if (bgFile.exists()) bgFile.delete();
            Toast.makeText(requireContext(),
                    R.string.preference_custom_bg_error, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * If MainMenuFragment (and thus RightPaneHomeFragment) is currently in the
     * back stack, tell it to reload the background immediately so the user sees
     * the change without restarting the app.
     */
    private void notifyHomeFragmentBgChanged() {
        MainMenuFragment mmf = (MainMenuFragment) requireActivity()
                .getSupportFragmentManager()
                .findFragmentByTag("ROOT");
        if (mmf == null) return;
        RightPaneHomeFragment home = (RightPaneHomeFragment) mmf
                .getChildFragmentManager()
                .findFragmentByTag(RightPaneHomeFragment.TAG);
        if (home != null) home.reloadBackground();
    }
}