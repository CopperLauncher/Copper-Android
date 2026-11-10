package git.artdeell.mojo.home;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

/** Keeps the layout of the home screen in the default preferences */
public final class HomeTileStore {
    public static final int COLUMNS = 8;
    public static final int MIN_ROWS = 3;
    private static final String PREF_KEY = "home_tiles_v1";

    private HomeTileStore() {}

    @NonNull
    public static List<HomeTile> load(@NonNull Context context) {
        String json = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, null);
        if (json == null) return defaults();
        try {
            List<HomeTile> loaded = new Gson().fromJson(json, new TypeToken<List<HomeTile>>() {}.getType());
            if (loaded == null) return defaults();
            List<HomeTile> tiles = new ArrayList<>(loaded.size());
            for (HomeTile tile : loaded) {
                if (tile != null && tile.id != null && tile.getType() != null) tiles.add(tile);
            }
            return tiles;
        } catch (JsonParseException e) {
            return defaults();
        }
    }

    public static void save(@NonNull Context context, @NonNull List<HomeTile> tiles) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(PREF_KEY, new Gson().toJson(tiles)).apply();
    }

    public static void reset(@NonNull Context context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(PREF_KEY).apply();
    }

    /** Fits in the smallest grid (3 rows) */
    @NonNull
    public static List<HomeTile> defaults() {
        List<HomeTile> tiles = new ArrayList<>();
        tiles.add(new HomeTile(HomeTileType.WIKI, 0, 0, 2, 2));
        tiles.add(new HomeTile(HomeTileType.DISCORD, 2, 0, 2, 2));
        tiles.add(new HomeTile(HomeTileType.CONTROLLER_SETTINGS, 4, 0, 2, 2));
        HomeTile instance = new HomeTile(HomeTileType.INSTANCE, 6, 0, 2, 2);
        instance.instanceKey = "";
        tiles.add(instance);
        tiles.add(new HomeTile(HomeTileType.SEPARATOR, 0, 2, COLUMNS, 1));
        return tiles;
    }
}
