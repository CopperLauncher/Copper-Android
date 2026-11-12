package git.artdeell.mojo.fragments;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.LauncherWallpaperView;

import git.artdeell.mojo.R;
import git.artdeell.mojo.home.HomeTileBoard;

/**
 * Home screen of the right pane of the two-pane landscape main menu: a board of tiles that can be
 * moved, resized, added and removed. The tiles themselves are run by {@link HomeTilesController},
 * which also runs the sidebar on the left, so a tile can be dragged from one side to the other.
 * The wallpaper is drawn behind the whole launcher by the activity, or by the view of this
 * screen when it isn't fullscreen.
 */
public class RightPaneHomeFragment extends Fragment {
    public static final String TAG = "RightPaneHomeFragment";

    private HomeTileBoard mBoard;

    public RightPaneHomeFragment() {
        super(R.layout.fragment_right_pane_home);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // The wallpaper behind the tiles, only shown when it isn't fullscreen
        ((LauncherWallpaperView) view.findViewById(R.id.pane_wallpaper)).setPane(true);
        mBoard = view.findViewById(R.id.home_tile_board);
        if (getParentFragment() instanceof MainMenuFragment) {
            HomeTilesController tiles = ((MainMenuFragment) getParentFragment()).getHomeTiles();
            if (tiles != null) tiles.attachRight(mBoard);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mBoard != null && getParentFragment() instanceof MainMenuFragment) {
            HomeTilesController tiles = ((MainMenuFragment) getParentFragment()).getHomeTiles();
            if (tiles != null) tiles.detachRight(mBoard);
        }
        mBoard = null;
    }
}
