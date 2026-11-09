package git.artdeell.mojo.fragments;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.modding.forge.ForgelikeUtils;

/**
 * Infers an instance's plain Minecraft version (e.g. "1.20.1") and mod loader
 * (fabric/forge/quilt/neoforge/"") from its {@code versionId}. Used to
 * auto-fill the Manage Content filter with sensible defaults the first time
 * it's opened for an instance, instead of leaving it blank.
 */
final class InstanceVersionResolver {

    private InstanceVersionResolver() {}

    static final class Info {
        /** May be null if it couldn't be determined at all. */
        final String mcVersion;
        /** "", "fabric", "forge", "quilt", "neoforge", "legacy-fabric" or "bta-babric". */
        final String loader;

        Info(String mcVersion, String loader) {
            this.mcVersion = mcVersion;
            this.loader = loader;
        }
    }

    static Info resolve(String instanceKey) {
        try {
            Instance instance = Instances.loadInstance(instanceKey);
            if (instance == null || instance.versionId == null || instance.versionId.isEmpty()) {
                return new Info(null, "");
            }
            String versionId = instance.versionId;
            // "latest release/snapshot" instances don't name a concrete version
            if (Instance.VERSION_LATEST_RELEASE.equals(versionId)
                    || Instance.VERSION_LATEST_SNAPSHOT.equals(versionId)) {
                return new Info(null, "");
            }
            String loader = loaderOf(instance, versionId);
            // Better than Adventure has versions of its own, there is no Minecraft version to filter by
            if ("bta-babric".equals(loader)) return new Info(null, loader);
            String mcVersion = cleanMcVersion(versionId, loader);
            return new Info(mcVersion, loader);
        } catch (Exception e) {
            return new Info(null, "");
        }
    }

    /** The loader the instance was created with, or the one its version id points at for older instances */
    private static String loaderOf(Instance instance, String versionId) {
        if (instance.loader != null && !instance.loader.isEmpty()) return instance.loader;
        return loaderFromVersionId(versionId);
    }

    private static String loaderFromVersionId(String versionId) {
        if (versionId.startsWith("fabric-loader-")) return "fabric";
        if (versionId.startsWith("quilt-loader-"))  return "quilt";
        if (versionId.startsWith("bta-"))           return "bta-babric";
        if (versionId.startsWith("neoforge-"))       return "neoforge";
        if (versionId.contains("-forge-"))           return "forge";
        return "";
    }

    /** Strips the loader naming convention off a version id (see the loader installers)
     *  to get the plain Minecraft version underneath — e.g. "fabric-loader-0.15.11-1.20.1"
     *  → "1.20.1". Falls back to the version JSON's own inheritsFrom (which every mod
     *  loader install here sets to the vanilla version it's built on), and finally to the
     *  raw id itself if neither approach works (e.g. NeoForge ids don't embed the MC
     *  version at all, so this relies entirely on inheritsFrom for that loader). */
    private static String cleanMcVersion(String versionId, String loader) {
        // The version JSON of every mod loader install here names the vanilla version it is built on.
        // Read it without resolving the parent: the vanilla version may not be downloaded yet,
        // which made the full resolution fail and left the version undetected.
        try {
            JVersionList.Version info = Tools.getVersionInfo(versionId, true);
            if (info != null && info.inheritsFrom != null && !info.inheritsFrom.isEmpty()) {
                return info.inheritsFrom;
            }
        } catch (Exception ignored) {}

        switch (loader) {
            case "fabric": case "quilt": case "legacy-fabric": {
                // "<prefix>-loader-<loader version>-<minecraft version>"
                int loaderIdx = versionId.indexOf("loader-");
                if (loaderIdx >= 0) {
                    String rest = versionId.substring(loaderIdx + "loader-".length());
                    int dash = rest.indexOf('-');
                    if (dash > 0) return rest.substring(dash + 1);
                }
                break;
            }
            case "forge": {
                int idx = versionId.indexOf("-forge-");
                if (idx > 0) return versionId.substring(0, idx);
                break;
            }
            case "neoforge":
                // "neoforge-<neoforge version>", the Minecraft version is encoded in the NeoForge one
                return ForgelikeUtils.getMcVersionForNeoVersion(versionId.substring("neoforge-".length()));
            default:
                break;
        }
        // Vanilla launches a plain Minecraft version
        return loader.isEmpty() ? versionId : null;
    }
}
