package git.artdeell.mojo.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Html;
import android.text.Spanned;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the descriptions and changelogs of Modrinth (Markdown) and CurseForge (HTML) into styled
 * text a TextView can show, used for changelogs. Markdown is converted by {@link ContentMarkdown},
 * full descriptions go through {@link ContentBody} instead. Pictures are downloaded into the
 * text when a target TextView is given.
 */
public final class ContentTextRenderer {
    private static final Pattern HTML_IMAGE = Pattern.compile("<img[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_BLOCK_TAGS = Pattern.compile(
            "</?(iframe|center|details|summary|table|thead|tbody|tr|td|th|span|div|section|hr)[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOISE = Pattern.compile(
            "<!--.*?-->|<script\\b.*?</script>|<style\\b.*?</style>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PRE = Pattern.compile("<pre>(.*?)</pre>", Pattern.DOTALL);

    private static final Pattern BREAK_RUN = Pattern.compile("(<br\\s*/?>\\s*){3,}", Pattern.CASE_INSENSITIVE);

    private ContentTextRenderer() {}

    /** Renders without pictures */
    @NonNull
    public static CharSequence render(@Nullable String text, boolean isHtml) {
        return render(text, isHtml, null);
    }

    /**
     * @param target the TextView the result is going to be shown in. When given, the pictures of
     *               the text are downloaded and appear in it as they arrive; without it they are dropped.
     */
    @NonNull
    public static CharSequence render(@Nullable String text, boolean isHtml, @Nullable TextView target) {
        if (text == null || text.trim().isEmpty()) return "";
        String html = isHtml ? text : ContentMarkdown.toHtml(text);
        html = NOISE.matcher(html).replaceAll("");
        html = codeBlocks(html);
        if (target == null) html = HTML_IMAGE.matcher(html).replaceAll("");
        html = HTML_BLOCK_TAGS.matcher(html).replaceAll("<br>");
        html = BREAK_RUN.matcher(html).replaceAll("<br><br>");
        return fromHtml(html, target == null ? null : new RemoteImageGetter(target));
    }

    private static String codeBlocks(String html) {
        Matcher matcher = PRE.matcher(html);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    "<tt>" + matcher.group(1).replace("\n", "<br>") + "</tt><br>"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Spanned fromHtml(String html, @Nullable Html.ImageGetter imageGetter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT, imageGetter, null);
        }
        //noinspection deprecation
        return Html.fromHtml(html, imageGetter, null);
    }

    /** Hands out empty drawables that fill in once their picture is downloaded */
    private static final class RemoteImageGetter implements Html.ImageGetter {
        private final TextView mTarget;

        RemoteImageGetter(TextView target) {
            mTarget = target;
        }

        @Override
        public Drawable getDrawable(String source) {
            RemoteDrawable drawable = new RemoteDrawable();
            String url = normalize(source);
            if (url == null) return drawable;

            int available = availableWidth();
            RemoteImageLoader.load(url, Math.min(available * 2, 1600), bitmap -> {
                if (bitmap == null) return;
                int width = Math.min(bitmap.getWidth(), availableWidth());
                int height = Math.max(1, Math.round(bitmap.getHeight() * (width / (float) bitmap.getWidth())));
                drawable.setBitmap(bitmap, width, height);
                // Makes the text lay itself out again with the picture's size
                mTarget.setText(mTarget.getText());
            });
            return drawable;
        }

        private int availableWidth() {
            int width = mTarget.getWidth() - mTarget.getPaddingLeft() - mTarget.getPaddingRight();
            return width > 0 ? width : mTarget.getResources().getDisplayMetrics().widthPixels / 3;
        }

        /** @return an http(s) address worth downloading, null for relative paths and vector badges */
        @Nullable
        private static String normalize(@Nullable String source) {
            if (source == null) return null;
            String url = source.trim();
            if (url.startsWith("//")) url = "https:" + url;
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null;
            String lower = url.toLowerCase();
            // Android can't decode SVG, status badges are nearly always SVG
            if (lower.contains(".svg") || lower.contains("shields.io") || lower.contains("badgen.net")) return null;
            return url;
        }
    }

    private static final class RemoteDrawable extends Drawable {
        private Bitmap mBitmap;

        void setBitmap(Bitmap bitmap, int width, int height) {
            mBitmap = bitmap;
            setBounds(0, 0, width, height);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (mBitmap != null) canvas.drawBitmap(mBitmap, null, getBounds(), null);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
