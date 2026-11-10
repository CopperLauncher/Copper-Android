package git.artdeell.mojo.home;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceIconProvider;
import git.artdeell.mojo.utils.ThemeColors;

/** The view of one tile. Its content adapts to the size the tile has */
final class HomeTileView extends FrameLayout {
    interface Callbacks {
        void onPlay(HomeTile tile);
        void onEdit(HomeTile tile);
    }

    final HomeTile tile;
    private final HomeTileType mType;
    private final Callbacks mCallbacks;

    private LinearLayout mContent;
    private ImageView mIcon;
    private TextView mTitle;
    private TextView mSubtitle;
    private LinearLayout mTextColumn;
    private LinearLayout mButtons;
    private ImageButton mEditButton;
    private ImageButton mPlayButton;
    private boolean mInstanceMissing;

    HomeTileView(@NonNull Context context, @NonNull HomeTile tile, @NonNull HomeTileType type,
                 @NonNull Callbacks callbacks) {
        super(context);
        this.tile = tile;
        mType = type;
        mCallbacks = callbacks;
        if (type == HomeTileType.SEPARATOR) buildSeparator();
        else if (type == HomeTileType.INSTANCE) buildInstance();
        else buildAction();
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private void buildSeparator() {
        View line = new View(getContext());
        line.setBackgroundColor(ThemeColors.get(this, com.google.android.material.R.attr.colorOutlineVariant));
        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.CENTER_VERTICAL);
        params.leftMargin = params.rightMargin = dp(8);
        addView(line, params);
    }

    private void buildAction() {
        setBackgroundResource(R.drawable.bg_home_tile);
        setClickable(true);
        setFocusable(true);
        mContent = new LinearLayout(getContext());
        mContent.setGravity(Gravity.CENTER);
        mContent.setPadding(dp(6), dp(6), dp(6), dp(6));

        mIcon = new ImageView(getContext());
        mIcon.setImageResource(mType.icon);
        mIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mContent.addView(mIcon, new LinearLayout.LayoutParams(dp(32), dp(32)));

        mTitle = newText(13, true);
        mTitle.setText(mType.label);
        mTitle.setMaxLines(2);
        mContent.addView(mTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(mContent, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentDescription(getResources().getString(mType.label));
    }

    private void buildInstance() {
        setBackgroundResource(R.drawable.bg_home_tile);
        setClickable(true);
        setFocusable(true);
        mContent = new LinearLayout(getContext());
        mContent.setGravity(Gravity.CENTER);
        mContent.setPadding(dp(8), dp(6), dp(8), dp(6));

        mIcon = new ImageView(getContext());
        mIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mIcon.setImageResource(R.drawable.ic_mojo_full);
        mContent.addView(mIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        mTextColumn = new LinearLayout(getContext());
        mTextColumn.setOrientation(LinearLayout.VERTICAL);
        mTitle = newText(13, true);
        mSubtitle = newText(11, false);
        mSubtitle.setTextColor(ThemeColors.get(this, com.google.android.material.R.attr.colorOnSurfaceVariant));
        mTextColumn.addView(mTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mTextColumn.addView(mSubtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mContent.addView(mTextColumn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mButtons = new LinearLayout(getContext());
        mButtons.setGravity(Gravity.CENTER);
        mEditButton = newIconButton(R.drawable.ic_px_edit, R.string.home_instance_edit);
        mPlayButton = newIconButton(R.drawable.ic_px_play, R.string.main_play);
        mEditButton.setOnClickListener(v -> mCallbacks.onEdit(tile));
        mPlayButton.setOnClickListener(v -> mCallbacks.onPlay(tile));
        mButtons.addView(mEditButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
        mButtons.addView(mPlayButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
        mContent.addView(mButtons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(mContent, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private TextView newText(float sp, boolean bold) {
        TextView text = new TextView(getContext());
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        text.setTextColor(ThemeColors.get(this, com.google.android.material.R.attr.colorOnSurface));
        text.setGravity(Gravity.CENTER);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setMaxLines(1);
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
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setContentDescription(getResources().getString(description));
        // A long press on a button drags the whole tile
        button.setOnLongClickListener(v -> performLongClick());
        return button;
    }

    /** Fills an instance tile, null when the instance doesn't exist anymore */
    void setInstance(@Nullable Instance instance) {
        if (mType != HomeTileType.INSTANCE) return;
        Resources resources = getResources();
        mInstanceMissing = instance == null;
        mPlayButton.setEnabled(!mInstanceMissing);
        mPlayButton.setAlpha(mInstanceMissing ? 0.4f : 1f);
        mEditButton.setEnabled(!mInstanceMissing);
        mEditButton.setAlpha(mInstanceMissing ? 0.4f : 1f);
        if (instance == null) {
            mIcon.setImageResource(R.drawable.ic_mojo_full);
            mTitle.setText(R.string.home_instance_missing);
            mSubtitle.setText("");
            setContentDescription(resources.getString(R.string.home_instance_missing));
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
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mType == HomeTileType.INSTANCE) arrangeInstance(w, h);
        else if (mType != HomeTileType.SEPARATOR) arrangeAction(w, h);
    }

    private void arrangeAction(int w, int h) {
        boolean wide = w > h * 1.6f && h < dp(100);
        mContent.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        boolean showLabel = w >= dp(80) && h >= dp(50);
        mTitle.setVisibility(showLabel ? VISIBLE : GONE);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) mTitle.getLayoutParams();
        params.leftMargin = wide ? dp(8) : 0;
        params.topMargin = wide ? 0 : dp(4);
        mTitle.setLayoutParams(params);
    }

    private void arrangeInstance(int w, int h) {
        boolean compact = w < dp(96) || h < dp(48);
        boolean wide = !compact && w > h * 1.6f && h < dp(110);
        boolean tinyHeight = h < dp(60);
        mContent.setOrientation(wide || (compact && w > h * 1.6f) ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        mTextColumn.setVisibility(compact ? GONE : VISIBLE);
        mSubtitle.setVisibility(tinyHeight ? GONE : VISIBLE);
        mEditButton.setVisibility(compact ? GONE : VISIBLE);
        mTitle.setGravity(wide ? Gravity.START : Gravity.CENTER);
        mSubtitle.setGravity(wide ? Gravity.START : Gravity.CENTER);

        LinearLayout.LayoutParams textParams = (LinearLayout.LayoutParams) mTextColumn.getLayoutParams();
        textParams.width = wide ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
        textParams.weight = wide ? 1 : 0;
        textParams.leftMargin = wide ? dp(8) : 0;
        textParams.topMargin = wide ? 0 : dp(4);
        mTextColumn.setLayoutParams(textParams);

        int iconSize = compact ? Math.min(dp(40), Math.max(dp(24), Math.min(w, h) - dp(36))) : dp(40);
        LinearLayout.LayoutParams iconParams = (LinearLayout.LayoutParams) mIcon.getLayoutParams();
        iconParams.width = iconParams.height = iconSize;
        mIcon.setLayoutParams(iconParams);
    }

    boolean isInstanceMissing() {
        return mInstanceMissing;
    }
}
