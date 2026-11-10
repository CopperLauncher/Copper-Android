package git.artdeell.mojo.home;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

/** One tile of the home screen, positioned and sized in grid cells. Serialized with Gson */
public class HomeTile {
    public String id;
    /** Name of a {@link HomeTileType} */
    public String type;
    public int x, y, w, h;
    /** Instance tiles only: the instance key, empty follows the selected instance */
    public String instanceKey;

    public HomeTile() {
    }

    public HomeTile(@NonNull HomeTileType type, int x, int y, int w, int h) {
        this.id = UUID.randomUUID().toString();
        this.type = type.name();
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public static HomeTile create(@NonNull HomeTileType type) {
        return new HomeTile(type, 0, 0, type.defaultW, type.defaultH);
    }

    @Nullable
    public HomeTileType getType() {
        return HomeTileType.fromName(type);
    }
}
