package git.artdeell.mojo.utils;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Html;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.AlignmentSpan;
import android.text.style.StyleSpan;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.divider.MaterialDivider;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A content description (Modrinth Markdown or CurseForge HTML) turned into views: styled text with
 * pictures in it (PNG, JPEG, GIF, WebP and SVG badges), centered paragraphs, tables, code blocks,
 * rules, collapsible sections and embedded videos / pages as link cards.
 * <p>
 * {@link #parse} does the slow part and can run on any thread, {@link #bind} creates the views
 * on the UI thread. Pictures arrive later and make their text lay itself out again.
 */
public final class ContentBody {
    private static final int ALIGN_START = 0, ALIGN_CENTER = 1, ALIGN_END = 2;

    private static final Pattern SPECIAL = Pattern.compile(
            "<(hr|iframe|video|embed|pre|table|details|center|div|p|section)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOISE = Pattern.compile(
            "<!--.*?-->|<script\\b.*?</script>|<style\\b.*?</style>|<head\\b.*?</head>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SVG = Pattern.compile("<svg\\b.*?</svg>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IMG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PICTURE = Pattern.compile("</?(picture|source|figure|figcaption)\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDERED = Pattern.compile("<ol\\b[^>]*>(.*?)</ol>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LIST_ITEM = Pattern.compile("<li\\b[^>]*>(.*?)(?:</li>|(?=<li\\b)|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ROW = Pattern.compile("<tr\\b[^>]*>(.*?)(?:</tr>|(?=<tr\\b)|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<(th|td)\\b([^>]*)>(.*?)(?:</\\1>|(?=<t[hd]\\b)|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TABLE_TAGS = Pattern.compile("</?(thead|tbody|tfoot|caption|colgroup|col)\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUMMARY = Pattern.compile("<summary\\b[^>]*>(.*?)</summary>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ALIGN = Pattern.compile("(?:align\\s*=|text-align\\s*:)\\s*[\"']?\\s*(center|right|end)", Pattern.CASE_INSENSITIVE);
    private static final Pattern YOUTUBE = Pattern.compile(
            "(?:youtube(?:-nocookie)?\\.com/(?:embed|v)/|youtu\\.be/)([\\w-]{6,})", Pattern.CASE_INSENSITIVE);
    private static final String HINT = "#__size__:";

    private static final class Block {
        static final int TEXT = 0, CODE = 1, TABLE = 2, EMBED = 3, RULE = 4;

        final int type;
        CharSequence text = "";
        String url;
        List<List<Block>> rows;
        boolean header;
        TextView view;
        private boolean relayoutQueued;

        Block(int type) {
            this.type = type;
        }

        void requestRelayout() {
            final TextView target = view;
            if (target == null || relayoutQueued) return;
            relayoutQueued = true;
            target.post(() -> {
                relayoutQueued = false;
                if (view == target) target.setText(target.getText());
            });
        }
    }

    private final List<Block> mBlocks = new ArrayList<>();
    private final List<Future<?>> mLoads = Collections.synchronizedList(new ArrayList<>());
    private final int mFallbackWidth;
    private final float mDensity;
    private volatile boolean mCancelled;

    private ContentBody(int fallbackWidth, float density) {
        mFallbackWidth = Math.max(fallbackWidth, 1);
        mDensity = density;
    }

    /**
     * @param fallbackWidth width in pixels pictures are scaled to until their text is on screen
     * @param density display density, used for pictures that come with a size in CSS pixels
     */
    @NonNull
    public static ContentBody parse(@Nullable String text, boolean isHtml, int fallbackWidth, float density) {
        ContentBody body = new ContentBody(fallbackWidth, density);
        if (text == null || text.trim().isEmpty()) return body;
        String html = isHtml ? text : ContentMarkdown.toHtml(text);
        html = NOISE.matcher(html).replaceAll("");
        html = inlineSvgToImages(html);
        html = PICTURE.matcher(html).replaceAll("");
        html = orderedListsToLines(html);
        html = encodeImageHints(html);
        body.split(html, ALIGN_START, body.mBlocks);
        return body;
    }

    public boolean isEmpty() {
        return mBlocks.isEmpty();
    }

    /** Stops the pictures that are still downloading, call when the views are gone */
    public void cancel() {
        mCancelled = true;
        synchronized (mLoads) {
            for (Future<?> load : mLoads) load.cancel(true);
            mLoads.clear();
        }
        detach(mBlocks);
    }

    private static void detach(List<Block> blocks) {
        for (Block block : blocks) {
            block.view = null;
            if (block.rows != null) {
                for (List<Block> row : block.rows) detach(row);
            }
        }
    }

    // ── Views ─────────────────────────────────────────────────────────────────

    /** Replaces the children of the container with the views of this description. UI thread only. */
    public void bind(@NonNull Activity activity, @NonNull ViewGroup container) {
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(container.getContext());
        for (Block block : mBlocks) {
            View view = createView(activity, inflater, container, block);
            if (view != null) container.addView(view);
        }
    }

    @Nullable
    private View createView(Activity activity, LayoutInflater inflater, ViewGroup parent, Block block) {
        switch (block.type) {
            case Block.TEXT: {
                TextView view = (TextView) inflater.inflate(R.layout.item_content_text, parent, false);
                bindText(view, block);
                return view;
            }
            case Block.CODE: {
                View root = inflater.inflate(R.layout.item_content_code, parent, false);
                ((TextView) root.findViewById(R.id.content_code_text)).setText(block.text);
                return root;
            }
            case Block.RULE: {
                MaterialDivider divider = new MaterialDivider(parent.getContext());
                int margin = Math.round(8 * mDensity);
                ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.topMargin = margin;
                params.bottomMargin = margin;
                divider.setLayoutParams(params);
                return divider;
            }
            case Block.TABLE:
                return createTable(inflater, parent, block);
            case Block.EMBED:
                return createEmbed(activity, inflater, parent, block);
            default:
                return null;
        }
    }

    private void bindText(TextView view, Block block) {
        block.view = view;
        view.setMovementMethod(LinkMovementMethod.getInstance());
        view.setText(block.text);
    }

    private View createTable(LayoutInflater inflater, ViewGroup parent, Block block) {
        TableLayout table = new TableLayout(parent.getContext());
        table.setStretchAllColumns(true);
        table.setShrinkAllColumns(true);
        ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = params.bottomMargin = Math.round(6 * mDensity);
        table.setLayoutParams(params);

        int pad = Math.round(6 * mDensity);
        for (List<Block> cells : block.rows) {
            TableRow row = new TableRow(parent.getContext());
            for (Block cell : cells) {
                TextView view = (TextView) inflater.inflate(R.layout.item_content_text, row, false);
                view.setPadding(pad, pad, pad, pad);
                view.setBackgroundResource(cell.header ? R.drawable.bg_content_table_header
                        : R.drawable.bg_content_table_cell);
                bindText(view, cell);
                row.addView(view, new TableRow.LayoutParams(
                        TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.MATCH_PARENT));
            }
            table.addView(row, new TableLayout.LayoutParams(
                    TableLayout.LayoutParams.MATCH_PARENT, TableLayout.LayoutParams.WRAP_CONTENT));
        }
        return table;
    }

    private View createEmbed(Activity activity, LayoutInflater inflater, ViewGroup parent, Block block) {
        View root = inflater.inflate(R.layout.item_content_embed, parent, false);
        ImageView thumb = root.findViewById(R.id.embed_thumb);
        TextView label = root.findViewById(R.id.embed_label);

        final String url = block.url;
        Matcher youtube = YOUTUBE.matcher(url);
        String openUrl = url;
        if (youtube.find()) {
            String id = youtube.group(1);
            openUrl = "https://www.youtube.com/watch?v=" + id;
            label.setText(R.string.content_embed_youtube);
            root.findViewById(R.id.embed_icon).setVisibility(View.GONE);
            thumb.setVisibility(View.VISIBLE);
            root.findViewById(R.id.embed_play).setVisibility(View.VISIBLE);
            if (!mCancelled) {
                mLoads.add(RemoteImageLoader.load("https://img.youtube.com/vi/" + id + "/hqdefault.jpg", 960, bitmap -> {
                    if (bitmap != null) thumb.setImageBitmap(bitmap);
                }));
            }
        } else {
            label.setText(url);
        }
        final String target = openUrl;
        root.setOnClickListener(v -> Tools.openURL(activity, target));
        return root;
    }

    // ── Splitting the HTML into blocks ────────────────────────────────────────

    private void split(String html, int alignment, List<Block> out) {
        Matcher matcher = SPECIAL.matcher(html);
        StringBuilder run = new StringBuilder();
        int pos = 0;
        while (pos < html.length() && matcher.find(pos)) {
            String name = matcher.group(1).toLowerCase(Locale.ROOT);
            String attrs = matcher.group(2);
            int tagEnd = matcher.end();
            run.append(html, pos, matcher.start());

            switch (name) {
                case "hr":
                    flush(run, alignment, out);
                    out.add(new Block(Block.RULE));
                    pos = tagEnd;
                    break;
                case "iframe":
                case "video":
                case "embed": {
                    int close = indexOfIgnoreCase(html, "</" + name + ">", tagEnd);
                    int end = close < 0 ? tagEnd : close + name.length() + 3;
                    String src = attribute(attrs, "src");
                    if (src == null && close >= 0) {
                        src = attribute(html.substring(tagEnd, close), "src");
                    }
                    flush(run, alignment, out);
                    String url = normalizeUrl(src);
                    if (url != null) {
                        Block embed = new Block(Block.EMBED);
                        embed.url = url;
                        out.add(embed);
                    }
                    pos = end;
                    break;
                }
                case "pre": {
                    int close = indexOfIgnoreCase(html, "</pre>", tagEnd);
                    String inner = html.substring(tagEnd, close < 0 ? html.length() : close);
                    flush(run, alignment, out);
                    String code = toPlainText(inner);
                    if (!code.trim().isEmpty()) {
                        Block block = new Block(Block.CODE);
                        block.text = code.replaceAll("^\\n+|\\s+$", "");
                        out.add(block);
                    }
                    pos = close < 0 ? html.length() : close + 6;
                    break;
                }
                case "table": {
                    int close = indexOfIgnoreCase(html, "</table>", tagEnd);
                    String inner = html.substring(tagEnd, close < 0 ? html.length() : close);
                    flush(run, alignment, out);
                    Block table = buildTable(inner);
                    if (table != null) out.add(table);
                    pos = close < 0 ? html.length() : close + 8;
                    break;
                }
                case "details": {
                    int close = findMatchingClose(html, "details", tagEnd);
                    String inner = html.substring(tagEnd, close < 0 ? html.length() : close);
                    flush(run, alignment, out);
                    Matcher summary = SUMMARY.matcher(inner);
                    if (summary.find()) {
                        Block title = textBlock(summary.group(1), alignment, true);
                        if (title != null) out.add(title);
                        inner = inner.substring(0, summary.start()) + inner.substring(summary.end());
                    }
                    split(inner, alignment, out);
                    pos = close < 0 ? html.length() : close + 10;
                    break;
                }
                default: {
                    // center, div, p, section: only the aligned ones need their own run
                    int aligned = name.equals("center") ? ALIGN_CENTER : alignmentOf(attrs);
                    if (aligned == ALIGN_START) {
                        run.append(matcher.group());
                        pos = tagEnd;
                        break;
                    }
                    int close = findMatchingClose(html, name, tagEnd);
                    String inner = html.substring(tagEnd, close < 0 ? html.length() : close);
                    flush(run, alignment, out);
                    split(inner, aligned, out);
                    pos = close < 0 ? html.length() : close + name.length() + 3;
                    break;
                }
            }
        }
        if (pos < html.length()) run.append(html, pos, html.length());
        flush(run, alignment, out);
    }

    private void flush(StringBuilder run, int alignment, List<Block> out) {
        if (run.length() == 0) return;
        String html = run.toString();
        run.setLength(0);
        Block block = textBlock(html, alignment, false);
        if (block != null) out.add(block);
    }

    @Nullable
    private Block textBlock(String html, int alignment, boolean bold) {
        if (html.trim().isEmpty()) return null;
        Block block = new Block(Block.TEXT);
        Spanned spanned = fromHtml(html, new RemoteImageGetter(block));
        SpannableStringBuilder text = new SpannableStringBuilder(spanned);
        trim(text);
        if (text.length() == 0) return null;
        if (bold) text.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (alignment != ALIGN_START) {
            text.setSpan(new AlignmentSpan.Standard(alignment == ALIGN_CENTER
                            ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_OPPOSITE),
                    0, text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        block.text = text;
        return block;
    }

    private static void trim(SpannableStringBuilder text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        text.delete(end, text.length());
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        if (start > 0) text.delete(0, start);
    }

    private static Spanned fromHtml(String html, Html.ImageGetter imageGetter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT, imageGetter, null);
        }
        //noinspection deprecation
        return Html.fromHtml(html, imageGetter, null);
    }

    @Nullable
    private Block buildTable(String html) {
        String inner = TABLE_TAGS.matcher(html).replaceAll("");
        List<List<Block>> rows = new ArrayList<>();
        Matcher rowMatcher = ROW.matcher(inner);
        int columns = 0;
        while (rowMatcher.find()) {
            List<Block> cells = new ArrayList<>();
            Matcher cellMatcher = CELL.matcher(rowMatcher.group(1));
            while (cellMatcher.find()) {
                boolean header = cellMatcher.group(1).equalsIgnoreCase("th");
                Block cell = textBlock(cellMatcher.group(3), alignmentOf(cellMatcher.group(2)), header);
                if (cell == null) {
                    cell = new Block(Block.TEXT);
                    cell.text = "";
                }
                cell.header = header;
                cells.add(cell);
            }
            if (cells.isEmpty()) continue;
            columns = Math.max(columns, cells.size());
            rows.add(cells);
        }
        if (rows.isEmpty()) return null;
        for (List<Block> cells : rows) {
            while (cells.size() < columns) {
                Block filler = new Block(Block.TEXT);
                filler.header = cells.get(0).header;
                cells.add(filler);
            }
        }
        Block table = new Block(Block.TABLE);
        table.rows = rows;
        return table;
    }

    // ── HTML helpers ──────────────────────────────────────────────────────────

    private static int alignmentOf(@Nullable String attrs) {
        if (attrs == null) return ALIGN_START;
        Matcher matcher = ALIGN.matcher(attrs);
        if (!matcher.find()) return ALIGN_START;
        return matcher.group(1).equalsIgnoreCase("center") ? ALIGN_CENTER : ALIGN_END;
    }

    @Nullable
    private static String attribute(String tag, String name) {
        Matcher matcher = Pattern.compile("(?<![\\w-])" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>\"']+))",
                Pattern.CASE_INSENSITIVE).matcher(tag);
        if (!matcher.find()) return null;
        for (int i = 1; i <= 3; i++) {
            if (matcher.group(i) != null) return Html.fromHtml(matcher.group(i)).toString().trim();
        }
        return null;
    }

    @Nullable
    private static String normalizeUrl(@Nullable String source) {
        if (source == null) return null;
        String url = source.trim();
        if (url.startsWith("//")) url = "https:" + url;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        return null;
    }

    private static int indexOfIgnoreCase(String text, String needle, int from) {
        int last = text.length() - needle.length();
        for (int i = from; i <= last; i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    /** @return the start of the closing tag that matches an opening tag that ended at {@code from} */
    private static int findMatchingClose(String html, String name, int from) {
        Matcher matcher = Pattern.compile("<(/?)" + name + "\\b[^>]*?(/?)>", Pattern.CASE_INSENSITIVE).matcher(html);
        int depth = 1;
        int pos = from;
        while (matcher.find(pos)) {
            if (!matcher.group(1).isEmpty()) {
                depth--;
                if (depth == 0) return matcher.start();
            } else if (matcher.group(2).isEmpty()) {
                depth++;
            }
            pos = matcher.end();
        }
        return -1;
    }

    private static String toPlainText(String html) {
        return Html.fromHtml(html).toString();
    }

    /** HTML lists with numbers become lines that start with their number, Html can't draw those */
    private static String orderedListsToLines(String html) {
        Matcher lists = ORDERED.matcher(html);
        StringBuffer out = new StringBuffer();
        while (lists.find()) {
            StringBuilder lines = new StringBuilder("<br>");
            Matcher items = LIST_ITEM.matcher(lists.group(1));
            int number = 1;
            while (items.find()) {
                lines.append(number++).append(".&nbsp;").append(items.group(1).trim()).append("<br>");
            }
            lists.appendReplacement(out, Matcher.quoteReplacement(lines.toString()));
        }
        lists.appendTail(out);
        return out.toString();
    }

    private static String inlineSvgToImages(String html) {
        Matcher matcher = SVG.matcher(html);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String svg = matcher.group();
            if (!svg.toLowerCase(Locale.ROOT).contains("xmlns")) {
                svg = svg.replaceFirst("(?i)<svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"");
            }
            String encoded = Base64.encodeToString(svg.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    "<img src=\"data:image/svg+xml;base64," + encoded + "\">"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Html.ImageGetter only gets the source of a picture, so the size from the tag travels in it:
     * {@code url HINT width ; height}
     */
    private static String encodeImageHints(String html) {
        Matcher matcher = IMG.matcher(html);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String tag = matcher.group();
            String src = attribute(tag, "src");
            if (src == null) src = attribute(tag, "data-src");
            if (src == null || src.isEmpty()) {
                matcher.appendReplacement(out, "");
                continue;
            }
            String width = digits(attribute(tag, "width"));
            String height = digits(attribute(tag, "height"));
            String encoded = src + HINT + width + ";" + height;
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    "<img src=\"" + encoded.replace("&", "&amp;").replace("\"", "&quot;") + "\">"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String digits(@Nullable String value) {
        if (value == null) return "";
        Matcher matcher = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*(?:px)?\\s*$").matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    // ── Pictures ──────────────────────────────────────────────────────────────

    /** Hands out empty drawables that fill in once their picture is downloaded */
    private final class RemoteImageGetter implements Html.ImageGetter {
        private final Block mBlock;

        RemoteImageGetter(Block block) {
            mBlock = block;
        }

        @Override
        public Drawable getDrawable(String source) {
            RemoteDrawable drawable = new RemoteDrawable();
            if (mCancelled || source == null) return drawable;

            String url = source;
            float hintWidth = 0, hintHeight = 0;
            int hint = source.indexOf(HINT);
            if (hint >= 0) {
                url = source.substring(0, hint);
                String[] size = source.substring(hint + HINT.length()).split(";", -1);
                hintWidth = parse(size.length > 0 ? size[0] : "");
                hintHeight = parse(size.length > 1 ? size[1] : "");
            }
            url = url.trim();
            if (url.startsWith("//")) url = "https:" + url;
            if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("data:")) return drawable;

            final float wantedWidth = hintWidth;
            final float wantedHeight = hintHeight;
            mLoads.add(RemoteImageLoader.load(url, 1600, bitmap -> {
                if (bitmap == null || mCancelled) return;
                int available = mBlock.view != null
                        ? mBlock.view.getWidth() - mBlock.view.getPaddingLeft() - mBlock.view.getPaddingRight()
                        : 0;
                if (available <= 0) available = mFallbackWidth;

                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                if (wantedWidth > 0) {
                    width = Math.max(1, Math.round(wantedWidth * mDensity));
                    height = Math.max(1, Math.round(bitmap.getHeight() * (width / (float) bitmap.getWidth())));
                } else if (wantedHeight > 0) {
                    height = Math.max(1, Math.round(wantedHeight * mDensity));
                    width = Math.max(1, Math.round(bitmap.getWidth() * (height / (float) bitmap.getHeight())));
                }
                if (width > available) {
                    height = Math.max(1, Math.round(height * (available / (float) width)));
                    width = available;
                }
                drawable.setBitmap(bitmap, width, height);
                mBlock.requestRelayout();
            }));
            return drawable;
        }

        private float parse(String value) {
            try {
                return value.isEmpty() ? 0 : Float.parseFloat(value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    private static final class RemoteDrawable extends Drawable {
        private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private Bitmap mBitmap;

        void setBitmap(Bitmap bitmap, int width, int height) {
            mBitmap = bitmap;
            setBounds(0, 0, width, height);
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (mBitmap != null) canvas.drawBitmap(mBitmap, null, getBounds(), mPaint);
        }

        @Override public void setAlpha(int alpha) { mPaint.setAlpha(alpha); }
        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { mPaint.setColorFilter(colorFilter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
