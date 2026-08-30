package net.kdt.pojavlaunch;

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static net.kdt.pojavlaunch.Tools.getMods;
import static net.kdt.pojavlaunch.Tools.hasMods;
import static net.kdt.pojavlaunch.Tools.hasNoOnlineProfileDialog;
import static net.kdt.pojavlaunch.Tools.isOnline;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;
import androidx.fragment.app.FragmentManager;

import com.kdt.mcgui.ProgressLayout;
import com.kdt.mcgui.mcAccountSpinner;

import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.extra.ExtraListener;
import net.kdt.pojavlaunch.fragments.MainMenuFragment;
import net.kdt.pojavlaunch.fragments.MicrosoftLoginFragment;
import net.kdt.pojavlaunch.fragments.SelectAuthFragment;
import net.kdt.pojavlaunch.fragments.SettingsHostFragment;
import net.kdt.pojavlaunch.lifecycle.ContextAwareDoneListener;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.modloaders.LWJGL3ifyUtils;
import net.kdt.pojavlaunch.modloaders.modpacks.ModloaderInstallTracker;
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModLoader;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.api.NotificationDownloadListener;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.IconCacheJanitor;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.prefs.screens.LauncherPreferenceFragment;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;
import net.kdt.pojavlaunch.services.ProgressServiceKeeper;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.tasks.AsyncVersionList;
import net.kdt.pojavlaunch.tasks.MinecraftDownloader;
import net.kdt.pojavlaunch.utils.DateUtils;
import net.kdt.pojavlaunch.utils.NotificationUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.List;
import java.util.Locale;

public class LauncherActivity extends BaseActivity
        implements androidx.preference.PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    public static final String SETTING_FRAGMENT_TAG = "SETTINGS_FRAGMENT";

    /**
     * Portrait fallback for preference sub-screen navigation (e.g. tapping "Video" in
     * pref_main). In landscape, {@link SettingsHostFragment} handles this itself since it's
     * LauncherPreferenceFragment's direct parent fragment and androidx.preference checks
     * that first; in portrait, LauncherPreferenceFragment has no parent fragment (it's added
     * straight to the activity's FragmentManager), so androidx.preference falls back to the
     * hosting Activity, which is here.
     */
    @Override
    public boolean onPreferenceStartFragment(@NonNull androidx.preference.PreferenceFragmentCompat caller,
                                              @NonNull androidx.preference.Preference pref) {
        String fragmentClassName = pref.getFragment();
        if (fragmentClassName == null) return false;
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Fragment> fragmentClass =
                    (Class<? extends Fragment>) Class.forName(fragmentClassName);
            Tools.swapFragment(this, fragmentClass, fragmentClassName, pref.getExtras());
            return true;
        } catch (ClassNotFoundException e) {
            Log.e("LauncherActivity", "Preference fragment class not found: " + fragmentClassName, e);
            return false;
        }
    }

    public final ActivityResultLauncher<Object> modInstallerLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("jar"), (data)->{
                if(data != null) Tools.launchModInstaller(this, data);
            });
    public final ActivityResultLauncher<Object> modpackImportLauncher =
            registerForActivityResult(new OpenDocumentWithExtension(new String[]{"zip", "mrpack"}), (data)->{
                if(data != null) {
                    PojavApplication.sExecutorService.execute(() -> {
                        try {
                            // Copy ZIP file to cache
                            long fileSize = -1;
                            try (Cursor returnCursor = getContentResolver().query(data, new String[]{OpenableColumns.SIZE}, null, null, null)) {
                                if (returnCursor != null && returnCursor.moveToFirst()) {
                                    fileSize = returnCursor.getLong(0);
                                }
                            }
                            File modpackFile = new File(Tools.DIR_CACHE, "import_modpack_placeholdername.cf");
                            long readTotal = 0;
                            try (InputStream inputStream = getContentResolver().openInputStream(data);
                                 FileOutputStream output = new FileOutputStream(modpackFile)) {
                                byte[] b = new byte[262144];
                                int read;
                                while ((read = inputStream.read(b)) != -1) {
                                    output.write(b, 0, read);
                                    readTotal += read;
                                    String readMB = fileSize > 0 ? String.format(Locale.US, "%.2f", readTotal / (1024.0 * 1024.0)) : "unknown";
                                    String totalMB = fileSize > 0 ? String.format(Locale.US, "%.2f", fileSize / (1024.0 * 1024.0)) : "unknown";
                                    int progress = fileSize > 0 ? (int) ((readTotal * 100L) / fileSize) : 0;
                                    ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, progress, R.string.import_modpack_copy, readMB, totalMB);
                                }
                                output.flush();
                            }
                            // Some content providers (notably cloud-backed ones) can end the
                            // stream early on a hiccup without throwing, silently truncating the
                            // copy. Catch that here instead of letting it surface later as a
                            // confusing EOFException deep inside zip extraction.
                            if (fileSize > 0 && readTotal != fileSize) {
                                modpackFile.delete();
                                throw new IOException("Modpack file copy was incomplete (got "
                                        + readTotal + " of " + fileSize + " bytes) - try importing again");
                            }
                            ModLoader loaderInfo = new CommonApi(
                                    net.kdt.pojavlaunch.prefs.LauncherPreferences.resolveCurseforgeApiKey(this))
                                    .importModpack(modpackFile);
                            modpackFile.delete();
                            if (loaderInfo == null) return;
                            loaderInfo.getDownloadTask(new NotificationDownloadListener(this, loaderInfo)).run();
                        } catch (IOException e) {
                            Tools.showErrorRemote(this, R.string.modpack_install_download_failed, e);
                            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                        } catch (IllegalArgumentException e) {
                            Tools.showError(this, R.string.not_modpack_file, e);
                            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                        } catch (NoSuchAlgorithmException e) {
                            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                            // Should literally never happen because SHA-1 is required Java spec
                            throw new RuntimeException(e);
                        }
                    });
                }
            });

    private mcAccountSpinner mAccountSpinner;
    private FragmentContainerView mFragmentView;
    private ImageButton mSettingsButton;
    private ProgressLayout mProgressLayout;
    private ProgressServiceKeeper mProgressServiceKeeper;
    private ModloaderInstallTracker mInstallTracker;
    private NotificationManager mNotificationManager;

    /* Allows to switch from one button "type" to another */
    private final FragmentManager.FragmentLifecycleCallbacks mFragmentCallbackListener = new FragmentManager.FragmentLifecycleCallbacks() {
        @Override
        public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment f) {
            mSettingsButton.setImageDrawable(ContextCompat.getDrawable(getBaseContext(), f instanceof MainMenuFragment
                    ? R.drawable.ic_px_sliders : R.drawable.ic_px_home));
        }
    };

    /* Listener for the back button in settings */
    private final ExtraListener<String> mBackPreferenceListener = (key, value) -> {
        if(value.equals("true")) onBackPressed();
        return false;
    };

    /* Listener for the auth method selection screen */
    private final ExtraListener<Boolean> mSelectAuthMethod = (key, value) -> {
        Fragment fragment = getSupportFragmentManager().findFragmentById(mFragmentView.getId());
        // Allow starting the add account only from the main menu, should it be moved to fragment itself ?
        if(!(fragment instanceof MainMenuFragment)) return false;

        // In landscape two-pane mode, load into right pane; otherwise full-screen swap
        MainMenuFragment mmf = (MainMenuFragment) fragment;
        if (!mmf.tryOpenInRightPane(SelectAuthFragment.class, SelectAuthFragment.TAG, null)) {
            Tools.swapFragment(this, SelectAuthFragment.class, SelectAuthFragment.TAG, null);
        }
        return false;
    };

    /* Listener for the settings fragment */
    private final View.OnClickListener mSettingButtonListener = v -> {
        Fragment fragment = getSupportFragmentManager().findFragmentById(mFragmentView.getId());
        boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        if (fragment instanceof MainMenuFragment) {
            MainMenuFragment mmf = (MainMenuFragment) fragment;
            // In two-pane landscape: if a pane already has content (e.g. the content
            // picker), pressing the gear/home button pops back to home first, same as
            // before. Only opens Settings once the main menu is already at home.
            if (mmf.isRightPaneActive()) {
                mmf.clearRightPane();
            } else if (isLandscape) {
                // Landscape: settings gets its own two-pane screen (pref_main on the
                // left, empty right pane) instead of borrowing the main menu's right pane.
                Tools.swapFragment(this, SettingsHostFragment.class, SETTING_FRAGMENT_TAG, null);
            } else {
                Tools.swapFragment(this, LauncherPreferenceFragment.class, SETTING_FRAGMENT_TAG, null);
            }
        } else if (fragment instanceof SettingsHostFragment) {
            // Already in settings (landscape): gear/home button pops the right pane's
            // sub-screen back to empty, or leaves settings entirely if already empty.
            SettingsHostFragment settingsHostFragment = (SettingsHostFragment) fragment;
            if (settingsHostFragment.isRightPaneActive()) {
                settingsHostFragment.clearRightPane();
            } else {
                Tools.backToMainMenu(this);
            }
        } else if (fragment instanceof LauncherPreferenceFragment
                && !fragment.getClass().equals(LauncherPreferenceFragment.class)) {
            // Portrait settings sub-screen (Video/Controls/Java/Misc/Appearance/
            // Experimental/Renderer): step back to the settings list instead of
            // jumping straight past it to the main menu.
            Tools.removeCurrentFragment(this);
        } else {
            // Portrait (or any other fullscreen sub-fragment, including the settings
            // list itself): settings button doubles as a home button when not on main menu
            Tools.backToMainMenu(this);
        }
    };

    private final ExtraListener<Boolean> mLaunchGameListener = (key, value) -> {
        if(ProgressLayout.hasProcesses()){
            Toast.makeText(this, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return false;
        }

        String selectedProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE,"");
        if (LauncherProfiles.mainProfileJson == null || !LauncherProfiles.mainProfileJson.profiles.containsKey(selectedProfile)){
            Toast.makeText(this, R.string.error_no_version, Toast.LENGTH_LONG).show();
            return false;
        }
        MinecraftProfile prof = LauncherProfiles.mainProfileJson.profiles.get(selectedProfile);
        if (prof == null || prof.lastVersionId == null || "Unknown".equals(prof.lastVersionId)){
            Toast.makeText(this, R.string.error_no_version, Toast.LENGTH_LONG).show();
            return false;
        }

        if(mAccountSpinner.getSelectedAccount() == null){
            Toast.makeText(this, R.string.no_saved_accounts, Toast.LENGTH_LONG).show();
            ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
            return false;
        }

        // Override whatever version is in use and replace it with lwjgl3ify if needed
        List<File> lwjgl3ifyJars = getMods("lwjgl3ify-3");
        if (!lwjgl3ifyJars.isEmpty()) {
            if (lwjgl3ifyJars.size() > 1) {
                // "Duplicate LWJGL3ify jars found, cannot launch."
                Tools.dialogOnUiThread(this, R.string.global_error, R.string.mc_download_failed);
                return false;
            }

            File lwjgl3ifyJar = lwjgl3ifyJars.get(0);

            // If the version contains lwjgl3ify, its probably someone who knows what they're doing
            // so lets leave that alone
            if (!prof.lastVersionId.toLowerCase().contains("lwjgl3ify")) {
                try {
                    prof.lastVersionId = LWJGL3ifyUtils.installJson(lwjgl3ifyJar).id;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                LauncherProfiles.mainProfileJson.profiles.put(selectedProfile, prof);
                LauncherProfiles.write();


            }
            // We just installed a json, we need internet + online acc to download so we add super
            // basic detection whether lwjgl3ify assets were downloaded
            try {
                String jsonPath = LWJGL3ifyUtils.getJsonPath(LWJGL3ifyUtils.getProfileID(lwjgl3ifyJar));
                File lwjgl3ifyClientJar = new File(jsonPath.replace(".json", ".jar"));
                if (!lwjgl3ifyClientJar.exists()){
                    if (mAccountSpinner.getSelectedAccount().isLocal() || !isOnline(this)){
                        Tools.dialogOnUiThread(this, R.string.global_error, R.string.mc_download_failed);
                        return false;
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        String normalizedVersionId = AsyncMinecraftDownloader.normalizeVersionId(prof.lastVersionId);
        JMinecraftVersionList.Version mcVersion = AsyncMinecraftDownloader.getListedVersion(normalizedVersionId);

        // Do not load when is a modded version or older than minecraft 1.3 on demo account
        if (mAccountSpinner.getSelectedAccount().isDemo()) {
            boolean isOlderThan13 = true;

            if (mcVersion != null) {
                try {
                    isOlderThan13 = DateUtils.dateBefore(DateUtils.parseReleaseDate(mcVersion.releaseTime), 2012, 6, 22);
                } catch (ParseException ignored) {}
            }

            if (isOlderThan13) {
                hasNoOnlineProfileDialog(this, getString(R.string.global_error), getString(R.string.demo_versions_supported));
                return false;
            }
        }

        new MinecraftDownloader().start(
                this,
                mcVersion,
                normalizedVersionId,
                new ContextAwareDoneListener(this, normalizedVersionId)
        );
        return false;
    };

    private final TaskCountListener mDoubleLaunchPreventionListener = taskCount -> {
        // Hide the notification that starts the game if there are tasks executing.
        // Prevents the user from trying to launch the game with tasks ongoing.
        if(taskCount > 0) {
            Tools.runOnUiThread(() ->
                    mNotificationManager.cancel(NotificationUtils.NOTIFICATION_ID_GAME_START)
            );
        }
    };

    private ActivityResultLauncher<String> mRequestNotificationPermissionLauncher;
    private ActivityResultLauncher<String> mRequestMicrophonePermissionLauncher;
    private WeakReference<Runnable> mRequestNotificationPermissionRunnable;
    private WeakReference<Runnable> mRequestMicrophonePermissionRunnable;

    @Override
    protected boolean shouldIgnoreNotch() {
        return true;
    }

    @Override
    public boolean setFullscreen() {
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Apply saved colour theme before layout inflation
        setTheme(net.kdt.pojavlaunch.theme.ThemeManager.getSavedTheme());
        // Apply force-landscape preference before layout inflation
        if (LauncherPreferences.DEFAULT_PREF.getBoolean("force_landscape", false)) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
        setContentView(R.layout.activity_pojav_launcher);
        FragmentManager fragmentManager = getSupportFragmentManager();
        // If we don't have a back stack root yet...
        if(fragmentManager.getBackStackEntryCount() < 1) {
            // Manually add the first fragment to the backstack to get easily back to it
            // There must be a better way to handle the root though...
            // (artDev: No, there is not. I've spent days researching this for another unrelated project.)
            fragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addToBackStack("ROOT")
                    .add(R.id.container_fragment, MainMenuFragment.class, null, "ROOT").commit();
        }


        IconCacheJanitor.runJanitor();
        mRequestNotificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isAllowed -> {
                    if(!isAllowed) handleNoNotificationPermission();
                    else {
                        Runnable runnable = Tools.getWeakReference(mRequestNotificationPermissionRunnable);
                        if(runnable != null) runnable.run();
                    }
                }
        );
        mRequestMicrophonePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isAllowed -> {
                    if(!isAllowed) handleNoNotificationPermission();
                    else {
                        Runnable runnable = Tools.getWeakReference(mRequestMicrophonePermissionRunnable);
                        if(runnable != null) runnable.run();
                    }
                }
        );
        getWindow().setBackgroundDrawable(null);
        bindViews();
        checkNotificationPermission();
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ProgressKeeper.addTaskCountListener(mDoubleLaunchPreventionListener);
        ProgressKeeper.addTaskCountListener((mProgressServiceKeeper = new ProgressServiceKeeper(this)));

        mSettingsButton.setOnClickListener(mSettingButtonListener);
        ProgressKeeper.addTaskCountListener(mProgressLayout);
        ExtraCore.addExtraListener(ExtraConstants.BACK_PREFERENCE, mBackPreferenceListener);
        ExtraCore.addExtraListener(ExtraConstants.SELECT_AUTH_METHOD, mSelectAuthMethod);

        ExtraCore.addExtraListener(ExtraConstants.LAUNCH_GAME, mLaunchGameListener);

        new AsyncVersionList().getVersionList(versions -> ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, versions), false);

        mInstallTracker = new ModloaderInstallTracker(this);

        mProgressLayout.observe(ProgressLayout.DOWNLOAD_MINECRAFT);
        mProgressLayout.observe(ProgressLayout.UNPACK_RUNTIME);
        mProgressLayout.observe(ProgressLayout.INSTALL_MODPACK);
        mProgressLayout.observe(ProgressLayout.AUTHENTICATE_MICROSOFT);
        mProgressLayout.observe(ProgressLayout.DOWNLOAD_VERSION_LIST);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ContextExecutor.setActivity(this);
        mInstallTracker.attach();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ContextExecutor.clearActivity();
        mInstallTracker.detach();
    }

    @Override
    protected void onStart() {
        super.onStart();
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(mFragmentCallbackListener, true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mProgressLayout.cleanUpObservers();
        ProgressKeeper.removeTaskCountListener(mProgressLayout);
        ProgressKeeper.removeTaskCountListener(mProgressServiceKeeper);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.BACK_PREFERENCE, mBackPreferenceListener);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.SELECT_AUTH_METHOD, mSelectAuthMethod);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.LAUNCH_GAME, mLaunchGameListener);

        getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(mFragmentCallbackListener);
    }

    /** Custom implementation to feel more natural when a backstack isn't present */
    @Override
    public void onBackPressed() {
        MicrosoftLoginFragment fragment = (MicrosoftLoginFragment) getVisibleFragment(MicrosoftLoginFragment.TAG);
        if(fragment != null){
            if(fragment.canGoBack()){
                fragment.goBack();
                return;
            }
        }

        // Inside the landscape settings screen: pop its right pane first (back to the
        // empty state), and only then fall through to leave the settings screen entirely.
        Fragment topFragment = getSupportFragmentManager().findFragmentById(mFragmentView.getId());
        if (topFragment instanceof SettingsHostFragment) {
            SettingsHostFragment settingsHostFragment = (SettingsHostFragment) topFragment;
            if (settingsHostFragment.isRightPaneActive()) {
                settingsHostFragment.popRightPane();
                return;
            }
            // Falls through to the default back-stack pop below, which reveals
            // MainMenuFragment/ROOT again.
        }

        // In landscape two-pane mode: if the right pane has content, pop it instead of exiting
        Fragment rootFrag = getVisibleFragment("ROOT");
        if (rootFrag instanceof MainMenuFragment) {
            MainMenuFragment mmf = (MainMenuFragment) rootFrag;
            if (mmf.isRightPaneActive()) {
                mmf.popRightPane();
                return;
            }
            finish();
            return;
        }

        // Check if we are at the root then
        if(getVisibleFragment("ROOT") != null){
            finish();
        }

        super.onBackPressed();
    }

    @Override
    public void onAttachedToWindow() {
        LauncherPreferences.computeNotchSize(this);
    }

    @SuppressWarnings("SameParameterValue")
    private Fragment getVisibleFragment(String tag){
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(tag);
        if(fragment != null && fragment.isVisible()) {
            return fragment;
        }
        return null;
    }

    @SuppressWarnings("unused")
    private Fragment getVisibleFragment(int id){
        Fragment fragment = getSupportFragmentManager().findFragmentById(id);
        if(fragment != null && fragment.isVisible()) {
            return fragment;
        }
        return null;
    }

    private void checkNotificationPermission() {
        if(LauncherPreferences.PREF_SKIP_NOTIFICATION_PERMISSION_CHECK ||
            checkForNotificationPermission()) {
            return;
        }

        if(ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.POST_NOTIFICATIONS)) {
            showNotificationPermissionReasoning();
            return;
        }
        askForNotificationPermission(null);
    }

    private void showNotificationPermissionReasoning() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.notification_permission_dialog_title)
                .setMessage(R.string.notification_permission_dialog_text)
                .setPositiveButton(android.R.string.ok, (d, w) -> askForNotificationPermission(null))
                .setNegativeButton(android.R.string.cancel, (d, w)-> handleNoNotificationPermission())
                .show();
    }

    private void handleNoNotificationPermission() {
        LauncherPreferences.PREF_SKIP_NOTIFICATION_PERMISSION_CHECK = true;
        LauncherPreferences.DEFAULT_PREF.edit()
                .putBoolean(LauncherPreferences.PREF_KEY_SKIP_NOTIFICATION_CHECK, true)
                .apply();
        Toast.makeText(this, R.string.notification_permission_toast, Toast.LENGTH_LONG).show();
    }

    public boolean checkForNotificationPermission() {
        return Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_DENIED;
    }
    public boolean checkForMicrophonePermission() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_DENIED;
    }

    public void askForNotificationPermission(Runnable onSuccessRunnable) {
        if(Build.VERSION.SDK_INT < 33) return;
        if(onSuccessRunnable != null) {
            mRequestNotificationPermissionRunnable = new WeakReference<>(onSuccessRunnable);
        }
        mRequestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    public void askForMicrophonePermission(Runnable onSuccessRunnable) {
        if(onSuccessRunnable != null) {
            mRequestMicrophonePermissionRunnable = new WeakReference<>(onSuccessRunnable);
        }
        mRequestMicrophonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
    }

    /** Stuff all the view boilerplate here */
    private void bindViews(){
        mFragmentView = findViewById(R.id.container_fragment);
        mSettingsButton = findViewById(R.id.setting_button);
        mAccountSpinner = findViewById(R.id.account_spinner);
        mProgressLayout = findViewById(R.id.progress_layout);
    }
}
