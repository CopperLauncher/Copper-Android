package net.kdt.pojavlaunch.modloaders.modpacks.models;

public class Constants {
    private Constants(){}

    /** Types of modpack apis */
    public static final int SOURCE_MODRINTH = 0x0;
    public static final int SOURCE_CURSEFORGE = 0x1;
    public static final int SOURCE_TECHNIC = 0x2;

    /**
     * Which search engine(s) to actually query — set via the "Source" picker
     * in the search filter dialog. Distinct from SOURCE_MODRINTH/SOURCE_CURSEFORGE
     * above, which tag where a given *result* came from; these instead say
     * which engines should be asked in the first place.
     */
    public static final int ENGINE_MODRINTH = 0x0;
    public static final int ENGINE_CURSEFORGE = 0x1;
    public static final int ENGINE_BOTH = 0x2;

    /** Modrinth api, file environments */
    public static final String MODRINTH_FILE_ENV_REQUIRED = "required";
    public static final String MODRINTH_FILE_ENV_OPTIONAL = "optional";
    public static final String MODRINTH_FILE_ENV_UNSUPPORTED = "unsupported";

}