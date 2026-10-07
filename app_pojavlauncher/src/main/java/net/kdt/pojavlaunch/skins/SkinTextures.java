package net.kdt.pojavlaunch.skins;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import androidx.annotation.NonNull;

/** Helpers that deal with the pixel layout of skin and cape textures */
public final class SkinTextures {
    private SkinTextures() {}

    /** The only sizes Mojang accepts for an uploaded skin */
    public static boolean isValidSkinSize(int width, int height) {
        return width == 64 && (height == 64 || height == 32);
    }

    /** Slim ("Alex") skins leave the last column of every arm front face transparent */
    public static boolean looksSlim(@NonNull Bitmap skin) {
        if (skin.getWidth() != 64 || skin.getHeight() != 64) return false;
        return isColumnTransparent(skin, 47, 20, 12) && isColumnTransparent(skin, 39, 52, 12);
    }

    private static boolean isColumnTransparent(Bitmap bmp, int x, int y, int height) {
        for (int i = 0; i < height; i++) {
            if (Color.alpha(bmp.getPixel(x, y + i)) != 0) return false;
        }
        return true;
    }

    /**
     * Returns a square 64x64 (times the HD scale) copy of the skin. Old 64x32 skins get
     * their left arm and leg created from the right ones, like the game does.
     */
    @NonNull
    public static Bitmap normalizeSkin(@NonNull Bitmap source) {
        int scale = Math.max(1, source.getWidth() / 64);
        int size = 64 * scale;
        Bitmap result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        new Canvas(result).drawBitmap(source, 0, 0, null);

        boolean legacy = source.getHeight() * 2 <= source.getWidth();
        if (legacy) {
            // Same quirk as the game: a fully opaque hat layer is a background, not a hat
            if (isRegionOpaque(result, 32 * scale, 0, 32 * scale, 16 * scale)) {
                clearRegion(result, 32 * scale, 0, 32 * scale, 16 * scale);
            }
            copyBoxFlipped(result, scale, 0, 16, 16, 48, 4, 12, 4);  // right leg -> left leg
            copyBoxFlipped(result, scale, 40, 16, 32, 48, 4, 12, 4); // right arm -> left arm
        }
        return result;
    }

    /** Capes are 64x32; the old OptiFine 22x17 ones are padded to that layout */
    @NonNull
    public static Bitmap normalizeCape(@NonNull Bitmap source) {
        int scale = Math.max(1, source.getWidth() / 64);
        Bitmap result = Bitmap.createBitmap(64 * scale, 32 * scale, Bitmap.Config.ARGB_8888);
        new Canvas(result).drawBitmap(source, 0, 0, null);
        return result;
    }

    /** The outside of the cape, as a flat image */
    @NonNull
    public static Bitmap capeFront(@NonNull Bitmap normalizedCape) {
        int scale = normalizedCape.getWidth() / 64;
        return Bitmap.createBitmap(normalizedCape, scale, scale, 10 * scale, 16 * scale);
    }

    private static boolean isRegionOpaque(Bitmap bmp, int x, int y, int w, int h) {
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, x, y, w, h);
        for (int pixel : pixels) if (Color.alpha(pixel) != 255) return false;
        return true;
    }

    private static void clearRegion(Bitmap bmp, int x, int y, int w, int h) {
        bmp.setPixels(new int[w * h], 0, w, x, y, w, h);
    }

    /** Mirrors a whole box layout (all six faces) from one place of the texture to another */
    private static void copyBoxFlipped(Bitmap bmp, int s, int srcU, int srcV, int dstU, int dstV,
                                       int w, int h, int d) {
        // top, bottom
        copyFlipped(bmp, s, srcU + d, srcV, dstU + d, dstV, w, d);
        copyFlipped(bmp, s, srcU + d + w, srcV, dstU + d + w, dstV, w, d);
        // the side faces swap places when mirrored: right <-> left
        copyFlipped(bmp, s, srcU, srcV + d, dstU + d + w, dstV + d, d, h);
        copyFlipped(bmp, s, srcU + d + w, srcV + d, dstU, dstV + d, d, h);
        // front, back
        copyFlipped(bmp, s, srcU + d, srcV + d, dstU + d, dstV + d, w, h);
        copyFlipped(bmp, s, srcU + d + w + d, srcV + d, dstU + d + w + d, dstV + d, w, h);
    }

    private static void copyFlipped(Bitmap bmp, int s, int sx, int sy, int dx, int dy, int w, int h) {
        int pw = w * s, ph = h * s;
        int[] src = new int[pw * ph];
        int[] dst = new int[pw * ph];
        bmp.getPixels(src, 0, pw, sx * s, sy * s, pw, ph);
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                dst[y * pw + (pw - 1 - x)] = src[y * pw + x];
            }
        }
        bmp.setPixels(dst, 0, pw, dx * s, dy * s, pw, ph);
    }
}
