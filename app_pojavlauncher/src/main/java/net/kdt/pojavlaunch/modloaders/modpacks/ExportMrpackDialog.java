package net.kdt.pojavlaunch.modloaders.modpacks;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.api.MrpackExporter;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Drives the "Export as .mrpack" dialog shown from the Edit Instance tab. Lets the user name
 * the pack, set a version/description, and pick exactly which files get bundled — defaulting
 * to the same selection the official Modrinth app pre-checks (config/mods/resourcepacks/
 * shaderpacks, everything else unchecked) — then hands the actual export work off to
 * {@link MrpackExporter}.
 */
public class ExportMrpackDialog {

    private static final Set<String> DEFAULT_CHECKED_TOP_LEVEL = new HashSet<>(Arrays.asList(
            "config", "mods", "resourcepacks", "shaderpacks"));

    public static void show(Activity activity, MinecraftProfile profile) {
        File instanceDir = Tools.getGameDirPath(profile);
        if (!instanceDir.isDirectory()) {
            Toast.makeText(activity, R.string.export_mrpack_error, Toast.LENGTH_SHORT).show();
            return;
        }

        Map<String, Boolean> overrides = new HashMap<>();
        File[] topLevelEntries = instanceDir.listFiles();
        if (topLevelEntries != null) {
            for (File entry : topLevelEntries) {
                boolean defaultChecked = DEFAULT_CHECKED_TOP_LEVEL.contains(entry.getName().toLowerCase(Locale.ROOT));
                overrides.put(entry.getName(), defaultChecked);
            }
        }

        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_export_mrpack, null);

        EditText nameInput = dialogView.findViewById(R.id.export_mrpack_name_input);
        EditText versionInput = dialogView.findViewById(R.id.export_mrpack_version_input);
        EditText descriptionInput = dialogView.findViewById(R.id.export_mrpack_description_input);

        String defaultName = (Tools.isValidString(profile.name) && !"New".equalsIgnoreCase(profile.name))
                ? profile.name.trim() : instanceDir.getName();
        nameInput.setText(defaultName);
        versionInput.setText("1.0.0");

        RecyclerView treeRecyclerView = dialogView.findViewById(R.id.export_mrpack_file_tree);
        treeRecyclerView.setLayoutManager(new LinearLayoutManager(activity));
        treeRecyclerView.setNestedScrollingEnabled(false);
        int indentPx = activity.getResources().getDimensionPixelSize(R.dimen._14sdp);
        treeRecyclerView.setAdapter(new ExportFileTreeAdapter(instanceDir, overrides, indentPx));

        View filesHeader = dialogView.findViewById(R.id.export_mrpack_files_header);
        ImageView filesChevron = dialogView.findViewById(R.id.export_mrpack_files_chevron);
        filesHeader.setOnClickListener(v -> {
            boolean willBeVisible = treeRecyclerView.getVisibility() != View.VISIBLE;
            treeRecyclerView.setVisibility(willBeVisible ? View.VISIBLE : View.GONE);
            filesChevron.setRotation(willBeVisible ? 180f : 0f);
        });

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.export_mrpack_dialog_title)
                .setView(dialogView)
                .create();

        dialogView.findViewById(R.id.export_mrpack_cancel_button).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.export_mrpack_confirm_button).setOnClickListener(v -> {
            String packName = nameInput.getText().toString().trim();
            if (packName.isEmpty()) {
                Toast.makeText(activity, R.string.export_mrpack_name_required, Toast.LENGTH_SHORT).show();
                return;
            }
            String packVersion = versionInput.getText().toString().trim();
            String packDescription = descriptionInput.getText().toString();
            dialog.dismiss();
            runExport(activity, profile, instanceDir, packName, packVersion, packDescription, overrides);
        });

        dialog.show();
    }

    private static void runExport(Activity activity, MinecraftProfile profile, File instanceDir,
                                   String packName, String packVersion, String packDescription,
                                   Map<String, Boolean> overrides) {

        if (MinecraftProfile.LATEST_RELEASE.equals(profile.lastVersionId)
                || MinecraftProfile.LATEST_SNAPSHOT.equals(profile.lastVersionId)) {
            Toast.makeText(activity, R.string.export_mrpack_mc_version_unknown, Toast.LENGTH_LONG).show();
        }

        ProgressDialog progressDialog = Tools.getWaitingDialog(activity, R.string.export_mrpack_progress_preparing);

        File outputFile = new File(new File(Tools.DIR_GAME_HOME, "exports"), buildOutputFileName(packName, packVersion));

        PojavApplication.sExecutorService.execute(() -> {
            try {
                MrpackExporter.ExportResult result = MrpackExporter.export(
                        instanceDir, packName, packVersion, packDescription, profile.lastVersionId,
                        overrides, outputFile,
                        rawMessage -> Tools.runOnUiThread(() -> applyProgressMessage(activity, progressDialog, rawMessage)));

                Tools.runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(activity,
                            activity.getString(R.string.export_mrpack_success, result.outputFile.getName()),
                            Toast.LENGTH_LONG).show();
                    Tools.openPath(activity, result.outputFile, true);
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Tools.showError(activity, R.string.export_mrpack_error, e);
                });
            }
        });
    }

    private static String buildOutputFileName(String packName, String packVersion) {
        String safeName = packName.replaceAll("[^a-zA-Z0-9._ -]", "_").trim();
        if (safeName.isEmpty()) safeName = "modpack";
        String safeVersion = packVersion == null ? "" : packVersion.replaceAll("[^a-zA-Z0-9._-]", "_").trim();
        return safeVersion.isEmpty() ? (safeName + ".mrpack") : (safeName + "-" + safeVersion + ".mrpack");
    }

    private static void applyProgressMessage(Activity activity, ProgressDialog progressDialog, String rawMessage) {
        if (rawMessage == null) return;
        String[] parts = rawMessage.split(":");
        String text;
        switch (parts[0]) {
            case "hashing":
                text = activity.getString(R.string.export_mrpack_progress_hashing,
                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                break;
            case "packaging":
                text = activity.getString(R.string.export_mrpack_progress_packaging,
                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                break;
            case "checking":
                text = activity.getString(R.string.export_mrpack_progress_checking);
                break;
            default:
                return;
        }
        progressDialog.setMessage(text);
    }
}