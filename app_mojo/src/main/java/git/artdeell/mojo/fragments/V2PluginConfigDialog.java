package git.artdeell.mojo.fragments;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.radiobutton.MaterialRadioButton;

import git.artdeell.mojo.R;
import git.artdeell.mojo.utils.plugins.RendererV2Plugin;
import git.artdeell.mojo.utils.plugins.V2PluginEnvStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dialog for configuring V2 renderer plugin environment variables, mirroring the
 * ZalithLauncher2 renderer config dialog: entries stacked as rounded cards, the
 * selected value shown under the title, choice options expanded inline and the
 * changes saved as they are made.
 */
public class V2PluginConfigDialog {
    private static final int CARD_TOP = 0;
    private static final int CARD_MIDDLE = 1;
    private static final int CARD_BOTTOM = 2;
    private static final int CARD_SINGLE = 3;

    private V2PluginConfigDialog() {}

    /**
     * Show the configuration dialog for a V2 renderer plugin.
     *
     * @param context host context
     * @param plugin  the V2 plugin to configure
     */
    public static void show(@NonNull Context context, @NonNull RendererV2Plugin plugin) {
        show(context, plugin, V2PluginEnvStore.load(plugin.packageName));
    }

    private static void show(@NonNull Context context, @NonNull RendererV2Plugin plugin,
                             @NonNull Map<String, V2PluginEnvStore.Entry> config) {
        List<RendererV2Plugin.Env> entries = new ArrayList<>();
        for (RendererV2Plugin.Env env : plugin.env) {
            if (!(env instanceof RendererV2Plugin.NormalEnv)) entries.add(env);
        }

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
        for (int i = 0; i < entries.size(); i++) {
            RendererV2Plugin.Env env = entries.get(i);
            int position = entries.size() == 1 ? CARD_SINGLE
                    : i == 0 ? CARD_TOP
                    : i == entries.size() - 1 ? CARD_BOTTOM : CARD_MIDDLE;
            list.addView(buildEnvCard(context, plugin, config, env, position));
        }

        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);

        new MaterialAlertDialogBuilder(context)
                .setTitle(plugin.displayName)
                .setView(scroll)
                .setPositiveButton(R.string.mcl_setting_renderer_plugin_close,
                        (dialog, which) -> dialog.dismiss())
                .setNegativeButton(R.string.mcl_setting_renderer_plugin_reset,
                        (dialog, which) -> {
                            V2PluginEnvStore.save(plugin.packageName, new HashMap<>());
                            dialog.dismiss();
                            show(context, plugin);
                        })
                .show();
    }

    private static View buildEnvCard(Context context, RendererV2Plugin plugin,
                                     Map<String, V2PluginEnvStore.Entry> config,
                                     RendererV2Plugin.Env env, int position) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(buildCardBackground(card, position));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (position == CARD_MIDDLE || position == CARD_BOTTOM) params.topMargin = dp(context, 2);
        card.setLayoutParams(params);

        String title = env.getLabel();
        if (env instanceof RendererV2Plugin.ToggleableEnv) {
            buildToggleRow(context, plugin, config, card, (RendererV2Plugin.ToggleableEnv) env, title);
        } else if (env instanceof RendererV2Plugin.SelectableEnv) {
            buildChoiceCard(context, plugin, config, card, (RendererV2Plugin.SelectableEnv) env, title);
        } else if (env instanceof RendererV2Plugin.CustomizableEnv) {
            buildTextCard(context, plugin, config, card, (RendererV2Plugin.CustomizableEnv) env, title);
        }
        return card;
    }

    /** ToggleableEnv, like the ZL2 switch entry: title on the left, switch on the right */
    private static void buildToggleRow(Context context, RendererV2Plugin plugin,
                                       Map<String, V2PluginEnvStore.Entry> config,
                                       LinearLayout card,
                                       RendererV2Plugin.ToggleableEnv env, String title) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(15f);
        titleView.setTextColor(MaterialColors.getColor(card, R.attr.colorOnSurface));
        row.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialSwitch stateSwitch = new MaterialSwitch(context);
        stateSwitch.setChecked(entryEnabled(config, env.getKey(), env.enabledByDefault));
        stateSwitch.setOnCheckedChangeListener((button, checked) -> {
            V2PluginEnvStore.Entry entry = configEntry(config, env.getKey());
            entry.enabled = checked;
            V2PluginEnvStore.save(plugin.packageName, config);
        });
        row.addView(stateSwitch);

        row.setOnClickListener(v -> stateSwitch.setChecked(!stateSwitch.isChecked()));
        card.addView(row);
    }

    /** SelectableEnv, like the ZL2 choice entry: checkbox + selected value + inline options */
    private static void buildChoiceCard(Context context, RendererV2Plugin plugin,
                                        Map<String, V2PluginEnvStore.Entry> config,
                                        LinearLayout card,
                                        RendererV2Plugin.SelectableEnv env, String title) {
        // ZalithLauncher2 lists the default value first, then the plugin values.
        // Skip null/empty entries so no blank row ("string without the plugin text")
        // can ever appear in the list.
        Set<String> options = new LinkedHashSet<>();
        if (env.defaultValue != null && !env.defaultValue.isEmpty()) options.add(env.defaultValue);
        for (String value : env.values) {
            if (value != null && !value.isEmpty()) options.add(value);
        }

        String current = entryValue(config, env.getKey(), env.defaultValue);
        // A saved value the plugin no longer offers cannot be selected; fall back
        // to the first listed option so a radio is always checked.
        boolean currentValid = current != null && options.contains(current);
        if (!currentValid) current = options.isEmpty() ? null : options.iterator().next();
        boolean enabled = env.alwaysOn || entryEnabled(config, env.getKey(), env.enabledByDefault);
        final boolean[] headerEnabled = {enabled};

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(15f);
        titleView.setTextColor(MaterialColors.getColor(card, R.attr.colorOnSurface));
        textColumn.addView(titleView);

        TextView selectedView = new TextView(context);
        selectedView.setTextSize(12f);
        selectedView.setAlpha(0.7f);
        selectedView.setTextColor(MaterialColors.getColor(card, R.attr.colorOnSurface));
        textColumn.addView(selectedView);

        header.addView(textColumn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.ic_expand_more);
        arrow.setPadding(dp(context, 4), 0, dp(context, 4), 0);
        header.addView(arrow);

        LinearLayout optionsColumn = new LinearLayout(context);
        optionsColumn.setOrientation(LinearLayout.VERTICAL);
        optionsColumn.setPadding(dp(context, 8), 0, dp(context, 8), dp(context, 8));
        optionsColumn.setVisibility(View.GONE);
        List<MaterialRadioButton> radioButtons = new ArrayList<>();
        for (String option : options) {
            LinearLayout optionRow = new LinearLayout(context);
            optionRow.setOrientation(LinearLayout.HORIZONTAL);
            optionRow.setGravity(Gravity.CENTER_VERTICAL);
            optionRow.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));
            optionRow.setOnClickListener(v -> {
                V2PluginEnvStore.Entry entry = configEntry(config, env.getKey());
                entry.value = option;
                V2PluginEnvStore.save(plugin.packageName, config);
                refreshSelection(selectedView, radioButtons, options, option);
                updateExpansion(optionsColumn, arrow, false);
            });

            MaterialRadioButton button = new MaterialRadioButton(context);
            button.setClickable(false);
            button.setFocusable(false);
            optionRow.addView(button);

            TextView optionName = new TextView(context);
            optionName.setText(option);
            optionName.setTextSize(13f);
            optionName.setTextColor(MaterialColors.getColor(card, R.attr.colorOnSurface));
            optionRow.addView(optionName);

            optionsColumn.addView(optionRow);
            radioButtons.add(button);
        }

        if (!env.alwaysOn) {
            MaterialCheckBox stateBox = new MaterialCheckBox(context);
            stateBox.setChecked(enabled);
            stateBox.setOnCheckedChangeListener((button, checked) -> {
                V2PluginEnvStore.Entry entry = configEntry(config, env.getKey());
                entry.enabled = checked;
                headerEnabled[0] = checked;
                V2PluginEnvStore.save(plugin.packageName, config);
                if (!checked) updateExpansion(optionsColumn, arrow, false);
            });
            header.addView(stateBox, 0);
        }

        header.setOnClickListener(v -> {
            if (headerEnabled[0]) updateExpansion(optionsColumn, arrow,
                    optionsColumn.getVisibility() != View.VISIBLE);
        });

        refreshSelection(selectedView, radioButtons, options, current);
        card.addView(header);
        card.addView(optionsColumn);
    }

    /** CustomizableEnv, like the ZL2 text entry: title above an outlined text field */
    private static void buildTextCard(Context context, RendererV2Plugin plugin,
                                      Map<String, V2PluginEnvStore.Entry> config,
                                      LinearLayout card,
                                      RendererV2Plugin.CustomizableEnv env, String title) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(15f);
        titleView.setTextColor(MaterialColors.getColor(card, R.attr.colorOnSurface));
        column.addView(titleView);

        View textInput = LayoutInflater.from(context)
                .inflate(R.layout.dialog_v2_env_text_input, column, false);
        EditText edit = textInput.findViewById(R.id.v2_env_text_input);
        edit.setText(entryValue(config, env.getKey(), env.defaultValue));
        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                V2PluginEnvStore.Entry entry = configEntry(config, env.getKey());
                entry.value = s.toString();
                V2PluginEnvStore.save(plugin.packageName, config);
            }
        });
        column.addView(textInput);
        card.addView(column);
    }

    private static void refreshSelection(@NonNull TextView selectedView,
                                         List<MaterialRadioButton> radioButtons,
                                         Set<String> options, String selected) {
        selectedView.setText(selectedView.getContext()
                .getString(R.string.mcl_setting_renderer_plugin_selected, selected));
        int index = 0;
        for (String option : options) {
            radioButtons.get(index).setChecked(option.equals(selected));
            index++;
        }
    }

    private static void updateExpansion(LinearLayout optionsColumn, ImageView arrow, boolean expanded) {
        optionsColumn.setVisibility(expanded ? View.VISIBLE : View.GONE);
        arrow.animate().rotation(expanded ? 180f : 0f).setDuration(150).start();
    }

    private static GradientDrawable buildCardBackground(View host, int position) {
        Context context = host.getContext();
        float outer = dp(context, 28), inner = dp(context, 4);
        float topLeft, topRight, bottomRight, bottomLeft;
        switch (position) {
            case CARD_TOP:
                topLeft = topRight = outer; bottomRight = bottomLeft = inner; break;
            case CARD_BOTTOM:
                topLeft = topRight = inner; bottomRight = bottomLeft = outer; break;
            case CARD_SINGLE:
                topLeft = topRight = bottomRight = bottomLeft = outer; break;
            default:
                topLeft = topRight = bottomRight = bottomLeft = inner; break;
        }
        float[] radii = new float[8];
        radii[0] = radii[1] = topLeft;
        radii[2] = radii[3] = topRight;
        radii[4] = radii[5] = bottomRight;
        radii[6] = radii[7] = bottomLeft;
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadii(radii);
        background.setColor(MaterialColors.getColor(host, R.attr.colorSurfaceContainer, 0));
        return background;
    }

    @NonNull
    private static V2PluginEnvStore.Entry configEntry(Map<String, V2PluginEnvStore.Entry> config,
                                                      String key) {
        V2PluginEnvStore.Entry entry = config.get(key);
        if (entry == null) {
            entry = new V2PluginEnvStore.Entry();
            config.put(key, entry);
        }
        return entry;
    }

    private static boolean entryEnabled(Map<String, V2PluginEnvStore.Entry> config,
                                        String key, boolean def) {
        V2PluginEnvStore.Entry entry = config.get(key);
        return entry != null && entry.enabled != null ? entry.enabled : def;
    }

    private static String entryValue(Map<String, V2PluginEnvStore.Entry> config,
                                     String key, @Nullable String def) {
        V2PluginEnvStore.Entry entry = config.get(key);
        return entry != null && entry.value != null ? entry.value : def;
    }

    private static int dp(Context context, float value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }
}
