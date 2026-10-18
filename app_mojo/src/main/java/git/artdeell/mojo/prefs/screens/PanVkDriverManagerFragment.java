package git.artdeell.mojo.prefs.screens;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import git.artdeell.mojo.R;
import git.artdeell.mojo.utils.PanVkDriverManager;

import java.io.File;
import java.util.List;

/**
 * Settings screen that lists the PanVK drivers and installs them from the
 * configured repository or from a local file.
 */
public class PanVkDriverManagerFragment extends LauncherPreferenceFragment {

    private final ActivityResultLauncher<String> mDriverImportLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> importDriver(uri, false));

    private final ActivityResultLauncher<String> mDriverPackageImportLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> importDriver(uri, true));

    private PanVkDriverManager mManager;

    @Override
    public void onCreatePreferences(Bundle b, String str) {
        addPreferencesFromResource(R.xml.pref_panvk);
        mManager = new PanVkDriverManager(requireContext());

        requirePreference("panvkInstallRepo").setOnPreferenceClickListener(preference -> {
            fetchRemoteDrivers();
            return true;
        });
        requirePreference("panvkInstallLocal").setOnPreferenceClickListener(preference -> {
            showImportDialog();
            return true;
        });
        requirePreference("panvkRepo").setOnPreferenceClickListener(preference -> {
            showRepoDialog();
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshInstallOptions();
        populateInstalledDrivers();
    }

    private void refreshInstallOptions() {
        int arch = mManager.detectMaliArchVersion();
        Preference repoInstall = requirePreference("panvkInstallRepo");
        repoInstall.setEnabled(arch != 0);
        repoInstall.setSummary(arch == 0
                ? getString(R.string.panvk_driver_manager_status_unsupported)
                : getString(R.string.panvk_driver_manager_install_github_summary, "v" + arch));
        requirePreference("panvkInstallLocal").setEnabled(arch != 0);
        requirePreference("panvkRepo").setSummary(PanVkDriverManager.getDriverRepo(requireContext()));
    }

    private void populateInstalledDrivers() {
        PreferenceCategory category = requirePreference(
                "panvkInstalledCategory", PreferenceCategory.class);
        category.removeAll();
        List<PanVkDriverManager.DriverEntry> drivers = mManager.listInstalledDrivers();
        if(drivers.isEmpty()) {
            Preference empty = new Preference(requireContext());
            empty.setTitle(R.string.panvk_driver_manager_none_installed);
            empty.setSelectable(false);
            category.addPreference(empty);
            return;
        }
        for(PanVkDriverManager.DriverEntry driver : drivers) {
            Preference preference = new Preference(requireContext());
            preference.setTitle(driver.title);
            preference.setSummary(driver.subtitle);
            preference.setOnPreferenceClickListener(item -> {
                showInstalledDriverDialog(driver);
                return true;
            });
            category.addPreference(preference);
        }
    }

    private void showInstalledDriverDialog(PanVkDriverManager.DriverEntry driver) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(driver.title)
                .setMessage(driver.subtitle)
                .setPositiveButton(R.string.panvk_driver_manager_uninstall, (dialog, which) -> {
                    if(mManager.uninstallDriver(driver.arch)) {
                        toastShort(R.string.panvk_driver_manager_uninstalled);
                    } else {
                        toastShort(R.string.panvk_driver_manager_uninstall_failed);
                    }
                    populateInstalledDrivers();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void fetchRemoteDrivers() {
        if(mManager.detectMaliArchVersion() == 0) {
            toastLong(R.string.panvk_driver_manager_status_unsupported);
            return;
        }
        toastShort(R.string.panvk_driver_manager_fetching);
        new Thread(() -> {
            try {
                List<PanVkDriverManager.DriverEntry> drivers = mManager.listRemoteDrivers();
                post(() -> showRemoteDriversDialog(drivers));
            } catch(Exception e) {
                post(() -> toastLong(getString(R.string.panvk_driver_manager_download_failed, e.getMessage())));
            }
        }).start();
    }

    private void showRemoteDriversDialog(List<PanVkDriverManager.DriverEntry> drivers) {
        if(drivers.isEmpty()) {
            toastLong(R.string.panvk_driver_manager_none_available);
            return;
        }
        String[] labels = new String[drivers.size()];
        for(int i = 0; i < drivers.size(); i++) {
            PanVkDriverManager.DriverEntry driver = drivers.get(i);
            labels[i] = driver.title + " - " + driver.subtitle;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.panvk_driver_manager_available_title)
                .setItems(labels, (dialog, which) -> installDriver(drivers.get(which)))
                .show();
    }

    private void installDriver(PanVkDriverManager.DriverEntry driver) {
        int arch = mManager.detectMaliArchVersion();
        if(arch == 0) {
            toastLong(R.string.panvk_driver_manager_status_unsupported);
            return;
        }
        mManager.installDriverFromUrl(arch, driver.url, new PanVkDriverManager.DriverReadyListener() {
            @Override public void onDriverReady(File driverFile) {
                toastLong(R.string.panvk_driver_manager_installed);
                populateInstalledDrivers();
            }
            @Override public void onDriverError(String error) {
                toastLong(getString(R.string.panvk_driver_manager_download_failed, error));
            }
        });
        toastShort(R.string.panvk_driver_manager_installing);
    }

    private void showImportDialog() {
        String[] options = new String[] {
                getString(R.string.panvk_driver_manager_import_package),
                getString(R.string.panvk_driver_manager_import_local)
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.panvk_driver_manager_install_local)
                .setItems(options, (dialog, which) -> {
                    if(which == 0) mDriverPackageImportLauncher.launch("*/*");
                    else mDriverImportLauncher.launch("*/*");
                })
                .show();
    }

    private void importDriver(Uri uri, boolean fromPackage) {
        if(uri == null) return;
        PanVkDriverManager.DriverReadyListener listener = new PanVkDriverManager.DriverReadyListener() {
            @Override public void onDriverReady(File driverFile) {
                toastLong(R.string.panvk_driver_manager_imported);
                populateInstalledDrivers();
            }
            @Override public void onDriverError(String error) {
                toastLong(getString(R.string.panvk_driver_manager_download_failed, error));
            }
        };
        if(fromPackage) mManager.importDriverPackage(uri, listener);
        else mManager.importDriverFromUri(uri, listener);
        toastShort(R.string.panvk_driver_manager_importing);
    }

    private void showRepoDialog() {
        EditText input = new EditText(requireContext());
        input.setText(PanVkDriverManager.getDriverRepo(requireContext()));
        input.setHint(R.string.panvk_driver_manager_repo_hint);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.panvk_driver_manager_set_repo)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    PanVkDriverManager.setDriverRepo(requireContext(), input.getText().toString());
                    toastShort(R.string.panvk_driver_manager_repo_saved);
                    requirePreference("panvkRepo").setSummary(
                            PanVkDriverManager.getDriverRepo(requireContext()));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toastShort(int res) {
        Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show();
    }

    private void toastLong(int res) {
        Toast.makeText(requireContext(), res, Toast.LENGTH_LONG).show();
    }

    private void toastShort(CharSequence text) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
    }

    private void toastLong(CharSequence text) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show();
    }

    private void post(Runnable action) {
        new Handler(Looper.getMainLooper()).post(action);
    }
}
