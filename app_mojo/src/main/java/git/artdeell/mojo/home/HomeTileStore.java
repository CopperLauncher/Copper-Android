package git.artdeell.mojo.home;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

/** Keeps the layout of the home screen and the order of the sidebar in the default preferences */
public final class HomeTileStore {
    public static final int COLUMNS = 12;
    public static final int MIN_ROWS = 4;
    private static final int MAX_TILES = 64;
    private static final int FORMAT_VERSION = 2;
    private static final String PREF_KEY = "home_tiles_v2";
    private static final String PREF_SIDEBAR_KEY = "sidebar_order_v1";

    private HomeTileStore() {}

    /** A layout that was exported or imported: the tiles and the order of the sidebar buttons */
    public static final class Layout {
        @NonNull public final List<HomeTile> tiles;
        @NonNull public final List<String> sidebar;

        Layout(@NonNull List<HomeTile> tiles, @NonNull List<String> sidebar) {
            this.tiles = tiles;
            this.sidebar = sidebar;
        }
    }

    @NonNull
    public static List<HomeTile> load(@NonNull Context context) {
        String json = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, null);
        if (json == null) return defaults();
        try {
            List<HomeTile> loaded = new Gson().fromJson(json, new TypeToken<List<HomeTile>>() {}.getType());
            if (loaded == null) return defaults();
            return sanitize(loaded);
        } catch (JsonParseException e) {
            return defaults();
        }
    }

    public static void save(@NonNull Context context, @NonNull List<HomeTile> tiles) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(PREF_KEY, new Gson().toJson(tiles)).apply();
    }

    @NonNull
    public static List<String> loadSidebarOrder(@NonNull Context context) {
        String saved = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_SIDEBAR_KEY, "");
        List<String> order = new ArrayList<>();
        for (String key : saved.split(",")) {
            if (!key.isEmpty()) order.add(key);
        }
        return order;
    }

    public static void saveSidebarOrder(@NonNull Context context, @NonNull List<String> order) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(PREF_SIDEBAR_KEY, android.text.TextUtils.join(",", order)).apply();
    }

    /** Back to the default tiles and the default order of the sidebar */
    public static void reset(@NonNull Context context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .remove(PREF_KEY).remove(PREF_SIDEBAR_KEY).apply();
    }

    /** Wiki and Discord, small like the two buttons the home screen had before it had tiles */
    @NonNull
    public static List<HomeTile> defaults() {
        List<HomeTile> tiles = new ArrayList<>();
        tiles.add(new HomeTile(HomeTileType.WIKI, 0, 0, COLUMNS / 2, 1));
        tiles.add(new HomeTile(HomeTileType.DISCORD, COLUMNS / 2, 0, COLUMNS / 2, 1));
        return tiles;
    }

    /** Drops what can't be shown: unknown types, tiles without an id, nonsense sizes */
    @NonNull
    private static List<HomeTile> sanitize(@NonNull List<HomeTile> loaded) {
        List<HomeTile> tiles = new ArrayList<>(loaded.size());
        for (HomeTile tile : loaded) {
            if (tiles.size() >= MAX_TILES) break;
            if (tile == null || tile.getType() == null) continue;
            if (tile.id == null || tile.id.isEmpty()) tile.id = java.util.UUID.randomUUID().toString();
            tile.w = Math.max(1, Math.min(tile.w, COLUMNS));
            tile.h = Math.max(1, tile.h);
            tile.x = Math.max(0, tile.x);
            tile.y = Math.max(0, tile.y);
            tiles.add(tile);
        }
        return tiles;
    }

    // ─── Share / import ──────────────────────────────────────────────────────

    @NonNull
    public static String toJson(@NonNull List<HomeTile> tiles, @NonNull List<String> sidebar) {
        Gson gson = new Gson();
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("columns", COLUMNS);
        root.add("tiles", gson.toJsonTree(tiles));
        root.add("sidebar", gson.toJsonTree(sidebar));
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    /**
     * Reads an exported layout. A plain array of tiles is accepted as well, and a layout made for
     * another number of columns is scaled to this one.
     * @return the layout, null if the text is not a layout
     */
    @Nullable
    public static Layout parse(@Nullable String json) {
        if (json == null) return null;
        try {
            Gson gson = new Gson();
            JsonElement root = JsonParser.parseString(json);
            JsonElement tilesElement;
            List<String> sidebar = new ArrayList<>();
            int columns = COLUMNS;
            if (root.isJsonArray()) {
                tilesElement = root;
            } else if (root.isJsonObject()) {
                JsonObject object = root.getAsJsonObject();
                tilesElement = object.get("tiles");
                if (object.has("columns") && object.get("columns").isJsonPrimitive()) {
                    columns = Math.max(1, object.get("columns").getAsInt());
                }
                JsonElement sidebarElement = object.get("sidebar");
                if (sidebarElement != null && sidebarElement.isJsonArray()) {
                    JsonArray array = sidebarElement.getAsJsonArray();
                    for (JsonElement element : array) {
                        if (element.isJsonPrimitive()) sidebar.add(element.getAsString());
                    }
                }
            } else {
                return null;
            }
            if (tilesElement == null || !tilesElement.isJsonArray()) return null;
            List<HomeTile> loaded = gson.fromJson(tilesElement, new TypeToken<List<HomeTile>>() {}.getType());
            if (loaded == null) return null;
            List<HomeTile> tiles = sanitize(loaded);
            if (tiles.isEmpty() && !loaded.isEmpty()) return null;
            if (columns != COLUMNS) {
                float scale = COLUMNS / (float) columns;
                for (HomeTile tile : tiles) {
                    tile.x = Math.round(tile.x * scale);
                    tile.w = Math.max(1, Math.round(tile.w * scale));
                }
            }
            return new Layout(tiles, sidebar);
        } catch (RuntimeException e) {
            // JsonParseException and wrong typed fields (ClassCast, IllegalState, NumberFormat)
            return null;
        }
    }
}
