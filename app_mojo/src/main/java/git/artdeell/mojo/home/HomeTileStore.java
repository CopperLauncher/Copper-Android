package git.artdeell.mojo.home;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import git.artdeell.mojo.R;

/** Keeps the tiles of the home screen (right) and of the sidebar (left) in the default preferences */
public final class HomeTileStore {
    public static final int COLUMNS = 12;
    public static final int SIDEBAR_COLUMNS = 6;
    public static final int MIN_ROWS = 4;
    private static final int MAX_TILES = 128;
    private static final int FORMAT_VERSION = 3;
    private static final String PREF_KEY = "home_tiles_v2";
    private static final String PREF_SIDEBAR_KEY = "sidebar_tiles_v1";
    /** Left over from the version where the sidebar buttons were only reordered */
    private static final String PREF_OLD_SIDEBAR_ORDER = "sidebar_order_v1";

    private HomeTileStore() {}

    /** A layout that was exported or imported: the tiles of the home screen and, if it has them, of the sidebar */
    public static final class Layout {
        @NonNull public final List<HomeTile> tiles;
        @Nullable public final List<HomeTile> sidebarTiles;

        Layout(@NonNull List<HomeTile> tiles, @Nullable List<HomeTile> sidebarTiles) {
            this.tiles = tiles;
            this.sidebarTiles = sidebarTiles;
        }
    }

    // ─── Saved layouts ───────────────────────────────────────────────────────

    @NonNull
    public static List<HomeTile> load(@NonNull Context context) {
        List<HomeTile> loaded = read(context, PREF_KEY, COLUMNS);
        return loaded != null ? loaded : defaults();
    }

    @NonNull
    public static List<HomeTile> loadSidebar(@NonNull Context context) {
        List<HomeTile> loaded = read(context, PREF_SIDEBAR_KEY, SIDEBAR_COLUMNS);
        return loaded != null ? loaded : sidebarDefaults(context);
    }

    @Nullable
    private static List<HomeTile> read(Context context, String key, int columns) {
        String json = PreferenceManager.getDefaultSharedPreferences(context).getString(key, null);
        if (json == null) return null;
        try {
            List<HomeTile> loaded = new Gson().fromJson(json, new TypeToken<List<HomeTile>>() {}.getType());
            return loaded == null ? null : sanitize(loaded, columns);
        } catch (JsonParseException e) {
            return null;
        }
    }

    public static void save(@NonNull Context context, @NonNull List<HomeTile> tiles) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(PREF_KEY, new Gson().toJson(tiles)).apply();
    }

    public static void saveSidebar(@NonNull Context context, @NonNull List<HomeTile> tiles) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(PREF_SIDEBAR_KEY, new Gson().toJson(tiles)).apply();
    }

    /** Back to the default tiles on both sides */
    public static void reset(@NonNull Context context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .remove(PREF_KEY).remove(PREF_SIDEBAR_KEY).remove(PREF_OLD_SIDEBAR_ORDER).apply();
    }

    // ─── Defaults ────────────────────────────────────────────────────────────

    /** Wiki and Discord, small like the two buttons the home screen had before it had tiles */
    @NonNull
    public static List<HomeTile> defaults() {
        List<HomeTile> tiles = new ArrayList<>();
        tiles.add(new HomeTile(HomeTileType.WIKI, 0, 0, COLUMNS / 2, 1));
        tiles.add(new HomeTile(HomeTileType.DISCORD, COLUMNS / 2, 0, COLUMNS / 2, 1));
        return tiles;
    }

    /** The sidebar as it was before it had tiles: two titles, a line and the buttons under them */
    @NonNull
    public static List<HomeTile> sidebarDefaults(@NonNull Context context) {
        List<HomeTile> tiles = new ArrayList<>();
        int w = SIDEBAR_COLUMNS;
        HomeTile quick = new HomeTile(HomeTileType.TEXT, 0, 0, w, 1);
        quick.label = context.getString(R.string.mcl_section_quick_actions);
        tiles.add(quick);
        tiles.add(new HomeTile(HomeTileType.BROWSE_CONTENT, 0, 1, w, 1));
        tiles.add(new HomeTile(HomeTileType.CUSTOM_CONTROLS, 0, 2, w, 1));
        tiles.add(new HomeTile(HomeTileType.SEPARATOR, 0, 3, w, 1));
        HomeTile tools = new HomeTile(HomeTileType.TEXT, 0, 4, w, 1);
        tools.label = context.getString(R.string.mcl_section_instance_tools);
        tiles.add(tools);
        tiles.add(new HomeTile(HomeTileType.MANAGE_CONTENT, 0, 5, w, 1));
        tiles.add(new HomeTile(HomeTileType.OPEN_FOLDER, 0, 6, w, 1));
        tiles.add(new HomeTile(HomeTileType.INSTALL_JAR, 0, 7, w, 1));
        tiles.add(new HomeTile(HomeTileType.SHARE_LOGS, 0, 8, w, 1));
        return tiles;
    }

    /** Drops what can't be shown: unknown types, tiles without an id, nonsense sizes */
    @NonNull
    private static List<HomeTile> sanitize(@NonNull List<HomeTile> loaded, int columns) {
        List<HomeTile> tiles = new ArrayList<>(loaded.size());
        for (HomeTile tile : loaded) {
            if (tiles.size() >= MAX_TILES) break;
            if (tile == null || tile.getType() == null) continue;
            if (tile.id == null || tile.id.isEmpty()) tile.id = UUID.randomUUID().toString();
            tile.w = Math.max(1, Math.min(tile.w, columns));
            tile.h = Math.max(1, tile.h);
            tile.x = Math.max(0, tile.x);
            tile.y = Math.max(0, tile.y);
            tiles.add(tile);
        }
        return tiles;
    }

    // ─── Share / import ──────────────────────────────────────────────────────

    @NonNull
    public static String toJson(@NonNull List<HomeTile> tiles, @NonNull List<HomeTile> sidebarTiles) {
        Gson gson = new Gson();
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("columns", COLUMNS);
        root.add("tiles", gson.toJsonTree(tiles));
        root.addProperty("sidebarColumns", SIDEBAR_COLUMNS);
        root.add("sidebarTiles", gson.toJsonTree(sidebarTiles));
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    /**
     * Reads an exported layout. A plain array of tiles is accepted as well, and a layout made for
     * another number of columns is scaled to the one of the side it goes to.
     * @return the layout, null if the text is not a layout
     */
    @Nullable
    public static Layout parse(@Nullable String json) {
        if (json == null) return null;
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonElement tilesElement;
            JsonElement sidebarElement = null;
            int columns = COLUMNS, sidebarColumns = SIDEBAR_COLUMNS;
            if (root.isJsonArray()) {
                tilesElement = root;
            } else if (root.isJsonObject()) {
                JsonObject object = root.getAsJsonObject();
                tilesElement = object.get("tiles");
                sidebarElement = object.get("sidebarTiles");
                columns = intOr(object.get("columns"), COLUMNS);
                sidebarColumns = intOr(object.get("sidebarColumns"), SIDEBAR_COLUMNS);
            } else {
                return null;
            }
            List<HomeTile> tiles = readTiles(tilesElement, columns, COLUMNS);
            if (tiles == null) return null;
            // The sidebar is optional: older exports have none, the sidebar then stays as it is
            List<HomeTile> sidebar = readTiles(sidebarElement, sidebarColumns, SIDEBAR_COLUMNS);
            return new Layout(tiles, sidebar);
        } catch (RuntimeException e) {
            // JsonParseException and wrong typed fields (ClassCast, IllegalState, NumberFormat)
            return null;
        }
    }

    private static int intOr(@Nullable JsonElement element, int fallback) {
        if (element == null || !element.isJsonPrimitive()) return fallback;
        return Math.max(1, element.getAsInt());
    }

    @Nullable
    private static List<HomeTile> readTiles(@Nullable JsonElement element, int fromColumns, int toColumns) {
        if (element == null || !element.isJsonArray()) return null;
        JsonArray array = element.getAsJsonArray();
        // Exports of the first version had the sidebar as a list of names, not tiles
        if (array.size() > 0 && !array.get(0).isJsonObject()) return null;
        List<HomeTile> loaded = new Gson().fromJson(array, new TypeToken<List<HomeTile>>() {}.getType());
        if (loaded == null) return null;
        List<HomeTile> tiles = sanitize(loaded, Math.max(fromColumns, toColumns));
        if (tiles.isEmpty() && !loaded.isEmpty()) return null;
        if (fromColumns != toColumns) {
            float scale = toColumns / (float) fromColumns;
            for (HomeTile tile : tiles) {
                tile.x = Math.round(tile.x * scale);
                tile.w = Math.max(1, Math.round(tile.w * scale));
            }
        }
        return tiles;
    }
}
