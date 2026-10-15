package git.artdeell.mojo.fragments;

import android.content.Context;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import git.artdeell.mojo.R;
import git.artdeell.mojo.modding.modpacks.models.Constants;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.profiles.VersionSelectorDialog;

/**
 * The one filter dialog of the content browser, the modpack browser and the update check of
 * Manage Content. Search source and loader are chips, the Minecraft version is a single tappable
 * row, and Reset puts everything back to "any". Sections a screen does not use are hidden.
 */
public final class SearchFilterDialog {
    private static final int[] LOADER_CHIPS = {
            R.id.filter_loader_any, R.id.filter_loader_fabric, R.id.filter_loader_forge,
            R.id.filter_loader_quilt, R.id.filter_loader_neoforge
    };
    private static final String[] LOADER_VALUES = {"", "fabric", "forge", "quilt", "neoforge"};

    private SearchFilterDialog() {}

    public interface OnApply {
        /** @param engine one of Constants.ENGINE_*, the options' value when the source section is hidden */
        void onApply(int engine, @NonNull String mcVersion, @NonNull String loader);
    }

    public static final class Options {
        public boolean showEngine = true;
        public boolean showLoader = true;
        public int engine = Constants.ENGINE_MODRINTH;
        public String mcVersion = "";
        public String loader = "";
        @StringRes public int subtitle = R.string.filter_dialog_subtitle;
    }

    public static void show(@NonNull Context context, @NonNull Options options, @NonNull OnApply onApply) {
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setView(R.layout.dialog_content_filters)
                .create();
        dialog.setOnShowListener(di -> bind(dialog, options, onApply));
        dialog.show();
    }

    private static void bind(AlertDialog dialog, Options options, OnApply onApply) {
        TextView subtitle = dialog.findViewById(R.id.filter_subtitle);
        View engineSection = dialog.findViewById(R.id.filter_section_engine);
        ChipGroup engineGroup = dialog.findViewById(R.id.filter_engine_group);
        Chip modrinthChip = dialog.findViewById(R.id.filter_engine_modrinth);
        Chip curseforgeChip = dialog.findViewById(R.id.filter_engine_curseforge);
        Chip bothChip = dialog.findViewById(R.id.filter_engine_both);
        View versionRow = dialog.findViewById(R.id.filter_version_row);
        TextView versionText = dialog.findViewById(R.id.filter_version_text);
        ImageButton versionClear = dialog.findViewById(R.id.filter_version_clear);
        View loaderSection = dialog.findViewById(R.id.filter_section_loader);
        ChipGroup loaderGroup = dialog.findViewById(R.id.filter_loader_group);
        View resetButton = dialog.findViewById(R.id.filter_reset);
        View applyButton = dialog.findViewById(R.id.filter_apply);
        if (engineGroup == null || versionRow == null || versionText == null || versionClear == null
                || loaderGroup == null || resetButton == null || applyButton == null
                || modrinthChip == null || curseforgeChip == null || bothChip == null) return;

        if (subtitle != null) subtitle.setText(options.subtitle);

        // With CurseForge turned off in the experimental settings only Modrinth can be searched,
        // a CurseForge or "Both" search would silently return nothing
        boolean curseforgeDisabled = LauncherPreferences.DEFAULT_PREF.getBoolean("disableCurseforgeApi", false);
        if (engineSection != null) engineSection.setVisibility(options.showEngine ? View.VISIBLE : View.GONE);
        curseforgeChip.setVisibility(curseforgeDisabled ? View.GONE : View.VISIBLE);
        bothChip.setVisibility(curseforgeDisabled ? View.GONE : View.VISIBLE);
        if (loaderSection != null) loaderSection.setVisibility(options.showLoader ? View.VISIBLE : View.GONE);

        // The version is kept here and only handed back on Apply
        final String[] version = {options.mcVersion == null ? "" : options.mcVersion};

        checkEngine(engineGroup, curseforgeDisabled ? Constants.ENGINE_MODRINTH : options.engine);
        checkLoader(loaderGroup, options.loader);
        updateVersionRow(versionText, versionClear, version[0]);

        View.OnClickListener pickVersion = v -> VersionSelectorDialog.open(v.getContext(), true,
                (id, snapshot) -> {
                    version[0] = id == null ? "" : id;
                    updateVersionRow(versionText, versionClear, version[0]);
                });
        versionRow.setOnClickListener(pickVersion);
        versionClear.setOnClickListener(v -> {
            version[0] = "";
            updateVersionRow(versionText, versionClear, version[0]);
        });

        resetButton.setOnClickListener(v -> {
            version[0] = "";
            checkEngine(engineGroup, Constants.ENGINE_MODRINTH);
            checkLoader(loaderGroup, "");
            updateVersionRow(versionText, versionClear, version[0]);
        });

        applyButton.setOnClickListener(v -> {
            int engine = options.showEngine ? engineFromChips(engineGroup, curseforgeDisabled) : options.engine;
            String loader = options.showLoader ? loaderFromChips(loaderGroup) : "";
            onApply.onApply(engine, version[0].trim(), loader);
            dialog.dismiss();
        });
    }

    private static void updateVersionRow(TextView text, ImageButton clear, String version) {
        boolean any = version.isEmpty();
        text.setText(any ? text.getContext().getString(R.string.filter_any_version) : version);
        text.setTextColor(MaterialColors.getColor(text, any
                ? com.google.android.material.R.attr.colorOnSurfaceVariant
                : com.google.android.material.R.attr.colorOnSurface));
        clear.setVisibility(any ? View.GONE : View.VISIBLE);
    }

    private static void checkEngine(ChipGroup group, int engine) {
        switch (engine) {
            case Constants.ENGINE_CURSEFORGE: group.check(R.id.filter_engine_curseforge); break;
            case Constants.ENGINE_BOTH: group.check(R.id.filter_engine_both); break;
            default: group.check(R.id.filter_engine_modrinth); break;
        }
    }

    private static int engineFromChips(ChipGroup group, boolean curseforgeDisabled) {
        if (curseforgeDisabled) return Constants.ENGINE_MODRINTH;
        int checked = group.getCheckedChipId();
        if (checked == R.id.filter_engine_curseforge) return Constants.ENGINE_CURSEFORGE;
        if (checked == R.id.filter_engine_both) return Constants.ENGINE_BOTH;
        return Constants.ENGINE_MODRINTH;
    }

    private static void checkLoader(ChipGroup group, String loader) {
        for (int i = 0; i < LOADER_VALUES.length; i++) {
            if (LOADER_VALUES[i].equals(loader == null ? "" : loader)) {
                group.check(LOADER_CHIPS[i]);
                return;
            }
        }
        group.check(R.id.filter_loader_any);
    }

    private static String loaderFromChips(ChipGroup group) {
        int checked = group.getCheckedChipId();
        for (int i = 0; i < LOADER_CHIPS.length; i++) {
            if (LOADER_CHIPS[i] == checked) return LOADER_VALUES[i];
        }
        return "";
    }
}
