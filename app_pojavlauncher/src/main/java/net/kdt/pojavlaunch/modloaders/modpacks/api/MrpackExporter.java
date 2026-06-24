package net.kdt.pojavlaunch.modloaders.modpacks.api;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModrinthIndex;
import net.kdt.pojavlaunch.utils.HashUtils;
import net.kdt.pojavlaunch.utils.ZipUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds a Modrinth-format .mrpack from an existing launcher instance, exactly the way the
 * official Modrinth app does it: jars under mods/ are hash-matched against Modrinth's database
 * so the pack can reference them by URL instead of bundling them, and everything else that was
 * selected for export is bundled raw under overrides/.
 */
public class MrpackExporter {
    private static final String TAG = "MrpackExporter";
    private static final String MODRINTH_API = "https://api.modrinth.com/v2";

    public interface ProgressListener {
        /** message is one of the export_mrpack_progress_* string resource ids, pre-formatted by the caller */
        void onProgress(String message);
    }

    public static class ExportResult {
        public final int totalModJars;
        public final int matchedOnModrinth;
        public final File outputFile;
        public ExportResult(int totalModJars, int matchedOnModrinth, File outputFile) {
            this.totalModJars = totalModJars;
            this.matchedOnModrinth = matchedOnModrinth;
            this.outputFile = outputFile;
        }
    }

    /**
     * @param instanceDir the root directory of the instance being exported (as returned by
     *                     {@code Tools.getGameDirPath(profile)})
     * @param packName free-form modpack name, goes into modrinth.index.json's "name" field
     * @param packVersion free-form version string, goes into modrinth.index.json's "versionId" field
     * @param packDescription optional description, goes into the "summary" field if non-empty
     * @param minecraftVersionId the profile's lastVersionId, used to detect the Minecraft version
     *                           and mod loader for the "dependencies" field
     * @param selectionOverrides explicit checked/unchecked state for every path the user
     *                           interacted with in the export dialog, keyed by path relative to
     *                           instanceDir (no leading slash, "/" separated). A path with no
     *                           explicit entry inherits the state of its nearest ancestor that
     *                           has one.
     * @param outputFile the destination .mrpack file to write
     * @param progressListener optional progress callback, invoked on the calling thread (so callers
     *                         should call {@link #export} from a background thread already)
     */
    public static ExportResult export(File instanceDir, String packName, String packVersion,
                                       @Nullable String packDescription, @Nullable String minecraftVersionId,
                                       Map<String, Boolean> selectionOverrides, File outputFile,
                                       @Nullable ProgressListener progressListener) throws IOException {

        List<File> modJarFiles = new ArrayList<>();
        List<String> modJarRelPaths = new ArrayList<>();
        List<File> overrideFiles = new ArrayList<>();
        List<String> overrideRelPaths = new ArrayList<>();

        collectSelectedFiles(instanceDir, "", selectionOverrides, false,
                modJarFiles, modJarRelPaths, overrideFiles, overrideRelPaths);

        // Hash every candidate mod jar (both sha1, for the Modrinth lookup, and sha512, which
        // every file entry in modrinth.index.json is required to have)
        Map<String, String> sha1ByRelPath = new HashMap<>();
        Map<String, String> sha512ByRelPath = new HashMap<>();
        List<String> sha1List = new ArrayList<>();
        for (int i = 0; i < modJarFiles.size(); i++) {
            if (progressListener != null) {
                progressListener.onProgress("hashing:" + (i + 1) + ":" + modJarFiles.size());
            }
            String relPath = modJarRelPaths.get(i);
            String sha1 = HashUtils.sha1Hex(modJarFiles.get(i));
            String sha512 = HashUtils.sha512Hex(modJarFiles.get(i));
            sha1ByRelPath.put(relPath, sha1);
            sha512ByRelPath.put(relPath, sha512);
            sha1List.add(sha1);
        }

        if (progressListener != null) progressListener.onProgress("checking");

        Map<String, JsonObject> versionsByHash = lookupModrinthVersionsByHash(sha1List);

        Set<String> projectIds = new HashSet<>();
        for (JsonObject version : versionsByHash.values()) {
            if (version.has("project_id") && !version.get("project_id").isJsonNull()) {
                projectIds.add(version.get("project_id").getAsString());
            }
        }
        Map<String, JsonObject> projectsById = lookupModrinthProjects(projectIds);

        List<ModrinthIndex.ModrinthIndexFile> indexFiles = new ArrayList<>();
        int matchedCount = 0;
        for (int i = 0; i < modJarFiles.size(); i++) {
            String relPath = modJarRelPaths.get(i);
            String sha1 = sha1ByRelPath.get(relPath);
            JsonObject version = versionsByHash.get(sha1);
            JsonObject matchedFile = (version != null) ? findMatchingFile(version, sha1) : null;

            if (matchedFile == null) {
                // Not found on Modrinth (private/local build, removed listing, etc.) — bundle the
                // raw jar like every other override file instead of referencing a URL.
                overrideFiles.add(modJarFiles.get(i));
                overrideRelPaths.add(relPath);
                continue;
            }

            matchedCount++;
            ModrinthIndex.ModrinthIndexFile indexFile = new ModrinthIndex.ModrinthIndexFile();
            indexFile.path = relPath;
            indexFile.fileSize = (int) modJarFiles.get(i).length();
            indexFile.hashes = new ModrinthIndex.ModrinthIndexFile.ModrinthIndexFileHashes();
            indexFile.hashes.sha1 = sha1;
            indexFile.hashes.sha512 = sha512ByRelPath.get(relPath);
            indexFile.downloads = new String[]{ matchedFile.get("url").getAsString() };

            String envClient = "required";
            String envServer = "required";
            String projectId = (version.has("project_id") && !version.get("project_id").isJsonNull())
                    ? version.get("project_id").getAsString() : null;
            JsonObject project = (projectId != null) ? projectsById.get(projectId) : null;
            if (project != null) {
                if (project.has("client_side") && !project.get("client_side").isJsonNull())
                    envClient = project.get("client_side").getAsString();
                if (project.has("server_side") && !project.get("server_side").isJsonNull())
                    envServer = project.get("server_side").getAsString();
            }
            indexFile.env = new ModrinthIndex.ModrinthIndexFile.ModrinthIndexFileEnv();
            indexFile.env.client = envClient;
            indexFile.env.server = envServer;

            indexFiles.add(indexFile);
        }

        ModrinthIndex index = new ModrinthIndex();
        index.formatVersion = 1;
        index.game = "minecraft";
        index.versionId = (packVersion == null || packVersion.trim().isEmpty()) ? "1.0.0" : packVersion.trim();
        index.name = packName;
        if (packDescription != null && !packDescription.trim().isEmpty()) index.summary = packDescription.trim();
        index.dependencies = detectDependencies(minecraftVersionId);
        index.files = indexFiles.toArray(new ModrinthIndex.ModrinthIndexFile[0]);

        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("Could not create output directory: " + parentDir);
        }

        try (FileOutputStream fileOutputStream = new FileOutputStream(outputFile);
             java.util.zip.ZipOutputStream zipOutputStream = new java.util.zip.ZipOutputStream(fileOutputStream)) {
            ZipUtils.putBytes(zipOutputStream, "modrinth.index.json",
                    Tools.GLOBAL_GSON.toJson(index).getBytes(StandardCharsets.UTF_8));

            for (int i = 0; i < overrideFiles.size(); i++) {
                if (progressListener != null) {
                    progressListener.onProgress("packaging:" + (i + 1) + ":" + overrideFiles.size());
                }
                ZipUtils.putFile(zipOutputStream, "overrides/" + overrideRelPaths.get(i), overrideFiles.get(i));
            }
        }

        return new ExportResult(modJarFiles.size(), matchedCount, outputFile);
    }

    /**
     * Recursively walks instanceDir, splitting selected files into two buckets: jar files
     * directly inside mods/ (candidates for Modrinth hash-matching) and everything else
     * selected (always bundled raw into overrides/).
     */
    private static void collectSelectedFiles(File node, String relPath, Map<String, Boolean> overrides,
                                              boolean inheritedChecked, List<File> modJarFiles,
                                              List<String> modJarRelPaths, List<File> overrideFiles,
                                              List<String> overrideRelPaths) {
        Boolean explicit = overrides.get(relPath);
        boolean checked = (explicit != null) ? explicit : inheritedChecked;

        if (node.isFile()) {
            if (!checked) return;
            boolean isTopLevelModJar = relPath.startsWith("mods/")
                    && !relPath.substring("mods/".length()).contains("/")
                    && relPath.toLowerCase(Locale.ROOT).endsWith(".jar");
            if (isTopLevelModJar) {
                modJarFiles.add(node);
                modJarRelPaths.add(relPath);
            } else {
                overrideFiles.add(node);
                overrideRelPaths.add(relPath);
            }
            return;
        }

        File[] children = node.listFiles();
        if (children == null) return;
        for (File child : children) {
            String childRelPath = relPath.isEmpty() ? child.getName() : relPath + "/" + child.getName();
            collectSelectedFiles(child, childRelPath, overrides, checked, modJarFiles, modJarRelPaths,
                    overrideFiles, overrideRelPaths);
        }
    }

    /** Finds the file entry inside a Modrinth version's files[] array whose sha1 matches. */
    @Nullable
    private static JsonObject findMatchingFile(JsonObject version, String sha1) {
        if (!version.has("files")) return null;
        JsonArray files = version.getAsJsonArray("files");
        JsonObject firstFile = null;
        for (JsonElement element : files) {
            JsonObject fileObject = element.getAsJsonObject();
            if (firstFile == null) firstFile = fileObject;
            JsonObject hashes = fileObject.has("hashes") ? fileObject.getAsJsonObject("hashes") : null;
            if (hashes != null && hashes.has("sha1") && !hashes.get("sha1").isJsonNull()
                    && sha1.equalsIgnoreCase(hashes.get("sha1").getAsString())) {
                return fileObject;
            }
        }
        // Fall back to the first file in the version if none matched by hash directly — this can
        // happen if Modrinth's version_files response was keyed by a *different* file in a
        // multi-file version (rare, e.g. sources jars).
        return firstFile;
    }

    /**
     * Bulk-resolves a list of SHA1 hashes against Modrinth's version_files endpoint, in a single
     * request, exactly like the Modrinth app does when scanning an instance for export.
     * @return a map of sha1 (lowercase hex) -> matching version JSON object, only for hashes that matched
     */
    private static Map<String, JsonObject> lookupModrinthVersionsByHash(List<String> sha1Hashes) {
        Map<String, JsonObject> result = new HashMap<>();
        if (sha1Hashes.isEmpty()) return result;

        JsonObject body = new JsonObject();
        JsonArray hashArray = new JsonArray();
        for (String hash : sha1Hashes) hashArray.add(hash);
        body.add("hashes", hashArray);
        body.addProperty("algorithm", "sha1");

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");

        String responseRaw = ApiHandler.postRaw(headers, MODRINTH_API + "/version_files", body.toString());
        if (responseRaw == null) return result;
        try {
            JsonObject responseObject = JsonParser.parseString(responseRaw).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : responseObject.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    result.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().getAsJsonObject());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse Modrinth version_files response", e);
        }
        return result;
    }

    /**
     * Bulk-fetches project metadata (used for the per-mod client/server "env" requirement) for
     * a set of Modrinth project ids, in a single request.
     */
    private static Map<String, JsonObject> lookupModrinthProjects(Set<String> projectIds) {
        Map<String, JsonObject> result = new HashMap<>();
        if (projectIds.isEmpty()) return result;

        JsonArray idsArray = new JsonArray();
        for (String id : projectIds) idsArray.add(id);

        String idsParam;
        try {
            idsParam = URLEncoder.encode(idsArray.toString(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return result; // UTF-8 is always available; unreachable in practice
        }

        String responseRaw = ApiHandler.getRaw(MODRINTH_API + "/projects?ids=" + idsParam);
        if (responseRaw == null) return result;
        try {
            JsonArray responseArray = JsonParser.parseString(responseRaw).getAsJsonArray();
            for (JsonElement element : responseArray) {
                JsonObject project = element.getAsJsonObject();
                if (project.has("id")) result.put(project.get("id").getAsString(), project);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse Modrinth projects response", e);
        }
        return result;
    }

    /**
     * Reverse-engineers the "dependencies" block of modrinth.index.json (minecraft version +
     * mod loader/version) from a profile's lastVersionId, by reversing the naming convention
     * {@link ModLoader#getVersionId()} uses, and reading the installed version JSON's
     * inheritsFrom for the underlying Minecraft version.
     */
    public static Map<String, String> detectDependencies(@Nullable String versionId) {
        Map<String, String> dependencies = new LinkedHashMap<>();
        if (versionId == null || versionId.isEmpty()) return dependencies;
        if (MinecraftProfile.LATEST_RELEASE.equals(versionId) || MinecraftProfile.LATEST_SNAPSHOT.equals(versionId)) {
            // These are launch-time placeholders, not on-disk version ids — there is no local
            // version JSON to read inheritsFrom from. The caller surfaces a warning in this case.
            return dependencies;
        }

        String minecraftVersion = resolveMinecraftVersion(versionId);
        if (minecraftVersion != null) dependencies.put("minecraft", minecraftVersion);

        if (versionId.contains("-forge-")) {
            int index = versionId.indexOf("-forge-");
            dependencies.put("forge", versionId.substring(index + "-forge-".length()));
        } else if (versionId.startsWith("fabric-loader-")) {
            String rest = versionId.substring("fabric-loader-".length());
            int lastDash = rest.lastIndexOf('-');
            if (lastDash > 0) dependencies.put("fabric-loader", rest.substring(0, lastDash));
        } else if (versionId.startsWith("quilt-loader-")) {
            String rest = versionId.substring("quilt-loader-".length());
            int lastDash = rest.lastIndexOf('-');
            if (lastDash > 0) dependencies.put("quilt-loader", rest.substring(0, lastDash));
        } else if (versionId.startsWith("neoforge-")) {
            dependencies.put("neoforge", versionId.substring("neoforge-".length()));
        }

        return dependencies;
    }

    @Nullable
    private static String resolveMinecraftVersion(String versionId) {
        File versionJsonFile = new File(Tools.DIR_HOME_VERSION, versionId + "/" + versionId + ".json");
        if (!versionJsonFile.exists()) return versionId;
        try {
            JMinecraftVersionList.Version version = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(versionJsonFile), JMinecraftVersionList.Version.class);
            if (version == null) return versionId;
            if (version.inheritsFrom != null && !version.inheritsFrom.isEmpty()) return version.inheritsFrom;
            return versionId;
        } catch (Exception e) {
            Log.w(TAG, "Failed to read version JSON for " + versionId, e);
            return versionId;
        }
    }
}