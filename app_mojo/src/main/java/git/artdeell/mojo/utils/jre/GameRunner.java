package git.artdeell.mojo.utils.jre;

import android.content.Context;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import git.artdeell.mojo.Architecture;
import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.Logger;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.authenticator.AuthType;
import git.artdeell.mojo.skins.LocalSkinServer;
import git.artdeell.mojo.game.renderer.def.Renderers;
import git.artdeell.mojo.game.renderer.impl.GLESRenderSpec;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.utils.DownloadUtils;
import git.artdeell.mojo.utils.lifecycle.LifecycleAwareAlertDialog;
import git.artdeell.mojo.multirt.MultiRTUtils;
import git.artdeell.mojo.multirt.Runtime;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.game.renderer.GameRenderer;
import git.artdeell.mojo.game.renderer.RenderSpec;
import git.artdeell.mojo.utils.DateUtils;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.GpuUtils;
import git.artdeell.mojo.utils.GameOptionsUtils;
import git.artdeell.mojo.utils.JREUtils;
import git.artdeell.mojo.utils.JSONUtils;
import git.artdeell.mojo.utils.MCOptionUtils;
import git.artdeell.mojo.utils.OldVersionsUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import git.artdeell.mojo.R;

public class GameRunner {
    private static final String FILEDIALOG_BRIDGE_ASSET = "components/filedialog_bridge/filedialog_bridge.jar";
    /**
     * Optimization mods based on Sodium can mitigate the render distance issue. Check if Sodium
     * or its derivative is currently installed to skip the render distance check.
     * @param gameDir current game directory
     * @return whether sodium or a sodium-based mod is installed
     */
    private static boolean hasSodium(File gameDir) {
        File modsDir = new File(gameDir, "mods");
        File[] mods = modsDir.listFiles(file -> file.isFile() && file.getName().endsWith(".jar"));
        if(mods == null) return false;
        for(File file : mods) {
            String name = file.getName();
            if(name.contains("sodium") ||
                    name.contains("embeddium") ||
                    name.contains("rubidium")) return true;
        }
        return false;
    }

    /**
     * Check if Angelica is currently installed to allow usage of LTW
     * @param gameDir current game directory
     * @return whether Angelica is installed
     */
    public static boolean hasAngelica(File gameDir) {
        File modsDir = new File(gameDir, "mods");
        File[] mods = modsDir.listFiles(file -> file.isFile() && file.getName().endsWith(".jar"));
        if(mods == null) return false;
        for(File file : mods) {
            String name = file.getName();
            if(name.contains("angelica")) return true;
        }
        return false;
    }

    /**
     * Initialize OpenGL and do checks to see if the GPU of the device is affected by the render
     * distance issue.

     * Currently only checks whether the user has an Adreno GPU capable of OpenGL ES 3.

     * This issue is caused by a very severe limit on the amount of GL buffer names that could be allocated
     * by the Adreno properietary GLES driver.

     * @return whether the GPU is affected by the Large Thin Wrapper render distance issue on vanilla
     */

    private static boolean affectedByRenderDistanceIssue(JVersionList.Version version) throws ParseException {
        if(LauncherPreferences.PREF_USE_ANGLE || LauncherPreferences.PREF_USE_SYSTEM_ANGLE) return false;
        GpuUtils.GLInfo info = GpuUtils.getGlInfo();
        return info.isAdreno() &&
                info.glesMajorVersion >= 3 &&
                // 1.21.5 fixes the RD issue, released on march 25 2025
                DateUtils.dateBefore(DateUtils.getOriginalReleaseDate(version), 2025, 2, 25);
    }

    private static boolean checkRenderDistance(JVersionList.Version version, File gamedir) throws ParseException {
        if(!affectedByRenderDistanceIssue(version)) return false;
        if(hasSodium(gamedir)) return false;
        try {
            MCOptionUtils.load();
        }catch (Exception e) {
            Log.e("Tools", "Failed to load config", e);
        }
        int renderDistance = GameOptionsUtils.parseIntDefault(MCOptionUtils.get("renderDistance"),12);
        // 7 is the render distance "magic number" above which MC creates too many buffers
        // for Adreno's OpenGL ES implementation
        return renderDistance > 7;
    }

    /**
     * Persist the per-instance graphics backend choice into options.txt.
     * MC 26.2+ reads preferredGraphicsBackend (default/opengl/vulkan) from there;
     * older versions ignore the key, but we still skip writing for them.
     */
    private static void applyGraphicsBackend(Instance instance, String versionId) {
        String backend = instance.getLaunchBackend();
        if(backend == null || "default".equals(backend)) return;
        if(!Tools.isMcVersionAtLeast(versionId, 1, 21)) return;
        try {
            MCOptionUtils.load(instance.getGameDirectory().getAbsolutePath());
            if(!backend.equals(MCOptionUtils.get("preferredGraphicsBackend"))) {
                MCOptionUtils.set("preferredGraphicsBackend", backend);
                MCOptionUtils.save();
            }
        }catch (Exception e) {
            Log.w("GameRunner", "Failed to apply graphics backend", e);
        }
    }

    private static boolean isGl4esCompatible(JVersionList.Version version) throws Exception{
        return DateUtils.dateBefore(DateUtils.getOriginalReleaseDate(version), 2025, 1, 7);
    }

    private static boolean isCompatContext(JVersionList.Version version) throws Exception{
        // Day before the release date of 21w10a, the first OpenGL 3 Core Minecraft version
        return DateUtils.dateBefore(DateUtils.getOriginalReleaseDate(version), 2021, 3, 9);
    }

    private static boolean showDialog(AppCompatActivity activity, int message) throws InterruptedException {
        LifecycleAwareAlertDialog.DialogCreator dialogCreator = ((alertDialog, dialogBuilder) ->
                dialogBuilder.setMessage(activity.getString(message))
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok, (d, w)->{}));
        return LifecycleAwareAlertDialog.haltOnDialog(activity.getLifecycle(), activity, dialogCreator);
    }

    // Autoswitch to provided renderer if supported, otherwise - crash with resId dialog message
    private static void switchRendererIfSupported(boolean support,
                                                  RenderSpec renderer,
                                                  GameRenderer gameRenderer,
                                                  Instance instance,
                                                  AppCompatActivity activity,
                                                  int resId) throws InterruptedException, IOException {
        if(support) {
            instance.renderer = renderer.tag();
            instance.write();
            gameRenderer.setCurrentRenderer(renderer);
        }else {
            showDialog(activity, resId);
            System.exit(0);
        }
    }

    public static void launchGame(final AppCompatActivity activity, Account account,
                                  Instance instance, String versionId, File[] classpath, GameRenderer gameRenderer) throws Throwable {
        if(!performAuthPing(activity, account)) account = account.toLocal();

        int freeDeviceMemory = Tools.getFreeDeviceMemory(activity);
        int localeString;
        int freeAddressSpace = Architecture.is32BitsDevice() ? Tools.getMaxContinuousAddressSpaceSize() : -1;
        Log.i("MemStat", "Free RAM: " + freeDeviceMemory + " Addressable: " + freeAddressSpace);
        boolean showAddressMemoryWarning = freeDeviceMemory > freeAddressSpace && freeAddressSpace != -1;
        if(showAddressMemoryWarning) {
            freeDeviceMemory = freeAddressSpace;
            localeString = R.string.address_memory_warning_msg;
        } else {
            localeString = R.string.memory_warning_msg;
        }

        if(LauncherPreferences.PREF_RAM_ALLOCATION > freeDeviceMemory && (showAddressMemoryWarning || LauncherPreferences.PREF_SHOW_MEMORY_WARNING_DIALOG)) {
            int finalDeviceMemory = freeDeviceMemory;
            LifecycleAwareAlertDialog.DialogCreator dialogCreator = (dialog, builder) -> {
                builder.setMessage(activity.getString(localeString, finalDeviceMemory, LauncherPreferences.PREF_RAM_ALLOCATION))
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                        });

                if (!showAddressMemoryWarning) {
                    builder.setNegativeButton(R.string.option_do_not_show_again, (d, w) -> {
                        LauncherPreferences.DEFAULT_PREF.edit().putBoolean("showMemoryWarning", false).apply();
                        Toast.makeText(activity, R.string.notification_permission_toast, Toast.LENGTH_SHORT).show();
                    });
                }
            };


                if (LifecycleAwareAlertDialog.haltOnDialog(activity.getLifecycle(), activity, dialogCreator)) {
                    return; // If the dialog's lifecycle has ended, return without
                    // actually launching the game, thus giving us the opportunity
                    // to start after the activity is shown again
                }
            }
        File gamedir = instance.getGameDirectory();
        JVersionList.Version versionInfo = Tools.getVersionInfo(versionId);
        // We don't need the library list, the asset index, client download info for the code below
        versionInfo.libraries = null;
        versionInfo.downloads = null;

        RenderSpec renderer = gameRenderer.getCurrentRenderer();

        if(!versionInfo.disableRendererChecks) {
            // Switch renderer to GL4ES when running a compat context version on LTW
            if(isCompatContext(versionInfo) && !hasAngelica(gamedir) && renderer instanceof GLESRenderSpec.LTWRenderSpec) {
                switchRendererIfSupported(true, GameRenderer.getKnownRenderer(Renderers.GL4ES_RENDERER), gameRenderer, instance, activity, 0);
            }

            boolean isGl4es = renderer instanceof GLESRenderSpec.GL4ESRenderSpec;
            RenderSpec ltw = GameRenderer.getKnownRenderer(Renderers.LTW_RENDERER);
            boolean ltwSupported = ltw != null && ltw.compatibleDevice(activity);
            // Block Sodium from running with GL4ES on 1.17+
            if(!isCompatContext(versionInfo) && isGl4es && hasSodium(gamedir)) {
                switchRendererIfSupported(ltwSupported, ltw, gameRenderer, instance, activity, R.string.compat_sodium_not_supported);
            }

            // Switch renderer to LTW when running 1.21.5
            if(!isGl4esCompatible(versionInfo) && isGl4es) {
                switchRendererIfSupported(ltwSupported, ltw, gameRenderer, instance, activity, R.string.compat_version_not_supported);
            }
        }

        boolean isLtw = renderer instanceof GLESRenderSpec.LTWRenderSpec;

        if(isLtw && checkRenderDistance(versionInfo, gamedir)) {
            if(showDialog(activity, R.string.ltw_render_distance_warning_msg)) return;
            // If the code goes here, it means that the user clicked "OK". Fix the render distance.
            try {
                MCOptionUtils.set("renderDistance", "7");
                MCOptionUtils.save();
            }catch (Exception e) {
                Log.e("Tools", "Failed to fix render distance setting", e);
            }
        }

        GameOptionsUtils.fixOptions(isLtw);

        applyGraphicsBackend(instance, versionId);

        if(isLtw && GpuUtils.getGlInfo().forcedMsaa) {
            if(showDialog(activity, R.string.ltw_4x_msaa_warning_msg)) return;
        }

        int requiredJavaVersion = 8;
        if(versionInfo.javaVersion != null) requiredJavaVersion = versionInfo.javaVersion.majorVersion;

        Runtime runtime = MultiRTUtils.forceReread(pickRuntime(instance, requiredJavaVersion));

        // Pre-process specific files
        disableSplash(gamedir);
        List<String> launchArgs = getMoJsonClientArgs(account, versionInfo, gamedir);

        // Select the appropriate openGL version
        OldVersionsUtils.selectOpenGlVersion(versionInfo);

        ArrayList<String> launchClassPath = new ArrayList<>(classpath.length + 1);
        classpath = patchFileDialogJars(activity, classpath);
        for(int i = 0; i < classpath.length; i++) {
            File classpathEntry = classpath[i];
            String entryPath = classpathEntry.getAbsolutePath();
            if(!classpathEntry.exists()) {
                Log.w("GameRunner", "Skipped classpath entry " + entryPath + " because it is missing");
            }
            launchClassPath.add(entryPath);
            // Unreference the classpath entry to avoid retaining it on heap
            classpath[i] = null;
        }
        launchClassPath.trimToSize();

        List<String> javaArgList = new ArrayList<>();

        if (versionInfo.logging != null && versionInfo.logging.client != null && versionInfo.logging.client.file != null) {
            String configFile = Tools.DIR_DATA + "/security/" + versionInfo.logging.client.file.id.replace("client", "log4j-rce-patch");
            if (!new File(configFile).exists()) {
                configFile = Tools.DIR_GAME_NEW + "/" + versionInfo.logging.client.file.id;
            }
            javaArgList.add("-Dlog4j.configurationFile=" + configFile);
        }

        versionInfo.logging = null;

        File versionSpecificNativesDir = new File(Tools.DIR_CACHE, "natives/"+versionId);
        String libraryPath = Tools.NATIVE_LIB_DIR;
        if(versionSpecificNativesDir.exists()) {
            String dirPath = versionSpecificNativesDir.getAbsolutePath();
            libraryPath = dirPath+":"+libraryPath;
            javaArgList.add("-Djna.boot.library.path="+dirPath);
            // Sometimes, the game can extract natives itself onto this path
            javaArgList.add("-Dorg.lwjgl.librarypath="+dirPath);
        }
        // On Java 8, System.loadLibrary("lwjgl_tinyfd") refuses to call the
        // NativeLibraries.load hook unless a file named liblwjgl_tinyfd.so is
        // actually present on java.library.path. Ship an empty placeholder there
        // so the existence check passes; our hook then redirects the real load
        // to libmojort.so, which provides the tinyfd JNI entry points.
        File tinyfdPlaceholder = new File(Tools.DIR_CACHE, "natives/liblwjgl_tinyfd.so");
        if (!tinyfdPlaceholder.exists()) {
            FileUtils.ensureDirectory(tinyfdPlaceholder.getParentFile());
            try {
                new FileOutputStream(tinyfdPlaceholder).close();
            } catch (IOException ignored) {}
        }
        libraryPath = tinyfdPlaceholder.getParent()+":"+libraryPath;

        // Launcher libraries (libmojort) live in the app native dir and must stay
        // reachable even when a version has no extracted natives directory.
        javaArgList.add("-Djava.library.path="+libraryPath);

        File lwjglExtractDir = new File(Tools.DIR_CACHE, "lwjgl_native/"+versionId);
        FileUtils.ensureDirectory(lwjglExtractDir);
        javaArgList.add("-Dorg.lwjgl.system.SharedLibraryExtractPath="+lwjglExtractDir.getAbsolutePath());

        addAuthlibInjectorArgs(javaArgList, account);
        addNoBlocklistArgs(javaArgList);

        mergeMoJsonArgs(javaArgList, getMoJsonJvmArgs(versionId));

        versionInfo.arguments = null;
        versionInfo.minecraftArguments = null;
        versionInfo.assets = null;
        versionInfo.assetIndex = null;

        javaArgList.addAll(JREUtils.parseJavaArguments(instance.getLaunchArgs()));

        Map<String, String> versionEnvMap = versionInfo.environment;

        // TODO: this should be decoupled from GameRunner completely
        gameRenderer.setupEnvironment(activity);
        JREUtils.setGameEnvironment(activity, versionEnvMap);
        JREUtils.chdir(instance.getGameDirectory().getAbsolutePath());

        if(!gameRenderer.maybeSetupRenderer()) {
            if(showDialog(activity, R.string.gr_err_renderer_load_Failed)) return;
            System.exit(0);
        }
        javaArgList.add("-Dorg.lwjgl.opengl.libname=libGLMojo.so");
        javaArgList.add("-Dorg.lwjgl.freetype.libname="+ Tools.NATIVE_LIB_DIR+"/libfreetype.so");

        activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.autoram_info_msg,LauncherPreferences.PREF_RAM_ALLOCATION), Toast.LENGTH_SHORT).show());

        Log.i("GameRunner", "Running with "+ launchArgs.toString());

        String mainClass = versionInfo.mainClass;

        try {
            JavaRunner.nativeSetupExit(activity);
            JavaRunner.startJvm(runtime, javaArgList, launchClassPath, mainClass, launchArgs);
        }catch (VMLoadException e) {
            LifecycleAwareAlertDialog.DialogCreator dialogCreator = (dialog, builder) ->
                builder.setMessage(e.toString(activity)).setPositiveButton(android.R.string.ok, (d, w)->{});

            if(LifecycleAwareAlertDialog.haltOnDialog(activity.getLifecycle(), activity, dialogCreator)) {
                return;
            }
        }

        Tools.restartLauncherActivity(activity);
        Tools.fullyExit();
    }

    /**
     * Rewrites the lwjgl-tinyfd and lwjgl-nfd jars on the game classpath so their
     * dialog binding classes come from the launcher bridge instead of the bundled
     * native glue libraries (which have no Android file picker). The lwjgl jars
     * stay on the classpath as themselves: MC 26.2+ extracts their native
     * libraries through its own bootstrap, and Forge resolves each package from
     * exactly one module, so a separate bridge jar would fail as a split package.
     */
    private static File[] patchFileDialogJars(AppCompatActivity activity, File[] classpath) {
        try {
            File bridgeDir = new File(Tools.DIR_CACHE, "filedialog_bridge");
            if (!bridgeDir.isDirectory() && !bridgeDir.mkdirs()) return classpath;
            File bridgeJar = new File(bridgeDir, "filedialog_bridge.jar");
            try (InputStream in = activity.getAssets().open(FILEDIALOG_BRIDGE_ASSET);
                 OutputStream out = new FileOutputStream(bridgeJar)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            Map<String, byte[]> bridgeClasses = readBridgeClasses(bridgeJar);
            File[] result = new File[classpath.length];
            boolean patched = false;
            boolean hasNfdJar = false;
            for (int i = 0; i < classpath.length; i++) {
                File entry = classpath[i];
                String name = entry.getName().toLowerCase();
                if (name.contains("tinyfd")) {
                    // tinyfd is served by the vendored native implementation; the
                    // original lwjgl-tinyfd classes stay untouched.
                    result[i] = entry;
                } else if (name.contains("-nfd")) {
                    hasNfdJar = true;
                    result[i] = patchDialogJar(bridgeDir, entry, bridgeClasses, "org/lwjgl/util/nfd/", DIALOG_BRIDGE_NFD_CLASSES);
                    patched |= result[i] != entry;
                } else {
                    result[i] = entry;
                }
            }
            if (!hasNfdJar) {
                File nfdJar = writeBridgePackageJar(bridgeDir, "filedialog_nfd.jar",
                        bridgeClasses, "org/lwjgl/util/nfd/");
                if (nfdJar != null) {
                    result = java.util.Arrays.copyOf(result, result.length + 1);
                    result[result.length - 1] = nfdJar;
                    patched = true;
                }
            }
            if (patched) {
                Log.i("GameRunner", "File dialog bridge injected into the game classpath");
            }
            return result;
        } catch (Throwable t) {
            Log.w("GameRunner", "File dialog bridge unavailable, file dialogs will stay inactive", t);
            return classpath;
        }
    }

    /** Bridge classes replacing the NFD binding: the dialog class and its path set. */
    private static final String[] DIALOG_BRIDGE_NFD_CLASSES = {
            "org/lwjgl/util/nfd/NativeFileDialog",
            "org/lwjgl/util/nfd/NFDPathSet",
    };

    private static Map<String, byte[]> readBridgeClasses(File bridgeJar) throws IOException {
        Map<String, byte[]> classes = new HashMap<>();
        try (ZipFile zip = new ZipFile(bridgeJar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                classes.put(entry.getName(), readZipEntry(zip, entry));
            }
        }
        return classes;
    }

    /**
     * Write a patched copy of the jar: every original class is kept, but the
     * binding classes of one dialog package are shadowed by the bridge
     * implementations (written first so they resolve first). Nothing is
     * removed from the jar, so mods and the game keep referencing classes
     * like org.lwjgl.util.nfd.NFDFilterItem that the bridge does not touch.
     */
    private static File patchDialogJar(File bridgeDir, File source, Map<String, byte[]> bridgeClasses,
                                       String packageName, String[] replaceClassPrefixes) {
        Map<String, byte[]> replacements = new HashMap<>();
        for (Map.Entry<String, byte[]> bridgeClass : bridgeClasses.entrySet()) {
            String name = bridgeClass.getKey();
            if (!name.startsWith(packageName)) continue;
            String base = name.substring(packageName.length(), name.length() - ".class".length());
            if (replaceClassPrefixes == null || matchesReplacePrefix(base, replaceClassPrefixes)) {
                replacements.put(name, bridgeClass.getValue());
            }
        }
        if (replacements.isEmpty()) return source;
        File output = new File(bridgeDir, source.getName());
        try (ZipFile zip = new ZipFile(source);
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(output))) {
            // Shadowing classes first: classloaders pick the first entry by name.
            for (Map.Entry<String, byte[]> replacement : replacements.entrySet()) {
                zout.putNextEntry(new ZipEntry(replacement.getKey()));
                zout.write(replacement.getValue());
                zout.closeEntry();
            }
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (replacements.containsKey(entry.getName())) continue;
                zout.putNextEntry(new ZipEntry(entry.getName()));
                if (!entry.isDirectory()) {
                    zout.write(readZipEntry(zip, entry));
                }
                zout.closeEntry();
            }
        } catch (IOException e) {
            Log.w("GameRunner", "Failed to patch the dialog jar " + source.getName(), e);
            return source;
        }
        return output;
    }

    /**
     * Writes a jar holding only one bridge package. Games that do not ship
     * lwjgl-nfd still need the NFD API types referenced by NativeFileDialog,
     * including mods that call NFD directly.
     */
    private static File writeBridgePackageJar(File bridgeDir, String jarName,
                                              Map<String, byte[]> bridgeClasses,
                                              String packageName) {
        File output = new File(bridgeDir, jarName);
        int written = 0;
        try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(output))) {
            for (Map.Entry<String, byte[]> bridgeClass : bridgeClasses.entrySet()) {
                String name = bridgeClass.getKey();
                if (!name.startsWith(packageName) || !name.endsWith(".class")) continue;
                zout.putNextEntry(new ZipEntry(name));
                zout.write(bridgeClass.getValue());
                zout.closeEntry();
                written++;
            }
        } catch (IOException e) {
            Log.w("GameRunner", "Failed to write " + jarName, e);
            return null;
        }
        if (written == 0) {
            output.delete();
            return null;
        }
        return output;
    }

    private static boolean matchesReplacePrefix(String className, String[] prefixes) {
        for (String prefix : prefixes) {
            if (className.equals(prefix) || className.startsWith(prefix + "$")) return true;
        }
        return false;
    }

    private static byte[] readZipEntry(ZipFile zip, ZipEntry entry) throws IOException {
        byte[] buffer = new byte[(int) entry.getSize()];
        try (InputStream in = zip.getInputStream(entry)) {
            int offset = 0;
            while (offset < buffer.length) {
                int read = in.read(buffer, offset, buffer.length - offset);
                if (read < 0) throw new IOException("Truncated zip entry " + entry.getName());
                offset += read;
            }
        }
        return buffer;
    }

    private static void disableSplash(File dir) {
        File configDir = new File(dir, "config");
        if(FileUtils.ensureDirectorySilently(configDir)) {
            File forgeSplashFile = new File(dir, "config/splash.properties");
            String forgeSplashContent = "enabled=true";
            try {
                if (forgeSplashFile.exists()) {
                    forgeSplashContent = Tools.read(forgeSplashFile.getAbsolutePath());
                }
                if (forgeSplashContent.contains("enabled=true")) {
                    Tools.write(forgeSplashFile,
                            forgeSplashContent.replace("enabled=true", "enabled=false"));
                }
            } catch (IOException e) {
                Log.w(Tools.APP_NAME, "Could not disable Forge 1.12.2 and below splash screen!", e);
            }
        } else {
            Log.w(Tools.APP_NAME, "Failed to create the configuration directory");
        }
    }

    private static boolean performAuthPing(Context context, Account account) {
        String injectorUrl = account.authType.injectorUrl;
        if(injectorUrl == null) return true;
        Logger.appendToLog("Pinging authentication service...");
        if(DownloadUtils.pingInjectorUrl(injectorUrl)) return true;
        Logger.appendToLog("Authentication service did not respond, disabling");
        Tools.runOnUiThread(()-> Toast.makeText(context, context.getString(R.string.warn_auth_type_missing, injectorUrl), Toast.LENGTH_SHORT).show());
        return false;
    }

    private static void addAuthlibInjectorArgs(List<String> javaArgList, Account account) {
        String injectorUrl = account.authType.injectorUrl;
        if(injectorUrl == null && account.authType == AuthType.LOCAL) {
            injectorUrl = LocalSkinServer.prepare(account);
        }
        if(injectorUrl == null) return;
        javaArgList.add("-javaagent:"+Tools.DIR_DATA+"/authlib-injector/authlib-injector.jar="+injectorUrl);
    }

    private static void addNoBlocklistArgs(List<String> javaArgList) {
        if(!LauncherPreferences.PREF_DISABLE_BLOCKLIST) return;
        javaArgList.add("-javaagent:"+Tools.DIR_DATA+"/noblocklist/NoBlocklist-1.0-SNAPSHOT-all.jar");
    }

    // Skip setting essential flags from the version JSON as we already override them
    private static boolean shouldSkipArg(String arg) {
        final String[] args = {
                "-Djava.library.path=",
                "-Djna.tmpdir=",
                "-Dorg.lwjgl.system.SharedLibraryExtractPath=",
                "-Dio.netty.native.workdir="
        };
        for(String s : args) {
            if(arg.startsWith(s)) return true;
        }
        return false;
    }

    private static void mergeMoJsonArgs(List<String> userArgs, List<String> moJsonArgs) {
        for(String arg : moJsonArgs) {
            if(shouldSkipArg(arg)) continue;
            userArgs.add(arg);
        }
    }

    private static List<String> getMoJsonJvmArgs(String versionName) {
        JVersionList.Version versionInfo = Tools.getVersionInfo(versionName, true);
        // Parse Forge 1.17+ additional JVM Arguments
        if (versionInfo.arguments == null || versionInfo.arguments.jvm == null) {
            return Collections.emptyList();
        }

        Map<String, String> varArgMap = new ArrayMap<>();
        varArgMap.put("classpath_separator", ":");
        varArgMap.put("library_directory", Tools.DIR_HOME_LIBRARY);
        varArgMap.put("version_name", versionInfo.id);
        varArgMap.put("natives_directory", Tools.NATIVE_LIB_DIR);

        List<String> clientVmArgs = new ArrayList<>();
        if (versionInfo.arguments != null) {
            for (Object arg : versionInfo.arguments.jvm) {
                if (arg instanceof String) {
                    clientVmArgs.add((String) arg);
                } //TODO: implement (?maybe?)
            }
        }
        return JSONUtils.insertJSONValueList(clientVmArgs, varArgMap);
    }

    private static List<String> getMoJsonClientArgs(Account profile, JVersionList.Version versionInfo, File gameDir) {
        String username = profile.username;
        String versionName = versionInfo.id;
        if (versionInfo.inheritsFrom != null) {
            versionName = versionInfo.inheritsFrom;
        }

        String userType = "mojang";
        try {
            Date creationDate = DateUtils.getOriginalReleaseDate(versionInfo);
            // Minecraft 22w43a which adds chat reporting (and signing) was released on
            // 26th October 2022. So, if the date is not before that (meaning it is equal or higher)
            // change the userType to MSA to fix the missing signature
            if(creationDate != null && !DateUtils.dateBefore(creationDate, 2022, 9, 26)) {
                userType = "msa";
            }
        }catch (ParseException e) {
            Log.e("CheckForProfileKey", "Failed to determine profile creation date, using \"mojang\"", e);
        }


        Map<String, String> varArgMap = new ArrayMap<>();
        varArgMap.put("auth_session", profile.accessToken); // For legacy versions of MC
        varArgMap.put("auth_access_token", profile.accessToken);
        varArgMap.put("auth_player_name", username);
        varArgMap.put("auth_uuid", profile.profileId.replace("-", ""));
        varArgMap.put("auth_xuid", profile.xuid);
        varArgMap.put("assets_root", Tools.ASSETS_PATH);
        varArgMap.put("assets_index_name", versionInfo.assets);
        varArgMap.put("game_assets", Tools.ASSETS_PATH + File.separator + "virtual" + File.separator + versionInfo.assets);
        varArgMap.put("game_directory", gameDir.getAbsolutePath());
        varArgMap.put("user_properties", "{}");
        varArgMap.put("user_type", userType);
        varArgMap.put("version_name", versionName);
        varArgMap.put("version_type", versionInfo.type);

        List<String> clientArgs = new ArrayList<>();
        if (versionInfo.arguments != null && versionInfo.arguments.game != null) {
            // Support Minecraft 1.13+
            for (Object arg : versionInfo.arguments.game) {
                if (arg instanceof String) {
                    clientArgs.add((String) arg);
                } //TODO: implement else clause
            }
        }
        if(versionInfo.minecraftArguments != null){
            clientArgs.addAll(splitAndFilterEmpty(versionInfo.minecraftArguments));
        }
        return JSONUtils.insertJSONValueList(clientArgs, varArgMap);
    }

    private static List<String> splitAndFilterEmpty(String argStr) {
        List<String> strList = new ArrayList<>();
        for (String arg : argStr.split(" ")) {
            if (!arg.isEmpty()) {
                strList.add(arg);
            }
        }
        return strList;
    }

    public static @NonNull String pickRuntime(Instance instance, int targetJavaVersion) {
        String runtime = Tools.getSelectedRuntime(instance);
        String profileRuntime = instance.selectedRuntime;
        Runtime pickedRuntime = MultiRTUtils.read(runtime);
        if(runtime == null || pickedRuntime.javaVersion == 0 || pickedRuntime.javaVersion < targetJavaVersion) {
            String preferredRuntime = MultiRTUtils.getNearestJreName(targetJavaVersion);
            if(preferredRuntime == null) throw new RuntimeException("Failed to autopick runtime!");
            if(profileRuntime != null) {
                instance.selectedRuntime = preferredRuntime;
                instance.maybeWrite();
            }
            runtime = preferredRuntime;
        }
        return runtime;
    }
}
