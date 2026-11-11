package git.artdeell.mojo.home;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewGroupOverlay;
import android.view.ViewParent;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.utils.ThemeColors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A grid of tiles, used for the home screen and for the sidebar.
 * <p>
 * Long pressing a tile or the empty space starts the edit mode, on every board at once. There tiles
 * are dragged to move them, even from one board to the other, resized with the corner handle and
 * removed with the badge. Everything snaps to the grid and tiles never overlap.
 * <p>
 * A board is either fixed, the grid fits its height exactly, or scrollable: it is as tall as its
 * tiles plus an empty row, inside a ScrollView, so there is always room to add more.
 */
public class HomeTileBoard extends ViewGroup implements HomeTileView.Callbacks {
    public interface Listener {
        void onTileClick(@NonNull HomeTile tile);
        void onTilePlay(@NonNull HomeTile tile);
        void onTileEdit(@NonNull HomeTile tile);
        /** A tap on a tile while editing, without moving it */
        void onTileTap(@NonNull HomeTile tile);
        /** Long press on the empty space */
        void onEmptyLongPress();
        void onEditModeChanged(boolean editMode);
        /** The layout changed and has to be saved */
        void onTilesChanged(@NonNull List<HomeTile> tiles);
    }

    private enum Mode { NONE, DRAG, RESIZE, REMOVE }

    private static final int MAX_ROWS = 200;
    private static final List<HomeTileBoard> sBoards = new CopyOnWriteArrayList<>();

    private final List<HomeTile> mTiles = new ArrayList<>();
    private final Map<HomeTile, HomeTileView> mViews = new HashMap<>();
    private Listener mListener;
    private boolean mEditMode;

    private int mColumns = HomeTileStore.COLUMNS;
    private boolean mScrollable;
    private int mRows = HomeTileStore.MIN_ROWS;
    private float mCellW, mCellH;
    private boolean mGridReady;
    private boolean mTilesNeedFit = true;
    private final int mPad, mGap, mTouchSlop;

    private Map<String, Instance> mInstances;
    private String mSelectedKey;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final int[] mLocation = new int[2];
    private final GestureDetector mDetector;
    private final WallpaperBackdrop.Listener mBackdropListener = this::invalidateTiles;

    // The gesture on a tile
    private HomeTile mActive;
    private Mode mMode = Mode.NONE;
    private float mRawX, mRawY, mLastX, mLastY, mDownRawX, mDownRawY, mGrabX, mGrabY;
    private boolean mTapCandidate;
    private int mDropX, mDropY, mDragRows;
    private boolean mDropValid;
    /** The other board the tile is over, null when it is over this one */
    private HomeTileBoard mHover;
    // Where a tile dragged from another board would land
    private boolean mExternalShown, mExternalValid;
    private int mExternalX, mExternalY, mExternalW, mExternalH;
    // The picture of the tile that follows the finger, drawn above everything, so it can cross over to the other board
    private BitmapDrawable mProxy;
    private ViewGroupOverlay mProxyOverlay;
    private int mProxyW, mProxyH;

    private final Runnable mAutoScroll = new Runnable() {
        @Override
        public void run() {
            if (mActive == null || mMode != Mode.DRAG) return;
            ScrollView scroll = findScrollView();
            if (scroll != null && mHover == null) {
                Rect visible = new Rect();
                scroll.getLocalVisibleRect(visible);
                scroll.getLocationOnScreen(mLocation);
                int edge = dp(56), step = dp(10);
                int top = mLocation[1], bottom = mLocation[1] + scroll.getHeight();
                if (mRawY < top + edge && scroll.getScrollY() > 0) {
                    scroll.scrollBy(0, -step);
                    updateDrag(mRawX, mRawY);
                } else if (mRawY > bottom - edge) {
                    scroll.scrollBy(0, step);
                    updateDrag(mRawX, mRawY);
                }
            }
            postOnAnimation(this);
        }
    };

    public HomeTileBoard(Context context) {
        this(context, null);
    }

    public HomeTileBoard(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mPad = dp(8);
        mGap = dp(3);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
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

    // ─── Setup ───────────────────────────────────────────────────────────────

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    /** The number of cells across, the sidebar has fewer than the home screen */
    public void setColumns(int columns) {
        if (columns < 1 || columns == mColumns) return;
        mColumns = columns;
        mTilesNeedFit = true;
        requestLayout();
    }

    public int getColumns() {
        return mColumns;
    }

    /** A scrollable board has to be in a ScrollView that fills its viewport */
    public void setScrollable(boolean scrollable) {
        if (mScrollable == scrollable) return;
        mScrollable = scrollable;
        mTilesNeedFit = true;
        requestLayout();
    }

    public boolean isEditMode() {
        return mEditMode;
    }

    /** @return whether any board is in the edit mode */
    public static boolean anyEditing() {
        for (HomeTileBoard board : sBoards) {
            if (board.mEditMode) return true;
        }
        return false;
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
        mTilesNeedFit = true;
        requestLayout();
    }

    private void addTileView(HomeTile tile, HomeTileType type) {
        HomeTileView view = new HomeTileView(getContext(), tile, type, this);
        view.setEditing(mEditMode);
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
        if (type == HomeTileType.INSTANCE && mInstances != null) applyInstance(tile, view);
    }

    /** Gives the instance tiles their instance, selectedKey is used by the tiles that follow the selection */
    public void setInstanceData(@NonNull Map<String, Instance> instances, @Nullable String selectedKey) {
        mInstances = instances;
        mSelectedKey = selectedKey;
        for (HomeTile tile : mTiles) {
            HomeTileView view = mViews.get(tile);
            if (view != null && HomeTileType.INSTANCE.name().equals(tile.type)) applyInstance(tile, view);
        }
    }

    private void applyInstance(HomeTile tile, HomeTileView view) {
        String key = tile.instanceKey == null || tile.instanceKey.isEmpty() ? mSelectedKey : tile.instanceKey;
        view.setInstance(key == null || mInstances == null ? null : mInstances.get(key));
    }

    /**
     * Adds a tile in the first free place, shrinking it if needed.
     * @return false when there is no room, which only happens on a fixed board
     */
    public boolean addTile(@NonNull HomeTile tile) {
        HomeTileType type = tile.getType();
        if (type == null) return false;
        if (!findFreeSpot(tile, mTiles)) return false;
        mTiles.add(tile);
        addTileView(tile, type);
        requestLayout();
        notifyChanged();
        return true;
    }

    public void removeTile(@NonNull HomeTile tile) {
        detachTile(tile);
        invalidate();
        notifyChanged();
    }

    private void detachTile(HomeTile tile) {
        HomeTileView view = mViews.remove(tile);
        mTiles.remove(tile);
        if (view != null) removeView(view);
        requestLayout();
    }

    private void notifyChanged() {
        if (mListener != null) mListener.onTilesChanged(mTiles);
    }

    /** The text of a separator or text tile changed */
    public void refreshTile(@NonNull HomeTile tile) {
        HomeTileView view = mViews.get(tile);
        if (view != null) view.bindLabel();
    }

    /** The tile opacity or the frosted glass setting changed */
    public void refreshStyle() {
        for (HomeTileView view : mViews.values()) view.applyStyle();
    }

    private void invalidateTiles() {
        post(() -> {
            for (HomeTileView view : mViews.values()) view.invalidate();
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        WallpaperBackdrop.addListener(mBackdropListener);
        sBoards.add(this);
        // A board that appears while the others are being edited is edited too
        for (HomeTileBoard other : sBoards) {
            if (other != this && other.mEditMode) {
                applyEditMode(true);
                break;
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        WallpaperBackdrop.removeListener(mBackdropListener);
        sBoards.remove(this);
        removeCallbacks(mAutoScroll);
        cancelGesture();
        clearProxy();
    }

    // ─── Edit mode ───────────────────────────────────────────────────────────

    /** Starts or ends the edit mode on every board */
    public void setEditMode(boolean editMode) {
        if (!sBoards.contains(this)) {
            applyEditMode(editMode);
            return;
        }
        for (HomeTileBoard board : sBoards) board.applyEditMode(editMode);
    }

    private void applyEditMode(boolean editMode) {
        if (mEditMode == editMode) return;
        mEditMode = editMode;
        if (!editMode) cancelGesture();
        for (HomeTileView view : mViews.values()) view.setEditing(editMode);
        invalidate();
        if (mListener != null) mListener.onEditModeChanged(editMode);
    }

    private void startEditWithDrag(HomeTile tile) {
        setEditMode(true);
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        beginDrag(tile, mRawX, mRawY);
        mTapCandidate = false;
    }

    // ─── Geometry ────────────────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        mCellW = Math.max(1, width - 2 * mPad) / (float) mColumns;

        int height;
        if (mScrollable) {
            mCellH = mCellW;
            int contentRows = contentRows();
            int needed = Math.round(2 * mPad + contentRows * mCellH);
            // Inside a ScrollView that fills its viewport the height is exact when the tiles are fewer than the viewport
            height = heightMode == MeasureSpec.EXACTLY ? heightSize : needed;
            mRows = Math.max(contentRows, (int) ((height - 2 * mPad) / mCellH));
            mGridReady = true;
        } else if (heightMode == MeasureSpec.EXACTLY && heightSize > 0) {
            height = heightSize;
            float innerH = Math.max(1, height - 2 * mPad);
            mRows = Math.max(HomeTileStore.MIN_ROWS, Math.round(innerH / mCellW));
            mCellH = innerH / mRows;
            mGridReady = true;
        } else {
            // The ScrollView first asks how tall the board wants to be and then gives it its own height
            setMeasuredDimension(width, 0);
            return;
        }
        setMeasuredDimension(width, height);

        if (mTilesNeedFit) {
            mTilesNeedFit = false;
            if (fitAll()) post(this::notifyChanged);
        }
        for (HomeTile tile : mTiles) {
            View view = mViews.get(tile);
            if (view == null) continue;
            RectF rect = tileRect(tile.x, tile.y, tile.w, tile.h, new RectF());
            view.measure(MeasureSpec.makeMeasureSpec(Math.max(0, Math.round(rect.width())), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, Math.round(rect.height())), MeasureSpec.EXACTLY));
        }
    }

    /** The rows the tiles use, plus an empty one, so there is always a place to add more */
    private int contentRows() {
        int bottom = 0;
        for (HomeTile tile : mTiles) bottom = Math.max(bottom, tile.y + tile.h);
        int rows = bottom + 1;
        if (mActive != null || mExternalShown) rows = Math.max(rows, mDragRows);
        return Math.min(Math.max(rows, 1), MAX_ROWS);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        RectF rect = new RectF();
        for (HomeTile tile : mTiles) {
            View view = mViews.get(tile);
            if (view == null || view.getMeasuredWidth() == 0 && view.getMeasuredHeight() == 0) continue;
            tileRect(tile.x, tile.y, tile.w, tile.h, rect);
            view.layout(Math.round(rect.left), Math.round(rect.top), Math.round(rect.right), Math.round(rect.bottom));
        }
    }

    private RectF tileRect(int x, int y, int w, int h, RectF out) {
        out.set(mPad + x * mCellW + mGap, mPad + y * mCellH + mGap,
                mPad + (x + w) * mCellW - mGap, mPad + (y + h) * mCellH - mGap);
        return out;
    }

    /** How far down a tile of this height can go */
    private int maxY(int h) {
        return Math.max(0, (mScrollable ? MAX_ROWS : mRows) - h);
    }

    /** Puts every tile inside the grid and out of the way of the others. @return whether anything moved */
    private boolean fitAll() {
        boolean changed = false;
        List<HomeTile> placed = new ArrayList<>();
        for (HomeTile tile : new ArrayList<>(mTiles)) {
            HomeTileType type = tile.getType();
            int w = Math.max(1, Math.min(tile.w, mColumns));
            int h = type != null && type.lockHeight ? 1 : Math.max(1, mScrollable ? tile.h : Math.min(tile.h, mRows));
            int x = Math.max(0, Math.min(tile.x, mColumns - w));
            int y = Math.max(0, Math.min(tile.y, maxY(h)));
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

    private boolean collides(List<HomeTile> others, @Nullable HomeTile self, int x, int y, int w, int h) {
        for (HomeTile other : others) {
            if (other == self) continue;
            if (x < other.x + other.w && x + w > other.x && y < other.y + other.h && y + h > other.y) return true;
        }
        return false;
    }

    private boolean isFree(@Nullable HomeTile self, int x, int y, int w, int h) {
        if (x < 0 || y < 0 || x + w > mColumns || y > maxY(h)) return false;
        return !collides(mTiles, self, x, y, w, h);
    }

    /** First free place for the tile among the others, shrinking it step by step when it doesn't fit */
    private boolean findFreeSpot(HomeTile tile, List<HomeTile> others) {
        HomeTileType type = tile.getType();
        boolean lockHeight = type != null && type.lockHeight;
        int w = Math.min(tile.w, mColumns);
        int h = lockHeight ? 1 : (mScrollable ? tile.h : Math.min(tile.h, mRows));
        while (w >= 1 && h >= 1) {
            for (int y = 0; y <= maxY(h); y++) {
                for (int x = 0; x + w <= mColumns; x++) {
                    if (!collides(others, tile, x, y, w, h)) {
                        tile.x = x; tile.y = y; tile.w = w; tile.h = h;
                        return true;
                    }
                }
            }
            if (w >= h || lockHeight) w--;
            else h--;
        }
        return false;
    }

    /** The free place closest to the wanted one for a tile of this size, null if there is none */
    @Nullable
    private int[] nearestFree(@Nullable HomeTile self, int wantedX, int wantedY, int w, int h) {
        int[] best = null;
        int bestDistance = Integer.MAX_VALUE;
        // On a scrollable board the places below the tiles are all free, a few rows past the wanted one are enough
        int lastY = mScrollable ? Math.min(maxY(h), Math.max(mRows, wantedY + h) + 1) : maxY(h);
        for (int y = 0; y <= lastY; y++) {
            for (int x = 0; x + w <= mColumns; x++) {
                if (!isFree(self, x, y, w, h)) continue;
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
        mRawX = ev.getRawX();
        mRawY = ev.getRawY();
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
                        beginGesture(tile, x, y, ev.getRawX(), ev.getRawY());
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mActive != null) {
                    updateGesture(x, y, ev.getRawX(), ev.getRawY());
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
    private void beginGesture(HomeTile tile, float x, float y, float rawX, float rawY) {
        HomeTileView view = mViews.get(tile);
        mDownRawX = rawX;
        mDownRawY = rawY;
        mTapCandidate = true;
        HomeTileView.Badge badge = view == null ? HomeTileView.Badge.NONE
                : view.badgeAt(x - view.getLeft(), y - view.getTop());
        if (badge == HomeTileView.Badge.REMOVE) {
            mActive = tile;
            mMode = Mode.REMOVE;
        } else if (badge == HomeTileView.Badge.RESIZE) {
            mActive = tile;
            mMode = Mode.RESIZE;
        } else {
            beginDrag(tile, rawX, rawY);
        }
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
    }

    private void beginDrag(HomeTile tile, float rawX, float rawY) {
        HomeTileView view = mViews.get(tile);
        if (view == null) return;
        mActive = tile;
        mMode = Mode.DRAG;
        getLocationOnScreen(mLocation);
        mGrabX = rawX - (mLocation[0] + view.getLeft());
        mGrabY = rawY - (mLocation[1] + view.getTop());
        mRawX = rawX;
        mRawY = rawY;
        mDropX = tile.x;
        mDropY = tile.y;
        mDropValid = true;
        mHover = null;
        mDragRows = 0;
        startProxy(view);
        view.setAlpha(0.25f);
        moveProxy(rawX - mGrabX, rawY - mGrabY);
        removeCallbacks(mAutoScroll);
        postOnAnimation(mAutoScroll);
        invalidate();
    }

    private void updateGesture(float x, float y, float rawX, float rawY) {
        if (mActive == null) return;
        mLastX = x;
        mLastY = y;
        if (mTapCandidate && Math.hypot(rawX - mDownRawX, rawY - mDownRawY) > mTouchSlop) mTapCandidate = false;
        if (mMode == Mode.DRAG) {
            updateDrag(rawX, rawY);
        } else if (mMode == Mode.RESIZE) {
            HomeTileType type = mActive.getType();
            int newW = Math.round((x - mPad - mActive.x * mCellW) / mCellW);
            int newH = Math.round((y - mPad - mActive.y * mCellH) / mCellH);
            newW = Math.max(1, Math.min(newW, mColumns - mActive.x));
            newH = type != null && type.lockHeight ? 1 : Math.max(1, Math.min(newH, (mScrollable ? MAX_ROWS : mRows) - mActive.y));
            if ((newW != mActive.w || newH != mActive.h) && isFree(mActive, mActive.x, mActive.y, newW, newH)) {
                mActive.w = newW;
                mActive.h = newH;
                requestLayout();
            }
        }
        invalidate();
    }

    /** Follows the finger: moves the picture of the tile and looks for the place it would land in */
    private void updateDrag(float rawX, float rawY) {
        if (mActive == null || mMode != Mode.DRAG) return;
        mRawX = rawX;
        mRawY = rawY;
        float screenLeft = rawX - mGrabX, screenTop = rawY - mGrabY;
        moveProxy(screenLeft, screenTop);

        HomeTileBoard other = boardUnder(rawX, rawY);
        for (HomeTileBoard board : sBoards) {
            if (board != this && board != other) board.clearExternalPreview();
        }
        if (other != null) {
            mHover = other;
            mDropValid = false;
            other.showExternalPreview(mActive, screenLeft, screenTop);
            invalidate();
            return;
        }
        mHover = null;

        getLocationOnScreen(mLocation);
        float left = screenLeft - mLocation[0], top = screenTop - mLocation[1];
        int wantedX = Math.round((left - mPad - mGap) / mCellW);
        int wantedY = Math.round((top - mPad - mGap) / mCellH);
        wantedX = Math.max(0, Math.min(wantedX, mColumns - mActive.w));
        wantedY = Math.max(0, Math.min(wantedY, maxY(mActive.h)));
        int[] spot = nearestFree(mActive, wantedX, wantedY, mActive.w, mActive.h);
        mDropValid = spot != null;
        if (spot != null) {
            mDropX = spot[0];
            mDropY = spot[1];
            growForDrop(mDropY + mActive.h);
        }
        invalidate();
    }

    /** A scrollable board gets taller while a tile is held low, so it can be taken further down */
    private void growForDrop(int bottomRow) {
        if (!mScrollable) return;
        int rows = Math.min(MAX_ROWS, bottomRow + 1);
        if (rows > mDragRows) {
            mDragRows = rows;
            requestLayout();
        }
    }

    /** @return the other board whose visible area is under the point, null if it isn't over one */
    @Nullable
    private HomeTileBoard boardUnder(float rawX, float rawY) {
        Rect visible = new Rect();
        int[] location = new int[2];
        for (HomeTileBoard board : sBoards) {
            if (board == this || !board.isShown() || !board.mGridReady) continue;
            if (!board.getLocalVisibleRect(visible)) continue;
            board.getLocationOnScreen(location);
            visible.offset(location[0], location[1]);
            if (visible.contains(Math.round(rawX), Math.round(rawY))) return board;
        }
        return null;
    }

    /** Shows where a tile dragged from another board would land here */
    private void showExternalPreview(HomeTile tile, float screenLeft, float screenTop) {
        HomeTileType type = tile.getType();
        int w = Math.min(tile.w, mColumns);
        int h = type != null && type.lockHeight ? 1 : (mScrollable ? tile.h : Math.min(tile.h, mRows));
        getLocationOnScreen(mLocation);
        float left = screenLeft - mLocation[0], top = screenTop - mLocation[1];
        int wantedX = Math.max(0, Math.min(Math.round((left - mPad - mGap) / mCellW), mColumns - w));
        int wantedY = Math.max(0, Math.min(Math.round((top - mPad - mGap) / mCellH), maxY(h)));
        int[] spot = nearestFree(null, wantedX, wantedY, w, h);
        mExternalShown = true;
        mExternalValid = spot != null;
        mExternalW = w;
        mExternalH = h;
        if (spot != null) {
            mExternalX = spot[0];
            mExternalY = spot[1];
            growForDrop(mExternalY + h);
        }
        invalidate();
    }

    private void clearExternalPreview() {
        if (!mExternalShown) return;
        mExternalShown = false;
        mExternalValid = false;
        if (mActive == null) mDragRows = 0;
        requestLayout();
        invalidate();
    }

    private void finishGesture(float x, float y, boolean commit) {
        HomeTile tile = mActive;
        Mode mode = mMode;
        if (tile == null) return;
        mActive = null;
        mMode = Mode.NONE;
        removeCallbacks(mAutoScroll);
        HomeTileView view = mViews.get(tile);
        if (mode == Mode.REMOVE) {
            boolean inside = view != null
                    && view.badgeAt(x - view.getLeft(), y - view.getTop()) == HomeTileView.Badge.REMOVE;
            if (commit && inside) removeTile(tile);
        } else if (mode == Mode.DRAG && view != null) {
            finishDrag(tile, view, commit);
        } else if (mode == Mode.RESIZE) {
            notifyChanged();
        }
        mDragRows = 0;
        mHover = null;
        requestLayout();
        invalidate();
    }

    private void finishDrag(HomeTile tile, HomeTileView view, boolean commit) {
        HomeTileBoard target = mHover;
        for (HomeTileBoard board : sBoards) {
            if (board != target && board != this) board.clearExternalPreview();
        }
        if (commit && target != null && target.mExternalValid) {
            // Over to the other board: the tile leaves this one and the other one gets it
            int x = target.mExternalX, y = target.mExternalY, w = target.mExternalW, h = target.mExternalH;
            target.mExternalShown = false;
            target.mExternalValid = false;
            detachTile(tile);
            tile.x = x;
            tile.y = y;
            tile.w = w;
            tile.h = h;
            target.mDragRows = 0;
            target.addExistingTile(tile);
            notifyChanged();
            HomeTileView arrived = target.mViews.get(tile);
            if (arrived != null) arrived.setAlpha(0f);
            RectF rect = target.tileRect(x, y, w, h, new RectF());
            target.getLocationOnScreen(mLocation);
            settleProxy(mLocation[0] + rect.left, mLocation[1] + rect.top, arrived == null ? null : () -> arrived.setAlpha(1f));
            return;
        }
        if (target != null) target.clearExternalPreview();

        boolean moved = false;
        if (commit && mHover == null && mDropValid && (tile.x != mDropX || tile.y != mDropY)) {
            tile.x = mDropX;
            tile.y = mDropY;
            moved = true;
        }
        if (moved) notifyChanged();
        else if (commit && mTapCandidate && mListener != null) mListener.onTileTap(tile);
        RectF rect = tileRect(tile.x, tile.y, tile.w, tile.h, new RectF());
        getLocationOnScreen(mLocation);
        view.setAlpha(0f);
        settleProxy(mLocation[0] + rect.left, mLocation[1] + rect.top, () -> view.setAlpha(1f));
    }

    /** A tile that came from the other board */
    private void addExistingTile(HomeTile tile) {
        HomeTileType type = tile.getType();
        if (type == null) return;
        mTiles.add(tile);
        addTileView(tile, type);
        requestLayout();
        notifyChanged();
    }

    private void cancelGesture() {
        if (mActive != null) finishGesture(0, 0, false);
    }

    @Nullable
    private ScrollView findScrollView() {
        ViewParent parent = getParent();
        while (parent != null) {
            if (parent instanceof ScrollView) return (ScrollView) parent;
            parent = parent.getParent();
        }
        return null;
    }

    // ─── The picture that follows the finger ─────────────────────────────────

    private void startProxy(HomeTileView view) {
        clearProxy();
        int w = view.getWidth(), h = view.getHeight();
        View root = getRootView();
        if (w <= 0 || h <= 0 || !(root instanceof ViewGroup)) return;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        mProxy = new BitmapDrawable(getResources(), bitmap);
        mProxy.setAlpha(235);
        mProxyW = w;
        mProxyH = h;
        mProxyOverlay = ((ViewGroup) root).getOverlay();
        mProxyOverlay.add(mProxy);
    }

    private void moveProxy(float screenLeft, float screenTop) {
        if (mProxy == null) return;
        getRootView().getLocationOnScreen(mLocation);
        int left = Math.round(screenLeft - mLocation[0]);
        int top = Math.round(screenTop - mLocation[1]);
        mProxy.setBounds(left, top, left + mProxyW, top + mProxyH);
    }

    private void clearProxy() {
        if (mProxy != null && mProxyOverlay != null) mProxyOverlay.remove(mProxy);
        mProxy = null;
        mProxyOverlay = null;
    }

    /** Slides the picture to where the tile is now, then removes it and runs what is to be done once it is there */
    private void settleProxy(float toScreenLeft, float toScreenTop, @Nullable Runnable end) {
        final BitmapDrawable proxy = mProxy;
        final ViewGroupOverlay overlay = mProxyOverlay;
        mProxy = null;
        mProxyOverlay = null;
        if (proxy == null || overlay == null) {
            if (end != null) end.run();
            return;
        }
        getRootView().getLocationOnScreen(mLocation);
        final Rect from = new Rect(proxy.getBounds());
        final int toLeft = Math.round(toScreenLeft - mLocation[0]);
        final int toTop = Math.round(toScreenTop - mLocation[1]);
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(140);
        animator.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            int left = Math.round(from.left + (toLeft - from.left) * f);
            int top = Math.round(from.top + (toTop - from.top) * f);
            proxy.setBounds(left, top, left + from.width(), top + from.height());
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                overlay.remove(proxy);
                if (end != null) end.run();
            }
        });
        animator.start();
    }

    // ─── Drawing ─────────────────────────────────────────────────────────────

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // Where the tile will land. The outline and the badges are drawn by the tiles themselves
        if (mEditMode) {
            if (mActive != null && mMode == Mode.DRAG && mHover == null && mDropValid) {
                drawPreview(canvas, mDropX, mDropY, mActive.w, mActive.h);
            } else if (mExternalShown && mExternalValid) {
                drawPreview(canvas, mExternalX, mExternalY, mExternalW, mExternalH);
            }
        }
        super.dispatchDraw(canvas);
    }

    private void drawPreview(Canvas canvas, int x, int y, int w, int h) {
        tileRect(x, y, w, h, mRect);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(withAlpha(ThemeColors.primary(getContext()), 70));
        canvas.drawRoundRect(mRect, dp(16), dp(16), mPaint);
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
