package git.artdeell.mojo.utils.plugins;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * Native library provided by an installed FCL native plugin APK.
 * The metadata follows the {@code FCLNativePlugin}/{@code environment} format.
 */
public class NativePlugin {
    public final String packageName;
    public final String nativeLibraryDir;
    public final String displayName;
    public final List<String> jvmArgs;

    private NativePlugin(String packageName, String nativeLibraryDir, String displayName,
                         List<String> jvmArgs) {
        this.packageName = packageName;
        this.nativeLibraryDir = nativeLibraryDir;
        this.displayName = displayName;
        this.jvmArgs = jvmArgs;
    }

    static NativePlugin parse(Context context, ApplicationInfo info, Bundle metaData) {
        if (info.nativeLibraryDir == null) return null;
        String environment = metaData.getString("environment");
        if (environment == null) return null;

        List<String> jvmArgs = new ArrayList<>();
        for (String argument : environment.split(" ")) {
            if (argument.isEmpty()) continue;
            jvmArgs.add(argument.replace("{nativeLibraryDir}", info.nativeLibraryDir + "/"));
        }

        String appLabel = String.valueOf(context.getPackageManager().getApplicationLabel(info));
        String description = metaData.getString("des");
        String displayName;
        if (description == null || description.isEmpty()) {
            displayName = appLabel;
        } else {
            displayName = description + " (" + appLabel + ")";
        }
        return new NativePlugin(info.packageName, info.nativeLibraryDir, displayName, jvmArgs);
    }
}
