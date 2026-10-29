package git.artdeell.mojo.fragments;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.modding.modpacks.models.Constants;
import git.artdeell.mojo.modding.modpacks.models.ContentCategory;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.profiles.VersionSelectorDialog;

import java.util.ArrayList;
import java.util.List;

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

    /** Loads the categories of a search source (Constants.ENGINE_*), called off the main thread */
    public interface CategoryProvider {
        @NonNull List<ContentCategory> getCategories(int engine);
    }

    public interface OnCategory {
        /** @param category the picked category, null for any, called right before OnApply */
        void onCategory(@Nullable ContentCategory category);
    }

    public static final class Options {
        public boolean showEngine = true;
        public boolean showLoader = true;
        public int engine = Constants.ENGINE_MODRINTH;
        public String mcVersion = "";
        public String loader = "";
        @StringRes public int subtitle = R.string.filter_dialog_subtitle;
        /** The category section is shown when this is set and there is a provider to load them */
        public boolean showCategories = false;
        @Nullable public ContentCategory category;
        @Nullable public CategoryProvider categoryProvider;
        @Nullable public OnCategory onCategoryApply;
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
        View categorySection = dialog.findViewById(R.id.filter_section_category);
        ChipGroup categoryGroup = dialog.findViewById(R.id.filter_category_group);
        TextView categoryStatus = dialog.findViewById(R.id.filter_category_status);
        View resetButton = dialog.findViewById(R.id.filter_reset);
        View applyButton = dialog.findViewById(R.id.filter_apply);
        if (engineGroup == null || versionRow == null || versionText == null || versionClear == null
                || loaderGroup == null || resetButton == null || applyButton == null
                || modrinthChip == null || curseforgeChip == null || bothChip == null
                || categorySection == null || categoryGroup == null || categoryStatus == null) return;

        if (subtitle != null) subtitle.setText(options.subtitle);

        // With CurseForge turned off in the experimental settings only Modrinth can be searched,
        // a CurseForge or "Both" search would silently return nothing
        boolean curseforgeDisabled = LauncherPreferences.DEFAULT_PREF.getBoolean("disableCurseforgeApi", false);
        if (engineSection != null) engineSection.setVisibility(options.showEngine ? View.VISIBLE : View.GONE);
        curseforgeChip.setVisibility(curseforgeDisabled ? View.GONE : View.VISIBLE);
        bothChip.setVisibility(curseforgeDisabled ? View.GONE : View.VISIBLE);
        if (loaderSection != null) loaderSection.setVisibility(options.showLoader ? View.VISIBLE : View.GONE);

        boolean categoriesEnabled = options.showCategories && options.categoryProvider != null;
        categorySection.setVisibility(categoriesEnabled ? View.VISIBLE : View.GONE);

        // The version is kept here and only handed back on Apply
        final String[] version = {options.mcVersion == null ? "" : options.mcVersion};

        checkEngine(engineGroup, curseforgeDisabled ? Constants.ENGINE_MODRINTH : options.engine);
        checkLoader(loaderGroup, options.loader);
        updateVersionRow(versionText, versionClear, version[0]);

        // The picked category is kept here too. The chips are rebuilt whenever the source changes,
        // a category the new source doesn't have falls back to "Any"
        final ContentCategory[] category = {options.category};
        final boolean[] populating = {false};
        final int[] loadToken = {0};
        Runnable reloadCategories = () -> {
            if (!categoriesEnabled) return;
            int engine = options.showEngine ? engineFromChips(engineGroup, curseforgeDisabled) : options.engine;
            loadCategories(dialog, options, engine, categoryGroup, categoryStatus, category, populating, loadToken);
        };
        categoryGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (populating[0]) return;
            category[0] = categoryOfChip(group.findViewById(group.getCheckedChipId()));
        });
        engineGroup.setOnCheckedStateChangeListener((group, checkedIds) -> reloadCategories.run());
        reloadCategories.run();

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
            category[0] = null;
            categoryGroup.check(R.id.filter_category_any);
            version[0] = "";
            checkEngine(engineGroup, Constants.ENGINE_MODRINTH);
            checkLoader(loaderGroup, "");
            updateVersionRow(versionText, versionClear, version[0]);
        });

        applyButton.setOnClickListener(v -> {
            int engine = options.showEngine ? engineFromChips(engineGroup, curseforgeDisabled) : options.engine;
            String loader = options.showLoader ? loaderFromChips(loaderGroup) : "";
            if (categoriesEnabled && options.onCategoryApply != null) options.onCategoryApply.onCategory(category[0]);
            onApply.onApply(engine, version[0].trim(), loader);
            dialog.dismiss();
        });
    }

    /** Replaces the category chips by the categories of the engine once they are loaded */
    private static void loadCategories(AlertDialog dialog, Options options, int engine, ChipGroup group,
                                       TextView status, ContentCategory[] picked, boolean[] populating, int[] loadToken) {
        final int token = ++loadToken[0];
        populating[0] = true;
        group.check(R.id.filter_category_any);
        removeCategoryChips(group);
        populating[0] = false;
        status.setText(R.string.filter_category_loading);
        status.setVisibility(View.VISIBLE);

        final ContentCategory wanted = picked[0];
        MojoApplication.sExecutorService.execute(() -> {
            List<ContentCategory> categories;
            try {
                categories = options.categoryProvider.getCategories(engine);
            } catch (Exception e) {
                e.printStackTrace();
                categories = new ArrayList<>();
            }
            final List<ContentCategory> loaded = categories;
            new Handler(Looper.getMainLooper()).post(() -> {
                // The engine changed meanwhile or the dialog is gone
                if (token != loadToken[0] || !dialog.isShowing()) return;
                populating[0] = true;
                removeCategoryChips(group);
                LayoutInflater inflater = LayoutInflater.from(group.getContext());
                Chip toCheck = null;
                for (ContentCategory category : loaded) {
                    Chip chip = (Chip) inflater.inflate(R.layout.item_filter_category_chip, group, false);
                    chip.setId(View.generateViewId());
                    chip.setText(category.name);
                    chip.setTag(category);
                    group.addView(chip);
                    if (category.equals(wanted)) toCheck = chip;
                }
                if (toCheck != null) group.check(toCheck.getId());
                picked[0] = toCheck != null ? (ContentCategory) toCheck.getTag() : null;
                populating[0] = false;
                if (loaded.isEmpty()) status.setText(R.string.filter_category_unavailable);
                else status.setVisibility(View.GONE);
            });
        });
    }

    private static void removeCategoryChips(ChipGroup group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            if (group.getChildAt(i).getTag() instanceof ContentCategory) group.removeViewAt(i);
        }
    }

    @Nullable
    private static ContentCategory categoryOfChip(@Nullable View chip) {
        return chip != null && chip.getTag() instanceof ContentCategory ? (ContentCategory) chip.getTag() : null;
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
