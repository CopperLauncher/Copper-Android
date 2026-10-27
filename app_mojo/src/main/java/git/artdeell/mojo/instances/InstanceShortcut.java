package git.artdeell.mojo.instances;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import git.artdeell.mojo.R;
import git.artdeell.mojo.TestStorageActivity;
import git.artdeell.mojo.Tools;

/**
 * Pins a home screen shortcut that selects an instance and launches it.
 */
public final class InstanceShortcut {
    public static final String ACTION_LAUNCH_INSTANCE = "git.artdeell.mojo.ACTION_LAUNCH_INSTANCE";
    public static final String EXTRA_INSTANCE_KEY = "instance_key";
    private static final int ICON_SIZE_PX = 192;

    private InstanceShortcut() {}

    public static void create(@NonNull Context context, @Nullable Instance instance) {
        String key = instance == null ? null : instance.getKey();
        if(key == null) {
            Toast.makeText(context, R.string.error_no_version, Toast.LENGTH_SHORT).show();
            return;
        }
        if(!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, R.string.shortcut_not_supported_toast, Toast.LENGTH_LONG).show();
            return;
        }

        Intent shortcutIntent = new Intent(context, TestStorageActivity.class);
        shortcutIntent.setAction(ACTION_LAUNCH_INSTANCE);
        shortcutIntent.putExtra(EXTRA_INSTANCE_KEY, key);
        shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        String label = Tools.isValidString(instance.name) ? instance.name : key;
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(context, key)
                .setShortLabel(label)
                .setIcon(IconCompat.createWithBitmap(renderIcon(context, instance)))
                .setIntent(shortcutIntent)
                .build();

        ShortcutManagerCompat.requestPinShortcut(context, info, null);
        Toast.makeText(context, R.string.shortcut_created_toast, Toast.LENGTH_SHORT).show();
    }

    private static Bitmap renderIcon(Context context, Instance instance) {
        Drawable drawable = InstanceIconProvider.fetchIcon(context.getResources(), instance);
        Bitmap bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX);
        drawable.draw(canvas);
        return bitmap;
    }
}
