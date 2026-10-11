package git.artdeell.mojo.game.platform.dialog;

import git.mojo.sdl.FileDialogHandler;

/**
 * Routes SDL3 file dialog requests (SDL_ShowOpenFileDialog and friends) into
 * the shared file manager bridge.
 */
public class SDLFileDialogHandler implements FileDialogHandler {
    @Override
    public boolean showFileDialog(String[] filters, boolean allowMultiple,
                                  int type, String initialPath, int requestCode) {
        return FileDialogBridge.launchSdlDialog(filters, allowMultiple, type, initialPath, requestCode);
    }
}
