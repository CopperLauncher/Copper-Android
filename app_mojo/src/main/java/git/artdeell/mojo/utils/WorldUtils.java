package git.artdeell.mojo.utils;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;

import org.apache.commons.io.IOUtils;

/**
 * Where the worlds and datapacks of the selected instance live, a world chooser and the zip
 * extraction used when a world is downloaded from CurseForge.
 */
public final class WorldUtils {
    private static final String LEVEL_DAT = "level.dat";

    private WorldUtils() {}

    public interface OnWorldPicked {
        void onWorldPicked(@NonNull String worldFolderName);
    }

    /** Game directory of the selected instance, the default one when it can't be loaded */
    @NonNull
    public static File getGameDirectory() {
        try {
            Instance instance = Instances.loadInstance(Instances.getSelectedInstanceKey());
            if (instance != null) return instance.getGameDirectory();
        } catch (Exception ignored) {}
        return new File(Tools.DIR_GAME_NEW);
    }

    @NonNull
    public static File getSavesDirectory() {
        return new File(getGameDirectory(), "saves");
    }

    @NonNull
    public static File getScreenshotsDirectory() {
        return new File(getGameDirectory(), "screenshots");
    }

    @NonNull
    public static File getDatapacksDirectory(@NonNull String worldFolderName) {
        return new File(new File(getSavesDirectory(), worldFolderName), "datapacks");
    }

    /** The worlds in the saves folder, the most recently played one first */
    @NonNull
    public static File[] listWorlds() {
        File[] worlds = getSavesDirectory().listFiles(f -> f.isDirectory() && new File(f, LEVEL_DAT).isFile());
        if (worlds == null) return new File[0];
        Arrays.sort(worlds, (a, b) -> Long.compare(
                new File(b, LEVEL_DAT).lastModified(), new File(a, LEVEL_DAT).lastModified()));
        return worlds;
    }

    /**
     * Lets the user pick one of the worlds of the selected instance.
     * @param context an Activity context, the chooser is a dialog
     */
    public static void pickWorld(@NonNull Context context, int titleRes, @NonNull OnWorldPicked callback) {
        File[] worlds = listWorlds();
        if (worlds.length == 0) {
            Toast.makeText(context, R.string.world_none_found, Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[worlds.length];
        for (int i = 0; i < worlds.length; i++) names[i] = worlds[i].getName();
        new MaterialAlertDialogBuilder(context)
                .setTitle(titleRes)
                .setItems(names, (dialog, which) -> callback.onWorldPicked(names[which]))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** A folder name that doesn't exist yet in the parent, "name (1)", "name (2)" and so on */
    @NonNull
    public static File uniqueDirectory(@NonNull File parent, @NonNull String name) {
        File candidate = new File(parent, name);
        int count = 1;
        while (candidate.exists()) {
            candidate = new File(parent, name + " (" + count + ")");
            count++;
        }
        return candidate;
    }

    /** Strips what can't be in a folder name, never returns an empty string */
    @NonNull
    public static String sanitizeName(@Nullable String name) {
        String clean = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (clean.startsWith(".")) clean = clean.substring(1);
        return clean.isEmpty() ? "World" : clean;
    }

    /**
     * Extracts a downloaded world zip into the saves folder. The world is the folder of the zip
     * that has a level.dat, a zip that has the level.dat at its top level is extracted as is and
     * named after the zip.
     * @return the folder of the new world
     * @throws IOException when the zip has no world in it or extracting failed
     */
    @NonNull
    public static File extractWorldZip(@NonNull File zipFile, @NonNull File savesDir,
                                       @NonNull String fallbackName) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            String root = findWorldRoot(zip);
            if (root == null) throw new IOException("The zip has no world in it, level.dat is missing");

            String name = fallbackName;
            if (!root.isEmpty()) {
                String trimmed = root.substring(0, root.length() - 1);
                name = trimmed.substring(trimmed.lastIndexOf('/') + 1);
            }
            FileUtils.ensureDirectory(savesDir);
            File destination = uniqueDirectory(savesDir, sanitizeName(name));
            FileUtils.ensureDirectory(destination);
            try {
                extractRoot(zip, root, destination);
            } catch (IOException | RuntimeException e) {
                org.apache.commons.io.FileUtils.deleteQuietly(destination);
                throw e;
            }
            return destination;
        }
    }

    /** Folder in the zip that holds level.dat, "" for the top level, null when there is none */
    @Nullable
    private static String findWorldRoot(ZipFile zip) {
        String best = null;
        int bestDepth = Integer.MAX_VALUE;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = normalizeEntryName(entry.getName());
            if (entry.isDirectory() || name.startsWith("__MACOSX/")) continue;
            if (!name.equals(LEVEL_DAT) && !name.endsWith("/" + LEVEL_DAT)) continue;
            String root = name.substring(0, name.length() - LEVEL_DAT.length());
            int depth = 0;
            for (int i = 0; i < root.length(); i++) if (root.charAt(i) == '/') depth++;
            if (depth < bestDepth) {
                best = root;
                bestDepth = depth;
            }
        }
        return best;
    }

    private static void extractRoot(ZipFile zip, String root, File destination) throws IOException {
        String destinationPath = destination.getCanonicalPath() + File.separator;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = normalizeEntryName(entry.getName());
            if (!name.startsWith(root) || name.startsWith("__MACOSX/")) continue;
            String relative = name.substring(root.length());
            if (relative.isEmpty()) continue;

            File output = new File(destination, relative);
            if (!output.getCanonicalPath().startsWith(destinationPath)) {
                throw new IOException("Unsafe path in the zip: " + entry.getName());
            }
            if (entry.isDirectory()) {
                FileUtils.ensureDirectory(output);
                continue;
            }
            FileUtils.ensureParentDirectory(output);
            try (InputStream input = zip.getInputStream(entry);
                 OutputStream out = new FileOutputStream(output)) {
                IOUtils.copy(input, out);
            }
        }
    }

    private static String normalizeEntryName(String name) {
        String normalized = name.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }

    /** Zips a world folder, with the folder itself as the top level entry, so it can be imported again */
    public static void zipWorld(@NonNull File worldDir, @NonNull File zipFile) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zipFolder(zip, worldDir, worldDir.getName() + "/");
        }
    }

    private static void zipFolder(ZipOutputStream zip, File folder, String prefix) throws IOException {
        File[] children = folder.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                zip.putNextEntry(new ZipEntry(prefix + child.getName() + "/"));
                zip.closeEntry();
                zipFolder(zip, child, prefix + child.getName() + "/");
            } else if (!child.getName().equals("session.lock")) {
                zip.putNextEntry(new ZipEntry(prefix + child.getName()));
                try (InputStream in = new java.io.FileInputStream(child)) {
                    IOUtils.copy(in, zip);
                }
                zip.closeEntry();
            }
        }
    }

    /** Size of everything in a folder, in bytes */
    public static long folderSize(@NonNull File folder) {
        return org.apache.commons.io.FileUtils.sizeOfDirectory(folder);
    }

    /** Lower-case extension check that also accepts the ".disabled" suffix */
    public static boolean hasExtension(@NonNull String fileName, @NonNull String extension) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(extension) || lower.endsWith(extension + ".disabled");
    }
}
