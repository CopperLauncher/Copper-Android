package com.kdt.mcgui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ProgressBar;

import com.google.android.material.color.MaterialColors;

import git.artdeell.mojo.R;

/**
 * Loading indicator in the Material 3 Expressive style: a filled shape that morphs from one
 * rounded form to the next while it turns. It extends {@link ProgressBar} so it can stand in for
 * any spinner, the code that holds a ProgressBar keeps working. Only the indeterminate look is drawn.
 */
public class ExpressiveLoadingView extends ProgressBar {
    private static final int POINTS = 96;
    private static final float[][] SHAPES = buildShapes();
    private static final long STEP_MS = 900;
    private static final float MORPH_PART = 0.55f;
    private static final float DEFAULT_SIZE_DP = 48f;

    private final Paint mContainerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final OvershootInterpolator mSpring = new OvershootInterpolator(1.6f);
    private final float[] mRadii = new float[POINTS];
    private final float[] mX = new float[POINTS];
    private final float[] mY = new float[POINTS];
    private boolean mContained;
    private float mTime;
    private ValueAnimator mAnimator;

    public ExpressiveLoadingView(Context context) {
        this(context, null);
    }

    public ExpressiveLoadingView(Context context, AttributeSet attrs) {
        super(context, attrs, 0);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ExpressiveLoadingView);
        int primary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4);
        int shapeColor = a.getColor(R.styleable.ExpressiveLoadingView_copperLoaderColor, primary);
        mContained = a.getBoolean(R.styleable.ExpressiveLoadingView_copperLoaderContained, false);
        a.recycle();
        if (mContained) {
            int container = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer, 0xFFEADDFF);
            int onContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF21005D);
            mContainerPaint.setColor(container);
            mShapePaint.setColor(onContainer);
        } else {
            mShapePaint.setColor(shapeColor);
        }
        mShapePaint.setStyle(Paint.Style.FILL);
        mContainerPaint.setStyle(Paint.Style.FILL);
        setIndeterminateDrawable(null);
    }

    @Override
    protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int def = Math.round(DEFAULT_SIZE_DP * getResources().getDisplayMetrics().density);
        int w = resolveSize(def + getPaddingLeft() + getPaddingRight(), widthMeasureSpec);
        int h = resolveSize(def + getPaddingTop() + getPaddingBottom(), heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        float availW = getWidth() - getPaddingLeft() - getPaddingRight();
        float availH = getHeight() - getPaddingTop() - getPaddingBottom();
        float size = Math.min(availW, availH);
        if (size <= 0) return;
        float cx = getPaddingLeft() + availW / 2f;
        float cy = getPaddingTop() + availH / 2f;

        if (mContained) canvas.drawCircle(cx, cy, size / 2f, mContainerPaint);
        float radius = size / 2f * (mContained ? 0.58f : 0.95f);

        int step = (int) mTime;
        float local = mTime - step;
        float[] from = SHAPES[step % SHAPES.length];
        float[] to = SHAPES[(step + 1) % SHAPES.length];
        float morph = local >= MORPH_PART ? 1f : mSpring.getInterpolation(local / MORPH_PART);
        double turn = Math.toRadians(mTime * 140.0);

        for (int i = 0; i < POINTS; i++) {
            float r = (from[i] + (to[i] - from[i]) * morph) * radius;
            double angle = Math.PI * 2.0 * i / POINTS + turn;
            mX[i] = cx + (float) (Math.cos(angle) * r);
            mY[i] = cy + (float) (Math.sin(angle) * r);
        }
        mPath.rewind();
        mPath.moveTo((mX[POINTS - 1] + mX[0]) / 2f, (mY[POINTS - 1] + mY[0]) / 2f);
        for (int i = 0; i < POINTS; i++) {
            int next = (i + 1) % POINTS;
            mPath.quadTo(mX[i], mY[i], (mX[i] + mX[next]) / 2f, (mY[i] + mY[next]) / 2f);
        }
        mPath.close();
        canvas.drawPath(mPath, mShapePaint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimator();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateAnimator();
    }

    private void updateAnimator() {
        if (isAttachedToWindow() && getVisibility() == VISIBLE) startAnimator();
        else stopAnimator();
    }

    private void startAnimator() {
        if (mAnimator != null) return;
        mAnimator = ValueAnimator.ofFloat(0f, SHAPES.length);
        mAnimator.setDuration(STEP_MS * SHAPES.length);
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mAnimator.addUpdateListener(animation -> {
            mTime = (float) animation.getAnimatedValue();
            invalidate();
        });
        mAnimator.start();
    }

    private void stopAnimator() {
        if (mAnimator == null) return;
        mAnimator.cancel();
        mAnimator = null;
    }

    /** Radius of each shape in {@link #POINTS} directions, the largest one is 1 */
    private static float[][] buildShapes() {
        float[][] shapes = new float[6][POINTS];
        for (int i = 0; i < POINTS; i++) {
            double t = Math.PI * 2.0 * i / POINTS;
            shapes[0][i] = (float) (1.0 + 0.10 * Math.cos(8 * t));
            shapes[1][i] = (float) polygon(t, 5, 0.72);
            shapes[2][i] = (float) (1.0 + 0.17 * Math.cos(4 * t));
            shapes[3][i] = (float) (0.62 / Math.sqrt(Math.pow(0.62 * Math.cos(t), 2) + Math.pow(Math.sin(t), 2)));
            shapes[4][i] = (float) (1.0 + 0.13 * Math.cos(12 * t));
            shapes[5][i] = (float) polygon(t, 4, 0.8);
        }
        for (float[] shape : shapes) {
            float max = 0f;
            for (float v : shape) max = Math.max(max, v);
            for (int i = 0; i < POINTS; i++) shape[i] /= max;
        }
        return shapes;
    }

    /** Regular polygon blended with a circle, so the corners come out rounded */
    private static double polygon(double t, int sides, double sharpness) {
        double sector = Math.PI * 2.0 / sides;
        double local = ((t % sector) + sector) % sector - sector / 2.0;
        double poly = Math.cos(sector / 2.0) / Math.cos(local);
        return 1.0 + (poly - 1.0) * sharpness;
    }
}
