package net.kdt.pojavlaunch.modloaders.modpacks.models;

import org.jetbrains.annotations.Nullable;

/**
 * Search filters, passed to APIs
 */
public class SearchFilters {
    public boolean isModpack;
    public String name;
    @Nullable public String mcVersion;
    /** Mod loader filter: "fabric", "forge", "quilt", "neoforge", or null/empty for any */
    @Nullable public String modLoader;
    /**
     * Which search engine(s) to query: {@link Constants#ENGINE_MODRINTH},
     * {@link Constants#ENGINE_CURSEFORGE}, or {@link Constants#ENGINE_BOTH}.
     * Defaults to Modrinth only — CurseForge's API is noticeably slower, so
     * it's opt-in per search rather than queried by default.
     */
    public int engine = Constants.ENGINE_MODRINTH;

    /**
     * Which kind of content to search for. Defaults to MOD; isModpack (when
     * true) still takes priority over this for the existing modpack-browsing
     * flow, since modpacks aren't one of the three ContentType values.
     */
    public ContentType contentType = ContentType.MOD;

}