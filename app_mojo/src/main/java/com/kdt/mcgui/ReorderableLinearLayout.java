package com.kdt.mcgui;

import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A vertical list whose children are reordered by pressing one for a moment and dragging it.
 * While dragging, the other children slide out of the way, and the new order is applied when the
 * finger is lifted. Children are identified by the entry name of their id.
 */
public class ReorderableLinearLayout extends LinearLayout {
    public interface OnOrderChangedListener {
        void onOrderChanged();
    }

    private OnOrderChangedListener mListener;
    private View mDragged;
    private int mStartIndex, mTargetIndex;
    private float mGrabOffset, mLastRawY;
    private final int[] mLocation = new int[2];

    private final Runnable mAutoScroll = new Runnable() {
        @Override
        public void run() {
            if (mDragged == null) return;
            ScrollView scroll = findScrollView();
            if (scroll == null) return;
            scroll.getLocationOnScreen(mLocation);
            int edge = dp(56), step = dp(10);
            if (mLastRawY < mLocation[1] + edge) scroll.scrollBy(0, -step);
            else if (mLastRawY > mLocation[1] + scroll.getHeight() - edge) scroll.scrollBy(0, step);
            else {
                postOnAnimation(this);
                return;
            }
            updateDrag();
            postOnAnimation(this);
        }
    };

    public ReorderableLinearLayout(Context context) {
        this(context, null);
    }

    public ReorderableLinearLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    public void setOnOrderChangedListener(@Nullable OnOrderChangedListener listener) {
        mListener = listener;
    }

    /** Makes every child, as it is now, draggable with a long press */
    public void enableReordering() {
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setOnLongClickListener(v -> {
                beginDrag(v);
                return true;
            });
        }
    }

    // ─── Order ───────────────────────────────────────────────────────────────

    /** The entry names of the ids of the children, in order. Children without an id are skipped */
    @NonNull
    public List<String> getOrder() {
        List<String> order = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) {
            String name = entryName(getChildAt(i));
            if (name != null) order.add(name);
        }
        return order;
    }

    /** Puts the children in this order. Unknown names are ignored, children not named stay after the others in their order */
    public void setOrder(@NonNull List<String> order) {
        if (order.isEmpty()) return;
        Map<String, View> byName = new HashMap<>();
        List<View> unnamed = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            String name = entryName(child);
            if (name == null) unnamed.add(child);
            else byName.put(name, child);
        }
        List<View> ordered = new ArrayList<>();
        for (String name : order) {
            View view = byName.remove(name);
            if (view != null) ordered.add(view);
        }
        // What the order doesn't know about, in the order it already had
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (byName.containsValue(child) || unnamed.contains(child)) ordered.add(child);
        }
        removeAllViews();
        for (View view : ordered) addView(view);
    }

    @Nullable
    private String entryName(View view) {
        if (view.getId() == NO_ID) return null;
        try {
            return getResources().getResourceEntryName(view.getId());
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }

    // ─── Dragging ────────────────────────────────────────────────────────────

    private void beginDrag(View view) {
        mDragged = view;
        mStartIndex = mTargetIndex = indexOfChild(view);
        getLocationOnScreen(mLocation);
        mGrabOffset = mLastRawY - (mLocation[1] + view.getTop());
        view.setTranslationZ(dp(6));
        view.setAlpha(0.9f);
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        postOnAnimation(mAutoScroll);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        mLastRawY = ev.getRawY();
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return mDragged != null || super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mDragged == null) return super.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                mLastRawY = ev.getRawY();
                updateDrag();
                return true;
            case MotionEvent.ACTION_UP:
                finishDrag(true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                finishDrag(false);
                return true;
            default:
                return true;
        }
    }

    private void updateDrag() {
        View dragged = mDragged;
        if (dragged == null) return;
        getLocationOnScreen(mLocation);
        float top = mLastRawY - mLocation[1] - mGrabOffset;
        top = Math.max(0, Math.min(top, getHeight() - dragged.getHeight()));
        dragged.setTranslationY(top - dragged.getTop());

        // The new place is the number of the other children that are above the middle of the dragged one
        float middle = top + dragged.getHeight() / 2f;
        int target = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != dragged && child.getTop() + child.getHeight() / 2f < middle) target++;
        }
        if (target == mTargetIndex) return;
        mTargetIndex = target;

        int shift = dragged.getHeight();
        if (dragged.getLayoutParams() instanceof MarginLayoutParams) {
            MarginLayoutParams params = (MarginLayoutParams) dragged.getLayoutParams();
            shift += params.topMargin + params.bottomMargin;
        }
        for (int i = 0; i < getChildCount(); i++) {
            if (i == mStartIndex) continue;
            float offset = 0;
            if (i > mStartIndex && i <= target) offset = -shift;
            else if (i < mStartIndex && i >= target) offset = shift;
            getChildAt(i).animate().translationY(offset).setDuration(120).start();
        }
    }

    private void finishDrag(boolean commit) {
        View dragged = mDragged;
        if (dragged == null) return;
        mDragged = null;
        removeCallbacks(mAutoScroll);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.animate().cancel();
            child.setTranslationY(0);
        }
        dragged.setTranslationZ(0);
        dragged.setAlpha(1f);
        if (commit && mTargetIndex != mStartIndex) {
            removeView(dragged);
            addView(dragged, mTargetIndex);
            if (mListener != null) mListener.onOrderChanged();
        }
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
}
