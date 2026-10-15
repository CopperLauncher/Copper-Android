package git.artdeell.mojo.prefs;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;

/**
 * Dialog that lets the user type the amount of RAM to allocate, instead of dragging the slider.
 * Shows the amount of RAM that is currently free on the device, refreshed while the dialog is open.
 */
public final class RamInputDialog {
    private static final long FREE_RAM_REFRESH_MS = 1000L;

    /** Receives the validated value, already within [min, max] */
    public interface OnRamSelectedListener {
        void onRamSelected(int megabytes);
    }

    private RamInputDialog() {}

    public static void show(@NonNull Context context, int current, int min, int max,
                            @NonNull OnRamSelectedListener listener) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_ram_input, null);
        TextView freeRamView = view.findViewById(R.id.ram_dialog_free);
        TextInputLayout inputLayout = view.findViewById(R.id.ram_dialog_input_layout);
        TextInputEditText input = view.findViewById(R.id.ram_dialog_input);

        inputLayout.setHelperText(context.getString(R.string.mcl_memory_allocation_dialog_helper, min, max));
        input.setText(String.valueOf(current));
        input.setSelection(input.length());

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.mcl_memory_allocation)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null) // real handler set below, so invalid input doesn't dismiss
                .create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);

        // Keep the "free RAM" line up to date while the dialog is visible
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable refresher = new Runnable() {
            @Override
            public void run() {
                freeRamView.setText(context.getString(R.string.mcl_memory_allocation_dialog_free,
                        Tools.getFreeDeviceMemory(context)));
                handler.postDelayed(this, FREE_RAM_REFRESH_MS);
            }
        };
        dialog.setOnShowListener(d -> {
            refresher.run();

            View okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Runnable submit = () -> {
                int value = parse(input.getText());
                if (value < min || value > max) {
                    inputLayout.setError(context.getString(R.string.mcl_memory_allocation_dialog_error, min, max));
                    return;
                }
                listener.onRamSelected(value);
                dialog.dismiss();
            };
            okButton.setOnClickListener(v -> submit.run());
            input.setOnEditorActionListener((v, actionId, event) -> {
                submit.run();
                return true;
            });
        });
        dialog.setOnDismissListener(d -> handler.removeCallbacks(refresher));

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                inputLayout.setError(null);
            }
        });

        dialog.show();
    }

    /** @return the parsed value, or -1 if the text is empty or not a valid number */
    private static int parse(CharSequence text) {
        if (text == null || text.length() == 0) return -1;
        try {
            return Integer.parseInt(text.toString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
