package git.artdeell.mojo.home;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import git.artdeell.mojo.R;

/** Every kind of tile the home screen can show */
public enum HomeTileType {
    WIKI(R.string.mcl_tab_wiki, R.drawable.ic_px_book, 4, 1, false, true),
    DISCORD(R.string.mcl_button_social_media, R.drawable.ic_social_media, 4, 1, false, true),
    CONTROLLER_SETTINGS(R.string.home_tile_controller_settings, R.drawable.ic_px_gestures, 4, 1, false, true),
    CUSTOM_CONTROLS(R.string.mcl_option_customcontrol, R.drawable.ic_px_gamepad, 4, 1, false, true),
    BROWSE_CONTENT(R.string.mcl_button_mod_store, R.drawable.ic_add, 4, 1, false, true),
    MANAGE_CONTENT(R.string.mcl_button_manage_mods, R.drawable.ic_folder_managed, 4, 1, false, true),
    OPEN_FOLDER(R.string.mcl_button_open_directory, R.drawable.ic_px_folder, 4, 1, false, true),
    INSTALL_JAR(R.string.main_install_jar_file, R.drawable.ic_px_java_run, 4, 1, false, true),
    SHARE_LOGS(R.string.main_share_logs, R.drawable.ic_px_sharelog, 4, 1, false, true),
    INSTANCE(R.string.home_tile_instance, R.drawable.ic_px_play, 6, 2, false, false),
    TEXT(R.string.home_tile_text, R.drawable.ic_px_drag_handle, 6, 1, true, false),
    SEPARATOR(R.string.home_tile_separator, R.drawable.ic_px_drag_handle, HomeTileStore.COLUMNS, 1, true, false);

    @StringRes public final int label;
    @DrawableRes public final int icon;
    public final int defaultW;
    public final int defaultH;
    /** The height can't be changed, the tile is a thin line */
    public final boolean lockHeight;
    /** Only one tile of the type makes sense */
    public final boolean unique;

    HomeTileType(int label, int icon, int defaultW, int defaultH, boolean lockHeight, boolean unique) {
        this.label = label;
        this.icon = icon;
        this.defaultW = defaultW;
        this.defaultH = defaultH;
        this.lockHeight = lockHeight;
        this.unique = unique;
    }

    /** @return the type with this name, null when it is not known (a tile saved by another version) */
    @Nullable
    public static HomeTileType fromName(@Nullable String name) {
        if (name == null) return null;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
