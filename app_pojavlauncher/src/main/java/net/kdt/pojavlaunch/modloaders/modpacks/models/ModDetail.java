package net.kdt.pojavlaunch.modloaders.modpacks.models;


import androidx.annotation.NonNull;

import java.util.Arrays;

public class ModDetail extends ModItem {
    /* A cheap way to map from the front facing name to the underlying id */
    public String[] versionNames;
    public String [] mcVersionNames;
    public String[] versionUrls;
    /* SHA 1 hashes, null if a hash is unavailable */
    public String[] versionHashes;
    /* Per-version project ID that this version belongs to (Modrinth's version id) */
    public String[] versionIds;
    public Dependencies[][] dependencies;
    /* Index into the version arrays that matches a mod already present in the
     * current instance's mods folder (matched by SHA1 hash), or -1 if this
     * mod/project isn't currently installed. Resolved by the caller after
     * fetching the details, since hashing local files needs filesystem access
     * this model class doesn't have. Versions are ordered newest-first, so a
     * lower index than installedVersionIndex means "update available", and a
     * higher index means "downgrade". */
    public int installedVersionIndex = -1;
    /* Absolute path of the on-disk jar that matched installedVersionIndex
     * (set alongside it by the same resolution pass), or null if nothing is
     * installed. Used by the install/update/downgrade flow to remove the old
     * file once the new version has finished downloading, instead of leaving
     * both versions sitting in the mods folder side-by-side. */
    public String installedFilePath = null;

    public ModDetail(ModItem item, String[] versionNames, String[] mcVersionNames, String[] versionUrls, String[] hashes) {
        this(item, versionNames, null, mcVersionNames, versionUrls, hashes, null);
    }

    public ModDetail(ModItem item, String[] versionNames, String[] versionIds, String[] mcVersionNames, String[] versionUrls, String[] hashes,
                     Dependencies[][] dependencies) {
        super(item.apiSource, item.isModpack, item.id, item.title, item.description, item.imageUrl);
        this.isRestricted = item.isRestricted;
        this.websiteUrl = item.websiteUrl;
        this.versionNames = versionNames;
        this.mcVersionNames = mcVersionNames;
        this.versionIds = versionIds;
        this.versionUrls = versionUrls;
        this.versionHashes = hashes;
        this.dependencies = dependencies;

        // Add the mc version to the version model
        for (int i=0; i<versionNames.length; i++){
            if (mcVersionNames[i] != null && !versionNames[i].contains(mcVersionNames[i]))
                versionNames[i] += " - " + mcVersionNames[i];
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "ModDetail{" +
                "versionNames=" + Arrays.toString(versionNames) +
                ", mcVersionNames=" + Arrays.toString(mcVersionNames) +
                ", versionIds=" + Arrays.toString(versionIds) +
                ", versionUrls=" + Arrays.toString(versionUrls) +
                ", id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", imageUrl='" + imageUrl + '\'' +
                ", apiSource=" + apiSource +
                ", isModpack=" + isModpack +
                '}';
    }

    public static class Dependencies{
        public String project_id; // the main id in item.id
        public String version_id;
        public String file_name;
        public String dependency_type;
        public Dependencies(String project_id, String version_id, String file_name, String dependency_type){
            this.project_id = project_id;
            this.version_id = version_id;
            this.file_name = file_name;
            this.dependency_type = dependency_type;
        }
    }

}
