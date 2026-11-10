package git.artdeell.mojo.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import git.artdeell.mojo.R;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.utils.ThemeColors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The home screen: a fixed grid of tiles. Long pressing a tile or the empty space starts the edit
 * mode, where tiles are dragged to move them, resized with the corner handle and removed with the
 * badge. Everything snaps to the grid and tiles never overlap.
 */
public class HomeTileBoard extends ViewGroup implements HomeTileView.Callbacks {
    public interface Listener {
        void onTileClick(@NonNull HomeTile tile);
        void onTilePlay(@NonNull HomeTile tile);
        void onTileEdit(@NonNull HomeTile tile);
        /** Long press on the empty space */
        void onEmptyLongPress();
        void onEditModeChanged(boolean editMode);
        /** The layout changed and has to be saved */
        void onTilesChanged(@NonNull List<HomeTile> tiles);
    }

    private enum Mode { NONE, DRAG, RESIZE, REMOVE }

    private final List<HomeTile> mTiles = new ArrayList<>();
    private final Map<HomeTile, HomeTileView> mViews = new HashMap<>();
    private Listener mListener;
    private boolean mEditMode;

    private int mRows = HomeTileStore.MIN_ROWS;
    private float mCellW, mCellH;
    private final int mPad, mGap, mBadgeRadius, mBadgeInset;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private Drawable mResizeIcon, mRemoveIcon;
    private final GestureDetector mDetector;

    private HomeTile mActive;
    private Mode mMode = Mode.NONE;
    private float mLastX, mLastY, mGrabDX, mGrabDY;
    private int mDropX, mDropY;
    private boolean mDropValid;

    public HomeTileBoard(Context context) {
        this(context, null);
    }

    public HomeTileBoard(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mPad = dp(8);
        mGap = dp(3);
        mBadgeRadius = dp(12);
        mBadgeInset = dp(14);
        setWillNotDraw(false);
        mDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (mActive == null && mListener != null && hitTile(e.getX(), e.getY()) == null) {
                    mListener.onEmptyLongPress();
                }
            }
        });
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    public boolean isEditMode() {
        return mEditMode;
    }

    @NonNull
    public List<HomeTile> getTiles() {
        return mTiles;
    }

    // ─── Content ─────────────────────────────────────────────────────────────

    public void setTiles(@NonNull List<HomeTile> tiles) {
        removeAllViews();
        mViews.clear();
        mTiles.clear();
        for (HomeTile tile : tiles) {
            HomeTileType type = tile.getType();
            if (type == null) continue;
            mTiles.add(tile);
            addTileView(tile, type);
        }
        if (mRows > 0 && getWidth() > 0 && fitAll()) notifyChanged();
        requestLayout();
    }

    private void addTileView(HomeTile tile, HomeTileType type) {
        HomeTileView view = new HomeTileView(getContext(), tile, type, this);
        view.setOnClickListener(v -> {
            if (mListener != null && !mEditMode) mListener.onTileClick(tile);
        });
        view.setOnLongClickListener(v -> {
            if (mEditMode) return true;
            startEditWithDrag(tile);
            return true;
        });
        mViews.put(tile, view);
        addView(view);
    }

    /** Gives the instance tiles their instance, selectedKey is used by the tiles that follow the selection */
    public void setInstanceData(@NonNull Map<String, Instance> instances, @Nullable String selectedKey) {
        for (HomeTile tile : mTiles) {
            if (!HomeTileType.INSTANCE.name().equals(tile.type)) continue;
            String key = tile.instanceKey == null || tile.instanceKey.isEmpty() ? selectedKey : tile.instanceKey;
            HomeTileView view = mViews.get(tile);
            if (view != null) view.setInstance(key == null ? null : instances.get(key));
        }
    }

    /** Adds a tile in the first free place, shrinking it if needed. @return false when there is no room */
    public boolean addTile(@NonNull HomeTile tile) {
        HomeTileType type = tile.getType();
        if (type == null) return false;
        if (!findFreeSpot(tile)) return false;
        mTiles.add(tile);
        addTileView(tile, type);
        requestLayout();
        notifyChanged();
        return true;
    }

    public void removeTile(@NonNull HomeTile tile) {
        HomeTileView view = mViews.remove(tile);
        mTiles.remove(tile);
        if (view != null) removeView(view);
        requestLayout();
        invalidate();
        notifyChanged();
    }

    private void notifyChanged() {
        if (mListener != null) mListener.onTilesChanged(mTiles);
    }

    // ─── Edit mode ───────────────────────────────────────────────────────────

    public void setEditMode(boolean editMode) {
        if (mEditMode == editMode) return;
        mEditMode = editMode;
        if (!editMode) cancelGesture();
        invalidate();
        if (mListener != null) mListener.onEditModeChanged(editMode);
    }

    private void startEditWithDrag(HomeTile tile) {
        setEditMode(true);
        getParent().requestDisallowInterceptTouchEvent(true);
        beginDrag(tile, mLastX, mLastY);
    }

    // ─── Geometry ────────────────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        computeGrid(width, height);
        for (HomeTile tile : mTiles) {
            View view = mViews.get(tile);
            if (view == null) continue;
            RectF rect = tileRect(tile.x, tile.y, tile.w, tile.h, new RectF());
            view.measure(MeasureSpec.makeMeasureSpec(Math.max(0, Math.round(rect.width())), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, Math.round(rect.height())), MeasureSpec.EXACTLY));
        }
    }

    private void computeGrid(int width, int height) {
        float innerW = Math.max(1, width - 2 * mPad);
        float innerH = Math.max(1, height - 2 * mPad);
        mCellW = innerW / HomeTileStore.COLUMNS;
        int rows = Math.max(HomeTileStore.MIN_ROWS, Math.round(innerH / mCellW));
        boolean rowsChanged = rows != mRows;
        mRows = rows;
        mCellH = innerH / mRows;
        if ((rowsChanged || mTilesNeedFit) && fitAll()) post(this::notifyChanged);
        mTilesNeedFit = false;
    }

    private boolean mTilesNeedFit = true;

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        RectF rect = new RectF();
        for (HomeTile tile : mTiles) {
            View view = mViews.get(tile);
            if (view == null) continue;
            tileRect(tile.x, tile.y, tile.w, tile.h, rect);
            view.layout(Math.round(rect.left), Math.round(rect.top), Math.round(rect.right), Math.round(rect.bottom));
        }
    }

    private RectF tileRect(int x, int y, int w, int h, RectF out) {
        out.set(mPad + x * mCellW + mGap, mPad + y * mCellH + mGap,
                mPad + (x + w) * mCellW - mGap, mPad + (y + h) * mCellH - mGap);
        return out;
    }

    /** Puts every tile inside the grid and out of the way of the others. @return whether anything moved */
    private boolean fitAll() {
        boolean changed = false;
        List<HomeTile> placed = new ArrayList<>();
        for (HomeTile tile : new ArrayList<>(mTiles)) {
            HomeTileType type = tile.getType();
            int w = Math.max(1, Math.min(tile.w, HomeTileStore.COLUMNS));
            int h = type != null && type.lockHeight ? 1 : Math.max(1, Math.min(tile.h, mRows));
            int x = Math.max(0, Math.min(tile.x, HomeTileStore.COLUMNS - w));
            int y = Math.max(0, Math.min(tile.y, mRows - h));
            boolean moved = x != tile.x || y != tile.y || w != tile.w || h != tile.h;
            tile.x = x; tile.y = y; tile.w = w; tile.h = h;
            if (collides(placed, tile, tile.x, tile.y, tile.w, tile.h)) {
                // Placed against the tiles that were handled before it only
                moved = true;
                if (!findFreeSpot(tile, placed)) {
                    mTiles.remove(tile);
                    HomeTileView view = mViews.remove(tile);
                    if (view != null) removeView(view);
                    changed = true;
                    continue;
                }
            }
            changed |= moved;
            placed.add(tile);
        }
        return changed;
    }

    private boolean collides(List<HomeTile> others, HomeTile self, int x, int y, int w, int h) {
        for (HomeTile other : others) {
            if (other == self) continue;
            if (x < other.x + other.w && x + w > other.x && y < other.y + other.h && y + h > other.y) return true;
        }
        return false;
    }

    private boolean isFree(HomeTile self, int x, int y, int w, int h) {
        if (x < 0 || y < 0 || x + w > HomeTileStore.COLUMNS || y + h > mRows) return false;
        return !collides(mTiles, self, x, y, w, h);
    }

    private boolean findFreeSpot(HomeTile tile) {
        return findFreeSpot(tile, mTiles);
    }

    /** First free place for the tile among the others, shrinking it step by step when it doesn't fit */
    private boolean findFreeSpot(HomeTile tile, List<HomeTile> others) {
        HomeTileType type = tile.getType();
        int w = Math.min(tile.w, HomeTileStore.COLUMNS);
        int h = type != null && type.lockHeight ? 1 : Math.min(tile.h, mRows);
        while (w >= 1 && h >= 1) {
            for (int y = 0; y + h <= mRows; y++) {
                for (int x = 0; x + w <= HomeTileStore.COLUMNS; x++) {
                    if (!collides(others, tile, x, y, w, h)) {
                        tile.x = x; tile.y = y; tile.w = w; tile.h = h;
                        return true;
                    }
                }
            }
            if (w >= h || (type != null && type.lockHeight)) w--;
            else h--;
        }
        return false;
    }

    /** The free place closest to the wanted one for a tile of this size, null if there is none */
    @Nullable
    private int[] nearestFree(HomeTile tile, int wantedX, int wantedY, int w, int h) {
        int[] best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int y = 0; y + h <= mRows; y++) {
            for (int x = 0; x + w <= HomeTileStore.COLUMNS; x++) {
                if (!isFree(tile, x, y, w, h)) continue;
                int distance = Math.abs(x - wantedX) + Math.abs(y - wantedY);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new int[]{x, y};
                }
            }
        }
        return best;
    }

    // ─── Touch ───────────────────────────────────────────────────────────────

    @Nullable
    private HomeTile hitTile(float x, float y) {
        RectF rect = new RectF();
        for (int i = mTiles.size() - 1; i >= 0; i--) {
            HomeTile tile = mTiles.get(i);
            tileRect(tile.x, tile.y, tile.w, tile.h, rect);
            if (rect.contains(x, y)) return tile;
        }
        return null;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        mLastX = ev.getX();
        mLastY = ev.getY();
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mActive != null) return true;
        if (mEditMode && ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            return hitTile(ev.getX(), ev.getY()) != null;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float x = ev.getX(), y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mEditMode) {
                    HomeTile tile = hitTile(x, y);
                    if (tile != null) {
                        beginGesture(tile, x, y);
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mActive != null) {
                    updateGesture(x, y);
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (mActive != null) {
                    finishGesture(x, y, true);
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (mActive != null) {
                    finishGesture(x, y, false);
                    return true;
                }
                break;
            default:
                break;
        }
        return mDetector.onTouchEvent(ev) || mActive != null;
    }

    /** Decides what a touch that landed on a tile in the edit mode does */
    private void beginGesture(HomeTile tile, float x, float y) {
        RectF rect = tileRect(tile.x, tile.y, tile.w, tile.h, new RectF());
        float slop = mBadgeRadius + dp(6);
        float resizeX = rect.right - mBadgeInset, resizeY = rect.bottom - mBadgeInset;
        float removeX = rect.right - mBadgeInset, removeY = rect.top + mBadgeInset;
        if (Math.hypot(x - removeX, y - removeY) <= slop) {
            mActive = tile;
            mMode = Mode.REMOVE;
        } else if (Math.hypot(x - resizeX, y - resizeY) <= slop) {
            mActive = tile;
            mMode = Mode.RESIZE;
        } else {
            beginDrag(tile, x, y);
        }
        getParent().requestDisallowInterceptTouchEvent(true);
    }

    private void beginDrag(HomeTile tile, float x, float y) {
        HomeTileView view = mViews.get(tile);
        if (view == null) return;
        mActive = tile;
        mMode = Mode.DRAG;
        mGrabDX = x - view.getLeft();
        mGrabDY = y - view.getTop();
        mDropX = tile.x;
        mDropY = tile.y;
        mDropValid = true;
        view.bringToFront();
        view.setTranslationZ(dp(8));
        view.setAlpha(0.92f);
        invalidate();
    }

    private void updateGesture(float x, float y) {
        if (mActive == null) return;
        if (mMode == Mode.DRAG) {
            HomeTileView view = mViews.get(mActive);
            if (view == null) return;
            float left = x - mGrabDX, top = y - mGrabDY;
            view.setTranslationX(left - view.getLeft());
            view.setTranslationY(top - view.getTop());
            int wantedX = Math.round((left - mPad - mGap) / mCellW);
            int wantedY = Math.round((top - mPad - mGap) / mCellH);
            wantedX = Math.max(0, Math.min(wantedX, HomeTileStore.COLUMNS - mActive.w));
            wantedY = Math.max(0, Math.min(wantedY, mRows - mActive.h));
            int[] spot = nearestFree(mActive, wantedX, wantedY, mActive.w, mActive.h);
            mDropValid = spot != null;
            if (spot != null) {
                mDropX = spot[0];
                mDropY = spot[1];
            }
        } else if (mMode == Mode.RESIZE) {
            HomeTileType type = mActive.getType();
            int newW = Math.round((x - mPad - mActive.x * mCellW) / mCellW);
            int newH = Math.round((y - mPad - mActive.y * mCellH) / mCellH);
            newW = Math.max(1, Math.min(newW, HomeTileStore.COLUMNS - mActive.x));
            newH = type != null && type.lockHeight ? 1 : Math.max(1, Math.min(newH, mRows - mActive.y));
            if ((newW != mActive.w || newH != mActive.h) && isFree(mActive, mActive.x, mActive.y, newW, newH)) {
                mActive.w = newW;
                mActive.h = newH;
                requestLayout();
            }
        }
        invalidate();
    }

    private void finishGesture(float x, float y, boolean commit) {
        HomeTile tile = mActive;
        Mode mode = mMode;
        if (tile == null) return;
        mActive = null;
        mMode = Mode.NONE;
        HomeTileView view = mViews.get(tile);
        if (mode == Mode.REMOVE) {
            RectF rect = tileRect(tile.x, tile.y, tile.w, tile.h, new RectF());
            boolean inside = Math.hypot(x - (rect.right - mBadgeInset), y - (rect.top + mBadgeInset))
                    <= mBadgeRadius + dp(10);
            if (commit && inside) removeTile(tile);
        } else if (mode == Mode.DRAG && view != null) {
            float visualLeft = view.getLeft() + view.getTranslationX();
            float visualTop = view.getTop() + view.getTranslationY();
            if (commit && mDropValid && (tile.x != mDropX || tile.y != mDropY)) {
                tile.x = mDropX;
                tile.y = mDropY;
                notifyChanged();
            }
            RectF target = tileRect(tile.x, tile.y, tile.w, tile.h, new RectF());
            view.setTranslationX(visualLeft - target.left);
            view.setTranslationY(visualTop - target.top);
            view.animate().translationX(0).translationY(0).translationZ(0).alpha(1f).setDuration(150).start();
            requestLayout();
        } else if (mode == Mode.RESIZE) {
            notifyChanged();
        }
        invalidate();
    }

    private void cancelGesture() {
        if (mActive != null) finishGesture(0, 0, false);
    }

    // ─── Drawing ─────────────────────────────────────────────────────────────

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (mEditMode && mActive != null && mMode == Mode.DRAG && mDropValid) {
            tileRect(mDropX, mDropY, mActive.w, mActive.h, mRect);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(withAlpha(ThemeColors.primary(getContext()), 70));
            canvas.drawRoundRect(mRect, dp(16), dp(16), mPaint);
        }
        super.dispatchDraw(canvas);
        if (!mEditMode) return;

        int primary = ThemeColors.primary(getContext());
        int onPrimary = ThemeColors.get(getContext(), com.google.android.material.R.attr.colorOnPrimary);
        if (mResizeIcon == null) mResizeIcon = tinted(R.drawable.ic_px_resize, onPrimary);
        if (mRemoveIcon == null) mRemoveIcon = tinted(R.drawable.ic_close, onPrimary);
        for (HomeTile tile : mTiles) {
            HomeTileView view = mViews.get(tile);
            if (view == null) continue;
            mRect.set(view.getLeft() + view.getTranslationX(), view.getTop() + view.getTranslationY(),
                    view.getRight() + view.getTranslationX(), view.getBottom() + view.getTranslationY());
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(dp(2));
            mPaint.setColor(primary);
            canvas.drawRoundRect(mRect, dp(16), dp(16), mPaint);

            mPaint.setStyle(Paint.Style.FILL);
            drawBadge(canvas, mRect.right - mBadgeInset, mRect.top + mBadgeInset, primary, mRemoveIcon);
            drawBadge(canvas, mRect.right - mBadgeInset, mRect.bottom - mBadgeInset, primary, mResizeIcon);
        }
    }

    private void drawBadge(Canvas canvas, float cx, float cy, int color, Drawable icon) {
        mPaint.setColor(color);
        canvas.drawCircle(cx, cy, mBadgeRadius, mPaint);
        int half = dp(8);
        icon.setBounds(Math.round(cx) - half, Math.round(cy) - half, Math.round(cx) + half, Math.round(cy) + half);
        icon.draw(canvas);
    }

    private Drawable tinted(int resource, int color) {
        Drawable drawable = DrawableCompat.wrap(ContextCompat.getDrawable(getContext(), resource).mutate());
        DrawableCompat.setTint(drawable, color);
        return drawable;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mTilesNeedFit = true;
    }

    // ─── HomeTileView.Callbacks ──────────────────────────────────────────────

    @Override
    public void onPlay(HomeTile tile) {
        if (mListener != null && !mEditMode) mListener.onTilePlay(tile);
    }

    @Override
    public void onEdit(HomeTile tile) {
        if (mListener != null && !mEditMode) mListener.onTileEdit(tile);
    }
}
