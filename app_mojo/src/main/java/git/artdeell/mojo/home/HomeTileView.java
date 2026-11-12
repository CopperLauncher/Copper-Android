package git.artdeell.mojo.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.drawable.DrawableCompat;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceIconProvider;
import git.artdeell.mojo.utils.ThemeColors;

/**
 * The view of one tile. It places its children itself, while it is measured, from the size the
 * tile has: a bigger tile shows more (a label, the version, the edit button) and a small one
 * shows less, but whatever is shown always fits and is centered. In the edit mode it also draws
 * the outline and the remove / resize badges, so they move with the tile.
 */
final class HomeTileView extends ViewGroup {
    interface Callbacks {
        void onPlay(HomeTile tile);
        void onEdit(HomeTile tile);
    }

    enum Badge { NONE, REMOVE, RESIZE }

    final HomeTile tile;
    private final HomeTileType mType;
    private final Callbacks mCallbacks;

    private ImageView mIcon;
    private TextView mTitle;
    private TextView mSubtitle;
    private ImageButton mEditButton;
    private ImageButton mPlayButton;
    private View mLineA;
    private View mLineB;

    // Where each child goes, null hides it. Filled by plan() while measuring
    private Rect mIconRect, mTitleRect, mSubtitleRect, mEditRect, mPlayRect, mLineARect, mLineBRect;

    private TileBackground mBackground;
    private boolean mEditing;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRectF = new RectF();
    private Drawable mRemoveIcon, mResizeIcon;

    private final int mCorner = dp(16);
    private final int mBadgeRadius = dp(9);
    private final int mBadgeInset = dp(11);

    HomeTileView(@NonNull Context context, @NonNull HomeTile tile, @NonNull HomeTileType type,
                 @NonNull Callbacks callbacks) {
        super(context);
        this.tile = tile;
        mType = type;
        mCallbacks = callbacks;
        if (type == HomeTileType.SEPARATOR) buildSeparator();
        else if (type == HomeTileType.TEXT) buildText();
        else if (type == HomeTileType.INSTANCE) buildInstance();
        else buildAction();
        applyStyle();
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    // ─── Building ────────────────────────────────────────────────────────────

    private void buildSeparator() {
        mLineA = newLine();
        mLineB = newLine();
        mTitle = newText(12, false);
        mTitle.setTextColor(ThemeColors.get(this, com.google.android.material.R.attr.colorOnSurfaceVariant));
        mTitle.setGravity(Gravity.CENTER);
        addView(mLineA);
        addView(mLineB);
        addView(mTitle);
        bindLabel();
    }

    /** A title like "Quick Actions": text with no tile behind it */
    private void buildText() {
        mTitle = newText(12, true);
        mTitle.setTextColor(ThemeColors.get(this, com.google.android.material.R.attr.colorOnSurfaceVariant));
        mTitle.setAllCaps(true);
        mTitle.setLetterSpacing(0.08f);
        mTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        addView(mTitle);
        bindLabel();
    }

    private View newLine() {
        View line = new View(getContext());
        line.setBackgroundColor(ThemeColors.get(this, com.google.android.material.R.attr.colorOutlineVariant));
        return line;
    }

    private void buildAction() {
        setClickable(true);
        setFocusable(true);
        mIcon = newIcon();
        mIcon.setImageResource(mType.icon);
        mTitle = newText(13, true);
        mTitle.setText(mType.label);
        addView(mIcon);
        addView(mTitle);
        setContentDescription(getResources().getString(mType.label));
    }

    private void buildInstance() {
        setClickable(true);
        setFocusable(true);
        mIcon = newIcon();
        mIcon.setImageResource(R.drawable.ic_mojo_full);
        mTitle = newText(13, true);
        mSubtitle = newText(11, false);
        mSubtitle.setTextColor(ThemeColors.get(this, com.google.android.material.R.attr.colorOnSurfaceVariant));
        mEditButton = newIconButton(R.drawable.ic_px_edit, R.string.home_instance_edit);
        mPlayButton = newIconButton(R.drawable.ic_px_play, R.string.main_play);
        mEditButton.setOnClickListener(v -> mCallbacks.onEdit(tile));
        mPlayButton.setOnClickListener(v -> mCallbacks.onPlay(tile));
        addView(mIcon);
        addView(mTitle);
        addView(mSubtitle);
        addView(mEditButton);
        addView(mPlayButton);
    }

    private ImageView newIcon() {
        ImageView icon = new ImageView(getContext());
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private TextView newText(float sp, boolean bold) {
        TextView text = new TextView(getContext());
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        text.setTextColor(ThemeColors.get(this, com.google.android.material.R.attr.colorOnSurface));
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxLines(1);
        text.setIncludeFontPadding(false);
        if (bold) text.setTypeface(text.getTypeface(), Typeface.BOLD);
        return text;
    }

    private ImageButton newIconButton(int icon, int description) {
        ImageButton button = new ImageButton(getContext());
        TypedValue value = new TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true);
        button.setBackgroundResource(value.resourceId);
        button.setImageResource(icon);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setPadding(dp(6), dp(6), dp(6), dp(6));
        button.setContentDescription(getResources().getString(description));
        return button;
    }

    // ─── Content ─────────────────────────────────────────────────────────────

    /** Separator and text tiles: shows the text of the tile */
    void bindLabel() {
        if (mType != HomeTileType.SEPARATOR && mType != HomeTileType.TEXT) return;
        mTitle.setText(tile.label == null ? "" : tile.label);
        requestLayout();
    }

    /** Fills an instance tile, null when the instance doesn't exist anymore */
    void setInstance(@Nullable Instance instance) {
        if (mType != HomeTileType.INSTANCE) return;
        Resources resources = getResources();
        boolean missing = instance == null;
        mPlayButton.setEnabled(!missing);
        mPlayButton.setAlpha(missing ? 0.4f : 1f);
        mEditButton.setEnabled(!missing);
        mEditButton.setAlpha(missing ? 0.4f : 1f);
        if (instance == null) {
            mIcon.setImageResource(R.drawable.ic_mojo_full);
            mTitle.setText(R.string.home_instance_missing);
            mSubtitle.setText("");
            setContentDescription(resources.getString(R.string.home_instance_missing));
            requestLayout();
            return;
        }
        mIcon.setImageDrawable(InstanceIconProvider.fetchIcon(resources, instance));
        String name = Tools.validOrNullString(instance.name);
        if (name == null) name = instance.getKey();
        String version = Tools.validOrNullString(instance.versionId);
        if (Instance.VERSION_LATEST_RELEASE.equalsIgnoreCase(version)) version = resources.getString(R.string.profiles_latest_release);
        else if (Instance.VERSION_LATEST_SNAPSHOT.equalsIgnoreCase(version)) version = resources.getString(R.string.profiles_latest_snapshot);
        mTitle.setText(name);
        mSubtitle.setText(version == null ? "" : version);
        setContentDescription(name);
        requestLayout();
    }

    /** Reads the tile opacity and the frosted glass setting again */
    void applyStyle() {
        if (mType == HomeTileType.SEPARATOR || mType == HomeTileType.TEXT) return;
        if (mBackground == null) {
            mBackground = new TileBackground(this, mCorner);
            GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(mCorner);
            mask.setColor(0xFFFFFFFF);
            int ripple = ColorUtils.setAlphaComponent(ThemeColors.onSurface(getContext()), 0x26);
            setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), mBackground, mask));
        }
        mBackground.setStyle(HomeTileStyle.getOpacity(getContext()), HomeTileStyle.isFrosted(getContext()));
        invalidate();
    }

    void setEditing(boolean editing) {
        if (mEditing == editing) return;
        mEditing = editing;
        invalidate();
    }

    // ─── Layout ──────────────────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(w, h);
        mIconRect = mTitleRect = mSubtitleRect = mEditRect = mPlayRect = mLineARect = mLineBRect = null;
        if (mType == HomeTileType.SEPARATOR) planSeparator(w, h);
        else if (mType == HomeTileType.TEXT) planText(w, h);
        else if (mType == HomeTileType.INSTANCE) planInstance(w, h);
        else planAction(w, h);
        measureTo(mIcon, mIconRect);
        measureTo(mTitle, mTitleRect);
        measureTo(mSubtitle, mSubtitleRect);
        measureTo(mEditButton, mEditRect);
        measureTo(mPlayButton, mPlayRect);
        measureTo(mLineA, mLineARect);
        measureTo(mLineB, mLineBRect);
    }

    private void measureTo(@Nullable View view, @Nullable Rect rect) {
        if (view == null) return;
        int visibility = rect == null || rect.width() <= 0 || rect.height() <= 0 ? GONE : VISIBLE;
        if (view.getVisibility() != visibility) view.setVisibility(visibility);
        if (visibility == GONE) return;
        view.measure(MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        place(mIcon, mIconRect);
        place(mTitle, mTitleRect);
        place(mSubtitle, mSubtitleRect);
        place(mEditButton, mEditRect);
        place(mPlayButton, mPlayRect);
        place(mLineA, mLineARect);
        place(mLineB, mLineBRect);
    }

    private void place(@Nullable View view, @Nullable Rect rect) {
        if (view == null || rect == null || view.getVisibility() == GONE) return;
        view.layout(rect.left, rect.top, rect.right, rect.bottom);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Rect rect(int left, int top, int width, int height) {
        return new Rect(left, top, left + width, top + height);
    }

    private void setTextStyle(TextView view, int maxLines, int gravity) {
        if (view.getMaxLines() != maxLines) view.setMaxLines(maxLines);
        if (view.getGravity() != gravity) view.setGravity(gravity);
    }

    /** A plain button: the icon and the label side by side when it is wide, stacked otherwise */
    private void planAction(int w, int h) {
        int pad = dp(6);
        int icon = clamp(Math.round(Math.min(w, h) * 0.45f), dp(18), dp(32));
        boolean wide = w >= h * 1.7f;
        boolean showLabel = wide ? w >= dp(88) : w >= dp(68) && h >= dp(64);
        int lineHeight = mTitle.getLineHeight();
        if (showLabel && wide) {
            int gap = dp(8);
            int lines = h >= dp(56) ? 2 : 1;
            int available = w - 2 * pad - icon - gap;
            int textWidth = Math.min(available, Math.round(mTitle.getPaint().measureText(mTitle.getText().toString())) + dp(2));
            int textHeight = Math.min(h - dp(4), lineHeight * lines);
            int left = (w - (icon + gap + textWidth)) / 2;
            mIconRect = rect(left, (h - icon) / 2, icon, icon);
            mTitleRect = rect(left + icon + gap, (h - textHeight) / 2, textWidth, textHeight);
            setTextStyle(mTitle, lines, Gravity.START | Gravity.CENTER_VERTICAL);
            return;
        }
        if (showLabel) {
            int lines = h >= dp(92) ? 2 : 1;
            int gap = dp(4);
            int textHeight = lineHeight * lines;
            int group = icon + gap + textHeight;
            if (group <= h - 2 * dp(4)) {
                int top = (h - group) / 2;
                mIconRect = rect((w - icon) / 2, top, icon, icon);
                mTitleRect = rect(pad, top + icon + gap, w - 2 * pad, textHeight);
                setTextStyle(mTitle, lines, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
                return;
            }
        }
        // Too small for a label: the icon alone, in the exact middle
        mIconRect = rect((w - icon) / 2, (h - icon) / 2, icon, icon);
    }

    private void planInstance(int w, int h) {
        int pad = dp(8);
        int titleHeight = mTitle.getLineHeight();
        int subtitleHeight = mSubtitle.getLineHeight();
        boolean horizontal = w >= h * 1.5f && h < dp(120);
        if (horizontal) {
            int icon = clamp(h - dp(12), dp(24), dp(44));
            int button = clamp(h - dp(8), dp(28), dp(40));
            int gap = dp(8);
            int right = w - pad;
            mPlayRect = rect(right - button, (h - button) / 2, button, button);
            right -= button;
            int left = pad + icon + gap;
            // The edit button only gets a place when the text still has room next to it
            if (right - button - left - gap >= dp(72)) {
                mEditRect = rect(right - button, (h - button) / 2, button, button);
                right -= button;
            }
            mIconRect = rect(pad, (h - icon) / 2, icon, icon);
            int textWidth = right - gap - left;
            if (textWidth >= dp(40)) {
                boolean subtitle = h >= dp(44);
                int group = titleHeight + (subtitle ? subtitleHeight : 0);
                int top = (h - group) / 2;
                mTitleRect = rect(left, top, textWidth, titleHeight);
                if (subtitle) mSubtitleRect = rect(left, top + titleHeight, textWidth, subtitleHeight);
                setTextStyle(mTitle, 1, Gravity.START | Gravity.CENTER_VERTICAL);
                setTextStyle(mSubtitle, 1, Gravity.START | Gravity.CENTER_VERTICAL);
            }
            return;
        }

        // Stacked: icon, name, version, buttons. Each level adds one row and is only used when it fits
        int icon = dp(32), button = dp(36), gap = dp(4), padV = dp(6);
        boolean twoButtons = w >= 2 * button + dp(12);
        int withVersion = 2 * padV + icon + gap + titleHeight + subtitleHeight + gap + button;
        int withName = 2 * padV + icon + gap + titleHeight + gap + button;
        int iconAndButtons = 2 * padV + icon + gap + button;
        if (h < iconAndButtons) {
            // Tiny: only the play button
            int size = clamp(Math.min(w, h) - dp(8), dp(24), dp(40));
            mPlayRect = rect((w - size) / 2, (h - size) / 2, size, size);
            return;
        }
        boolean name = h >= withName;
        boolean version = h >= withVersion;
        int group = icon + gap + (name ? titleHeight : 0) + (version ? subtitleHeight : 0) + gap + button;
        int top = (h - group) / 2;
        mIconRect = rect((w - icon) / 2, top, icon, icon);
        int y = top + icon + gap;
        if (name) {
            mTitleRect = rect(pad, y, w - 2 * pad, titleHeight);
            setTextStyle(mTitle, 1, Gravity.CENTER);
            y += titleHeight;
        }
        if (version) {
            mSubtitleRect = rect(pad, y, w - 2 * pad, subtitleHeight);
            setTextStyle(mSubtitle, 1, Gravity.CENTER);
            y += subtitleHeight;
        }
        y += gap;
        if (twoButtons) {
            int spacing = dp(2);
            mEditRect = rect(w / 2 - button - spacing, y, button, button);
            mPlayRect = rect(w / 2 + spacing, y, button, button);
        } else {
            mPlayRect = rect((w - button) / 2, y, button, button);
        }
    }

    private void planText(int w, int h) {
        int pad = dp(12);
        int textHeight = Math.min(h, mTitle.getLineHeight());
        mTitleRect = rect(pad, (h - textHeight) / 2, Math.max(0, w - 2 * pad), textHeight);
        setTextStyle(mTitle, 1, Gravity.START | Gravity.CENTER_VERTICAL);
    }

    /** A line, with the label of the tile in the middle of it when there is one */
    private void planSeparator(int w, int h) {
        int pad = dp(8), thickness = dp(2), gap = dp(8);
        int lineTop = (h - thickness) / 2;
        String label = tile.label == null ? "" : tile.label.trim();
        if (label.isEmpty()) {
            mLineARect = rect(pad, lineTop, w - 2 * pad, thickness);
            return;
        }
        int available = w - 2 * pad;
        int textWidth = Math.min(available, Math.round(mTitle.getPaint().measureText(label)) + dp(2));
        int textHeight = Math.min(h, mTitle.getLineHeight());
        int textLeft = pad + (available - textWidth) / 2;
        mTitleRect = rect(textLeft, (h - textHeight) / 2, textWidth, textHeight);
        setTextStyle(mTitle, 1, Gravity.CENTER);
        int leftLine = textLeft - gap - pad;
        int rightLine = (w - pad) - (textLeft + textWidth + gap);
        if (leftLine > dp(4)) mLineARect = rect(pad, lineTop, leftLine, thickness);
        if (rightLine > dp(4)) mLineBRect = rect(textLeft + textWidth + gap, lineTop, rightLine, thickness);
    }

    // ─── Edit mode ───────────────────────────────────────────────────────────

    /** Which badge is under the point, in the coordinates of this view */
    @NonNull
    Badge badgeAt(float x, float y) {
        float slop = mBadgeRadius + dp(7);
        float badgeX = getWidth() - mBadgeInset;
        double toRemove = Math.hypot(x - badgeX, y - mBadgeInset);
        double toResize = Math.hypot(x - badgeX, y - (getHeight() - mBadgeInset));
        if (toRemove <= slop && toRemove <= toResize) return Badge.REMOVE;
        if (toResize <= slop) return Badge.RESIZE;
        return Badge.NONE;
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!mEditing) return;
        int primary = ThemeColors.primary(getContext());
        int onPrimary = ThemeColors.get(getContext(), com.google.android.material.R.attr.colorOnPrimary);
        if (mRemoveIcon == null) mRemoveIcon = tinted(R.drawable.ic_close, onPrimary);
        if (mResizeIcon == null) mResizeIcon = tinted(R.drawable.ic_px_resize, onPrimary);

        float stroke = dp(2);
        mRectF.set(stroke / 2, stroke / 2, getWidth() - stroke / 2, getHeight() - stroke / 2);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(stroke);
        mPaint.setColor(primary);
        canvas.drawRoundRect(mRectF, mCorner, mCorner, mPaint);

        mPaint.setStyle(Paint.Style.FILL);
        drawBadge(canvas, getWidth() - mBadgeInset, mBadgeInset, primary, mRemoveIcon);
        drawBadge(canvas, getWidth() - mBadgeInset, getHeight() - mBadgeInset, primary, mResizeIcon);
    }

    private void drawBadge(Canvas canvas, float cx, float cy, int color, Drawable icon) {
        mPaint.setColor(color);
        canvas.drawCircle(cx, cy, mBadgeRadius, mPaint);
        int half = dp(6);
        icon.setBounds(Math.round(cx) - half, Math.round(cy) - half, Math.round(cx) + half, Math.round(cy) + half);
        icon.draw(canvas);
    }

    private Drawable tinted(int resource, int color) {
        Drawable drawable = DrawableCompat.wrap(ContextCompat.getDrawable(getContext(), resource).mutate());
        DrawableCompat.setTint(drawable, color);
        return drawable;
    }

    // ─── Background ──────────────────────────────────────────────────────────

    /** The rounded background: the tile color with an opacity, over a blurred copy of the wallpaper when frosted */
    private static final class TileBackground extends Drawable {
        private final View mHost;
        private final float mRadius;
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path mClip = new Path();
        private final RectF mBounds = new RectF();
        private final RectF mDestination = new RectF();
        private int mOpacity = 100;
        private boolean mFrosted;

        TileBackground(@NonNull View host, float radius) {
            mHost = host;
            mRadius = radius;
        }

        void setStyle(int opacity, boolean frosted) {
            mOpacity = opacity;
            mFrosted = frosted;
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            mBounds.set(bounds);
            if (mFrosted) {
                Bitmap backdrop = WallpaperBackdrop.getBitmap();
                if (backdrop != null && WallpaperBackdrop.mapTo(mHost, mDestination)) {
                    mClip.reset();
                    mClip.addRoundRect(mBounds, mRadius, mRadius, Path.Direction.CW);
                    canvas.save();
                    canvas.clipPath(mClip);
                    canvas.drawBitmap(backdrop, null, mDestination, mBitmapPaint);
                    canvas.restore();
                }
            }
            int color = ThemeColors.surfaceContainerHigh(mHost.getContext());
            // A frosted tile never covers the blurred wallpaper completely, or it would not show at any opacity
            float alpha = mFrosted ? mOpacity * 0.75f : mOpacity;
            mFill.setColor(ColorUtils.setAlphaComponent(color, Math.round(alpha * 2.55f)));
            canvas.drawRoundRect(mBounds, mRadius, mRadius, mFill);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(@Nullable android.graphics.ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
