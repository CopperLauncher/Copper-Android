package git.artdeell.mojo.utils;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.Tools;

/**
 * Helpers for reasoning about plain Minecraft version ids, e.g. "1.21.5" or "26.2".
 */
public final class MinecraftVersion {
    /** The vanilla release that introduced the in-game Graphics API (Vulkan) selector. */
    private static final int GRAPHICS_BACKEND_MAJOR = 26;
    private static final int GRAPHICS_BACKEND_MINOR = 2;

    private MinecraftVersion() {}

    /**
     * Whether the given version (including snapshots/pre-releases of it) exposes the
     * Graphics API setting, where an OpenGL or Vulkan backend can be chosen.
     */
    public static boolean supportsGraphicsBackend(String versionId) {
        return isAtLeast(getMcVersion(versionId), GRAPHICS_BACKEND_MAJOR, GRAPHICS_BACKEND_MINOR);
    }

    /**
     * Strips the mod loader naming convention off a version id to get the plain Minecraft
     * version underneath, e.g. "fabric-loader-0.15.11-1.20.1" -> "1.20.1". Falls back to the
     * version JSON's inheritsFrom, and finally to the raw id when neither works.
     */
    public static String getMcVersion(String versionId) {
        if (versionId == null) return null;
        if (versionId.startsWith("fabric-loader-")) {
            String rest = versionId.substring("fabric-loader-".length());
            int dash = rest.indexOf('-');
            if (dash > 0) return rest.substring(dash + 1);
        } else if (versionId.startsWith("quilt-loader-")) {
            String rest = versionId.substring("quilt-loader-".length());
            int dash = rest.indexOf('-');
            if (dash > 0) return rest.substring(dash + 1);
        } else if (versionId.contains("-forge-")) {
            int idx = versionId.indexOf("-forge-");
            if (idx > 0) return versionId.substring(0, idx);
        }
        try {
            JVersionList.Version info = Tools.getVersionInfo(versionId);
            if (info != null && info.inheritsFrom != null && !info.inheritsFrom.isEmpty()) {
                return info.inheritsFrom;
            }
        } catch (Exception ignored) {}
        return versionId;
    }

    private static boolean isAtLeast(String mcVersion, int major, int minor) {
        if (mcVersion == null) return false;
        String[] parts = mcVersion.split("\\.");
        if (parts.length < 2) return false;
        try {
            int vMajor = Integer.parseInt(parts[0]);
            int vMinor = Integer.parseInt(leadingDigits(parts[1]));
            if (vMajor != major) return vMajor > major;
            return vMinor >= minor;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String leadingDigits(String str) {
        int end = 0;
        while (end < str.length() && Character.isDigit(str.charAt(end))) end++;
        return str.substring(0, end);
    }
}
