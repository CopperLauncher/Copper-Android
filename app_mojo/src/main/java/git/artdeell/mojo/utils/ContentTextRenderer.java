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
 * text a TextView can show. The Markdown support is deliberately small, it covers what project
 * pages actually use: headings, lists, quotes, bold, italic, code, links and rules. Pictures are
 * downloaded into the text when a target TextView is given.
 */
public final class ContentTextRenderer {
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)");
    private static final Pattern EMPTY_LINK = Pattern.compile("\\[\\s*\\]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)[^)]*\\)");
    private static final Pattern BOLD = Pattern.compile("(\\*\\*|__)(.+?)\\1");
    private static final Pattern ITALIC = Pattern.compile("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*(\\d+)[.)]\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern HTML_IMAGE = Pattern.compile("<img[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_BLOCK_TAGS = Pattern.compile(
            "</?(iframe|script|style|center|details|summary|table|thead|tbody|tr|td|th|span|div|section)[^>]*>",
            Pattern.CASE_INSENSITIVE);

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
        String html = isHtml ? text : markdownToHtml(text);
        if (target == null) html = HTML_IMAGE.matcher(html).replaceAll("");
        html = HTML_BLOCK_TAGS.matcher(html).replaceAll("<br>");
        html = BREAK_RUN.matcher(html).replaceAll("<br><br>");
        return fromHtml(html, target == null ? null : new RemoteImageGetter(target));
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

    private static String markdownToHtml(String markdown) {
        StringBuilder out = new StringBuilder(markdown.length() + 256);
        boolean inCodeBlock = false;
        boolean inList = false;

        for (String line : markdown.replace("\r\n", "\n").split("\n", -1)) {
            String trimmed = line.trim();

            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inCodeBlock = !inCodeBlock;
                if (inList) { out.append("</ul>"); inList = false; }
                out.append("<br>");
                continue;
            }
            if (inCodeBlock) {
                out.append("<tt>").append(escape(line)).append("</tt><br>");
                continue;
            }

            Matcher bullet = BULLET.matcher(line);
            Matcher numbered = NUMBERED.matcher(line);
            boolean isListItem = !RULE.matcher(line).matches() && (bullet.matches() || numbered.matches());
            if (!isListItem && inList) {
                out.append("</ul>");
                inList = false;
            }

            if (trimmed.isEmpty()) {
                out.append("<br>");
                continue;
            }
            if (RULE.matcher(line).matches()) {
                out.append("<br>");
                continue;
            }

            Matcher heading = HEADING.matcher(trimmed);
            if (heading.matches()) {
                int level = Math.min(heading.group(1).length() + 1, 6);
                out.append("<h").append(level).append(">")
                        .append(inline(heading.group(2)))
                        .append("</h").append(level).append(">");
                continue;
            }
            if (isListItem) {
                if (!inList) { out.append("<ul>"); inList = true; }
                String content = bullet.matches() ? bullet.group(1) : numbered.group(2);
                out.append("<li>").append(inline(content)).append("</li>");
                continue;
            }
            if (trimmed.startsWith(">")) {
                out.append("<i>").append(inline(trimmed.substring(1).trim())).append("</i><br>");
                continue;
            }
            out.append(inline(line)).append("<br>");
        }
        if (inList) out.append("</ul>");
        return out.toString();
    }

    /** Inline Markdown. Raw HTML in the source is kept, Modrinth pages are full of it. */
    private static String inline(String line) {
        String result = IMAGE.matcher(line).replaceAll("<img src=\"$2\">");
        result = EMPTY_LINK.matcher(result).replaceAll("");
        // Code first so its content is not styled, the rest of the line is left alone
        Matcher code = CODE.matcher(result);
        StringBuffer buffer = new StringBuffer();
        while (code.find()) {
            code.appendReplacement(buffer, Matcher.quoteReplacement("<tt>" + escape(code.group(1)) + "</tt>"));
        }
        code.appendTail(buffer);
        result = buffer.toString();
        result = LINK.matcher(result).replaceAll("<a href=\"$2\">$1</a>");
        result = BOLD.matcher(result).replaceAll("<b>$2</b>");
        result = ITALIC.matcher(result).replaceAll("<i>$1</i>");
        return result;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
