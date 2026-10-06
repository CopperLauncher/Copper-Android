package net.kdt.pojavlaunch.modloaders.modpacks.api;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.R;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Installs a modpack the user picked from their device (.mrpack or a CurseForge .zip) as a new
 * instance. The archive is copied to the cache first, the {@link ModpackApi} then detects its
 * format and runs the usual modpack installation.
 */
public final class LocalModpackImporter {
    private LocalModpackImporter() {}

    /** Copies and installs the modpack on a background thread */
    public static void importAsync(Context context, Uri uri, ModpackApi modpackApi) {
        Context appContext = context.getApplicationContext();
        ContentResolver contentResolver = appContext.getContentResolver();
        PojavApplication.sExecutorService.execute(
                () -> performLocalInstall(appContext, contentResolver, uri, modpackApi));
    }

    private static void performLocalInstall(Context context, ContentResolver contentResolver,
                                            Uri uri, ModpackApi modpackApi) {
        String fileName = Tools.getFileName(context, uri);
        if (fileName == null) return;
        File outFile = new File(Tools.DIR_CACHE, fileName + ".cf");
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, R.string.multirt_progress_caching);
        try (InputStream inputStream = contentResolver.openInputStream(uri);
             OutputStream outputStream = new FileOutputStream(outFile)) {
            if (inputStream == null) {
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                return;
            }
            IOUtils.copy(inputStream, outputStream);
            outputStream.flush();
        } catch (IOException e) {
            Tools.showErrorRemote("Error", e);
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            return;
        }
        try {
            modpackApi.installLocalModpack(fileName, outFile, null);
        } catch (IOException e) {
            Tools.showErrorRemote("Error", e);
        } finally {
            outFile.delete();
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
        }
    }
}
