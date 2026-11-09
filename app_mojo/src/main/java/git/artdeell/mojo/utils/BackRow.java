package git.artdeell.mojo.utils;

import android.view.View;

import androidx.annotation.NonNull;

import git.artdeell.mojo.R;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;

/**
 * The "Back to the last screen" row of {@code include_mg_back_row.xml}, the same row the settings
 * screens end with. Going back works like it does there: the LauncherActivity catches
 * {@link ExtraConstants#BACK_PREFERENCE} and handles it as a back press.
 */
public final class BackRow {
    private BackRow() {}

    /** Makes the back row found in the given view go back when it is clicked */
    public static void bind(@NonNull View root) {
        View row = root.findViewById(R.id.mg_back_row);
        if (row != null) row.setOnClickListener(v -> ExtraCore.setValue(ExtraConstants.BACK_PREFERENCE, "true"));
    }
}
