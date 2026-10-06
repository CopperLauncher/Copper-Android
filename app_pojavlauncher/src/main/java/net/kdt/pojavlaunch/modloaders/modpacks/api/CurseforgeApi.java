package net.kdt.pojavlaunch.modloaders.modpacks.api;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kdt.mcgui.ProgressLayout;
import git.artdeell.mojo.R;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.downloader.AcquireableTaskMetadata;
import net.kdt.pojavlaunch.downloader.Downloader;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.modloaders.ForgelikeUtils;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.FabriclikeLoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.ForgelikeLoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.LoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.CurseManifest;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.utils.GsonJsonUtils;
import net.kdt.pojavlaunch.utils.ZipUtils;

public class CurseforgeApi implements ModpackApi{
    private static final Pattern sMcVersionPattern = Pattern.compile("([0-9]+)\\.([0-9]+)\\.?([0-9]+)?");
    private static final int ALGO_SHA_1 = 1;
    // Stolen from
    // https://github.com/AnzhiZhang/CurseForgeModpackDownloader/blob/6cb3f428459f0cc8f444d16e54aea4cd1186fd7b/utils/requester.py#L93
    private static final int CURSEFORGE_MINECRAFT_GAME_ID = 432;
    private static final int CURSEFORGE_MODPACK_CLASS_ID = 4471;
    // https://api.curseforge.com/v1/categories?gameId=432 and search for "Mods" (case-sensitive)
    private static final int CURSEFORGE_MOD_CLASS_ID = 6;
    private static final int CURSEFORGE_SORT_RELEVANCY = 1;
    private static final int CURSEFORGE_PAGINATION_SIZE = 50;
    private static final int CURSEFORGE_PAGINATION_END_REACHED = -1;
    private static final int CURSEFORGE_PAGINATION_ERROR = -2;

    private final ApiHandler mApiHandler;
    public CurseforgeApi(String apiKey) {
        mApiHandler = new ApiHandler("https://api.curseforge.com/v1", apiKey);
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        CurseforgeSearchResult curseforgeSearchResult = (CurseforgeSearchResult) previousPageResult;

        HashMap<String, Object> params = new HashMap<>();
        params.put("gameId", CURSEFORGE_MINECRAFT_GAME_ID);
        params.put("classId", searchFilters.isModpack
                ? CURSEFORGE_MODPACK_CLASS_ID : searchFilters.contentType.curseforgeClassId);
        params.put("searchFilter", searchFilters.name);
        params.put("sortField", CURSEFORGE_SORT_RELEVANCY);
        params.put("sortOrder", "desc");
        if(searchFilters.mcVersion != null && !searchFilters.mcVersion.isEmpty())
            params.put("gameVersion", searchFilters.mcVersion);
        if(searchFilters.modLoader != null && !searchFilters.modLoader.isEmpty()) {
            // CF modLoaderType: 1=Forge, 4=Fabric, 5=Quilt, 6=NeoForge
            int modLoaderType = 0;
            switch(searchFilters.modLoader.toLowerCase()) {
                case "forge":    modLoaderType = 1; break;
                case "fabric":   modLoaderType = 4; break;
                case "quilt":    modLoaderType = 5; break;
                case "neoforge": modLoaderType = 6; break;
            }
            if(modLoaderType != 0) params.put("modLoaderType", modLoaderType);
        }
        if(previousPageResult != null)
            params.put("index", curseforgeSearchResult.previousOffset);

        JsonObject response = mApiHandler.get("mods/search", params, JsonObject.class);
        if(response == null) return null;
        JsonArray dataArray = response.getAsJsonArray("data");
        if(dataArray == null) return null;
        JsonObject paginationInfo = response.getAsJsonObject("pagination");
        ArrayList<ModItem> modItemList = new ArrayList<>(dataArray.size());
        for(int i = 0; i < dataArray.size(); i++) {
            JsonObject dataElement = dataArray.get(i).getAsJsonObject();
            JsonElement allowModDistribution = dataElement.get("allowModDistribution");
            // Gson automatically casts null to false, which leans to issues
            // So, only check the distribution flag if it is non-null
            boolean restricted = !allowModDistribution.isJsonNull() && !allowModDistribution.getAsBoolean();
            // For modpacks, skip restricted entries entirely (same as before)
            // For individual mods, keep them so we can show the CF website dialog
            if (restricted && searchFilters.isModpack) {
                Log.i("CurseforgeApi", "Skipping modpack "+dataElement.get("name").getAsString() + " because curseforge sucks");
                continue;
            }
            JsonObject logo = dataElement.getAsJsonObject("logo");
            String thumbnailUrl = (logo != null && logo.has("thumbnailUrl") && !logo.get("thumbnailUrl").isJsonNull())
                    ? logo.get("thumbnailUrl").getAsString() : "";
            ModItem modItem = new ModItem(Constants.SOURCE_CURSEFORGE,
                    searchFilters.isModpack,
                    dataElement.get("id").getAsString(),
                    dataElement.get("name").getAsString(),
                    dataElement.get("summary").getAsString(),
                    thumbnailUrl);
            modItem.isRestricted = restricted;
            // Capture the mod page URL from CF API for use in restriction dialog
            JsonObject links = dataElement.getAsJsonObject("links");
            if (links != null && links.has("websiteUrl") && !links.get("websiteUrl").isJsonNull()) {
                modItem.websiteUrl = links.get("websiteUrl").getAsString();
            } else {
                // Fallback using slug if available, otherwise numeric id
                String slug = dataElement.has("slug") && !dataElement.get("slug").isJsonNull()
                        ? dataElement.get("slug").getAsString() : modItem.id;
                modItem.websiteUrl = "https://www.curseforge.com/minecraft/mc-mods/" + slug;
            }
            modItemList.add(modItem);
        }
        if(curseforgeSearchResult == null) curseforgeSearchResult = new CurseforgeSearchResult();
        curseforgeSearchResult.results = modItemList.toArray(new ModItem[0]);
        curseforgeSearchResult.totalResultCount = paginationInfo.get("totalCount").getAsInt();
        curseforgeSearchResult.previousOffset += dataArray.size();
        return curseforgeSearchResult;

    }

    @Override
    public ModDetail getModDetails(ModItem item) {
        return getModDetails(item, null, null);
    }

    public ModDetail getModDetails(ModItem item, String filterMcVersion) {
        return getModDetails(item, filterMcVersion, null);
    }

    /**
     * @param filterMcVersion only return files tagged with this MC version (e.g. "1.20.1")
     * @param filterLoader    only return files tagged with this loader (e.g. "fabric",
     *                        "forge", "quilt", "neoforge"). CurseForge doesn't split loader
     *                        into its own field on the file object — it's just another
     *                        string mixed into the same "gameVersions" array as the MC
     *                        version tags (e.g. ["1.20.1", "Fabric", "Client"]) — so without
     *                        this filter, files built for every loader are shown together.
     */
    public ModDetail getModDetails(ModItem item, String filterMcVersion, String filterLoader) {
        fillInMissingModItemData(item);
        // Short-circuit for restricted mods — no point fetching versions
        if (item.isRestricted) {
            return new ModDetail(item,
                    new String[]{"Blocked by the author!"},
                    new String[]{null},
                    new String[]{null},
                    new String[]{null});
        }
        ArrayList<JsonObject> allModDetails = new ArrayList<>();
        int index = 0;
        while(index != CURSEFORGE_PAGINATION_END_REACHED &&
                index != CURSEFORGE_PAGINATION_ERROR) {
            index = getPaginatedDetails(allModDetails, index, item.id);
        }
        if(index == CURSEFORGE_PAGINATION_ERROR) return null;

        // Filter by MC version if specified
        if (filterMcVersion != null && !filterMcVersion.isEmpty()) {
            ArrayList<JsonObject> filtered = new ArrayList<>();
            for (JsonObject v : allModDetails) {
                JsonArray gameVersions = v.getAsJsonArray("gameVersions");
                for (JsonElement el : gameVersions) {
                    if (filterMcVersion.equals(el.getAsString())) {
                        filtered.add(v);
                        break;
                    }
                }
            }
            allModDetails = filtered;
        }

        // Filter by loader if specified. Same "gameVersions" array, just matched
        // case-insensitively against the loader name instead of an MC version.
        if (filterLoader != null && !filterLoader.isEmpty()) {
            ArrayList<JsonObject> filtered = new ArrayList<>();
            for (JsonObject v : allModDetails) {
                JsonArray gameVersions = v.getAsJsonArray("gameVersions");
                for (JsonElement el : gameVersions) {
                    if (filterLoader.equalsIgnoreCase(el.getAsString())) {
                        filtered.add(v);
                        break;
                    }
                }
            }
            allModDetails = filtered;
        }

        int length = allModDetails.size();

        // Check if ALL versions have null downloadUrl (fully restricted mod)
        boolean allRestricted = length > 0;
        for (int i = 0; i < length; i++) {
            JsonElement url = allModDetails.get(i).get("downloadUrl");
            if (url != null && !url.isJsonNull()) {
                allRestricted = false;
                break;
            }
        }
        if (allRestricted || length == 0) {
            // All versions restricted - return a ModDetail with one entry that signals this.
            // The version name shown in spinner makes it clear, tapping Install shows CF dialog.
            return new ModDetail(item,
                    new String[]{"Blocked by the author! By clicking the install button it will open the CurseForge page."},
                    new String[]{null},
                    new String[]{null},
                    new String[]{null});
        }

        String[] versionNames = new String[length];
        String[] versionIds = new String[length];
        String[] mcVersionNames = new String[length];
        String[] versionUrls = new String[length];
        String[] hashes = new String[length];
        ModDetail.Dependencies[][] dependencies = new ModDetail.Dependencies[length][];
        for(int i = 0; i < allModDetails.size(); i++) {
            JsonObject modDetail = allModDetails.get(i);
            versionNames[i] = modDetail.get("displayName").getAsString();
            versionIds[i] = modDetail.get("id").getAsString();
            JsonElement downloadUrl = modDetail.get("downloadUrl");
            if (downloadUrl == null || downloadUrl.isJsonNull()) {
                versionUrls[i] = null;
            } else {
                versionUrls[i] = downloadUrl.getAsString();
            }

            JsonArray gameVersions = modDetail.getAsJsonArray("gameVersions");
            try {
                JsonArray dependenciesJsonArray = modDetail.getAsJsonArray("dependencies");
                dependencies[i] = new ModDetail.Dependencies[dependenciesJsonArray.size()];
                for (int i1 = 0; i1 < dependenciesJsonArray.size(); ++i1) {
                    JsonObject obj = dependenciesJsonArray.get(i1).getAsJsonObject();
                    dependencies[i][i1] = new ModDetail.Dependencies(
                            GsonJsonUtils.getStringSafe(obj, "modId"),
                            null,
                            null, // These two are only present on modrinth
                            GsonJsonUtils.getStringSafe(obj, "relationType")
                    );
                }
            } catch (Exception ignored) {}
            for(JsonElement jsonElement : gameVersions) {
                String gameVersion = jsonElement.getAsString();
                if(!sMcVersionPattern.matcher(gameVersion).matches()) {
                    continue;
                }
                mcVersionNames[i] = gameVersion;
                break;
            }

            hashes[i] = getSha1FromModData(modDetail);
        }
        return new ModDetail(item, versionNames, versionIds, mcVersionNames, versionUrls, hashes, dependencies);
    }

    private void fillInMissingModItemData(ModItem item) {
        if (!(item.title == null || item.description == null || item.imageUrl == null)) return;
        JsonObject response = mApiHandler.get(String.format("mods/%s", item.id), JsonObject.class);
        JsonObject data = GsonJsonUtils.getJsonObjectSafe(response, "data");
        if (data == null) return;
        if (item.title == null) {
            JsonElement title = data.get("name");
            item.title = title != null ? title.getAsString() : "";
        }
        if (item.description == null) {
            JsonElement description = data.get("summary");
            item.description = description != null ? description.getAsString() : "";
        }
        if (item.imageUrl == null) {
            JsonElement imageUrl = data.getAsJsonObject("logo").get("thumbnailUrl");
            item.imageUrl = imageUrl != null ? imageUrl.getAsString() : null;
        }
    }

    @Override
    public LoaderInstaller installModpack(ModDetail modDetail, int selectedVersion) throws IOException{
        //TODO considering only modpacks for now
        return ModpackInstaller.downloadModpack(modDetail, selectedVersion, this::installCurseforgeZip);
    }

    public LoaderInstaller installLocalModpack(String modpackName, File modpackFile, String icon) throws IOException {
        return ModpackInstaller.installModpack(modpackName, modpackName, modpackFile, icon, this::installCurseforgeZip);
    }

    private int getPaginatedDetails(ArrayList<JsonObject> objectList, int index, String modId) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("index", index);
        params.put("pageSize", CURSEFORGE_PAGINATION_SIZE);

        JsonObject response = mApiHandler.get("mods/"+modId+"/files", params, JsonObject.class);
        JsonArray data = GsonJsonUtils.getJsonArraySafe(response, "data");
        Log.i("CurseforgeApi", "data...");
        if(data == null) return CURSEFORGE_PAGINATION_ERROR;
        Log.i("CurseforgeApi", "filtering...");
        for(int i = 0; i < data.size(); i++) {
            JsonObject fileInfo = data.get(i).getAsJsonObject();
            if(fileInfo.get("isServerPack").getAsBoolean()) continue;
            objectList.add(fileInfo);
        }
        Log.i("CurseforgeApi", "pag_end");
        if(data.size() < CURSEFORGE_PAGINATION_SIZE) {
            return CURSEFORGE_PAGINATION_END_REACHED; // we read the remainder! yay!
        }
        return index + data.size();
    }

    private LoaderInstaller installCurseforgeZip(File zipFile, File instanceDestination) throws IOException {
        try (ZipFile modpackZipFile = new ZipFile(zipFile)){
            CurseManifest curseManifest = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(ZipUtils.getEntryStream(modpackZipFile, "manifest.json")),
                    CurseManifest.class);
            if(!verifyManifest(curseManifest)) {
                Log.i("CurseforgeApi","manifest verification failed");
                return null;
            }
            try {
                new CurseDownloader().start(curseManifest, instanceDestination);
            }catch (InterruptedException e) {
                throw new IOException("NIY: InterruptedException", e);
            }
            String overridesDir = "overrides";
            if(curseManifest.overrides != null) overridesDir = curseManifest.overrides;
            ZipUtils.zipExtract(modpackZipFile, overridesDir, instanceDestination);
            return createInfo(curseManifest.minecraft);
        }
    }

    private LoaderInstaller createInfo(CurseManifest.CurseMinecraft minecraft) {
        CurseManifest.CurseModLoader primaryModLoader = null;
        for(CurseManifest.CurseModLoader modLoader : minecraft.modLoaders) {
            if(modLoader.primary) {
                primaryModLoader = modLoader;
                break;
            }
        }
        if(primaryModLoader == null) primaryModLoader = minecraft.modLoaders[0];
        String modLoaderId = primaryModLoader.id;
        int dashIndex = modLoaderId.indexOf('-');
        String modLoaderName = modLoaderId.substring(0, dashIndex);
        String modLoaderVersion = modLoaderId.substring(dashIndex+1);
        Log.i("CurseforgeApi", modLoaderId + " " + modLoaderName + " "+modLoaderVersion);
        LoaderInstaller loaderInstaller;
        switch (modLoaderName) {
            case "forge":
                return new ForgelikeLoaderInstaller(ForgelikeUtils.FORGE_UTILS, minecraft.version, modLoaderVersion);
            case "fabric":
                return new FabriclikeLoaderInstaller(FabriclikeUtils.FABRIC_UTILS, minecraft.version, modLoaderVersion);
            case "neoforge":
                return new ForgelikeLoaderInstaller(ForgelikeUtils.NEOFORGE_UTILS, minecraft.version, modLoaderVersion);
            default:
                return null;
            //TODO: Quilt is also Forge? How does that work?
        }
    }

    private String getDownloadUrl(JsonObject fileMetadata) throws IOException {
        if(fileMetadata.get("modId").isJsonNull() || fileMetadata.get("id").isJsonNull()) throw new IOException("Bad metadata schema!");
        long projectID = fileMetadata.get("modId").getAsLong();
        long fileID = fileMetadata.get("id").getAsLong();

        // First try the official api endpoint
        JsonObject response = mApiHandler.get("mods/"+projectID+"/files/"+fileID+"/download-url", JsonObject.class);
        if (response != null && !response.get("data").isJsonNull())
            return response.get("data").getAsString();

        // Otherwise, fallback to building an edge link
        return String.format("https://edge.forgecdn.net/files/%s/%s/%s", fileID/1000, fileID % 1000, fileMetadata.get("fileName").getAsString());
    }

    private void checkRequiredFileFields(JsonObject fileMetadata) throws IOException {
        if(fileMetadata == null || fileMetadata.isJsonNull()) throw new IOException("File metadata is null!");
        boolean hasProjectId = fileMetadata.has("modId");
        boolean hasFileId = fileMetadata.has("id");
        boolean hasLength = fileMetadata.has("fileLength");
        if(!hasProjectId || !hasFileId || !hasLength) {
            StringBuilder builder = new StringBuilder().append("File metadata is mising the following fields:");
            if(!hasProjectId) builder.append(" modId");
            if(!hasFileId) builder.append(" id");
            if(!hasLength) builder.append(" fileLength");
            throw new IOException(builder.toString());
        }
    }

    private @Nullable JsonObject getFile(long projectID, long fileID) {
        JsonObject response = mApiHandler.get("mods/"+projectID+"/files/"+fileID, JsonObject.class);
        return GsonJsonUtils.getJsonObjectSafe(response, "data");
    }

    private String getSha1FromModData(@NonNull JsonObject object) {
        JsonArray hashes = GsonJsonUtils.getJsonArraySafe(object, "hashes");
        if(hashes == null) return null;
        for (JsonElement jsonElement : hashes) {
            // The sha1 = 1; md5 = 2;
            JsonObject jsonObject = GsonJsonUtils.getJsonObjectSafe(jsonElement);
            if(GsonJsonUtils.getIntSafe(
                    jsonObject,
                    "algo",
                    -1) == ALGO_SHA_1) {
                return GsonJsonUtils.getStringSafe(jsonObject, "value");
            }
        }
        return null;
    }

    private boolean verifyManifest(CurseManifest manifest) {
        if(!"minecraftModpack".equals(manifest.manifestType)) return false;
        if(manifest.manifestVersion != 1) return false;
        if(manifest.minecraft == null) return false;
        if(manifest.minecraft.version == null) return false;
        if(manifest.minecraft.modLoaders == null) return false;
        return manifest.minecraft.modLoaders.length >= 1;
    }

    static class CurseforgeSearchResult extends SearchResult {
        int previousOffset;
    }

    class CurseDownloader extends Downloader {

        public CurseDownloader() {
            super(ProgressLayout.INSTALL_MODPACK);
        }

        public void start(CurseManifest curseManifest, File instanceDestination) throws IOException, InterruptedException {
            ArrayList<AcquireableTaskMetadata> taskMetadatas = new ArrayList<>(curseManifest.files.length);
            for(final CurseManifest.CurseFile file : curseManifest.files) {
                taskMetadatas.add(new CurseTaskMetadata(file, instanceDestination));
            }
            runDownloads(taskMetadatas);
        }
    }

    class CurseTaskMetadata extends AcquireableTaskMetadata {
        private final CurseManifest.CurseFile mFile;
        private final File mInstanceDestination;

        public CurseTaskMetadata(CurseManifest.CurseFile mFile, File mInstanceDestination) {
            super(DownloadMirror.DOWNLOAD_CLASS_METADATA);
            this.mFile = mFile;
            this.mInstanceDestination = mInstanceDestination;
        }

        @Override
        public void acquireMetadata() throws IOException {
            JsonObject fileMetadata = getFile(mFile.projectID, mFile.fileID);
            checkRequiredFileFields(fileMetadata);
            String url = getDownloadUrl(fileMetadata);
            this.url = new URL(url);
            this.path = new File(mInstanceDestination, "mods/"+ URLDecoder.decode(FileUtils.getFileName(url),"UTF-8"));
            FileUtils.ensureParentDirectorySilently(this.path);
            this.sha1Hash = getSha1FromModData(fileMetadata);
            this.size = fileMetadata.get("fileLength").getAsLong();
        }
    }
}
