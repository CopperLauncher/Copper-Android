package git.artdeell.mojo.skins;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.Tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;

/**
 * Official Minecraft capes available to local accounts.
 * <p>
 * The list of capes comes from the Minecraft Wiki, whose cape pages carry the texture id of each
 * cape, through its public MediaWiki API (see {@link #load()}). The images themselves are
 * downloaded from the Mojang texture server on first use and cached, nothing is bundled in the
 * app. A short built-in list is used when the wiki cannot be reached, and a cape that fails to
 * download is simply left out.
 */
public final class CapeCatalog {
    public static final String ID_PREFIX = "mc:";
    private static final String TEXTURE_URL = "https://textures.minecraft.net/texture/";
    private static final String WIKI_API = "https://minecraft.wiki/api.php";
    private static final String USER_AGENT = "CopperLauncher (https://github.com/CopperLauncher/Copper-Android)";
    private static final long CATALOG_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int WIKI_BATCH = 20;
    private static final Pattern HASH = Pattern.compile("(?<![0-9a-fA-F])[0-9a-f]{64}(?![0-9a-fA-F])");

    private CapeCatalog() {}

    public static final class Item {
        public final String name;
        public final String hash;

        Item(String name, String hash) {
            this.name = name;
            this.hash = hash;
        }

        public String id() {
            return ID_PREFIX + hash;
        }
    }

    private static final Item[] ALL = {
            new Item("Migrator Cape", "2340c0e03dd24a11b15a8b33c2a7e9e32abb2051b2481d0ba7defd635ca7a933"),
            new Item("15th Anniversary", "cd9d82ab17fd92022dbd4a86cde4c382a7540e117fae7b9a2853658505a80625"),
            new Item("Cherry Blossom", "afd553b39358a24edfe3b8a9a939fa5fa4faa4d9a9c3d6af8eafb377fa05c2bb"),
            new Item("Vanilla Cape", "f9a76537647989f9a0b6d001e320dac591c359e9e61a31f4ce11c88f207f0ad4"),
            new Item("Follower's Cape", "569b7f2a1d00d26f30efe3f9ab9ac817b1e6d35f4f3cfb0324ef2d328223d350"),
            new Item("Purple Heart", "cb40a92e32b57fd732a00fc325e7afb00a7ca74936ad50d8e860152e482cfbde"),
            new Item("Pan Cape", "28de4a81688ad18b49e735a273e086c18f1e3966956123ccb574034c06f5d336"),
            new Item("Common Cape", "5ec930cdd2629c8771655c60eebeb867b4b6559b0e6d3bc71c40c96347fa03f0"),
            new Item("Copper Cape", "5e6f3193e74cd16cdd6637d9bae5484e3a37ff2a14c2d157c659a07810b1bdca"),
            new Item("Menace Cape", "dbc21e222528e30dc88445314f7be6ff12d3aeebc3c192054fba7e3b3f8c77b1"),
            new Item("Mojang Classic", "10f135ef7010f36f6f9661e479a9539d91f4a43b2f279f1311b5e39d5b035133"),
            new Item("Mojang Studios", "8f9024f2e51f893114d24a0d93dd1c0ba0a0f828a2a86ef7842c0c7a523a676a"),
            new Item("Founder's Cape", "99aba02ef05ec6aa4d42db8ee43796d6cd50e4b2954ab29f0caeb85f96bf52a1"),
            new Item("Mojang Office Cape", "5c29410057e32abec02d870ecb52ec25fb45ea81e785a7854ae8429d7236ca26"),
            new Item("Home Cape", "1de21419009db483900da6298a1e6cbf9f1bc1523a0dcdc16263fab150693edd"),
            new Item("Crafter Cape", "1e72fb4d01b97b0a8f71bb572012d2281a17fa2b64d0d023f03b87bb900f6f41"),
            new Item("MCC 15th Year", "7f2e106df231c50e29b13c8f1352f20a108a7b9ef182a450e12f6d2146430e71"),
            new Item("MineCon 2011", "953cac8b779fe41383e675ee2b86071a71658f2180f56fbce8aa315ea70e2ed6"),
            new Item("MineCon 2012", "a21764cb5d438edc6d1f0578e9f50e9734df2fa5b7b960c1d6ffccb1e225e3d7"),
            new Item("MineCon 2013", "9876e5dcf49c48b1114d59a80436d4001994b2a473a21396eb1525a818317d7a"),
            new Item("MineCon 2015", "179127d606881c62f2a74c206d2c448d56b0d918a221f1e3895e54d3d9d30043"),
    };

    /**
     * Every official cape: the ones listed on the Minecraft Wiki, plus the built-in ones the wiki
     * did not return. Blocks, so it must not run on the UI thread. Never throws.
     */
    @NonNull
    public static List<MinecraftSkinApi.Cape> load() {
        List<Item> items = new ArrayList<>();
        try {
            items.addAll(wikiItems());
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Could not read the cape list from the wiki, using the built-in list", e);
        }
        for (Item item : ALL) items.add(item);

        List<MinecraftSkinApi.Cape> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item item : items) {
            if (!seen.add(item.hash)) continue;
            MinecraftSkinApi.Cape cape = new MinecraftSkinApi.Cape();
            cape.id = item.id();
            cape.alias = item.name;
            cape.url = TEXTURE_URL + item.hash;
            result.add(cape);
        }
        return result;
    }

    private static final String TAG = "CapeCatalog";

    private static File catalogFile() {
        File dir = new File(Tools.DIR_CACHE, "capes");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "catalog.json");
    }

    /** Wiki cape list, from the cache when it is less than a week old */
    private static List<Item> wikiItems() throws IOException, JSONException {
        File cache = catalogFile();
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < CATALOG_MAX_AGE_MS) {
            try {
                List<Item> cached = parseCache(new String(readAll(new java.io.FileInputStream(cache), 1 << 20), StandardCharsets.UTF_8));
                if (!cached.isEmpty()) return cached;
            } catch (JSONException ignored) {
                // A damaged cache is just fetched again
            }
        }

        JSONArray members = new JSONObject(httpGet(WIKI_API + "?action=query&list=categorymembers"
                + "&cmtitle=Category:Capes&cmnamespace=0&cmlimit=500&format=json&formatversion=2"))
                .getJSONObject("query").getJSONArray("categorymembers");
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < members.length(); i++) {
            String title = members.getJSONObject(i).getString("title");
            // "Cape" and "Vote Update capes" are overview pages, not single capes
            if (title.equals("Cape") || title.endsWith("capes") || title.contains("(duplicate)")) continue;
            titles.add(title);
        }

        List<Item> items = new ArrayList<>();
        for (int from = 0; from < titles.size(); from += WIKI_BATCH) {
            String joined = android.text.TextUtils.join("|", titles.subList(from, Math.min(from + WIKI_BATCH, titles.size())));
            JSONArray pages = new JSONObject(httpGet(WIKI_API + "?action=query&prop=revisions&rvprop=content"
                    + "&rvslots=main&redirects=1&format=json&formatversion=2&titles=" + URLEncoder.encode(joined, "UTF-8")))
                    .getJSONObject("query").getJSONArray("pages");
            for (int i = 0; i < pages.length(); i++) {
                JSONObject page = pages.getJSONObject(i);
                JSONArray revisions = page.optJSONArray("revisions");
                if (revisions == null || revisions.length() == 0) continue;
                String text = revisions.getJSONObject(0).getJSONObject("slots").getJSONObject("main").optString("content");
                // The first texture id on a cape page is the one in its infobox: the current texture
                Matcher matcher = HASH.matcher(text);
                if (matcher.find()) items.add(new Item(page.getString("title"), matcher.group()));
            }
        }

        if (!items.isEmpty()) {
            JSONArray array = new JSONArray();
            for (Item item : items) array.put(new JSONObject().put("name", item.name).put("hash", item.hash));
            try (FileOutputStream out = new FileOutputStream(cache)) {
                out.write(array.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        return items;
    }

    private static List<Item> parseCache(String json) throws JSONException {
        JSONArray array = new JSONArray(json);
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.getJSONObject(i);
            items.add(new Item(entry.getString("name"), entry.getString("hash")));
        }
        return items;
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("Wiki answered " + code);
            return new String(readAll(conn.getInputStream(), 4 << 20), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream stream, int limit) throws IOException {
        try (InputStream in = stream) {
            byte[] data = IOUtils.toByteArray(in);
            if (data.length > limit) throw new IOException("Response is too big");
            return data;
        }
    }

    public static boolean isOfficial(@Nullable String id) {
        return id != null && id.startsWith(ID_PREFIX);
    }

    private static File cacheFile(String id) {
        File dir = new File(Tools.DIR_CACHE, "capes");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, id.replace(':', '-') + ".png");
    }

    /** Returns the PNG of an official cape, downloading it the first time. Blocks. */
    @NonNull
    public static byte[] load(@NonNull String id) throws IOException {
        File file = cacheFile(id);
        if (!file.exists() || file.length() == 0) {
            byte[] data = MinecraftSkinApi.download(TEXTURE_URL + id.substring(ID_PREFIX.length()));
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(data);
            }
            return data;
        }
        try (InputStream in = new java.io.FileInputStream(file)) {
            return IOUtils.toByteArray(in);
        }
    }

    /** PNG bytes of any cape id: official ones from the catalog, custom ones from the library */
    @NonNull
    public static byte[] loadAny(@NonNull String id) throws IOException {
        if (isOfficial(id)) return load(id);
        try (InputStream in = new java.io.FileInputStream(SkinLibrary.capeFile(id))) {
            return IOUtils.toByteArray(in);
        }
    }
}
