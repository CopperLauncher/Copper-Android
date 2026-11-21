package git.artdeell.mojo.utils;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.animation.BounceInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LayoutAnimationController;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import androidx.transition.Fade;
import androidx.transition.Slide;
import androidx.transition.Transition;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.transition.MaterialElevationScale;
import com.google.android.material.transition.MaterialFade;
import com.google.android.material.transition.MaterialSharedAxis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import git.artdeell.mojo.R;

/**
 * Central place that decides which kinds of animations the launcher plays.
 * The user picks the kinds (or none) in the launcher appearance settings.
 * <ul>
 *     <li>{@link #TYPE_SCREENS}: activity and fragment transitions</li>
 *     <li>{@link #TYPE_DIALOGS}: dialogs and popups</li>
 *     <li>{@link #TYPE_BUTTONS}: button press effects</li>
 *     <li>{@link #TYPE_CONTENT}: lists and screen content entering</li>
 * </ul>
 * The look of all of them is picked with {@link #PREF_STYLE}.
 */
public final class AnimationManager {
    public static final String PREF_TYPES = "animation_types";
    /** Which look the enabled animation types use, one of the STYLE_ values */
    public static final String PREF_STYLE = "animation_style";

    public static final String STYLE_DEFAULT = "default";
    public static final String STYLE_FADE = "fade";
    public static final String STYLE_BOUNCE = "bounce";
    public static final String STYLE_ZOOM = "zoom";
    public static final String STYLE_RISE = "rise";
    public static final String STYLE_DROP = "drop";
    public static final String STYLE_POP = "pop";
    public static final String STYLE_VERTICAL = "vertical";
    public static final String STYLE_FROM_RIGHT = "from_right";
    public static final String STYLE_FROM_LEFT = "from_left";
    public static final String STYLE_ELASTIC = "elastic";
    public static final String STYLE_SPIN = "spin";
    public static final String STYLE_FLIP = "flip";
    public static final String STYLE_SWING = "swing";
    public static final String STYLE_CORNER = "corner";
    public static final String STYLE_SQUASH = "squash";
    public static final String STYLE_SLAM = "slam";
    public static final String STYLE_UNFOLD = "unfold";
    public static final String STYLE_DRIFT = "drift";
    public static final String STYLE_WOBBLE = "wobble";
    public static final String STYLE_TILT = "tilt";

    public static final String TYPE_NONE = "none";
    public static final String TYPE_SCREENS = "screens";
    public static final String TYPE_DIALOGS = "dialogs";
    public static final String TYPE_BUTTONS = "buttons";
    public static final String TYPE_CONTENT = "content";

    private static final Set<String> DEFAULT_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(TYPE_SCREENS, TYPE_DIALOGS, TYPE_BUTTONS, TYPE_CONTENT)));

    private AnimationManager() {}

    /** @return whether animations of this type should be played */
    public static boolean isEnabled(@NonNull Context context, @NonNull String type) {
        if (systemAnimationsDisabled(context)) return false;
        Set<String> types = PreferenceManager.getDefaultSharedPreferences(context)
                .getStringSet(PREF_TYPES, DEFAULT_TYPES);
        return types != null && !types.contains(TYPE_NONE) && types.contains(type);
    }

    /** Respect the "remove animations" accessibility setting */
    private static boolean systemAnimationsDisabled(Context context) {
        try {
            return Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception e) {
            return false;
        }
    }


    /** How an item enters in a style: where it comes from, how it starts out, how long it takes */
    private static final class Motion {
        static final int DECELERATE = 0, OVERSHOOT = 1, BOUNCE = 2, SMOOTH = 3;
        final float x, y, scaleX, scaleY, rotation, pivotX, pivotY;
        final int interpolator;
        final long duration;

        /** x and y are fractions of the size of the item */
        Motion(float x, float y, float scaleX, float scaleY, float rotation, float pivotX, float pivotY,
               int interpolator, long duration) {
            this.x = x; this.y = y; this.scaleX = scaleX; this.scaleY = scaleY; this.rotation = rotation;
            this.pivotX = pivotX; this.pivotY = pivotY; this.interpolator = interpolator; this.duration = duration;
        }

        Interpolator createInterpolator() {
            switch (interpolator) {
                case OVERSHOOT: return new OvershootInterpolator(1.8f);
                case BOUNCE: return new BounceInterpolator();
                case SMOOTH: return new PathInterpolator(0.4f, 0f, 0.2f, 1f);
                default: return new DecelerateInterpolator();
            }
        }
    }

    /** @return the motion of a style, null for the default style and for names that are not a style */
    @Nullable
    private static Motion motionOf(@NonNull String style) {
        switch (style) {
            case STYLE_FADE: return new Motion(0f, 0f, 1f, 1f, 0f, 0.5f, 0.5f, 0, 300);
            case STYLE_BOUNCE: return new Motion(0f, 0.6f, 1f, 1f, 0f, 0.5f, 0.5f, 2, 520);
            case STYLE_ZOOM: return new Motion(0f, 0f, 0.8f, 0.8f, 0f, 0.5f, 0.5f, 0, 300);
            case STYLE_RISE: return new Motion(0f, 0.35f, 1f, 1f, 0f, 0.5f, 0.5f, 0, 300);
            case STYLE_DROP: return new Motion(0f, -0.35f, 1f, 1f, 0f, 0.5f, 0.5f, 0, 300);
            case STYLE_POP: return new Motion(0f, 0f, 0.55f, 0.55f, 0f, 0.5f, 0.5f, 1, 380);
            case STYLE_VERTICAL: return new Motion(0f, 0.12f, 1f, 1f, 0f, 0.5f, 0.5f, 3, 300);
            case STYLE_FROM_RIGHT: return new Motion(0.5f, 0f, 1f, 1f, 0f, 0.5f, 0.5f, 3, 340);
            case STYLE_FROM_LEFT: return new Motion(-0.5f, 0f, 1f, 1f, 0f, 0.5f, 0.5f, 3, 340);
            case STYLE_ELASTIC: return new Motion(0f, 0.5f, 1f, 1f, 0f, 0.5f, 0.5f, 1, 520);
            case STYLE_SPIN: return new Motion(0f, 0f, 0.5f, 0.5f, -90f, 0.5f, 0.5f, 1, 440);
            case STYLE_FLIP: return new Motion(0f, 0f, 0f, 1f, 0f, 0.5f, 0.5f, 1, 400);
            case STYLE_SWING: return new Motion(0f, 0.1f, 1f, 1f, 14f, 0.5f, 0f, 1, 500);
            case STYLE_CORNER: return new Motion(0f, 0f, 0.3f, 0.3f, 0f, 0f, 0f, 0, 340);
            case STYLE_SQUASH: return new Motion(0f, 0f, 1.08f, 0.5f, 0f, 0.5f, 1f, 1, 420);
            case STYLE_SLAM: return new Motion(0f, 0f, 1.5f, 1.5f, 0f, 0.5f, 0.5f, 0, 240);
            case STYLE_UNFOLD: return new Motion(0f, 0f, 1f, 0f, 0f, 0.5f, 0f, 0, 340);
            case STYLE_DRIFT: return new Motion(0.08f, 0.04f, 1f, 1f, 0f, 0.5f, 0.5f, 3, 560);
            case STYLE_WOBBLE: return new Motion(-0.05f, 0f, 1f, 1f, -8f, 0.5f, 0.5f, 1, 520);
            case STYLE_TILT: return new Motion(0f, 0.2f, 1f, 1f, 6f, 0.5f, 1f, 0, 360);
            default: return null;
        }
    }

    /** Screen transition that moves the screen the way the style says, the same for every new style */
    private static final class MotionTransition extends androidx.transition.Visibility {
        private final Motion mMotion;

        MotionTransition(Motion motion) {
            mMotion = motion;
            setDuration(motion.duration);
        }

        @Override
        public android.animation.Animator onAppear(ViewGroup sceneRoot, View view,
                                                   androidx.transition.TransitionValues startValues,
                                                   androidx.transition.TransitionValues endValues) {
            view.setPivotX(view.getWidth() * mMotion.pivotX);
            view.setPivotY(view.getHeight() * mMotion.pivotY);
            android.animation.AnimatorSet set = new android.animation.AnimatorSet();
            java.util.List<android.animation.Animator> animators = new ArrayList<>();
            animators.add(android.animation.ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f));
            // Moves over a third of the size at most, a screen coming in from a whole width away looks wrong
            if (mMotion.x != 0f) animators.add(android.animation.ObjectAnimator.ofFloat(view, View.TRANSLATION_X, mMotion.x * view.getWidth() * 0.6f, 0f));
            if (mMotion.y != 0f) animators.add(android.animation.ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, mMotion.y * view.getHeight() * 0.6f, 0f));
            if (mMotion.scaleX != 1f) animators.add(android.animation.ObjectAnimator.ofFloat(view, View.SCALE_X, mMotion.scaleX, 1f));
            if (mMotion.scaleY != 1f) animators.add(android.animation.ObjectAnimator.ofFloat(view, View.SCALE_Y, mMotion.scaleY, 1f));
            if (mMotion.rotation != 0f) animators.add(android.animation.ObjectAnimator.ofFloat(view, View.ROTATION, mMotion.rotation * 0.6f, 0f));
            set.playTogether(animators);
            set.setInterpolator(mMotion.createInterpolator());
            return set;
        }

        @Override
        public android.animation.Animator onDisappear(ViewGroup sceneRoot, View view,
                                                      androidx.transition.TransitionValues startValues,
                                                      androidx.transition.TransitionValues endValues) {
            android.animation.ObjectAnimator fade = android.animation.ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f);
            fade.setDuration(Math.min(mMotion.duration, 200));
            fade.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    view.setAlpha(1f);
                }
            });
            return fade;
        }
    }

    /** @return the animation style the user picked, {@link #STYLE_DEFAULT} when none or an unknown one is saved */
    @NonNull
    public static String getStyle(@NonNull Context context) {
        String style = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_STYLE, STYLE_DEFAULT);
        return style != null && motionOf(style) != null ? style : STYLE_DEFAULT;
    }

    /**
     * Applies the animation related theme overlays and window animations to a not yet created activity.
     * Must be called before the content view is set.
     */
    public static void applyToActivity(@NonNull Activity activity) {
        Resources.Theme theme = activity.getTheme();
        String style = getStyle(activity);
        if (isEnabled(activity, TYPE_DIALOGS)) {
            theme.applyStyle(dialogOverlay(style), true);
        }
        if (isEnabled(activity, TYPE_BUTTONS)) {
            theme.applyStyle(buttonOverlay(style), true);
        }
        activity.getWindow().setWindowAnimations(isEnabled(activity, TYPE_SCREENS)
                ? activityAnimations(style)
                : R.style.Animation_Copper_ActivityNone);
    }

    private static int dialogOverlay(String style) {
        switch (style) {
            case STYLE_FADE: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Fade;
            case STYLE_BOUNCE: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Bounce;
            case STYLE_ZOOM: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Zoom;
            case STYLE_RISE: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Rise;
            case STYLE_DROP: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Drop;
            case STYLE_POP: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Pop;
            case STYLE_VERTICAL: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Vertical;
            case STYLE_FROM_RIGHT: return R.style.ThemeOverlay_Copper_Animations_Dialogs_FromRight;
            case STYLE_FROM_LEFT: return R.style.ThemeOverlay_Copper_Animations_Dialogs_FromLeft;
            case STYLE_ELASTIC: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Elastic;
            case STYLE_SPIN: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Spin;
            case STYLE_FLIP: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Flip;
            case STYLE_SWING: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Swing;
            case STYLE_CORNER: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Corner;
            case STYLE_SQUASH: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Squash;
            case STYLE_SLAM: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Slam;
            case STYLE_UNFOLD: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Unfold;
            case STYLE_DRIFT: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Drift;
            case STYLE_WOBBLE: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Wobble;
            case STYLE_TILT: return R.style.ThemeOverlay_Copper_Animations_Dialogs_Tilt;
            default: return R.style.ThemeOverlay_Copper_Animations_Dialogs;
        }
    }

    private static int buttonOverlay(String style) {
        switch (style) {
            case STYLE_FADE: case STYLE_VERTICAL: return R.style.ThemeOverlay_Copper_Animations_Buttons_Fade;
            case STYLE_BOUNCE: case STYLE_DROP: return R.style.ThemeOverlay_Copper_Animations_Buttons_Squish;
            case STYLE_ZOOM: case STYLE_RISE: return R.style.ThemeOverlay_Copper_Animations_Buttons_Grow;
            case STYLE_FROM_RIGHT: case STYLE_FROM_LEFT: case STYLE_DRIFT: return R.style.ThemeOverlay_Copper_Animations_Buttons_Fade;
            case STYLE_SQUASH: case STYLE_ELASTIC: case STYLE_SLAM: return R.style.ThemeOverlay_Copper_Animations_Buttons_Squish;
            case STYLE_CORNER: case STYLE_UNFOLD: case STYLE_FLIP: return R.style.ThemeOverlay_Copper_Animations_Buttons_Grow;
            case STYLE_SWING: case STYLE_WOBBLE: case STYLE_TILT: return R.style.ThemeOverlay_Copper_Animations_Buttons_Tilt;
            case STYLE_SPIN: return R.style.ThemeOverlay_Copper_Animations_Buttons_Sink;
            default: return R.style.ThemeOverlay_Copper_Animations_Buttons;
        }
    }

    private static int activityAnimations(String style) {
        switch (style) {
            case STYLE_FADE: return R.style.Animation_Copper_Activity_Fade;
            case STYLE_ZOOM: case STYLE_POP: return R.style.Animation_Copper_Activity_Zoom;
            case STYLE_RISE: case STYLE_BOUNCE: case STYLE_VERTICAL: return R.style.Animation_Copper_Activity_Rise;
            case STYLE_DROP: return R.style.Animation_Copper_Activity_Drop;
            case STYLE_FROM_RIGHT: return R.style.Animation_Copper_Activity_FromRight;
            case STYLE_FROM_LEFT: return R.style.Animation_Copper_Activity_FromLeft;
            case STYLE_ELASTIC: return R.style.Animation_Copper_Activity_Elastic;
            case STYLE_SPIN: return R.style.Animation_Copper_Activity_Spin;
            case STYLE_FLIP: return R.style.Animation_Copper_Activity_Flip;
            case STYLE_SWING: return R.style.Animation_Copper_Activity_Swing;
            case STYLE_CORNER: return R.style.Animation_Copper_Activity_Corner;
            case STYLE_SQUASH: return R.style.Animation_Copper_Activity_Squash;
            case STYLE_SLAM: return R.style.Animation_Copper_Activity_Slam;
            case STYLE_UNFOLD: return R.style.Animation_Copper_Activity_Unfold;
            case STYLE_DRIFT: return R.style.Animation_Copper_Activity_Drift;
            case STYLE_WOBBLE: return R.style.Animation_Copper_Activity_Wobble;
            case STYLE_TILT: return R.style.Animation_Copper_Activity_Tilt;
            default: return R.style.Animation_Copper_Activity;
        }
    }

    /** Fragment transitions for every fragment shown by this activity, in the picked style */
    public static void installFragmentTransitions(@NonNull FragmentActivity activity) {
        activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentPreCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                                                     @Nullable android.os.Bundle savedInstanceState) {
                        if (f instanceof DialogFragment) return;
                        if (!isEnabled(activity, TYPE_SCREENS)) return;
                        String style = getStyle(activity);
                        f.setEnterTransition(screenTransition(style, true, true));
                        f.setExitTransition(screenTransition(style, false, true));
                        f.setReenterTransition(screenTransition(style, true, false));
                        f.setReturnTransition(screenTransition(style, false, false));
                    }
                }, true);
    }

    /**
     * @param entering whether the screen comes in (enter, reenter) or goes out (exit, return)
     * @param forward whether it is part of going deeper (enter, exit) or coming back (reenter, return)
     */
    private static Transition screenTransition(String style, boolean entering, boolean forward) {
        switch (style) {
            case STYLE_FADE:
                return new MaterialFade();
            case STYLE_ZOOM:
                return new MaterialSharedAxis(MaterialSharedAxis.Z, forward);
            case STYLE_VERTICAL:
                return new MaterialSharedAxis(MaterialSharedAxis.Y, forward);
            case STYLE_POP: {
                MaterialElevationScale scale = new MaterialElevationScale(entering);
                if (entering) scale.setInterpolator(new OvershootInterpolator(1.4f));
                return scale;
            }
            case STYLE_BOUNCE:
            case STYLE_RISE:
            case STYLE_DROP: {
                if (!entering) {
                    Fade fade = new Fade();
                    fade.setDuration(180);
                    return fade;
                }
                Slide slide = new Slide(STYLE_DROP.equals(style) ? Gravity.TOP : Gravity.BOTTOM);
                slide.setDuration(STYLE_BOUNCE.equals(style) ? 520 : 320);
                slide.setInterpolator(STYLE_BOUNCE.equals(style) ? new BounceInterpolator() : new DecelerateInterpolator());
                return slide;
            }
            default: {
                Motion motion = motionOf(style);
                if (motion != null) return new MotionTransition(motion);
                return new MaterialSharedAxis(MaterialSharedAxis.X, forward);
            }
        }
    }

    /** Makes the items of a list enter one after the other the first time it is laid out */
    public static void applyListAnimation(@Nullable ViewGroup list) {
        if (list == null) return;
        Context context = list.getContext();
        if (!isEnabled(context, TYPE_CONTENT)) {
            list.setLayoutAnimation(null);
            return;
        }
        String style = getStyle(context);
        if (STYLE_DEFAULT.equals(style)) {
            list.setLayoutAnimation(AnimationUtils.loadLayoutAnimation(context, R.anim.copper_layout_list));
            return;
        }
        LayoutAnimationController controller = new LayoutAnimationController(listItemAnimation(style), 0.1f);
        controller.setOrder(LayoutAnimationController.ORDER_NORMAL);
        list.setLayoutAnimation(controller);
    }

    private static Animation listItemAnimation(String style) {
        Motion m = motionOf(style);
        if (m == null) m = new Motion(0f, 0.12f, 1f, 1f, 0f, 0.5f, 0.5f, Motion.DECELERATE, 300);
        AnimationSet set = new AnimationSet(false);
        AlphaAnimation alpha = new AlphaAnimation(0f, 1f);
        alpha.setDuration(Math.min(m.duration, 260));
        set.addAnimation(alpha);
        if (m.x != 0f || m.y != 0f) {
            set.addAnimation(new TranslateAnimation(Animation.RELATIVE_TO_SELF, m.x, Animation.RELATIVE_TO_SELF, 0f,
                    Animation.RELATIVE_TO_SELF, m.y, Animation.RELATIVE_TO_SELF, 0f));
        }
        if (m.scaleX != 1f || m.scaleY != 1f) {
            set.addAnimation(new ScaleAnimation(m.scaleX, 1f, m.scaleY, 1f,
                    Animation.RELATIVE_TO_SELF, m.pivotX, Animation.RELATIVE_TO_SELF, m.pivotY));
        }
        if (m.rotation != 0f) {
            set.addAnimation(new android.view.animation.RotateAnimation(m.rotation, 0f,
                    Animation.RELATIVE_TO_SELF, m.pivotX, Animation.RELATIVE_TO_SELF, m.pivotY));
        }
        set.setDuration(m.duration);
        set.setInterpolator(m.createInterpolator());
        return set;
    }

    /** Makes the direct children of a layout enter one after the other, in the picked style */
    public static void staggerIn(@Nullable ViewGroup parent) {
        if (parent == null || !isEnabled(parent.getContext(), TYPE_CONTENT)) return;
        Motion m = motionOf(getStyle(parent.getContext()));
        float density = parent.getResources().getDisplayMetrics().density;
        final boolean fallback = m == null;
        if (m == null) m = new Motion(0f, 0f, 1f, 1f, 0f, 0.5f, 0.5f, Motion.DECELERATE, 280);
        int shown = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            // The default style moves 16dp, the others move a fraction of the size, but never more than 48dp
            float dx = fallback ? 0f : m.x * Math.min(child.getWidth(), 120 * density) * 0.5f;
            float dy = fallback ? 16 * density
                    : Math.max(-48 * density, Math.min(48 * density, m.y * Math.max(child.getHeight(), 60 * density) * 0.5f));
            child.setPivotX(child.getWidth() * m.pivotX);
            child.setPivotY(child.getHeight() * m.pivotY);
            child.setAlpha(0f);
            child.setTranslationX(dx);
            child.setTranslationY(dy);
            child.setScaleX(m.scaleX);
            child.setScaleY(m.scaleY);
            child.setRotation(m.rotation);
            child.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .rotation(0f)
                    .setStartDelay(Math.min(shown, 12) * 35L)
                    .setDuration(m.duration)
                    .setInterpolator(m.createInterpolator())
                    .start();
            shown++;
        }
    }
}
