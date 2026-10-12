package git.artdeell.mojo.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.Tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * Small downloader for the pictures of the content detail screen (logo and gallery).
 * Pictures are downsampled while decoding and kept in a memory cache, the callbacks always run
 * on the UI thread. The mod list keeps using its own disk backed icon cache.
 */
public final class RemoteImageLoader {
    private static final int MAX_DOWNLOAD_BYTES = 12 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final String USER_AGENT =
            "CopperLauncher/Copper-Android (https://github.com/CopperLauncher/Copper-Android)";

    private static final LruCache<String, Bitmap> sCache =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 1024 / 10)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };

    private RemoteImageLoader() {}

    public interface Callback {
        /** @param bitmap the picture, null if it could not be loaded */
        void onImage(@Nullable Bitmap bitmap);
    }

    /**
     * @param maxDimension the longest side the picture is going to be shown at, in pixels
     * @return a handle to cancel the load with, cancelled loads never call back
     */
    @NonNull
    public static Future<?> load(@NonNull String url, int maxDimension, @NonNull Callback callback) {
        String cacheKey = url + "@" + maxDimension;
        Bitmap cached = sCache.get(cacheKey);
        if (cached != null) {
            Tools.runOnUiThread(() -> callback.onImage(cached));
            FutureTask<Object> done = new FutureTask<>(() -> null);
            done.run();
            return done;
        }
        return MojoApplication.sExecutorService.submit(() -> {
            Bitmap bitmap = download(url, maxDimension);
            if (bitmap != null) sCache.put(cacheKey, bitmap);
            if (Thread.currentThread().isInterrupted()) return;
            Tools.runOnUiThread(() -> callback.onImage(bitmap));
        });
    }

    @Nullable
    private static Bitmap download(String url, int maxDimension) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_DOWNLOAD_BYTES) return null;
                    if (Thread.currentThread().isInterrupted()) return null;
                }
                byte[] data = out.toByteArray();

                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

                int sample = 1;
                int longest = Math.max(bounds.outWidth, bounds.outHeight);
                while (longest / (sample * 2) >= maxDimension) sample *= 2;

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                return BitmapFactory.decodeByteArray(data, 0, data.length, options);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
