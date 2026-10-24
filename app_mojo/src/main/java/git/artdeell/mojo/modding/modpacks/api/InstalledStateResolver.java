package git.artdeell.mojo.modding.modpacks.api;

import git.artdeell.mojo.modding.modpacks.models.ModDetail;

/**
 * Implemented by the APIs that can tell which version of a piece of content is already in the
 * current instance, so the version list can show Installed / Update / Downgrade.
 */
public interface InstalledStateResolver {
    /**
     * Sets {@link ModDetail#installedVersionIndex} and {@link ModDetail#installedFilePath}.
     * Hashes files, so it must not be called on the UI thread.
     */
    void resolveInstalledState(ModDetail detail);
}
