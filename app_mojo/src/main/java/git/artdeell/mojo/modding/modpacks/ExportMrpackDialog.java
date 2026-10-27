package git.artdeell.mojo.modding.modpacks;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.modding.modpacks.api.MrpackExporter;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Export .mrpack" dialog of the instance editor. The user names the pack, sets a version and
 * description and picks the files to bundle, then {@link MrpackExporter} builds the archive.
 * config, mods, resourcepacks, shaderpacks and options.txt are selected by default.
 */
public class ExportMrpackDialog {

    private static final Set<String> DEFAULT_CHECKED_TOP_LEVEL = new HashSet<>(Arrays.asList(
            "config", "mods", "resourcepacks", "shaderpacks", "options.txt"));

    public static void show(@NonNull Activity activity, @NonNull Instance instance) {
        File instanceDir = instance.getGameDirectory();
        if (instanceDir == null || !instanceDir.isDirectory()) {
            Toast.makeText(activity, R.string.export_mrpack_error, Toast.LENGTH_SHORT).show();
            return;
        }

        Map<String, Boolean> overrides = new HashMap<>();
        File[] topLevelEntries = instanceDir.listFiles();
        if (topLevelEntries != null) {
            for (File entry : topLevelEntries) {
                overrides.put(entry.getName(),
                        DEFAULT_CHECKED_TOP_LEVEL.contains(entry.getName().toLowerCase(Locale.ROOT)));
            }
        }

        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_export_mrpack, null);
        TextInputEditText nameInput = dialogView.findViewById(R.id.export_mrpack_name_input);
        TextInputEditText versionInput = dialogView.findViewById(R.id.export_mrpack_version_input);
        TextInputEditText descriptionInput = dialogView.findViewById(R.id.export_mrpack_description_input);

        String defaultName = Tools.isValidString(instance.name) ? instance.name.trim() : instanceDir.getName();
        nameInput.setText(defaultName);
        nameInput.setSelection(nameInput.length());
        versionInput.setText("1.0.0");

        RecyclerView tree = dialogView.findViewById(R.id.export_mrpack_file_tree);
        tree.setLayoutManager(new LinearLayoutManager(activity));
        int indentPx = activity.getResources().getDimensionPixelSize(R.dimen._14sdp);
        tree.setAdapter(new ExportFileTreeAdapter(instanceDir, overrides, indentPx));

        ImageView chevron = dialogView.findViewById(R.id.export_mrpack_files_chevron);
        dialogView.findViewById(R.id.export_mrpack_files_header).setOnClickListener(v -> {
            boolean show = tree.getVisibility() != View.VISIBLE;
            tree.setVisibility(show ? View.VISIBLE : View.GONE);
            chevron.setRotation(show ? 180f : 0f);
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.export_mrpack_dialog_title)
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.export_mrpack_export_button, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String packName = String.valueOf(nameInput.getText()).trim();
            if (packName.isEmpty()) {
                Toast.makeText(activity, R.string.export_mrpack_name_required, Toast.LENGTH_SHORT).show();
                return;
            }
            String packVersion = String.valueOf(versionInput.getText()).trim();
            String packDescription = String.valueOf(descriptionInput.getText());
            dialog.dismiss();
            runExport(activity, instance, instanceDir, packName, packVersion, packDescription, overrides);
        }));
        dialog.show();
    }

    private static void runExport(Activity activity, Instance instance, File instanceDir,
                                  String packName, String packVersion, String packDescription,
                                  Map<String, Boolean> overrides) {
        if (Instance.VERSION_LATEST_RELEASE.equals(instance.versionId)
                || Instance.VERSION_LATEST_SNAPSHOT.equals(instance.versionId)) {
            Toast.makeText(activity, R.string.export_mrpack_mc_version_unknown, Toast.LENGTH_LONG).show();
        }

        View progressView = LayoutInflater.from(activity).inflate(R.layout.dialog_export_progress, null);
        TextView progressMessage = progressView.findViewById(R.id.export_progress_message);
        AlertDialog progressDialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.export_mrpack_dialog_title)
                .setView(progressView)
                .setCancelable(false)
                .create();
        progressDialog.show();

        File outputFile = new File(new File(Tools.DIR_GAME_HOME, "exports"), buildOutputFileName(packName, packVersion));

        MojoApplication.sExecutorService.execute(() -> {
            try {
                MrpackExporter.ExportResult result = MrpackExporter.export(
                        instanceDir, packName, packVersion, packDescription, instance.versionId,
                        overrides, outputFile,
                        raw -> Tools.runOnUiThread(() -> applyProgressMessage(activity, progressMessage, raw)));

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

    private static void applyProgressMessage(Activity activity, TextView target, String raw) {
        if (raw == null) return;
        String[] parts = raw.split(":");
        switch (parts[0]) {
            case "hashing":
                target.setText(activity.getString(R.string.export_mrpack_progress_hashing,
                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                break;
            case "packaging":
                target.setText(activity.getString(R.string.export_mrpack_progress_packaging,
                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                break;
            case "checking":
                target.setText(R.string.export_mrpack_progress_checking);
                break;
            default:
                break;
        }
    }
}
