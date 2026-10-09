package git.artdeell.mojo.skins;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A small software 3D renderer that shows a Minecraft player with a skin and (optionally) a cape.
 * Every body part is a textured box, drawn with an orthographic camera that can be rotated
 * by dragging. It auto-rotates while idle.
 */
public class PlayerPreviewView extends View {
    // Face order used everywhere in this class
    private static final int F_FRONT = 0;  // +z
    private static final int F_BACK = 1;   // -z
    private static final int F_RIGHT = 2;  // -x, the right side of the player
    private static final int F_LEFT = 3;   // +x, the left side of the player
    private static final int F_TOP = 4;    // +y
    private static final int F_BOTTOM = 5; // -y

    private static final float CAPE_TILT_DEGREES = 8f;
    private static final float AUTO_ROTATE_DEGREES_PER_SECOND = 28f;
    private static final float MODEL_CENTER_Y = 16f;
    private static final float MODEL_FIT_HEIGHT = 36f;
    private static final float MODEL_FIT_WIDTH = 24f;

    /** One textured box (plus, for body parts, its outer layer) */
    private static final class Part {
        final boolean cape;
        final float[][] baseFaces = new float[6][12];
        final float[][] overlayFaces = new float[6][12];
        final Rect[] baseRects = new Rect[6];
        final Rect[] overlayRects = new Rect[6];
        boolean hasOverlay;
        float centerX, centerY, centerZ;
        float depth;
        Part(boolean cape) { this.cape = cape; }
    }

    private final List<Part> mParts = new ArrayList<>();
    private final Comparator<Part> mDepthOrder = (a, b) -> Float.compare(a.depth, b.depth);

    private Bitmap mSkin;
    private boolean mSlim;
    private Bitmap mCape;
    private boolean mModelDirty = true;

    private float mYaw = 25f;
    private float mPitch = 12f;
    private float mLastTouchX, mLastTouchY;
    private boolean mDragging;
    private long mLastFrameTime;

    private final Paint mPaint = new Paint();
    private final Matrix mMatrix = new Matrix();
    private final RectF mDst = new RectF();
    private final float[] mSrcPoints = new float[6];
    private final float[] mDstPoints = new float[6];
    private final float[] mView = new float[12];

    public PlayerPreviewView(Context context) {
        super(context);
        init();
    }

    public PlayerPreviewView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PlayerPreviewView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mPaint.setAntiAlias(false);
        mPaint.setFilterBitmap(false); // keep the pixels crisp
        mPaint.setDither(false);
    }

    /** @param skin any skin bitmap, it gets normalized to the 64x64 layout; null clears the player */
    public void setSkin(@Nullable Bitmap skin, boolean slim) {
        mSkin = skin == null ? null : SkinTextures.normalizeSkin(skin);
        mSlim = slim;
        mModelDirty = true;
        invalidate();
    }

    /** Changes only the arm model of the current skin */
    public void setSlim(boolean slim) {
        if (mSlim == slim) return;
        mSlim = slim;
        mModelDirty = true;
        invalidate();
    }

    public void setCape(@Nullable Bitmap cape) {
        mCape = cape == null ? null : SkinTextures.normalizeCape(cape);
        mModelDirty = true;
        invalidate();
    }

    // ------------------------------------------------------------------ model

    private void rebuildModel() {
        mModelDirty = false;
        mParts.clear();
        if (mSkin == null) return;
        int s = mSkin.getWidth() / 64;
        float armWidth = mSlim ? 3 : 4;

        //                x               y   z   w         h   d  u   v   ou  ov  inflate
        addBody(s, -4,            24, -4, 8,        8,  8,  0,  0, 32,  0, 0.5f);  // head
        addBody(s, -4,            12, -2, 8,        12, 4, 16, 16, 16, 32, 0.25f); // body
        addBody(s, -4 - armWidth, 12, -2, armWidth, 12, 4, 40, 16, 40, 32, 0.25f); // right arm
        addBody(s,  4,            12, -2, armWidth, 12, 4, 32, 48, 48, 48, 0.25f); // left arm
        addBody(s, -4,            0,  -2, 4,        12, 4,  0, 16,  0, 32, 0.25f); // right leg
        addBody(s,  0,            0,  -2, 4,        12, 4, 16, 48,  0, 48, 0.25f); // left leg

        if (mCape != null) addCape(mCape.getWidth() / 64);
    }

    private void addBody(int s, float x, float y, float z, float w, float h, float d,
                         int u, int v, int ou, int ov, float inflate) {
        Part part = new Part(false);
        fillFaces(part.baseFaces, x, y, z, w, h, d, 0);
        fillRects(part.baseRects, s, u, v, (int) w, (int) h, (int) d, false);
        part.hasOverlay = true;
        fillFaces(part.overlayFaces, x, y, z, w, h, d, inflate);
        fillRects(part.overlayRects, s, ou, ov, (int) w, (int) h, (int) d, false);
        part.centerX = x + w / 2; part.centerY = y + h / 2; part.centerZ = z + d / 2;
        mParts.add(part);
    }

    private void addCape(int s) {
        Part part = new Part(true);
        // 10x16x1 box hanging off the back of the body
        float x = -5, y = 8, z = -3, w = 10, h = 16, d = 1;
        fillFaces(part.baseFaces, x, y, z, w, h, d, 0);
        fillRects(part.baseRects, s, 0, 0, 10, 16, 1, true);
        // Let it hang slightly away from the body, pivoting on the shoulders
        tilt(part.baseFaces, 24f, -2f, (float) Math.toRadians(CAPE_TILT_DEGREES));
        part.centerX = 0; part.centerY = 16; part.centerZ = -2.5f;
        float[] c = {part.centerX, part.centerY, part.centerZ};
        tiltPoint(c, 24f, -2f, (float) Math.toRadians(CAPE_TILT_DEGREES));
        part.centerX = c[0]; part.centerY = c[1]; part.centerZ = c[2];
        mParts.add(part);
    }

    private static void tilt(float[][] faces, float pivotY, float pivotZ, float angle) {
        for (float[] face : faces) {
            for (int i = 0; i < 12; i += 3) {
                float[] p = {face[i], face[i + 1], face[i + 2]};
                tiltPoint(p, pivotY, pivotZ, angle);
                face[i] = p[0]; face[i + 1] = p[1]; face[i + 2] = p[2];
            }
        }
    }

    /** Rotates around the X axis so that points below the pivot swing backwards (-z) */
    private static void tiltPoint(float[] p, float pivotY, float pivotZ, float angle) {
        float dy = p[1] - pivotY, dz = p[2] - pivotZ;
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        p[1] = pivotY + dy * cos - dz * sin;
        p[2] = pivotZ + dy * sin + dz * cos;
    }

    /**
     * Writes the 4 corners (top-left, top-right, bottom-left, bottom-right, as seen from outside
     * the box) of each face. Each corner is x,y,z.
     */
    private static void fillFaces(float[][] out, float x, float y, float z, float w, float h, float d, float e) {
        float x0 = x - e, x1 = x + w + e;
        float y0 = y - e, y1 = y + h + e;
        float z0 = z - e, z1 = z + d + e;
        set(out[F_FRONT],  x0, y1, z1,  x1, y1, z1,  x0, y0, z1,  x1, y0, z1);
        set(out[F_BACK],   x1, y1, z0,  x0, y1, z0,  x1, y0, z0,  x0, y0, z0);
        set(out[F_RIGHT],  x0, y1, z0,  x0, y1, z1,  x0, y0, z0,  x0, y0, z1);
        set(out[F_LEFT],   x1, y1, z1,  x1, y1, z0,  x1, y0, z1,  x1, y0, z0);
        set(out[F_TOP],    x0, y1, z0,  x1, y1, z0,  x0, y1, z1,  x1, y1, z1);
        set(out[F_BOTTOM], x0, y0, z1,  x1, y0, z1,  x0, y0, z0,  x1, y0, z0);
    }

    private static void set(float[] dst, float... values) {
        System.arraycopy(values, 0, dst, 0, 12);
    }

    /** Texture rectangle of each face of a box whose layout starts at (u, v) */
    private static void fillRects(Rect[] out, int s, int u, int v, int w, int h, int d, boolean cape) {
        Rect front = rect(s, u + d, v + d, w, h);
        Rect back = rect(s, u + d + w + d, v + d, w, h);
        Rect right = rect(s, u, v + d, d, h);
        Rect left = rect(s, u + d + w, v + d, d, h);
        if (cape) {
            // The outside of a cape faces backwards, so front/back and left/right trade places
            out[F_FRONT] = back; out[F_BACK] = front;
            out[F_RIGHT] = left; out[F_LEFT] = right;
        } else {
            out[F_FRONT] = front; out[F_BACK] = back;
            out[F_RIGHT] = right; out[F_LEFT] = left;
        }
        out[F_TOP] = rect(s, u + d, v, w, d);
        out[F_BOTTOM] = rect(s, u + d + w, v, w, d);
    }

    private static Rect rect(int s, int x, int y, int w, int h) {
        return new Rect(x * s, y * s, (x + w) * s, (y + h) * s);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (mModelDirty) rebuildModel();
        if (mSkin == null || mParts.isEmpty()) return;

        long now = SystemClock.uptimeMillis();
        if (mLastFrameTime != 0 && !mDragging) {
            float dt = Math.min(now - mLastFrameTime, 100) / 1000f;
            mYaw = (mYaw + AUTO_ROTATE_DEGREES_PER_SECOND * dt) % 360f;
        }
        mLastFrameTime = now;

        float yawRad = (float) Math.toRadians(mYaw);
        float pitchRad = (float) Math.toRadians(mPitch);
        float cy = (float) Math.cos(yawRad), sy = (float) Math.sin(yawRad);
        float cp = (float) Math.cos(pitchRad), sp = (float) Math.sin(pitchRad);

        float scale = Math.min(getWidth() / MODEL_FIT_WIDTH, getHeight() / MODEL_FIT_HEIGHT);
        float originX = getWidth() / 2f;
        float originY = getHeight() / 2f;

        // Far parts first
        for (Part part : mParts) {
            part.depth = transformZ(part.centerX, part.centerY, part.centerZ, cy, sy, cp, sp);
        }
        Collections.sort(mParts, mDepthOrder);

        for (Part part : mParts) {
            Bitmap texture = part.cape ? mCape : mSkin;
            drawFaces(canvas, texture, part.baseFaces, part.baseRects, cy, sy, cp, sp, scale, originX, originY);
            if (part.hasOverlay) {
                drawFaces(canvas, texture, part.overlayFaces, part.overlayRects, cy, sy, cp, sp, scale, originX, originY);
            }
        }
        postInvalidateOnAnimation();
    }

    private static float transformZ(float x, float y, float z, float cy, float sy, float cp, float sp) {
        float z1 = -x * sy + z * cy;
        return (y - MODEL_CENTER_Y) * sp + z1 * cp;
    }

    /** Projects model space to screen space; result in out[0..2] = screenX, screenY, depth */
    private static void project(float x, float y, float z, float cy, float sy, float cp, float sp,
                                float scale, float originX, float originY, float[] out, int offset) {
        float y0 = y - MODEL_CENTER_Y;
        float x1 = x * cy + z * sy;
        float z1 = -x * sy + z * cy;
        float y2 = y0 * cp - z1 * sp;
        float z2 = y0 * sp + z1 * cp;
        out[offset] = originX + x1 * scale;
        out[offset + 1] = originY - y2 * scale;
        out[offset + 2] = z2;
    }

    private void drawFaces(Canvas canvas, Bitmap texture, float[][] faces, Rect[] rects,
                           float cy, float sy, float cp, float sp,
                           float scale, float originX, float originY) {
        for (int f = 0; f < 6; f++) {
            float[] c = faces[f];
            for (int i = 0; i < 4; i++) {
                project(c[i * 3], c[i * 3 + 1], c[i * 3 + 2], cy, sy, cp, sp,
                        scale, originX, originY, mView, i * 3);
            }
            // Back-face culling: normal = down x right, visible when it points to the camera (+z)
            float rx = mView[3] - mView[0], ry = mView[4] - mView[1];
            float dx = mView[6] - mView[0], dy = mView[7] - mView[1];
            // Screen y points down, so the sign of the 2D cross product tells the facing
            float facing = rx * dy - ry * dx;
            if (facing <= 0.5f) continue;

            Rect src = rects[f];
            float sw = src.width(), sh = src.height();
            mSrcPoints[0] = 0;  mSrcPoints[1] = 0;
            mSrcPoints[2] = sw; mSrcPoints[3] = 0;
            mSrcPoints[4] = 0;  mSrcPoints[5] = sh;
            mDstPoints[0] = mView[0]; mDstPoints[1] = mView[1];
            mDstPoints[2] = mView[3]; mDstPoints[3] = mView[4];
            mDstPoints[4] = mView[6]; mDstPoints[5] = mView[7];
            if (!mMatrix.setPolyToPoly(mSrcPoints, 0, mDstPoints, 0, 3)) continue;

            mDst.set(0, 0, sw, sh);
            canvas.save();
            canvas.concat(mMatrix);
            canvas.drawBitmap(texture, src, mDst, mPaint);
            canvas.restore();
        }
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                mDragging = true;
                mLastTouchX = event.getX();
                mLastTouchY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                mYaw += (event.getX() - mLastTouchX) * 0.6f;
                mPitch = Math.max(-30f, Math.min(45f, mPitch + (event.getY() - mLastTouchY) * 0.3f));
                mLastTouchX = event.getX();
                mLastTouchY = event.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                mLastFrameTime = SystemClock.uptimeMillis();
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }
}
