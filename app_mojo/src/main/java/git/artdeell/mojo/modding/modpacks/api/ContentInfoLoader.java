package git.artdeell.mojo.modding.modpacks.api;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import git.artdeell.mojo.modding.modpacks.models.Constants;
import git.artdeell.mojo.modding.modpacks.models.ContentInfo;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.utils.GsonJsonUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Loads the extra information the content detail screen needs: the description, the gallery and
 * the changelogs. All the methods do networking, keep them off the UI thread.
 */
public final class ContentInfoLoader {
    private ContentInfoLoader() {}

    /**
     * @param curseforgeApiKey the CurseForge key, null when CurseForge is disabled
     * @return the info, or null if it could not be loaded at all
     */
    @Nullable
    public static ContentInfo load(ModItem item, @Nullable String curseforgeApiKey) {
        if (item.apiSource == Constants.SOURCE_MODRINTH) return loadModrinth(item);
        if (item.apiSource == Constants.SOURCE_CURSEFORGE && curseforgeApiKey != null) {
            return loadCurseforge(item, curseforgeApiKey);
        }
        return null;
    }

    /** Creates a pager over the changelogs of the item, newest first. Null if not available. */
    @Nullable
    public static ChangelogSource createChangelogSource(ModItem item, @Nullable String curseforgeApiKey) {
        if (item.apiSource == Constants.SOURCE_MODRINTH) return new ModrinthChangelogSource(item.id);
        if (item.apiSource == Constants.SOURCE_CURSEFORGE && curseforgeApiKey != null) {
            return new CurseforgeChangelogSource(item.id, curseforgeApiKey);
        }
        return null;
    }

    // ── Modrinth ──────────────────────────────────────────────────────────────

    private static ContentInfo loadModrinth(ModItem item) {
        ApiHandler api = new ApiHandler("https://api.modrinth.com/v2");
        JsonObject project = api.get("project/" + item.id, JsonObject.class);
        if (project == null) return null;

        ContentInfo info = new ContentInfo();
        info.body = GsonJsonUtils.getStringSafe(project, "body");
        info.bodyIsHtml = false;
        info.downloads = GsonJsonUtils.getLongSafe(project, "downloads", -1);
        info.favorites = GsonJsonUtils.getLongSafe(project, "followers", -1);

        String slug = GsonJsonUtils.getStringSafe(project, "slug");
        String type = GsonJsonUtils.getStringSafe(project, "project_type");
        if (slug != null && type != null) {
            info.websiteUrl = "https://modrinth.com/" + type + "/" + slug;
        }

        JsonArray gallery = GsonJsonUtils.getJsonArraySafe(project, "gallery");
        if (gallery != null) {
            for (JsonElement element : gallery) {
                JsonObject image = GsonJsonUtils.getJsonObjectSafe(element);
                if (image == null) continue;
                String url = GsonJsonUtils.getStringSafe(image, "url");
                if (url == null || url.isEmpty()) continue;
                ContentInfo.GalleryImage galleryImage = new ContentInfo.GalleryImage();
                galleryImage.url = url;
                galleryImage.title = GsonJsonUtils.getStringSafe(image, "title");
                galleryImage.description = GsonJsonUtils.getStringSafe(image, "description");
                info.gallery.add(galleryImage);
            }
        }
        return info;
    }

    private static class ModrinthChangelogSource implements ChangelogSource {
        private final String mProjectId;
        private JsonArray mVersions;
        private int mIndex;

        ModrinthChangelogSource(String projectId) {
            mProjectId = projectId;
        }

        @Override
        public boolean hasMore() {
            return mVersions == null || mIndex < mVersions.size();
        }

        @Nullable
        @Override
        public List<ContentInfo.ChangelogEntry> loadMore(int count) {
            if (mVersions == null) {
                ApiHandler api = new ApiHandler("https://api.modrinth.com/v2");
                mVersions = api.get("project/" + mProjectId + "/version", JsonArray.class);
                if (mVersions == null) return null;
            }
            List<ContentInfo.ChangelogEntry> entries = new ArrayList<>();
            while (mIndex < mVersions.size() && entries.size() < count) {
                JsonObject version = GsonJsonUtils.getJsonObjectSafe(mVersions.get(mIndex++));
                if (version == null) continue;
                ContentInfo.ChangelogEntry entry = new ContentInfo.ChangelogEntry();
                entry.versionName = GsonJsonUtils.getStringSafe(version, "name");
                if (entry.versionName == null || entry.versionName.isEmpty()) {
                    entry.versionName = GsonJsonUtils.getStringSafe(version, "version_number");
                }
                entry.date = GsonJsonUtils.getStringSafe(version, "date_published");
                entry.text = GsonJsonUtils.getStringSafe(version, "changelog");
                entry.textIsHtml = false;
                entries.add(entry);
            }
            return entries;
        }
    }

    // ── CurseForge ────────────────────────────────────────────────────────────

    private static ContentInfo loadCurseforge(ModItem item, String apiKey) {
        ApiHandler api = new ApiHandler("https://api.curseforge.com/v1", apiKey);
        JsonObject modResponse = api.get("mods/" + item.id, JsonObject.class);
        JsonObject data = GsonJsonUtils.getJsonObjectSafe(modResponse, "data");
        if (data == null) return null;

        ContentInfo info = new ContentInfo();
        info.downloads = GsonJsonUtils.getLongSafe(data, "downloadCount", -1);
        info.favorites = GsonJsonUtils.getLongSafe(data, "thumbsUpCount", -1);
        JsonObject links = GsonJsonUtils.getJsonObjectSafe(data, "links");
        info.websiteUrl = GsonJsonUtils.getStringSafe(links, "websiteUrl");

        JsonArray screenshots = GsonJsonUtils.getJsonArraySafe(data, "screenshots");
        if (screenshots != null) {
            for (JsonElement element : screenshots) {
                JsonObject screenshot = GsonJsonUtils.getJsonObjectSafe(element);
                if (screenshot == null) continue;
                String url = GsonJsonUtils.getStringSafe(screenshot, "url");
                if (url == null || url.isEmpty()) continue;
                ContentInfo.GalleryImage galleryImage = new ContentInfo.GalleryImage();
                galleryImage.url = url;
                galleryImage.title = GsonJsonUtils.getStringSafe(screenshot, "title");
                galleryImage.description = GsonJsonUtils.getStringSafe(screenshot, "description");
                info.gallery.add(galleryImage);
            }
        }

        // The description is a separate request, and an HTML document
        JsonObject descriptionResponse = api.get("mods/" + item.id + "/description", JsonObject.class);
        info.body = GsonJsonUtils.getStringSafe(descriptionResponse, "data");
        info.bodyIsHtml = true;
        if (info.body == null || info.body.isEmpty()) {
            // Fall back to the one line summary, that one is always there
            info.body = GsonJsonUtils.getStringSafe(data, "summary");
            info.bodyIsHtml = false;
        }
        return info;
    }

    private static class CurseforgeChangelogSource implements ChangelogSource {
        private final String mModId;
        private final ApiHandler mApi;
        private int mFileIndex;
        private boolean mEndReached;

        CurseforgeChangelogSource(String modId, String apiKey) {
            mModId = modId;
            mApi = new ApiHandler("https://api.curseforge.com/v1", apiKey);
        }

        @Override
        public boolean hasMore() {
            return !mEndReached;
        }

        @Nullable
        @Override
        public List<ContentInfo.ChangelogEntry> loadMore(int count) {
            HashMap<String, Object> params = new HashMap<>();
            params.put("index", mFileIndex);
            params.put("pageSize", count);
            JsonObject response = mApi.get("mods/" + mModId + "/files", params, JsonObject.class);
            JsonArray files = GsonJsonUtils.getJsonArraySafe(response, "data");
            if (files == null) return null;

            List<ContentInfo.ChangelogEntry> entries = new ArrayList<>();
            for (JsonElement element : files) {
                JsonObject file = GsonJsonUtils.getJsonObjectSafe(element);
                if (file == null) continue;
                JsonElement serverPack = GsonJsonUtils.getElementSafe(file, "isServerPack");
                if (serverPack != null && serverPack.getAsBoolean()) continue;

                ContentInfo.ChangelogEntry entry = new ContentInfo.ChangelogEntry();
                entry.versionName = GsonJsonUtils.getStringSafe(file, "displayName");
                entry.date = GsonJsonUtils.getStringSafe(file, "fileDate");
                entry.textIsHtml = true;
                String fileId = GsonJsonUtils.getStringSafe(file, "id");
                if (fileId != null) {
                    JsonObject changelogResponse = mApi.get(
                            "mods/" + mModId + "/files/" + fileId + "/changelog", JsonObject.class);
                    entry.text = GsonJsonUtils.getStringSafe(changelogResponse, "data");
                }
                entries.add(entry);
            }
            mFileIndex += files.size();
            if (files.size() < count) mEndReached = true;
            return entries;
        }
    }

    /** A pager over the changelogs of a piece of content */
    public interface ChangelogSource {
        boolean hasMore();

        /**
         * Loads the next entries. Blocking.
         * @return the entries, or null if the request failed (it can be retried)
         */
        @Nullable
        List<ContentInfo.ChangelogEntry> loadMore(int count);
    }
}
