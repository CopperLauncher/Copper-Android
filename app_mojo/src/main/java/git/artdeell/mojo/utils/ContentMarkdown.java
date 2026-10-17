package git.artdeell.mojo.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown to HTML for the project pages of Modrinth and the READMEs people paste into them.
 * Covers headings (ATX and setext), paragraphs, nested lists, task lists, block quotes, fenced
 * code, tables (with or without a delimiter row), rules, reference links and the usual inline
 * styles. Raw HTML blocks are passed through untouched, inline HTML is kept and the Markdown
 * around it is still handled. The output is meant for {@link ContentBody} and
 * {@link android.text.Html}, so it only uses tags those understand.
 */
public final class ContentMarkdown {
    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(```+|~~~+)\\s*([\\w+#.-]*).*$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})(?:\\s+(.*?))?\\s*#*\\s*$");
    private static final Pattern SETEXT = Pattern.compile("^\\s{0,3}(=+|-+)\\s*$");
    private static final Pattern RULE = Pattern.compile("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$");
    private static final Pattern TABLE_DELIMITER = Pattern.compile(
            "^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$");
    private static final Pattern HTML_BLOCK = Pattern.compile(
            "^\\s{0,3}</?(p|div|center|table|thead|tbody|tr|td|th|details|summary|iframe|h[1-6]|ul|ol|li|"
                    + "blockquote|pre|section|picture|video|figure|figcaption|dl|dt|dd|style|script)(\\s|/?>|$).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCE_DEFINITION = Pattern.compile(
            "^\\s{0,3}\\[([^\\]]+)\\]:\\s*<?(\\S+?)>?(?:\\s+[\"'(].*)?\\s*$");

    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(\\s*<?([^)\\s>]+)>?(?:\\s+(?:\"[^\"]*\"|'[^']*'))?\\s*\\)");
    private static final Pattern IMAGE_REF = Pattern.compile("!\\[([^\\]]*)\\]\\[([^\\]]*)\\]");
    private static final Pattern LINK = Pattern.compile("\\[((?:[^\\[\\]]|\\[[^\\]]*\\])+)\\]\\(\\s*<?([^)\\s>]*)>?(?:\\s+(?:\"[^\"]*\"|'[^']*'))?\\s*\\)");
    private static final Pattern LINK_REF = Pattern.compile("\\[((?:[^\\[\\]]|\\[[^\\]]*\\])+)\\]\\[([^\\]]*)\\]");
    private static final Pattern LINK_SHORTCUT = Pattern.compile("\\[([^\\[\\]]+)\\](?![\\[(:])");
    private static final Pattern CODE_SPAN = Pattern.compile("(`+)(.+?)\\1");
    private static final Pattern ESCAPED = Pattern.compile("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|~>])");
    private static final Pattern BOLD_ITALIC = Pattern.compile("(\\*\\*\\*|___)(?=\\S)(.+?)(?<=\\S)\\1");
    private static final Pattern BOLD = Pattern.compile("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1");
    private static final Pattern ITALIC_STAR = Pattern.compile("(?<![\\w*])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![\\w*])");
    private static final Pattern ITALIC_UNDERSCORE = Pattern.compile("(?<![\\w])_(?![\\s_])(.+?)(?<![\\s_])_(?![\\w])");
    private static final Pattern STRIKE = Pattern.compile("~~(?=\\S)(.+?)(?<=\\S)~~");
    private static final Pattern ANGLE_AUTOLINK = Pattern.compile("<((?:https?|ftp)://[^>\\s]+)>");
    private static final Pattern BARE_URL = Pattern.compile(
            "(?<![\"'=>\\w/@])((?:https?://|www\\.)[^\\s<>\"]*[^\\s<>\".,;:!?)'\\]])");
    private static final Pattern TAG = Pattern.compile("<(?:/?[a-zA-Z][^>]*|!--.*?--)>", Pattern.DOTALL);
    private static final Pattern STASH = Pattern.compile("\u0000(\\d+)\u0000");

    private ContentMarkdown() {}

    /** @return HTML for the given Markdown */
    public static String toHtml(String markdown) {
        String source = markdown.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
        List<String> lines = new ArrayList<>();
        Map<String, String> references = new HashMap<>();
        collectLines(source, lines, references);
        StringBuilder out = new StringBuilder(source.length() + 512);
        convertBlocks(lines, references, out);
        return out.toString();
    }

    /** Splits the text into lines and pulls the reference definitions out of them */
    private static void collectLines(String source, List<String> lines, Map<String, String> references) {
        boolean inFence = false;
        for (String line : source.split("\n", -1)) {
            if (FENCE.matcher(line).matches()) inFence = !inFence;
            if (!inFence) {
                Matcher definition = REFERENCE_DEFINITION.matcher(line);
                if (definition.matches()) {
                    references.put(definition.group(1).toLowerCase(), definition.group(2));
                    continue;
                }
            }
            lines.add(line);
        }
    }

    private static void convertBlocks(List<String> lines, Map<String, String> refs, StringBuilder out) {
        StringBuilder paragraph = new StringBuilder();
        int i = 0;
        int n = lines.size();
        while (i < n) {
            String line = lines.get(i);
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                flushParagraph(paragraph, refs, out);
                i++;
                continue;
            }

            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                flushParagraph(paragraph, refs, out);
                String marker = fence.group(1);
                StringBuilder code = new StringBuilder();
                i++;
                while (i < n) {
                    String candidate = lines.get(i).trim();
                    if (isClosingFence(candidate, marker.charAt(0), marker.length())) {
                        i++;
                        break;
                    }
                    if (code.length() > 0) code.append('\n');
                    code.append(lines.get(i));
                    i++;
                }
                out.append("<pre>").append(escape(code.toString())).append("</pre>");
                continue;
            }

            if (HTML_BLOCK.matcher(line).matches()) {
                flushParagraph(paragraph, refs, out);
                while (i < n && !lines.get(i).trim().isEmpty()) {
                    out.append(lines.get(i)).append('\n');
                    i++;
                }
                continue;
            }
            if (trimmed.startsWith("<!--")) {
                flushParagraph(paragraph, refs, out);
                while (i < n) {
                    boolean closes = lines.get(i).contains("-->");
                    i++;
                    if (closes) break;
                }
                continue;
            }

            if (paragraph.length() > 0 && SETEXT.matcher(line).matches()) {
                int level = trimmed.charAt(0) == '=' ? 1 : 2;
                String text = paragraph.toString();
                paragraph.setLength(0);
                appendHeading(out, level, inline(text, refs));
                i++;
                continue;
            }

            if (RULE.matcher(line).matches()) {
                flushParagraph(paragraph, refs, out);
                out.append("<hr>");
                i++;
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flushParagraph(paragraph, refs, out);
                String text = heading.group(2) == null ? "" : heading.group(2);
                appendHeading(out, heading.group(1).length(), inline(text, refs));
                i++;
                continue;
            }

            if (trimmed.startsWith(">")) {
                flushParagraph(paragraph, refs, out);
                List<String> quoted = new ArrayList<>();
                while (i < n && lines.get(i).trim().startsWith(">")) {
                    String quote = lines.get(i).trim().substring(1);
                    if (quote.startsWith(" ")) quote = quote.substring(1);
                    quoted.add(quote);
                    i++;
                }
                StringBuilder inner = new StringBuilder();
                convertBlocks(quoted, refs, inner);
                out.append("<blockquote>").append(inner).append("</blockquote>");
                continue;
            }

            int tableEnd = tableEnd(lines, i, paragraph.length() > 0);
            if (tableEnd > i) {
                flushParagraph(paragraph, refs, out);
                appendTable(lines.subList(i, tableEnd), refs, out);
                i = tableEnd;
                continue;
            }

            if (isListItem(line)) {
                flushParagraph(paragraph, refs, out);
                i = convertList(lines, i, refs, out);
                continue;
            }

            if (paragraph.length() > 0) {
                char last = paragraph.charAt(paragraph.length() - 1);
                paragraph.append(last == '\n' ? "" : " ");
            }
            String text = line.trim();
            if (line.endsWith("  ") || text.endsWith("\\")) {
                if (text.endsWith("\\")) text = text.substring(0, text.length() - 1);
                paragraph.append(text).append('\n');
            } else {
                paragraph.append(text);
            }
            i++;
        }
        flushParagraph(paragraph, refs, out);
    }

    private static boolean isClosingFence(String line, char fenceChar, int minLength) {
        if (line.length() < minLength) return false;
        for (int k = 0; k < line.length(); k++) {
            if (line.charAt(k) != fenceChar) return false;
        }
        return true;
    }

    private static boolean endsWith(StringBuilder builder, String suffix) {
        int offset = builder.length() - suffix.length();
        return offset >= 0 && builder.indexOf(suffix, offset) == offset;
    }

    private static void appendHeading(StringBuilder out, int level, String content) {
        if (out.length() > 0 && !endsWith(out, "<br>")) out.append("<br>");
        int tag = Math.min(level + 1, 6);
        out.append("<h").append(tag).append('>').append(content).append("</h").append(tag).append('>');
    }

    private static void flushParagraph(StringBuilder paragraph, Map<String, String> refs, StringBuilder out) {
        if (paragraph.length() == 0) return;
        String html = inline(paragraph.toString().replace("\n", "<br>"), refs);
        out.append(html).append("<br><br>");
        paragraph.setLength(0);
    }

    // ── Lists ─────────────────────────────────────────────────────────────────

    private static boolean isListItem(String line) {
        if (RULE.matcher(line).matches()) return false;
        return BULLET.matcher(line).matches() || NUMBERED.matcher(line).matches();
    }

    /** @return the index of the first line after the list */
    private static int convertList(List<String> lines, int start, Map<String, String> refs, StringBuilder out) {
        List<Integer> indents = new ArrayList<>();
        List<Integer> counters = new ArrayList<>();
        int openLists = 0;
        int i = start;
        int n = lines.size();

        StringBuilder itemText = null;
        int itemLevel = 0;
        boolean itemOrdered = false;
        int itemNumber = 0;

        while (i <= n) {
            String line = i < n ? lines.get(i) : null;
            boolean item = line != null && isListItem(line);
            boolean blank = line != null && line.trim().isEmpty();
            boolean continuation = line != null && !item && !blank && itemText != null && line.startsWith("  ")
                    && !HTML_BLOCK.matcher(line).matches();

            if (continuation) {
                itemText.append(' ').append(line.trim());
                i++;
                continue;
            }

            if (itemText != null) {
                openLists = emitItem(out, openLists, itemLevel, itemOrdered, itemNumber,
                        itemTextToHtml(itemText.toString(), refs));
                itemText = null;
            }

            if (blank) {
                // A blank line only continues the list when another item follows
                int next = i + 1;
                while (next < n && lines.get(next).trim().isEmpty()) next++;
                if (next < n && isListItem(lines.get(next))) {
                    i = next;
                    continue;
                }
                break;
            }
            if (!item) break;

            Matcher bullet = BULLET.matcher(line);
            Matcher numbered = NUMBERED.matcher(line);
            int indent;
            String content;
            if (bullet.matches()) {
                itemOrdered = false;
                indent = bullet.group(1).length();
                content = bullet.group(2);
            } else {
                numbered.matches();
                itemOrdered = true;
                indent = numbered.group(1).length();
                content = numbered.group(3);
                itemNumber = Integer.parseInt(numbered.group(2));
            }

            while (!indents.isEmpty() && indents.get(indents.size() - 1) > indent) {
                indents.remove(indents.size() - 1);
                if (counters.size() > indents.size()) counters.remove(counters.size() - 1);
            }
            if (indents.isEmpty() || indents.get(indents.size() - 1) < indent) indents.add(indent);
            itemLevel = indents.size() - 1;

            itemText = new StringBuilder(content);
            i++;
        }
        while (openLists > 0) {
            out.append("</ul>");
            openLists--;
        }
        out.append("<br>");
        return i;
    }

    private static String itemTextToHtml(String text, Map<String, String> refs) {
        if (text.startsWith("[ ] ")) text = "\u2610 " + text.substring(4);
        else if (text.startsWith("[x] ") || text.startsWith("[X] ")) text = "\u2611 " + text.substring(4);
        return inline(text, refs);
    }

    /** Bullets use real list tags (hanging indent), numbered items are plain lines with a number */
    private static int emitItem(StringBuilder out, int openLists, int level, boolean ordered, int number, String html) {
        if (ordered) {
            while (openLists > 0) {
                out.append("</ul>");
                openLists--;
            }
            for (int k = 0; k < level; k++) out.append("&nbsp;&nbsp;&nbsp;&nbsp;");
            out.append(number).append(".&nbsp;").append(html).append("<br>");
            return openLists;
        }
        int wanted = level + 1;
        while (openLists < wanted) {
            out.append("<ul>");
            openLists++;
        }
        while (openLists > wanted) {
            out.append("</ul>");
            openLists--;
        }
        out.append("<li>").append(html).append("</li>");
        return openLists;
    }

    // ── Tables ────────────────────────────────────────────────────────────────

    /**
     * @return the index after the table that starts at {@code start}, or {@code start} when
     * there is no table. A table is a run of lines with pipes that has a delimiter row or at
     * least two rows with the same number of columns.
     */
    private static int tableEnd(List<String> lines, int start, boolean afterParagraph) {
        String first = lines.get(start);
        if (first.indexOf('|') < 0 || start + 1 >= lines.size()) return start;
        String second = lines.get(start + 1);
        if (second.indexOf('|') < 0 && !TABLE_DELIMITER.matcher(second).matches()) return start;

        boolean delimited = TABLE_DELIMITER.matcher(second).matches() && second.indexOf('-') >= 0
                && (second.indexOf('|') >= 0 || first.indexOf('|') >= 0);
        int columns = splitRow(first).size();
        if (columns < 2 && !delimited) return start;
        if (!delimited && afterParagraph) return start;

        int end = start + 1;
        if (delimited) end = start + 2;
        while (end < lines.size()) {
            String row = lines.get(end);
            if (row.trim().isEmpty() || row.indexOf('|') < 0) break;
            if (!delimited && splitRow(row).size() != columns) break;
            end++;
        }
        if (!delimited && end - start < 2) return start;
        return end;
    }

    private static void appendTable(List<String> rows, Map<String, String> refs, StringBuilder out) {
        boolean delimited = rows.size() > 1 && TABLE_DELIMITER.matcher(rows.get(1)).matches();
        List<String> alignments = delimited ? splitRow(rows.get(1)) : new ArrayList<String>();
        out.append("<table>");
        for (int r = 0; r < rows.size(); r++) {
            if (delimited && r == 1) continue;
            boolean header = r == 0;
            out.append("<tr>");
            List<String> cells = splitRow(rows.get(r));
            for (int c = 0; c < cells.size(); c++) {
                String tag = header ? "th" : "td";
                String align = c < alignments.size() ? alignment(alignments.get(c)) : null;
                out.append('<').append(tag);
                if (align != null) out.append(" align=\"").append(align).append('"');
                out.append('>').append(inline(cells.get(c), refs)).append("</").append(tag).append('>');
            }
            out.append("</tr>");
        }
        out.append("</table>");
    }

    private static String alignment(String delimiterCell) {
        String cell = delimiterCell.trim();
        boolean left = cell.startsWith(":");
        boolean right = cell.endsWith(":");
        if (left && right) return "center";
        if (right) return "right";
        return null;
    }

    private static List<String> splitRow(String row) {
        String text = row.trim();
        if (text.startsWith("|")) text = text.substring(1);
        if (text.endsWith("|") && !text.endsWith("\\|")) text = text.substring(0, text.length() - 1);
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inCode = false;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == '\\' && k + 1 < text.length() && text.charAt(k + 1) == '|') {
                cell.append('|');
                k++;
            } else if (c == '`') {
                inCode = !inCode;
                cell.append(c);
            } else if (c == '|' && !inCode) {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString().trim());
        return cells;
    }

    // ── Inline ────────────────────────────────────────────────────────────────

    /** Inline Markdown. Tags that are already in the text are kept and their attributes are not touched. */
    static String inline(String line, Map<String, String> refs) {
        String text = ANGLE_AUTOLINK.matcher(line).replaceAll("<a href=\"$1\">$1</a>");
        StringBuilder out = new StringBuilder(text.length() + 32);
        Matcher tags = TAG.matcher(text);
        int last = 0;
        int anchorDepth = 0;
        while (tags.find()) {
            out.append(inlineText(text.substring(last, tags.start()), refs, anchorDepth > 0));
            String tag = tags.group();
            out.append(tag);
            String lower = tag.toLowerCase();
            if (lower.startsWith("<a ") || lower.equals("<a>")) anchorDepth++;
            else if (lower.startsWith("</a") && anchorDepth > 0) anchorDepth--;
            last = tags.end();
        }
        out.append(inlineText(text.substring(last), refs, anchorDepth > 0));
        return out.toString();
    }

    private static String inlineText(String text, Map<String, String> refs, boolean inAnchor) {
        if (text.isEmpty()) return text;
        List<String> stash = new ArrayList<>();

        text = replaceWith(CODE_SPAN, text, stash, m -> "<tt>" + escape(m.group(2).trim()) + "</tt>");
        text = replaceWith(ESCAPED, text, stash, m -> escape(m.group(1)));
        text = replaceWith(IMAGE, text, stash,
                m -> "<img src=\"" + attr(m.group(2)) + "\" alt=\"" + attr(m.group(1)) + "\">");
        text = replaceWith(IMAGE_REF, text, stash, m -> {
            String target = refs.get((m.group(2).isEmpty() ? m.group(1) : m.group(2)).toLowerCase());
            if (target == null) return null;
            return "<img src=\"" + attr(target) + "\" alt=\"" + attr(m.group(1)) + "\">";
        });
        if (!inAnchor) {
            text = replaceWith(LINK, text, stash,
                    m -> "<a href=\"" + attr(m.group(2)) + "\">" + styled(m.group(1)) + "</a>");
            text = replaceWith(LINK_REF, text, stash, m -> {
                String target = refs.get((m.group(2).isEmpty() ? m.group(1) : m.group(2)).toLowerCase());
                if (target == null) return null;
                return "<a href=\"" + attr(target) + "\">" + styled(m.group(1)) + "</a>";
            });
            if (!refs.isEmpty()) {
                text = replaceWith(LINK_SHORTCUT, text, stash, m -> {
                    String target = refs.get(m.group(1).toLowerCase());
                    if (target == null) return null;
                    return "<a href=\"" + attr(target) + "\">" + styled(m.group(1)) + "</a>";
                });
            }
            text = replaceWith(BARE_URL, text, stash, m -> {
                String url = m.group(1);
                String href = url.startsWith("www.") ? "https://" + url : url;
                return "<a href=\"" + attr(href) + "\">" + url + "</a>";
            });
        } else {
            text = LINK.matcher(text).replaceAll("$1");
        }
        text = styled(text);

        Matcher restore = STASH.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (restore.find()) {
            restore.appendReplacement(buffer, Matcher.quoteReplacement(stash.get(Integer.parseInt(restore.group(1)))));
        }
        restore.appendTail(buffer);
        return buffer.toString();
    }

    private static String styled(String text) {
        text = BOLD_ITALIC.matcher(text).replaceAll("<b><i>$2</i></b>");
        text = BOLD.matcher(text).replaceAll("<b>$2</b>");
        text = ITALIC_STAR.matcher(text).replaceAll("<i>$1</i>");
        text = ITALIC_UNDERSCORE.matcher(text).replaceAll("<i>$1</i>");
        text = STRIKE.matcher(text).replaceAll("<del>$1</del>");
        return text;
    }

    private interface Replacer {
        String replace(Matcher matcher);
    }

    /** Replaces matches with placeholders so the patterns that run later don't touch the result */
    private static String replaceWith(Pattern pattern, String text, List<String> stash, Replacer replacer) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = replacer.replace(matcher);
            String value;
            if (replacement == null) {
                value = matcher.group();
            } else {
                stash.add(replacement);
                value = "\u0000" + (stash.size() - 1) + "\u0000";
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String attr(String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;");
    }
}
