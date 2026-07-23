package com.kdt.mcgui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.VideoView;

/**
 * A {@link VideoView} that scales like {@code ImageView.ScaleType.CENTER_CROP} — fills
 * its bounds completely, cropping any overflow — instead of the stock VideoView behavior
 * of letterboxing/pillarboxing to preserve the video's aspect ratio. Used for the custom
 * video wallpaper background (see RightPaneHomeFragment), where black bars around a
 * non-matching aspect ratio would look broken.
 *
 * Call {@link #setVideoSize(int, int)} once the video's intrinsic dimensions are known
 * (e.g. from {@code MediaPlayer.OnPreparedListener}) to enable the crop; before that it
 * falls back to normal VideoView measurement.
 */
public class CenterCropVideoView extends VideoView {
    private int mVideoWidth = 0;
    private int mVideoHeight = 0;

    public CenterCropVideoView(Context context) {
        super(context);
    }

    public CenterCropVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CenterCropVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** Must be called with the video's real (unrotated) pixel dimensions once known. */
    public void setVideoSize(int width, int height) {
        mVideoWidth = width;
        mVideoHeight = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
        int parentHeight = MeasureSpec.getSize(heightMeasureSpec);

        if (mVideoWidth <= 0 || mVideoHeight <= 0 || parentWidth <= 0 || parentHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        float viewRatio = (float) parentWidth / parentHeight;
        float videoRatio = (float) mVideoWidth / mVideoHeight;

        int measuredWidth, measuredHeight;
        if (videoRatio > viewRatio) {
            // Video is relatively wider than the container — match height, let width overflow.
            measuredHeight = parentHeight;
            measuredWidth = Math.round(parentHeight * videoRatio);
        } else {
            measuredWidth = parentWidth;
            measuredHeight = Math.round(parentWidth / videoRatio);
        }
        setMeasuredDimension(measuredWidth, measuredHeight);
    }
}
