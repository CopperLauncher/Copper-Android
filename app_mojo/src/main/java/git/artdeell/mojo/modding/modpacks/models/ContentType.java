package git.artdeell.mojo.modding.modpacks.models;

/**
 * The kinds of content that "Browse Content" / "Manage Content" support. Mods, resource packs,
 * shader packs and datapacks go through the same search/install/update-check pipeline
 * (ModsSearchFragment, ManageModsFragment, InstalledModAdapter). Worlds are searched the same
 * way but are extracted into a folder when installed, and screenshots can only be managed.
 * This enum holds the few things that differ between them:
 *   - which folder the content lives in
 *   - what file extension its files use
 *   - the Modrinth `project_type` facet to search under, null when Modrinth has no such type
 *   - the CurseForge classId to search under, 0 when CurseForge has no such class
 */
public enum ContentType {
    MOD("mods", ".jar", "mod", 6),
    RESOURCE_PACK("resourcepacks", ".zip", "resourcepack", 12),
    // https://api.curseforge.com/v1/categories?gameId=432 — "Shaders" category id.
    SHADER_PACK("shaderpacks", ".zip", "shader", 6552),
    // Modrinth has no worlds, they are downloaded as a zip and extracted into the saves folder
    WORLD("saves", ".zip", null, 17),
    // Lives in <saves>/<world>/datapacks, the folder name here is the one inside a world
    DATAPACK("datapacks", ".zip", "datapack", 6945),
    // Manage only, there is nothing to download
    SCREENSHOT("screenshots", ".png", null, 0);

    /** Folder name under the instance's game directory (e.g. ".minecraft/mods"). */
    public final String folderName;
    /** File extension used for this content's files (e.g. ".jar", ".zip"). */
    public final String fileExtension;
    /** Modrinth `project_type` facet value, null when Modrinth doesn't have this content. */
    public final String modrinthType;
    /** CurseForge classId under the Minecraft game (id 432), 0 when CurseForge doesn't have it. */
    public final int curseforgeClassId;

    ContentType(String folderName, String fileExtension, String modrinthType, int curseforgeClassId) {
        this.folderName = folderName;
        this.fileExtension = fileExtension;
        this.modrinthType = modrinthType;
        this.curseforgeClassId = curseforgeClassId;
    }

    public boolean onModrinth() { return modrinthType != null; }

    public boolean onCurseforge() { return curseforgeClassId > 0; }

    /** Whether this content can be searched and installed from Modrinth or CurseForge. */
    public boolean isBrowsable() { return onModrinth() || onCurseforge(); }

    /** Whether the content is a flat list of files in a folder directly under the game directory. */
    public boolean isGameFolderFiles() {
        return this == MOD || this == RESOURCE_PACK || this == SHADER_PACK;
    }

    /** Inverse of {@link #modrinthType} — used to round-trip the selection through a Bundle. */
    public static ContentType fromModrinthType(String type) {
        if (type == null) return MOD;
        switch (type) {
            case "resourcepack": return RESOURCE_PACK;
            case "shader":       return SHADER_PACK;
            case "datapack":     return DATAPACK;
            default:             return MOD;
        }
    }
}
