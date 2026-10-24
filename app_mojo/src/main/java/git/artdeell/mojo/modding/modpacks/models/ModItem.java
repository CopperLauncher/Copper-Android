package git.artdeell.mojo.modding.modpacks.models;

import androidx.annotation.NonNull;

public class ModItem extends ModSource {

    public String id;
    public String title;
    public String description;
    public String imageUrl;
    /** True if the mod author blocked third-party distribution (CF allowModDistribution=false) */
    public boolean isRestricted;
    /** Direct website URL for the mod page (used for CF restricted mods) */
    public String websiteUrl;
    /** Total downloads, -1 if the API didn't say */
    public long downloads = -1;
    /** Modrinth follows / CurseForge likes, -1 if the API didn't say */
    public long favorites = -1;

    public ModItem(int apiSource, boolean isModpack, String id, String title, String description, String imageUrl) {
        this.apiSource = apiSource;
        this.isModpack = isModpack;
        this.id = id;
        this.title = title;
        this.description = description;
        this.imageUrl = imageUrl;
    }

    @NonNull
    @Override
    public String toString() {
        return "ModItem{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", imageUrl='" + imageUrl + '\'' +
                ", apiSource=" + apiSource +
                ", isModpack=" + isModpack +
                '}';
    }

    public String getIconCacheTag() {
        return apiSource+"_"+id;
    }
}