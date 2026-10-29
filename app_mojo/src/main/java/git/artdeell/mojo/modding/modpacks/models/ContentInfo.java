package git.artdeell.mojo.modding.modpacks.models;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the content detail screen shows besides the version list:
 * the long description, the gallery and where the project lives on the web.
 */
public class ContentInfo {
    /** The long description. Modrinth sends Markdown, CurseForge sends HTML, see {@link #bodyIsHtml}. */
    public String body;
    public boolean bodyIsHtml;
    /** The project page of the content, null if unknown */
    public String websiteUrl;
    /** Counts of the project page, -1 when the API didn't send them */
    public long downloads = -1;
    public long favorites = -1;
    public final List<GalleryImage> gallery = new ArrayList<>();

    public static class GalleryImage {
        /** The picture the gallery grid shows, may be recompressed by the site */
        public String url;
        /** The original upload, shown when a picture is opened. Null when the site has no other. */
        public String fullUrl;
        public String title;
        public String description;
    }

    /** One entry of the changelog tab, a single version of the content */
    public static class ChangelogEntry {
        public String versionName;
        /** ISO-8601 date, may be null */
        public String date;
        public String text;
        public boolean textIsHtml;
    }
}
