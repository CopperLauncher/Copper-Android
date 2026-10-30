package git.artdeell.mojo.skins;

import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.authenticator.AuthType;
import git.artdeell.mojo.authenticator.accounts.Account;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiny Yggdrasil-compatible server on the loopback interface. authlib-injector is pointed at it
 * when a local account is launched, so the game receives the skin and cape picked in the skin
 * editor even though there is no real account behind the name.
 */
public final class LocalSkinServer {
    private static final String TAG = "LocalSkinServer";
    private static final int MAX_BODY = 64 * 1024;

    private static LocalSkinServer sInstance;

    private final ConcurrentHashMap<String, Character> mByUuid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Character> mByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> mTextures = new ConcurrentHashMap<>();
    private ServerSocket mSocket;
    private KeyPair mKeyPair;

    private static final class Character {
        final String uuid;
        final String name;
        final String skinHash;
        final boolean slim;
        final String capeHash;

        Character(String uuid, String name, String skinHash, boolean slim, String capeHash) {
            this.uuid = uuid;
            this.name = name;
            this.skinHash = skinHash;
            this.slim = slim;
            this.capeHash = capeHash;
        }
    }

    private LocalSkinServer() {}

    /**
     * Registers the skin and cape selected for a local account and starts the server if needed.
     * Blocks, so it must not run on the UI thread.
     *
     * @return the authlib-injector API root, or null if the account has no skin or cape to serve
     */
    @Nullable
    public static synchronized String prepare(@NonNull Account account) {
        if (account.authType != AuthType.LOCAL) return null;
        String key = SkinLibrary.localKey(account.username);

        byte[] skin = null;
        boolean slim = false;
        SkinLibrary.Entry entry = SkinLibrary.find(SkinLibrary.getSelected(key));
        if (entry != null) {
            try {
                skin = SkinLibrary.readBytes(entry);
                slim = entry.slim;
            } catch (IOException e) {
                Log.w(TAG, "Could not read the selected skin", e);
            }
        }

        byte[] cape = null;
        String capeId = SkinLibrary.getSelectedCape(key);
        if (capeId != null) {
            try {
                cape = CapeCatalog.loadAny(capeId);
            } catch (IOException e) {
                Log.w(TAG, "Could not read the selected cape", e);
            }
        }

        if (skin == null && cape == null) return null;

        try {
            if (sInstance == null) sInstance = new LocalSkinServer();
            sInstance.register(account, skin, slim, cape);
            return "http://127.0.0.1:" + sInstance.ensureStarted();
        } catch (Exception e) {
            Log.e(TAG, "Could not start the local skin server", e);
            return null;
        }
    }

    private void register(Account account, @Nullable byte[] skin, boolean slim, @Nullable byte[] cape) throws Exception {
        mByUuid.clear();
        mByName.clear();
        mTextures.clear();
        String skinHash = skin == null ? null : store(skin);
        String capeHash = cape == null ? null : store(cape);
        String uuid = account.profileId.replace("-", "").toLowerCase(Locale.ROOT);
        Character character = new Character(uuid, account.username, skinHash, slim, capeHash);
        mByUuid.put(uuid, character);
        mByName.put(account.username.toLowerCase(Locale.ROOT), character);
    }

    private String store(byte[] png) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(png);
        StringBuilder hash = new StringBuilder();
        for (byte b : digest) hash.append(String.format(Locale.ROOT, "%02x", b));
        mTextures.put(hash.toString(), png);
        return hash.toString();
    }

    private int ensureStarted() throws Exception {
        if (mSocket != null && !mSocket.isClosed()) return mSocket.getLocalPort();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        mKeyPair = generator.generateKeyPair();
        mSocket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        final ServerSocket socket = mSocket;
        Thread acceptor = new Thread(() -> {
            while (!socket.isClosed()) {
                try {
                    final Socket client = socket.accept();
                    Thread worker = new Thread(() -> handle(client), "LocalSkinServer-worker");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException e) {
                    if (!socket.isClosed()) Log.w(TAG, "accept failed", e);
                }
            }
        }, "LocalSkinServer");
        acceptor.setDaemon(true);
        acceptor.start();
        return mSocket.getLocalPort();
    }

    // ------------------------------------------------------------------ http

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(10_000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            int contentLength = 0;
            String header;
            while ((header = readLine(in)) != null && !header.isEmpty()) {
                if (header.regionMatches(true, 0, "content-length:", 0, 15)) {
                    try {
                        contentLength = Integer.parseInt(header.substring(15).trim());
                    } catch (NumberFormatException ignored) {}
                }
            }
            byte[] body = new byte[Math.min(Math.max(contentLength, 0), MAX_BODY)];
            int read = 0;
            while (read < body.length) {
                int n = in.read(body, read, body.length - read);
                if (n < 0) break;
                read += n;
            }

            route(out, parts[0], parts[1], body);
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "request failed", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    @Nullable
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') line.write(b);
            if (line.size() > 8192) throw new IOException("Line too long");
        }
        if (b == -1 && line.size() == 0) return null;
        return line.toString("UTF-8");
    }

    private void route(OutputStream out, String method, String target, byte[] body) throws IOException {
        String path = target;
        String query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }

        try {
            if (method.equals("GET") && (path.equals("/") || path.equals("/api"))) {
                respond(out, 200, "application/json", root().toString().getBytes(StandardCharsets.UTF_8));
            } else if (method.equals("POST") && path.equals("/api/profiles/minecraft")) {
                respond(out, 200, "application/json", lookup(body).toString().getBytes(StandardCharsets.UTF_8));
            } else if (method.equals("GET") && path.equals("/sessionserver/session/minecraft/hasJoined")) {
                String username = queryParam(query, "username");
                profile(out, username == null ? null : mByName.get(username.toLowerCase(Locale.ROOT)));
            } else if (method.equals("POST") && path.equals("/sessionserver/session/minecraft/join")) {
                respond(out, 204, null, new byte[0]);
            } else if (method.equals("GET") && path.startsWith("/sessionserver/session/minecraft/profile/")) {
                String uuid = path.substring(path.lastIndexOf('/') + 1).replace("-", "").toLowerCase(Locale.ROOT);
                profile(out, mByUuid.get(uuid));
            } else if (method.equals("GET") && path.startsWith("/textures/")) {
                byte[] texture = mTextures.get(path.substring("/textures/".length()));
                if (texture == null) respond(out, 404, null, new byte[0]);
                else respond(out, 200, "image/png", texture);
            } else {
                respond(out, 404, null, new byte[0]);
            }
        } catch (JSONException | java.security.GeneralSecurityException e) {
            Log.w(TAG, "could not answer " + path, e);
            respond(out, 500, null, new byte[0]);
        }
    }

    private void profile(OutputStream out, @Nullable Character character) throws IOException, JSONException, java.security.GeneralSecurityException {
        if (character == null) {
            respond(out, 204, null, new byte[0]);
            return;
        }
        respond(out, 200, "application/json", profileJson(character).toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(OutputStream out, int code, @Nullable String type, byte[] body) throws IOException {
        String reason = code == 200 ? "OK" : code == 204 ? "No Content" : code == 404 ? "Not Found" : "Error";
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        if (type != null) head.append("Content-Type: ").append(type).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    @Nullable
    private static String queryParam(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (IOException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ yggdrasil payloads

    private JSONObject root() throws JSONException {
        JSONObject meta = new JSONObject();
        meta.put("serverName", "CopperLauncher");
        meta.put("implementationName", "copper-local-skins");
        meta.put("implementationVersion", "1.0");
        meta.put("feature.non_email_login", true);
        meta.put("feature.legacy_skin_api", true);

        String key = Base64.encodeToString(mKeyPair.getPublic().getEncoded(), Base64.DEFAULT).trim();

        JSONObject root = new JSONObject();
        root.put("skinDomains", new JSONArray().put("127.0.0.1").put("localhost"));
        root.put("meta", meta);
        root.put("signaturePublickey", "-----BEGIN PUBLIC KEY-----\n" + key + "\n-----END PUBLIC KEY-----");
        return root;
    }

    private JSONArray lookup(byte[] body) throws JSONException {
        JSONArray result = new JSONArray();
        JSONArray names = new JSONArray(new String(body, StandardCharsets.UTF_8));
        for (int i = 0; i < names.length(); i++) {
            Character character = mByName.get(names.getString(i).toLowerCase(Locale.ROOT));
            if (character == null) continue;
            result.put(new JSONObject().put("id", character.uuid).put("name", character.name));
        }
        return result;
    }

    private JSONObject profileJson(Character c) throws JSONException, java.security.GeneralSecurityException {
        String base = "http://127.0.0.1:" + mSocket.getLocalPort() + "/textures/";
        JSONObject textures = new JSONObject();
        if (c.skinHash != null) {
            JSONObject skin = new JSONObject().put("url", base + c.skinHash);
            if (c.slim) skin.put("metadata", new JSONObject().put("model", "slim"));
            textures.put("SKIN", skin);
        }
        if (c.capeHash != null) textures.put("CAPE", new JSONObject().put("url", base + c.capeHash));

        JSONObject payload = new JSONObject();
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("profileId", c.uuid);
        payload.put("profileName", c.name);
        payload.put("textures", textures);

        String value = Base64.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        Signature signature = Signature.getInstance("SHA1withRSA");
        signature.initSign(mKeyPair.getPrivate());
        signature.update(value.getBytes(StandardCharsets.UTF_8));

        JSONObject property = new JSONObject();
        property.put("name", "textures");
        property.put("value", value);
        property.put("signature", Base64.encodeToString(signature.sign(), Base64.NO_WRAP));

        return new JSONObject()
                .put("id", c.uuid)
                .put("name", c.name)
                .put("properties", new JSONArray().put(property));
    }
}
