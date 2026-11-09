package git.artdeell.mojo.modding.forge;

import android.util.Log;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.InstanceInstaller;
import git.artdeell.mojo.modding.ComparableVersionString;
import git.artdeell.mojo.utils.DownloadUtils;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

public abstract class ForgelikeUtils {
    public static final ForgelikeUtils FORGE_UTILS = new ForgeUtils();
    public static final ForgelikeUtils NEOFORGE_UTILS = new NeoforgeUtils();

    private final String mName;
    private final String mCachePrefix;
    private final String mVersionResolver;
    private final String mIconName;
    private final String mMetadataUrl;
    private final String mInstallerUrl;
    private final boolean mVersionOrderInversed;

    private ForgelikeUtils(String name, String cachePrefix, String iconName, String versionResolver, String metadataUrl, String installerUrl, boolean versionOrderInversed) {
        this.mName = name;
        this.mCachePrefix = cachePrefix;
        this.mIconName = iconName;
        this.mVersionResolver = versionResolver;
        this.mMetadataUrl = metadataUrl;
        this.mInstallerUrl = installerUrl;
        this.mVersionOrderInversed = versionOrderInversed;
    }

    public List<String> downloadVersions() throws IOException {
        SAXParser saxParser;
        try {
            SAXParserFactory parserFactory = SAXParserFactory.newInstance();
            saxParser = parserFactory.newSAXParser();
        } catch (SAXException | ParserConfigurationException e) {
            e.printStackTrace();
            // if we cant make a parser we might as well not even try to parse anything
            return null;
        }
        try {
            //of_test();
            return DownloadUtils.downloadStringCached(mMetadataUrl, mCachePrefix + "_versions", input -> {
                try {
                    ForgelikeVersionListHandler handler = new ForgelikeVersionListHandler();
                    saxParser.parse(new InputSource(new StringReader(input)), handler);
                    return handler.getVersions();
                    // IOException is present here StringReader throws it only if the parser called close()
                    // sooner than needed, which is a parser issue and not an I/O one
                } catch (SAXException | IOException e) {
                    throw new DownloadUtils.ParseException(e);
                }
            });
        } catch (DownloadUtils.ParseException e) {
            e.printStackTrace();
            return null;
        }
    }

    public String getInstallerUrl(String version) {
        return String.format(mInstallerUrl, version);
    }

    public InstanceInstaller createInstaller(String gameVersion, String modLoaderVersion) throws IOException {
        List<String> versions = downloadVersions();
        if (versions == null) return null;
        String versionStart = String.format(mVersionResolver, gameVersion, modLoaderVersion);
        for (String versionName : versions) {
            if (!versionName.startsWith(versionStart)) continue;
            return createInstaller(versionName);
        }
        return null;
    }

    public InstanceInstaller createInstaller(String fullVersion) throws IOException {
        String downloadUrl = getInstallerUrl(fullVersion);
        String hash = DownloadUtils.downloadString(downloadUrl + ".sha1");
        File installerLocation = new File(Tools.DIR_CACHE, mCachePrefix + "-installer-" + fullVersion + ".jar");
        InstanceInstaller instanceInstaller = new InstanceInstaller();
        instanceInstaller.commandLineArgs = List.of("-Duser.language=en", "-Duser.country=US", "-javaagent:"+Tools.DIR_DATA+"/forge_installer/forge_installer.jar");
        instanceInstaller.installerJar = installerLocation.getAbsolutePath();
        instanceInstaller.installerSha1 = hash;
        instanceInstaller.installerDownloadUrl = downloadUrl;
        return instanceInstaller;
    }

    public String getName() {
        return mName;
    }

    public String getIconName() {
        return mIconName;
    }

    public abstract String processVersionString(String version);

    public abstract boolean shouldSkipVersion(String version);

    public boolean isVersionOrderInversed() {
        return mVersionOrderInversed;
    }

    /**
     * Gets the Minecraft version a NeoForge version was made for.
     * <ul>
     *     <li>Until Minecraft 1.21.11 the NeoForge version is "&lt;minor&gt;.&lt;patch&gt;.&lt;build&gt;": it trims the
     *     "1." off Minecraft's version and appends its own build, e.g. 21.1.8 is Minecraft 1.21.1</li>
     *     <li>Since Minecraft 26.1 (year based versions, without the "1.") the first three numbers are the
     *     Minecraft version and the fourth one is the NeoForge build, e.g. 26.1.0.10-beta is Minecraft 26.1,
     *     and 26.1.1.3 would be Minecraft 26.1.1</li>
     * </ul>
     */
    public static String getMcVersionForNeoVersion(String neoVersion) {
        try {
            int firstIndex = neoVersion.indexOf('.');
            int secondIndex = neoVersion.indexOf('.', firstIndex + 1);
            if (firstIndex == -1 || secondIndex == -1) {
                Log.e("NeoforgeUtils", "Failed to parse neoforge version: " + neoVersion + "; not enough '.' found");
                return neoVersion;
            }
            if (Integer.parseInt(neoVersion.substring(0, firstIndex)) >= 26) {
                // Calendar versioning, the Minecraft version has no "1." in front and a hotfix of 0 is left out
                int thirdIndex = secondIndex + 1;
                int thirdEnd = thirdIndex;
                while (thirdEnd < neoVersion.length() && Character.isDigit(neoVersion.charAt(thirdEnd))) thirdEnd++;
                String hotfix = neoVersion.substring(thirdIndex, thirdEnd);
                String mcVersion = neoVersion.substring(0, secondIndex);
                if (!hotfix.isEmpty() && Integer.parseInt(hotfix) != 0) mcVersion += "." + hotfix;
                return mcVersion;
            }
            return "1." + neoVersion.substring(0, secondIndex);
        } catch (RuntimeException e) {
            Log.e("NeoforgeUtils", "Failed to parse neoforge version: " + neoVersion, e);
            return neoVersion;
        }
    }

    private static class ForgeUtils extends ForgelikeUtils {
        public ForgeUtils() {
            super(
                    "Forge", "forge", "forge", "%1$s-%2$s",
                    "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml",
                    "https://maven.minecraftforge.net/net/minecraftforge/forge/%1$s/forge-%1$s-installer.jar",
                    false
            );
        }

        @Override
        public String processVersionString(String version) {
            int dashIndex = version.indexOf("-");
            return version.substring(0, dashIndex);
        }

        @Override
        public boolean shouldSkipVersion(String version) {
            return false;
        }
    }

    private static class NeoforgeUtils extends ForgelikeUtils {
        public NeoforgeUtils() {
            super(
                    "NeoForge", "neoforge", "neoforge", "%2$s",
                    "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml",
                    "https://maven.neoforged.net/releases/net/neoforged/neoforge/%1$s/neoforge-%1$s-installer.jar",
                    true
            );
        }

        @Override
        public String processVersionString(String version) {
            return ComparableVersionString.parse(getMcVersionForNeoVersion(version)).getProper();
        }

        @Override
        public boolean shouldSkipVersion(String version) {
            return version.startsWith("0");
        }
    }
}
