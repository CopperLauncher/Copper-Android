package net.kdt.pojavlaunch.instances;

import java.io.File;

public class DisplayInstance {
    protected transient File mInstanceRoot;
    public String name;
    public String versionId;
    public String icon;

    /**
     * Stable identifier of the instance: the name of its directory in the instances folder.
     * This is also what the launcher stores as the selected instance.
     */
    public String getKey() {
        return mInstanceRoot == null ? null : mInstanceRoot.getName();
    }

    protected void sanitize() {
        sanitizeIcon();
    }

    protected DisplayInstance() {
    }

    protected File getInstanceIconLocation() {
        return new File(mInstanceRoot, "icon.webp");
    }

    private void sanitizeIcon() {
        if(!InstanceIconProvider.hasStaticIcon(icon)) {
            icon = InstanceIconProvider.FALLBACK_ICON_NAME;
        }
    }
}
