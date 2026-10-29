package git.artdeell.mojo.modding.modpacks.models;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * A category of content (Adventure, Technology, ...) that search results can be filtered by.
 * Modrinth and CurseForge name their categories differently, one entry carries the id of each
 * source that has it: Modrinth filters by the category slug, CurseForge by a numeric id.
 */
public final class ContentCategory {
    /** What the user sees */
    @NonNull public final String name;
    /** Modrinth category slug, null when Modrinth has no such category */
    @Nullable public final String modrinthId;
    /** CurseForge category id, 0 when CurseForge has no such category */
    public final int curseforgeId;

    public ContentCategory(@NonNull String name, @Nullable String modrinthId, int curseforgeId) {
        this.name = name;
        this.modrinthId = modrinthId;
        this.curseforgeId = curseforgeId;
    }

    /** Names that only differ in case, spaces or punctuation are the same category */
    @NonNull
    public String getKey() {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** The same category as seen by another source */
    @NonNull
    public ContentCategory merge(@NonNull ContentCategory other) {
        return new ContentCategory(name,
                modrinthId != null ? modrinthId : other.modrinthId,
                curseforgeId != 0 ? curseforgeId : other.curseforgeId);
    }

    /** "game-mechanics" -> "Game Mechanics", slugs ending in a dash (like "8x-") are kept as they are */
    @NonNull
    public static String prettify(@NonNull String slug) {
        String spaced = slug.endsWith("-") ? slug : slug.replace('-', ' ');
        StringBuilder out = new StringBuilder(spaced.length());
        boolean wordStart = true;
        for (char c : spaced.toCharArray()) {
            out.append(wordStart ? Character.toUpperCase(c) : c);
            wordStart = c == ' ';
        }
        return out.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ContentCategory && getKey().equals(((ContentCategory) o).getKey());
    }

    @Override
    public int hashCode() {
        return getKey().hashCode();
    }
}
