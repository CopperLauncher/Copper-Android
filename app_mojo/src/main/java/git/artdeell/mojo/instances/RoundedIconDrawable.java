package git.artdeell.mojo.instances;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Draws another drawable with rounded corners, the same look as the icons of the content browser
 * (a corner radius of a fifth of the icon size). Used for instance icons.
 */
public class RoundedIconDrawable extends Drawable {
    private static final float CORNER_RATIO = 0.2f;

    private final Drawable mIcon;
    private final Paint mMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mMaskBounds = new RectF();

    public RoundedIconDrawable(@NonNull Drawable icon) {
        mIcon = icon;
        mMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        // Draw the icon on its own layer, then keep only what is inside the rounded rectangle
        int saved = canvas.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, null);
        mIcon.draw(canvas);
        mMaskBounds.set(bounds);
        float radius = CORNER_RATIO * Math.min(bounds.width(), bounds.height());
        canvas.drawRoundRect(mMaskBounds, radius, radius, mMaskPaint);
        canvas.restoreToCount(saved);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        mIcon.setBounds(bounds);
    }

    @Override
    public int getIntrinsicWidth() {
        return mIcon.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mIcon.getIntrinsicHeight();
    }

    @Override
    public void setAlpha(int alpha) {
        mIcon.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mIcon.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
