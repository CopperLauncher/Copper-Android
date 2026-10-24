package git.artdeell.mojo.modding.modpacks.api;

import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.ModItem;

/**
 * Implemented by the APIs whose {@code getModDetails} only returns the versions that match the
 * Minecraft version / loader filters of the search, to also give every version of the content.
 * Backs the "Show incompatible" button of the content detail screen.
 */
public interface UnfilteredVersionsProvider {
    /** Every version of the item, whatever the filters are. Blocking, can return null. */
    ModDetail getAllVersions(ModItem item);
}
