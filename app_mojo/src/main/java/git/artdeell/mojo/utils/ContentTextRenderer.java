package git.artdeell.mojo.utils;

import android.os.Build;
import android.text.Html;
import android.text.Spanned;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the descriptions and changelogs of Modrinth (Markdown) and CurseForge (HTML) into styled
 * text a TextView can show. The Markdown support is deliberately small, it covers what project
 * pages actually use: headings, lists, quotes, bold, italic, code, links and rules. Pictures are
 * dropped, the gallery tab is where pictures live.
 */
public final class ContentTextRenderer {
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
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

    @NonNull
    public static CharSequence render(@Nullable String text, boolean isHtml) {
        if (text == null || text.trim().isEmpty()) return "";
        String html = isHtml ? text : markdownToHtml(text);
        html = HTML_IMAGE.matcher(html).replaceAll("");
        html = HTML_BLOCK_TAGS.matcher(html).replaceAll("<br>");
        html = BREAK_RUN.matcher(html).replaceAll("<br><br>");
        return fromHtml(html);
    }

    private static Spanned fromHtml(String html) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT);
        }
        //noinspection deprecation
        return Html.fromHtml(html);
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
        String result = IMAGE.matcher(line).replaceAll("");
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
