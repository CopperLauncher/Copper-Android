package net.kdt.pojavlaunch.skins;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.authenticator.accounts.Account;
import net.kdt.pojavlaunch.authenticator.listener.LoginListener;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Minimal client for the Minecraft Services profile endpoints:
 * reading the profile (active skin + owned capes), uploading a skin and choosing a cape.
 * Every method blocks, call them from a background thread.
 */
public final class MinecraftSkinApi {
    private static final String TAG = "MinecraftSkinApi";
    private static final String PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private static final String SKINS_URL = PROFILE_URL + "/skins";
    private static final String ACTIVE_CAPE_URL = PROFILE_URL + "/capes/active";
    private static final int MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024;

    private MinecraftSkinApi() {}

    /** A cape owned by the account */
    public static final class Cape {
        public String id;
        public String alias;
        public String url;
        public boolean active;
    }

    /** The parts of the Minecraft profile that matter to the skin editor */
    public static final class Profile {
        @Nullable public String skinUrl;
        public boolean skinSlim;
        public final List<Cape> capes = new ArrayList<>();

        @Nullable
        public String activeCapeId() {
            for (Cape cape : capes) if (cape.active) return cape.id;
            return null;
        }
    }

    /** A non-2xx answer of the server */
    public static final class ApiException extends IOException {
        public final int code;
        /** Human readable reason sent by the server, may be empty */
        public final String reason;

        ApiException(int code, String body) {
            super("HTTP " + code + ": " + body);
            this.code = code;
            String parsed = "";
            try {
                JSONObject json = new JSONObject(body);
                parsed = json.optString("errorMessage", json.optString("error", ""));
            } catch (JSONException ignored) {}
            this.reason = parsed;
        }
    }

    // ------------------------------------------------------------------ session handling

    /**
     * Makes sure that the Minecraft access token of the account is still valid, refreshing the
     * session if needed.
     * @return the up-to-date account (it can be a different instance than the one passed in)
     */
    @NonNull
    public static Account ensureFreshSession(@NonNull Account account) throws IOException {
        // Another login/refresh might be running (account spinner does it in the background).
        // Refresh tokens get rotated, so never run two refreshes at the same time.
        long waitUntil = System.currentTimeMillis() + 60_000;
        while (ProgressKeeper.hasProgressKey(ProgressLayout.AUTHENTICATE)
                && System.currentTimeMillis() < waitUntil) {
            sleep(200);
        }

        Account current = account.reload();
        if (current == null) current = account;
        if (!current.authType.requiresLogin() || System.currentTimeMillis() <= current.expiresAt) {
            return current;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] error = new Throwable[1];
        current.authType.createAuth().refreshAccount(new LoginListener() {
            @Override public void onLoginDone(Account done) { latch.countDown(); }
            @Override public void onLoginError(Throwable t) { error[0] = t; latch.countDown(); }
            @Override public void onLoginProgress(int step) {}
            @Override public void setMaxLoginProgress(int max) {}
        }, current);

        try {
            if (!latch.await(60, TimeUnit.SECONDS)) throw new IOException("Session refresh timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while refreshing the session", e);
        }
        if (error[0] != null) throw new IOException("Session refresh failed", error[0]);
        return current;
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    // ------------------------------------------------------------------ profile calls

    @NonNull
    public static Profile fetchProfile(@NonNull String token) throws IOException {
        HttpURLConnection conn = open(PROFILE_URL, "GET", token);
        return parseProfile(readResponse(conn));
    }

    /** Uploads a PNG skin and makes it the active one */
    public static void uploadSkin(@NonNull String token, @NonNull byte[] png, boolean slim) throws IOException {
        String boundary = "----CopperSkin" + System.currentTimeMillis();
        HttpURLConnection conn = open(SKINS_URL, "POST", token);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        ByteArrayOutputStream body = new ByteArrayOutputStream(png.length + 512);
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"variant\"\r\n\r\n"
                + (slim ? "slim" : "classic") + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(png);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        byte[] bytes = body.toByteArray();
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        readResponse(conn);
    }

    /** Equips a cape, or hides the current one when capeId is null */
    public static void setActiveCape(@NonNull String token, @Nullable String capeId) throws IOException {
        if (capeId == null) {
            readResponse(open(ACTIVE_CAPE_URL, "DELETE", token));
            return;
        }
        HttpURLConnection conn = open(ACTIVE_CAPE_URL, "PUT", token);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try {
            byte[] bytes = new JSONObject().put("capeId", capeId).toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        readResponse(conn);
    }

    /** Downloads a texture (skin or cape) from the texture server */
    @NonNull
    public static byte[] download(@NonNull String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(secure(url)).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new ApiException(code, "texture download failed");
            try (InputStream is = conn.getInputStream()) {
                byte[] data = IOUtils.toByteArray(is);
                if (data.length > MAX_DOWNLOAD_BYTES) throw new IOException("Texture is too big");
                return data;
            }
        } finally {
            conn.disconnect();
        }
    }

    // ------------------------------------------------------------------ internals

    private static HttpURLConnection open(String url, String method, String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        try {
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = "";
            if (stream != null) {
                try (InputStream is = stream) {
                    body = new String(IOUtils.toByteArray(is), StandardCharsets.UTF_8);
                }
            }
            if (code < 200 || code >= 300) {
                Log.w(TAG, "Request failed: " + code + " " + body);
                throw new ApiException(code, body);
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private static Profile parseProfile(String json) throws IOException {
        try {
            JSONObject root = new JSONObject(json);
            Profile profile = new Profile();

            JSONArray skins = root.optJSONArray("skins");
            if (skins != null) {
                for (int i = 0; i < skins.length(); i++) {
                    JSONObject skin = skins.getJSONObject(i);
                    if (!"ACTIVE".equalsIgnoreCase(skin.optString("state", "ACTIVE"))) continue;
                    profile.skinUrl = secure(skin.optString("url", null));
                    profile.skinSlim = "SLIM".equalsIgnoreCase(skin.optString("variant", "CLASSIC"));
                    break;
                }
            }

            JSONArray capes = root.optJSONArray("capes");
            if (capes != null) {
                for (int i = 0; i < capes.length(); i++) {
                    JSONObject json2 = capes.getJSONObject(i);
                    Cape cape = new Cape();
                    cape.id = json2.getString("id");
                    cape.alias = json2.optString("alias", "");
                    cape.url = secure(json2.optString("url", null));
                    cape.active = "ACTIVE".equalsIgnoreCase(json2.optString("state", "INACTIVE"));
                    if (cape.url != null) profile.capes.add(cape);
                }
            }
            return profile;
        } catch (JSONException e) {
            throw new IOException("Malformed profile response", e);
        }
    }

    /** The profile hands out http:// texture links, which Android blocks as cleartext */
    @Nullable
    private static String secure(@Nullable String url) {
        if (url == null) return null;
        return url.startsWith("http://") ? "https://" + url.substring("http://".length()) : url;
    }
}
