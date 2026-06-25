package net.kdt.pojavlaunch.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SeekBarPreference;

import net.kdt.pojavlaunch.R;

public class CustomSeekBarPreference extends SeekBarPreference {

    /** The suffix displayed */
    private String mSuffix = "";
    /** Custom minimum value to provide the same behavior as the usual setMin */
    private int mMin;
    /** The textview associated by default to the preference */
    private TextView mTextView;
    /** The seekbar widget associated to the preference, kept so a typed value can move it */
    private SeekBar mSeekBar;
    /** Seekbar increment in case the max gets set */
    private final int mIncrement;
    /** When true, tapping the value TextView opens a dialog to type an exact value */
    private boolean mTapToSetValueEnabled = false;


    @SuppressLint("PrivateResource")
    public CustomSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        try (TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.SeekBarPreference, defStyleAttr, defStyleRes)) {
            mMin = a.getInt(R.styleable.SeekBarPreference_min, 0);
            mIncrement = a.getInt(R.styleable.SeekBarPreference_seekBarIncrement, 0);
        }
    }

    public CustomSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public CustomSeekBarPreference(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.seekBarPreferenceStyle);
    }

    @SuppressWarnings("unused") public CustomSeekBarPreference(Context context) {
        this(context, null);
    }

    @Override
    public void setMin(int min) {
        //Note: since the max (setMax is a final function) is not taken into account properly, setting the min over the max may produce funky results
        super.setMin(min);
        if (min != mMin) mMin = min;
    }


    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder view) {
        super.onBindViewHolder(view);
        TextView titleTextView = (TextView) view.findViewById(android.R.id.title);
        titleTextView.setTextColor(Color.WHITE);

        mTextView = (TextView) view.findViewById(R.id.seekbar_value);
        mTextView.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        mSeekBar = (SeekBar) view.findViewById(R.id.seekbar);
        final SeekBar seekBar = mSeekBar;

        if (mTapToSetValueEnabled) {
            mTextView.setClickable(true);
            mTextView.setFocusable(true);
            mTextView.setOnClickListener(v -> showSetValueDialog());
        }

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                progress += mMin;
                progress = progress / getSeekBarIncrement();
                progress = progress * getSeekBarIncrement();
                progress -= mMin;

                mTextView.setText(String.valueOf(progress + mMin));
                updateTextViewWithSuffix();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

                int progress = seekBar.getProgress() + mMin;
                progress /= getSeekBarIncrement();
                progress *= getSeekBarIncrement();
                progress -= mMin;

                setValue(progress + mMin);
                updateTextViewWithSuffix();
            }
        });

        updateTextViewWithSuffix();
    }

    /**
     * Opt-in: makes the value TextView (e.g. "4096 MB") tappable, opening a
     * dialog where an exact value can be typed instead of dragged.
     */
    public void enableTapToSetValue() {
        mTapToSetValueEnabled = true;
    }

    private void showSetValueDialog() {
        Context context = mTextView.getContext();

        final EditText editText = new EditText(context);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        editText.setText(String.valueOf(getValue()));
        editText.setSelection(editText.getText().length());

        new AlertDialog.Builder(context)
                .setTitle(getTitle())
                .setView(editText)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String input = editText.getText().toString().trim();
                    if (input.isEmpty()) return;

                    int newValue;
                    try {
                        newValue = Integer.parseInt(input);
                    } catch (NumberFormatException e) {
                        return;
                    }

                    int max = getMax();
                    if (newValue < mMin) newValue = mMin;
                    if (newValue > max) newValue = max;

                    mSeekBar.setProgress(newValue - mMin);
                    setValue(newValue);
                    mTextView.setText(String.valueOf(newValue));
                    updateTextViewWithSuffix();
                })
                .show();
    }

    /**
     * Set a suffix to be appended on the TextView associated to the value
     * @param suffix The suffix to append as a String
     */
    public void setSuffix(String suffix) {
        this.mSuffix = suffix;
    }

    /**
     * Convenience function to set both min and max at the same time.
     * @param min The minimum value
     * @param max The maximum value
     */
    public void setRange(int min, int max){
        setMin(min);
        setMaxKeepIncrement(max);
    }

    public void setMaxKeepIncrement(int max) {
        super.setMax(max);
        setSeekBarIncrement(mIncrement);
    }


    private void updateTextViewWithSuffix(){
        if(!mTextView.getText().toString().endsWith(mSuffix)){
            mTextView.setText(String.format("%s%s", mTextView.getText(), mSuffix));
        }
    }
}