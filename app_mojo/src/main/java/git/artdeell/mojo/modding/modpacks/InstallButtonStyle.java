package git.artdeell.mojo.modding.modpacks;

import android.content.res.ColorStateList;

import androidx.annotation.NonNull;

import com.google.android.material.color.MaterialColors;
import com.kdt.mcgui.MineButton;

import git.artdeell.mojo.R;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;

/**
 * What an install button says and looks like: Install for a new version, Update / Downgrade when
 * another version of the same content is installed and Installed for the installed one. Shared
 * by the result cards of the browser and the version list of the detail screen.
 */
public final class InstallButtonStyle {
    private InstallButtonStyle() {}

    public enum State { INSTALL, INSTALLED, UPDATE, DOWNGRADE }

    /**
     * Versions are ordered newest first, so a lower index than the installed one is newer
     * (Update) and a higher one is older (Downgrade).
     */
    @NonNull
    public static State stateOf(@NonNull ModDetail detail, int position) {
        if (detail.isModpack || detail.installedVersionIndex < 0) return State.INSTALL;
        if (position == detail.installedVersionIndex) return State.INSTALLED;
        return position < detail.installedVersionIndex ? State.UPDATE : State.DOWNGRADE;
    }

    public static void apply(@NonNull MineButton button, @NonNull State state) {
        int label;
        int background;
        int foreground;
        switch (state) {
            case INSTALLED:
                label = R.string.mod_install_installed;
                background = com.google.android.material.R.attr.colorSurfaceContainerHighest;
                foreground = com.google.android.material.R.attr.colorOnSurfaceVariant;
                break;
            case DOWNGRADE:
                label = R.string.mod_install_downgrade;
                background = com.google.android.material.R.attr.colorSecondaryContainer;
                foreground = com.google.android.material.R.attr.colorOnSecondaryContainer;
                break;
            case UPDATE:
                label = R.string.mod_install_update;
                background = com.google.android.material.R.attr.colorPrimary;
                foreground = com.google.android.material.R.attr.colorOnPrimary;
                break;
            default:
                label = R.string.generic_install;
                background = com.google.android.material.R.attr.colorPrimary;
                foreground = com.google.android.material.R.attr.colorOnPrimary;
                break;
        }
        button.setText(label);
        button.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(button, background)));
        button.setTextColor(MaterialColors.getColor(button, foreground));
    }
}
