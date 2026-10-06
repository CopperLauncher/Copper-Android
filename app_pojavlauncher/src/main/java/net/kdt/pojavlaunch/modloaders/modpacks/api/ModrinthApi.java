package net.kdt.pojavlaunch.modloaders.modpacks.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kdt.mcgui.ProgressLayout;
import git.artdeell.mojo.R;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.downloader.Downloader;
import net.kdt.pojavlaunch.downloader.TaskMetadata;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.modloaders.ForgelikeUtils;
import net.kdt.pojavlaunch.modloaders.Lwjgl3ifyUtils;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.FabriclikeLoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.ForgelikeLoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.LoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.api.modloader.Lwjgl3ifyLoaderInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModrinthIndex;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;
import net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.utils.GsonJsonUtils;
import net.kdt.pojavlaunch.utils.ZipUtils;

public class ModrinthApi implements ModpackApi{
    private final ApiHandler mApiHandler;
    public ModrinthApi(){
        mApiHandler = new ApiHandler("https://api.modrinth.com/v2");
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        ModrinthSearchResult modrinthSearchResult = (ModrinthSearchResult) previousPageResult;

        // Fixes an issue where the offset being equal or greater than total_hits is ignored
        if (modrinthSearchResult != null && modrinthSearchResult.previousOffset >= modrinthSearchResult.totalResultCount) {
            ModrinthSearchResult emptyResult = new ModrinthSearchResult();
            emptyResult.results = new ModItem[0];
            emptyResult.totalResultCount = modrinthSearchResult.totalResultCount;
            emptyResult.previousOffset = modrinthSearchResult.previousOffset;
            return emptyResult;
        }


        // Build the facets filters
        HashMap<String, Object> params = new HashMap<>();
        StringBuilder facetString = new StringBuilder();
        facetString.append("[");
        facetString.append(String.format("[\"project_type:%s\"]",
                searchFilters.isModpack ? "modpack" : searchFilters.contentType.modrinthType));
        if(searchFilters.mcVersion != null && !searchFilters.mcVersion.isEmpty())
            facetString.append(String.format(",[\"versions:%s\"]", searchFilters.mcVersion));
        if(searchFilters.modLoader != null && !searchFilters.modLoader.isEmpty())
            facetString.append(String.format(",[\"categories:%s\"]", searchFilters.modLoader));
        facetString.append("]");
        params.put("facets", facetString.toString());
        params.put("query", searchFilters.name);
        params.put("limit", 50);
        params.put("index", "relevance");
        if(modrinthSearchResult != null)
            params.put("offset", modrinthSearchResult.previousOffset);

        JsonObject response = mApiHandler.get("search", params, JsonObject.class);
        if(response == null) return null;
        JsonArray responseHits = response.getAsJsonArray("hits");
        if(responseHits == null) return null;

        ModItem[] items = new ModItem[responseHits.size()];
        for(int i=0; i<responseHits.size(); ++i){
            JsonObject hit = responseHits.get(i).getAsJsonObject();
            items[i] = new ModItem(
                    Constants.SOURCE_MODRINTH,
                    hit.get("project_type").getAsString().equals("modpack"),
                    hit.get("project_id").getAsString(),
                    hit.get("title").getAsString(),
                    hit.get("description").getAsString(),
                    hit.get("icon_url").getAsString()
            );
        }
        if(modrinthSearchResult == null) modrinthSearchResult = new ModrinthSearchResult();
        modrinthSearchResult.previousOffset += responseHits.size();
        modrinthSearchResult.results = items;
        modrinthSearchResult.totalResultCount = response.get("total_hits").getAsInt();
        return modrinthSearchResult;
    }

    @Override
    public ModDetail getModDetails(ModItem item) {
        return getModDetails(item, null, null);
    }

    public ModDetail getModDetails(ModItem item, String filterMcVersion) {
        return getModDetails(item, filterMcVersion, null);
    }

    public ModDetail getModDetails(ModItem item, String filterMcVersion, String filterLoader) {
        fillInMissingModItemData(item);
        JsonArray response = mApiHandler.get(String.format("project/%s/version", item.id), JsonArray.class);
        if(response == null) return null;

        // Collect versions, optionally filtering by MC version and/or loader
        java.util.List<JsonObject> versions = new java.util.ArrayList<>();
        for (int i = 0; i < response.size(); i++) {
            JsonObject v = response.get(i).getAsJsonObject();
            if (filterMcVersion != null && !filterMcVersion.isEmpty()) {
                JsonArray gameVersions = v.get("game_versions").getAsJsonArray();
                boolean matches = false;
                for (int j = 0; j < gameVersions.size(); j++) {
                    if (filterMcVersion.equals(gameVersions.get(j).getAsString())) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) continue;
            }
            if (filterLoader != null && !filterLoader.isEmpty()) {
                JsonArray loaders = v.get("loaders").getAsJsonArray();
                boolean matches = false;
                for (int j = 0; j < loaders.size(); j++) {
                    if (filterLoader.equalsIgnoreCase(loaders.get(j).getAsString())) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) continue;
            }
            versions.add(v);
        }

        if (versions.isEmpty()) return null;

        int size = versions.size();
        String[] names = new String[size];
        String[] ids = new String[size];
        String[] mcNames = new String[size];
        String[] urls = new String[size];
        String[] hashes = new String[size];
        ModDetail.Dependencies[][] dependencies = new ModDetail.Dependencies[size][];

        for (int i = 0; i < size; i++) {
            JsonObject version = versions.get(i);
            names[i] = version.get("name").getAsString();
            ids[i] = version.get("id").getAsString();
            try {
                JsonArray dependenciesJsonArray = version.getAsJsonArray("dependencies");
                dependencies[i] = new ModDetail.Dependencies[dependenciesJsonArray.size()];
                for (int i1 = 0; i1 < dependenciesJsonArray.size(); ++i1) {
                    JsonObject obj = dependenciesJsonArray.get(i1).getAsJsonObject();
                    dependencies[i][i1] = new ModDetail.Dependencies(
                            GsonJsonUtils.getStringSafe(obj, "project_id"),
                            GsonJsonUtils.getStringSafe(obj, "version_id"),
                            GsonJsonUtils.getStringSafe(obj, "file_name"),
                            GsonJsonUtils.getStringSafe(obj, "dependency_type")
                    );
                }
            } catch (Exception ignored) {}

            mcNames[i] = version.get("game_versions").getAsJsonArray().get(0).getAsString();
            urls[i] = version.get("files").getAsJsonArray().get(0).getAsJsonObject().get("url").getAsString();
            // Assume there may not be hashes, in case the API changes
            JsonObject hashesMap = version.getAsJsonArray("files").get(0).getAsJsonObject()
                    .get("hashes").getAsJsonObject();
            hashes[i] = (hashesMap == null || hashesMap.get("sha1") == null) ? null
                    : hashesMap.get("sha1").getAsString();
        }

        return new ModDetail(item, names, ids, mcNames, urls, hashes, dependencies);
    }

    private void fillInMissingModItemData(ModItem item) {
        if (!(item.title == null || item.description == null || item.imageUrl == null)) return;
        JsonObject projectResponse = mApiHandler.get(String.format("project/%s", item.id), JsonObject.class);
        if (projectResponse == null) return;
        if (item.title == null) {
            JsonElement title = projectResponse.get("title");
            item.title = title != null ? title.getAsString() : "";
        }
        if (item.description == null) {
            JsonElement description = projectResponse.get("description");
            item.description = description != null ? description.getAsString() : "";
        }
        if (item.imageUrl == null) {
            JsonElement imageUrl = projectResponse.get("icon_url");
            item.imageUrl = imageUrl != null ? imageUrl.getAsString() : null;
        }
    }

    @Override
    public LoaderInstaller installModpack(ModDetail modDetail, int selectedVersion) throws IOException{
        //TODO considering only modpacks for now
        return ModpackInstaller.downloadModpack(modDetail, selectedVersion, this::installMrpack);
    }

    public LoaderInstaller installLocalModpack(String modpackName, File modpackFile, String icon) throws IOException {
        return ModpackInstaller.installModpack(modpackName, modpackName, modpackFile, icon, this::installMrpack);
    }

    private static LoaderInstaller createInfo(ModrinthIndex modrinthIndex, File installDestination) throws IOException {
        if(modrinthIndex == null) return null;
        Map<String, String> dependencies = modrinthIndex.dependencies;
        String mcVersion = dependencies.get("minecraft");
        if(mcVersion == null) return null;
        String modLoaderVersion;
        if((modLoaderVersion = dependencies.get("forge")) != null) {
            return new ForgelikeLoaderInstaller(ForgelikeUtils.FORGE_UTILS, mcVersion, modLoaderVersion);
        } else if((modLoaderVersion = dependencies.get("fabric-loader")) != null) {
            return new FabriclikeLoaderInstaller(FabriclikeUtils.FABRIC_UTILS, mcVersion, modLoaderVersion);
        } else if((modLoaderVersion = dependencies.get("quilt-loader")) != null) {
            return new FabriclikeLoaderInstaller(FabriclikeUtils.QUILT_UTILS, mcVersion, modLoaderVersion);
        } else if((modLoaderVersion = dependencies.get("neoforge")) != null) {
            return new ForgelikeLoaderInstaller(ForgelikeUtils.NEOFORGE_UTILS, mcVersion, modLoaderVersion);
        } else if(dependencies.size() == 1) {
            // "Vanilla" pack. Possibly GT:NH, let's try to detect lwjgl3ify
            File lwjgl3ifyJar = Lwjgl3ifyUtils.detectLwjgl3ifyJar(installDestination);
            if(lwjgl3ifyJar != null) return new Lwjgl3ifyLoaderInstaller(lwjgl3ifyJar);
        }

        return null;
    }

    private LoaderInstaller installMrpack(File mrpackFile, File instanceDestination) throws IOException {
        try (ZipFile modpackZipFile = new ZipFile(mrpackFile)){
            ModrinthIndex modrinthIndex = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(ZipUtils.getEntryStream(modpackZipFile, "modrinth.index.json")),
                    ModrinthIndex.class);
            try {
                new ModrinthDownloader().startDownloads(modrinthIndex.files, instanceDestination);
            }catch (InterruptedException e) {
                throw new IOException("NIY: InterruptedException", e);
            }
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.modpack_download_applying_overrides, 1, 2);
            ZipUtils.zipExtract(modpackZipFile, "overrides/", instanceDestination);
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 50, R.string.modpack_download_applying_overrides, 2, 2);
            ZipUtils.zipExtract(modpackZipFile, "client-overrides/", instanceDestination);
            return createInfo(modrinthIndex, instanceDestination);
        }
    }

    class ModrinthSearchResult extends SearchResult {
        int previousOffset;
    }

    static class ModrinthDownloader extends Downloader {
        public ModrinthDownloader() {
            super(ProgressLayout.INSTALL_MODPACK);
        }

        protected void startDownloads(ModrinthIndex.ModrinthIndexFile[] indexFiles, File instanceDestination) throws IOException, InterruptedException {
            String absoluteInstancePath = instanceDestination.getAbsolutePath();
            ArrayList<TaskMetadata> taskMetadatas = new ArrayList<>(indexFiles.length);
            for(ModrinthIndex.ModrinthIndexFile file : indexFiles) {
                File targetPath = new File(instanceDestination, file.path);
                if(!targetPath.getAbsolutePath().startsWith(absoluteInstancePath)) throw new IOException("Bad path!");
                FileUtils.ensureParentDirectory(targetPath);
                taskMetadatas.add(new TaskMetadata(
                        targetPath, new URL(file.downloads[0]), // TODO source selection
                        file.fileSize, file.hashes.sha1,
                        DownloadMirror.DOWNLOAD_CLASS_NONE
                ));
            }
            runDownloads(taskMetadatas);
        }
    }
}
